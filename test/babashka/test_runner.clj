(ns babashka.test-runner
  {:org.babashka/cli {:coerce {:dirs :strings
                               :nses :symbols
                               :patterns :strings
                               :vars :symbols
                               :includes :keywords
                               :excludes :keywords
                               :only :symbols}}}
  (:refer-clojure :exclude [test])
  (:require
   [clojure.test :as test]
   [cognitect.test-runner.api :as api]))

(def fail-meth (get-method test/report :fail))
(def err-meth (get-method test/report :error))

(def test-var (atom {}))

(def cmd (atom nil))

(defn print-only []
  (println)
  (println (str @cmd) ":only" (let [v (:var @test-var)
                                    v (meta v)]
                                (symbol (str (ns-name (:ns v))) (str (:name v))))))

(defmethod test/report :fail [m]
  (print-only)
  (fail-meth m))

(defmethod test/report :error [m]
  (print-only)
  (err-meth m))

(defmethod test/report :begin-test-var [m]
  (reset! test-var m))

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
