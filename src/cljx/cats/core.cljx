(ns cats.core
  "Category Theory abstractions for Clojure"
  (:require [cats.protocols :as p]
            [cats.types :as t])
  #+cljs
  (:require-macros [cats.core :as cm]))


(def ^{:dynamic true} *m-context*)

#+clj
(defmacro with-context
  [ctx & body]
  `(binding [*m-context* ~ctx]
     ~@body))

(defn pure
  "Given any value v, return it wrapped in
  default/effect free context.

  This is multiarity function that with arity pure/1
  it uses the dynamic scope for resolve the current
  context. With pure/2, you can force specific context
  value."
  ([v]
     (p/pure *m-context* v))
  ([av v]
     (p/pure av v)))

(def ^{:doc "This is a monad version of pure."}
  return pure)

(defn bind
  "Given a value inside monadic context mv and any function,
  applies a function to value of mv."
  [mv f]
  (#+clj  with-context
   #+cljs cm/with-context mv
    (p/bind mv f)))

(defn mzero
  []
  (p/mzero *m-context*))

(defn mplus
  [mv mv']
  (p/mplus mv mv'))

(defn guard
  [b]
  (if b
    (return nil)
    (mzero)))

(defn fmap
  "Apply a function f to the value inside functor's fv
  preserving the context type."
  [f fv]
  (p/fmap fv f))

(defn fapply
  "Given function inside af's conext and value inside
  av's context, applies the function to value and return
  a result wrapped in context of same type of av context."
  [af av]
  (p/fapply af av))

(defn >>=
  "Performs a Haskell-style left-associative bind."
  ([mv f]
     (bind mv f))
  ([mv f & fs]
     (reduce bind mv (cons f fs))))

(defn <$>
  "Alias of fmap."
  ([f]
     (fn [fv]
       (p/fmap fv f)))
  ([f fv]
     (p/fmap fv f)))

(defn <*>
  "Performs a Haskell-style left-associative fapply."
  ([af av]
     (p/fapply af av))
  ([af av & avs]
     (reduce p/fapply af (cons av avs))))

#+clj
(defmacro mlet
  [bindings & body]
  (when-not (and (vector? bindings) (even? (count bindings)))
    (throw (IllegalArgumentException. "bindings has to be a vector with even number of elements.")))
  (if (seq bindings)
    (let [l (get bindings 0)
          r (get bindings 1)
          next-mlet `(mlet ~(subvec bindings 2) ~@body)]
      (condp = l
        :let `(let ~r ~next-mlet)
        :when `(bind (guard ~r)
                     (fn [~(gensym)]
                       ~next-mlet))
        `(bind ~r
               (fn [~l]
                 ~next-mlet))))
    `(do ~@body)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Monadic functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn join
  "Remove one level of monadic structure."
  [mv]
  (bind mv identity))

(defn =<<
  "Same as the two argument version of `>>=` but with the
  arguments interchanged."
  [f mv]
  (>>= mv f))

(defn >=>
  "Left-to-right composition of monads."
  [mf mg x]
  (#+clj  mlet
   #+cljs cm/mlet [a (mf x)
                   b (mg a)]
                  (return b)))

(defn <=<
  "Right-to-left composition of monads.

  Same as `>=>` with its first two arguments flipped."
  [mg mf x]
  (#+clj  mlet
   #+cljs cm/mlet [a (mf x)
                   b (mg a)]
                  (return b)))

(defn sequence-m
  "Given a non-empty collection of monadic values, collect
  their values in a vector returned in the monadic context.

      (require '[cats.types :as t])
      (require '[cats.core :as m])

      (m/sequence-m [(t/just 2) (t/just 3)])
      ;=> <Just [[2, 3]]>

      (m/sequence-m [(t/nothing) (t/just 3)])
      ;=> <Nothing>
  "
  [mvs]
  {:pre [(not-empty mvs)]}
  (reduce (fn [mvs mv]
             (#+clj  mlet
              #+cljs cm/mlet [v mv
                              vs mvs]
                             (return (conj vs v))))
          (#+clj  with-context
           #+cljs cm/with-context (first mvs)
            (return []))
          mvs))

(defn map-m
   "Given a function that takes a value and puts it into a
   monadic context, map it into the given collection
   calling sequence-m on the results.

       (require '[cats.types :as t])
       (require '[cats.core :as m])

       (m/map-m t/just [2 3])
       ;=> <Just [[2 3]]>

       (m/for-m (fn [v]
                   (if (odd? v)
                     (t/just v)
                     (t/nothing)))
                [1 2])
       ;=> <Nothing>
     "
  [mf coll]
  (sequence-m (map mf coll)))

(defn for-m
  "Same as map-m but with the arguments in reverse order.

      (require '[cats.types :as t])
      (require '[cats.core :as m])

      (m/for-m [2 3] t/just)
      ;=> <Just [[2 3]]>

      (m/for-m [1 2]
               (fn [v]
                  (if (odd? v)
                    (t/just v)
                    (t/nothing))))
      ;=> <Nothing>
   "
  [vs mf]
  (map-m mf vs))

(defn lift-m
  "Lifts a function to a monadic context.

      (require '[cats.types :as t])
      (require '[cats.core :as m])

      (def monad+ (m/lift-m +))

      (monad+ (t/just 1) (t/just 2))
      ;=> <Just [3]>

      (monad+ (t/just 1) (t/nothing))
      ;=> <Nothing>
  "
  [f]
  (fn [& args]
    (#+clj  mlet
     #+cljs cm/mlet [vs (sequence-m args)]
                    (return (apply f vs)))))

(defn filter-m
  "Applies a predicate to a value in a `MonadZero` instance,
  returning the identity element when the predicate yields false.

  Otherwise, returns the instance unchanged.

      (require '[cats.types :as t])
      (require '[cats.core :as m])

      (m/filter-m (partial < 2) (t/just 3))
      ;=> <Just [3]>

      (m/filter-m (partial < 4) (t/just 3))
      ;=> <Nothing>
  "
  [p mv]
  (#+clj  mlet
   #+cljs cm/mlet [v mv
                  :when (p v)]
                  (return v)))

(defn when-m
  "If the expression is true, returns the monadic value.

  Otherwise, yields nil in a monadic context."
  [b mv]
  (if b
    mv
    (pure mv nil)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; State monad functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-state
  "Return State instance with computation that returns
  the current state."
  []
  (-> (fn [s] (t/pair s s))
      (t/state-t)))

(defn put-state
  "Return State instance with computation that replaces
  the current state with specified new state."
  [newstate]
  (-> (fn [s] (t/pair s newstate))
      (t/state-t)))

(defn swap-state
  [f]
  "Return State instance with computation that applies
  specified function to state and return the old state."
  (-> (fn [s] (t/pair s (f s)))
      (t/state-t)))

(defn run-state
  "Given a State instance, execute the
  wrapped computation and return Pair
  instance with result and new state.

  (def computation (mlet [x (get-state)
                          y (put-state (inc x))]
                     (return y)))

  (def initial-state 1)
  (run-state computation initial-state)

  This should be return something to: #<Pair [1 2]>"
  [state seed]
  #+clj
  (with-context state
    (state seed))
  #+cljs
  (cm/with-context state
    (state seed)))

(defn eval-state
  "Given a State instance, execute the
  wrapped computation and return the resultant
  value, ignoring the state.
  Shortly, return the first value of pair instance
  returned by `run-state` function."
  [state seed]
  (first (run-state state seed)))

(defn exec-state
  "Given a State instance, execute the
  wrapped computation and return the resultant
  state.
  Shortly, return the second value of pair instance
  returned by `run-state` function."
  [state seed]
  (second (run-state state seed)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Continuation monad functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn run-cont
  "Given a Continuation instance, execute the
  wrapped computation and return its value."
  [cont]
  (#+clj  with-context
   #+cljs cm/with-context cont
    (cont identity)))

(defn call-cc
  [f]
  (t/continuation
    (fn [cc]
      (let [k (fn [a]
                (t/continuation (fn [_] (cc a))))]
        ((f k) cc)))))
