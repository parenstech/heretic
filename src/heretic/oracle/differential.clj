(ns heretic.oracle.differential
  "Differential property-based oracle (docs/g1-recall-measurement-plan.md §5).

   Decides whether a mutant is KILLABLE — some input distinguishes it from the
   original, a SOUND witness — or a CANDIDATE-EQUIVALENT — no witness in N trials,
   an UPPER bound on equivalence only. First-order PURE fns; everything else is
   :oracle-not-applicable.

   The mutant's top-level form is evaluated IN MEMORY — no file write, no
   clj-reload, no ClojureStorm. We extract the original and mutated top-level
   form as strings, eval the mutated form to rebind the target var, observe both
   functions on the SAME generated inputs (each call under a hard timeout, since
   a mutant can introduce an infinite loop), short-circuiting at the first
   witness, then (in a finally) eval the original form to restore the namespace.

   The verdict is a TAGGED shape (planreview): exactly one arm, each carrying
   only its valid fields —
     {:label :killable             :witness <args|keyword>}
     {:label :candidate-equivalent :trials  n}
     {:label :oracle-not-applicable :reason <keyword>}"
  (:require [clojure.java.io :as io]
            [clojure.walk :as walk]
            [heretic.mutation-engine :as engine]
            [heretic.oracle.gen :as gen])
  (:import [java.io PushbackReader]))

;; Form extraction (original-form-string / mutated-form-string) lives in
;; heretic.mutation-engine — shared with the production equivalent filter.

;; ---------------------------------------------------------------------------
;; Target identification + purity gate
;; ---------------------------------------------------------------------------

(defn file->ns-sym
  "Read the first `(ns …)` form of a source file and return its namespace symbol.
   Reads with :read-cond :allow so .cljc files resolve."
  [file]
  (with-open [r (PushbackReader. (io/reader file))]
    (binding [*read-eval* false]
      (loop []
        (let [form (read {:read-cond :allow :eof ::eof} r)]
          (cond
            (= form ::eof) nil
            (and (seq? form) (= 'ns (first form))) (second form)
            :else (recur)))))))

(def ^:private impure-markers
  "Core symbols whose presence makes a fn body unreplayable by a pure differential
   oracle (side effects, IO, nondeterminism, mutable refs)."
  '#{atom swap! reset! swap-vals! reset-vals! compare-and-set!
     volatile! vreset! vswap! ref alter commute ref-set dosync
     rand rand-int rand-nth shuffle
     println print pr prn print-str printf newline flush
     read read-line read-string slurp spit with-open
     send send-off deliver promise future agent alter-var-root
     time})

(defn- impure-symbol? [x]
  (and (symbol? x)
       (let [s (name x)
             nsp (namespace x)]
         (or (contains? impure-markers (symbol s))
             (.startsWith s ".")                       ; interop method call (.write …)
             (and (> (count s) 1) (.endsWith s "."))   ; constructor (Writer. …)
             (and nsp (Character/isUpperCase (char (first nsp))))  ; Class/static
             (and nsp (#{"System" "Thread" "Math"} nsp))))))

(defn pure-defn?
  "Conservative purity check on a defn form (as data): reject any interop, class
   reference, or known side-effecting/nondeterministic core fn. False negatives
   (calling a pure fn impure) only shrink applicability; they never make an
   impure fn look pure, which is the direction that matters for soundness."
  [form]
  (let [bad (volatile! false)]
    (walk/postwalk (fn [x] (when (impure-symbol? x) (vreset! bad true)) x) form)
    (not @bad)))

(defn form-def-info
  "Classify a top-level form string: {:kind :defn|:def|:other :name sym :pure? bool}."
  [form-str]
  (try
    (let [form (read-string {:read-cond :allow} form-str)]
      (if (and (seq? form) (symbol? (first form)) (symbol? (second form)))
        (let [h (first form)]
          (cond
            (#{'defn 'defn-} h) {:kind :defn :name (second form) :pure? (pure-defn? form)}
            (= 'def h) {:kind :def :name (second form)}
            :else {:kind :other :name (second form)}))
        {:kind :other :name nil}))
    (catch Exception _ {:kind :other :name nil})))

(defn arities
  "Fixed arities implied by :arglists; a variadic arglist contributes its
   required count + 2 (a heuristic so we still exercise the variadic body)."
  [arglists]
  (->> arglists
       (map (fn [al]
              (let [v (vec al)
                    amp (.indexOf v '&)]
                (if (neg? amp) (count v) (+ amp 2)))))
       distinct
       (remove zero?)))

;; ---------------------------------------------------------------------------
;; Observation + short-circuiting witness search
;; ---------------------------------------------------------------------------

(defn observe-one
  "Apply f to args under a hard timeout (a mutant may loop forever), fully
   REALIZING the result with pr-str inside the guarded thread. Returns
   [:value <printed>] | [:throw class] | [:timeout].

   pr-str matters for safety AND fidelity: it forces lazy seqs (so a realization
   error is caught here, not later in the comparison) and makes the observation
   TYPE-sensitive — `(map f xs)` prints `(…)` while `(mapv f xs)` prints `[…]`,
   so a `map`↔`mapv` swap is correctly distinguishable (a test can tell a seq
   from a vector) where Clojure `=` would call them equal. An infinite/huge
   realization is caught by the deref-timeout. A timed-out call leaks its thread
   (an uninterruptible CPU loop can't be cancelled) — acceptable for a bounded
   research run.

   A result printed by IDENTITY (`#object[…@hash]` — a function, or any opaque
   value) is NOT reliably value-comparable: re-evaluating the mutant mints fresh
   class names / identity hashes, so two behaviourally-identical results (e.g. two
   transducers a fn returns) would compare unequal and manufacture a FALSE witness.
   Such results are collapsed to a single `::opaque` token so they never
   distinguish — sound (no false `:killable`), at the cost of conservatively
   missing a genuine difference between two opaque values."
  [f args timeout-ms]
  (let [fut (future (try (let [s (pr-str (apply f args))]
                           [:value (if (.contains ^String s "#object[") ::opaque s)])
                         (catch Throwable t [:throw (class t)])))
        res (deref fut timeout-ms ::timeout)]
    (if (= res ::timeout)
      (do (future-cancel fut) [:timeout])
      res)))

(defn find-witness
  "Walk inputs; return {:witness args} at the first input whose two
   observations differ, else {:witness nil :applicable bool}. `applicable?` is
   true once the ORIGINAL returns a value on some input (so a fn that throws on
   every generated input — wrong shape / HOF — is reported not-applicable rather
   than a false equivalent). Distinguishing = the two observations are not=
   (one throws/loops & the other returns; unequal values; different thrown
   classes). Same throw/timeout on both ⇒ not distinguishing (conservative)."
  [f-orig f-mut inputs timeout-ms]
  (loop [in (seq inputs) applicable? false]
    (if-not in
      {:witness nil :applicable applicable?}
      (let [args (first in)
            o (observe-one f-orig args timeout-ms)
            m (observe-one f-mut args timeout-ms)]
        (if (not= o m)
          {:witness args}
          (recur (next in) (or applicable? (= :value (first o)))))))))

;; ---------------------------------------------------------------------------
;; Classification
;; ---------------------------------------------------------------------------

(defn classify-mutant
  "Classify one mutant. opts: {:n-trials :seed :ns-sym :timeout-ms :inputs}.
   When :inputs (a seq of real arg-tuples, e.g. suite-harvested + perturbed) is
   given it is used verbatim — these reach the mutation site by construction, so
   the witness search is far stronger than blind random generation; otherwise
   N=n-trials random tuples per declared arity are generated. Temporarily
   re-evaluates the mutated form in `ns-sym` and always restores the original."
  [mutant {:keys [n-trials seed ns-sym timeout-ms inputs]
           :or {n-trials 200 seed 42 timeout-ms 300}}]
  (let [orig-str (engine/original-form-string mutant)
        the-ns (some-> ns-sym find-ns)]
    (cond
      (nil? orig-str) {:label :oracle-not-applicable :reason :no-form}
      (nil? the-ns) {:label :oracle-not-applicable :reason :ns-not-loaded}
      :else
      (let [info (form-def-info orig-str)]
        (cond
          (not= :defn (:kind info))
          {:label :oracle-not-applicable :reason (keyword (str "kind-" (name (:kind info))))}

          (not (:pure? info))
          {:label :oracle-not-applicable :reason :impure}

          :else
          (let [v (ns-resolve the-ns (:name info))]
            (if-not (and v (fn? (deref v)))
              {:label :oracle-not-applicable :reason :not-a-fn}
              (let [mut-str (engine/mutated-form-string mutant orig-str)]
                (if (nil? mut-str)
                  {:label :oracle-not-applicable :reason :no-mutated-form}
                  (let [f-orig (deref v)
                        ars (seq (arities (:arglists (meta v))))
                        input-set (if (seq inputs)
                                    (vec inputs)
                                    (vec (mapcat #(gen/inputs % n-trials seed) ars)))
                        mut-form (try (read-string {:read-cond :allow} mut-str)
                                      (catch Exception _ ::read-error))]
                    (if (= mut-form ::read-error)
                      {:label :killable :witness :unreadable}
                      (try
                        (let [ev (try (binding [*ns* the-ns] (eval mut-form)) :ok
                                      (catch Throwable t t))]
                          (if (instance? Throwable ev)
                            {:label :killable :witness :compile-error}
                            (let [f-mut (deref (ns-resolve the-ns (:name info)))]
                              (if (empty? input-set)
                                {:label :oracle-not-applicable :reason :no-inputs}
                                (let [{:keys [witness applicable]}
                                      (find-witness f-orig f-mut input-set timeout-ms)]
                                  (cond
                                    witness    {:label :killable :witness witness}
                                    applicable {:label :candidate-equivalent :trials (count input-set)}
                                    :else      {:label :oracle-not-applicable :reason :orig-threw-all}))))))
                        (finally
                          (binding [*ns* the-ns]
                            (try (eval (read-string {:read-cond :allow} orig-str))
                                 (catch Throwable _ nil))))))))))))))))
