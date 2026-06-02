(ns babashka.test-runner
  {:org.babashka/cli {:coerce {:dirs [:string]
                               :nses [:symbol]
                               :patterns [:string]
                               :vars [:symbol]
                               :includes [:keyword]
                               :excludes [:keyword]
                               :only :symbol}}}
  (:refer-clojure :exclude [test])
  (:require
   [clojure.test :as test]
   [cognitect.test-runner.api :as api]))

(def fail-meth (get-method test/report :fail))
(def err-meth (get-method test/report :error))

(def cmd (atom nil))

(defn print-only []
  ;; the test namespaces load babashka.cli.test-report, which defines a
  ;; :begin-test-var reporter; don't add a second one here (a duplicate defmethod
  ;; clobbers it). Read the current var from clojure.test's own stack instead -
  ;; *testing-vars* is conj'd onto, so `first` is the innermost (current) var.
  (when-let [v (first test/*testing-vars*)]
    (let [m (meta v)]
      (println)
      (println (str @cmd) ":only" (symbol (str (ns-name (:ns m))) (str (:name m)))))))

(defmethod test/report :fail [m]
  (print-only)
  (fail-meth m))

(defmethod test/report :error [m]
  (print-only)
  (err-meth m))

(defn test [opts]
  (let [_ (reset! cmd (:cmd opts))
        only (:only opts)
        opts
        (if only
          (if (qualified-symbol? only)
            (update opts :vars (fnil conj []) only)
            (update opts :nses (fnil conj []) only))
          opts)]
    (api/test opts)))
