(ns babashka.test-runner
  {:org.babashka/cli {:coerce {:dirs :strings
                               :nses :symbols
                               :patterns :strings
                               :vars :symbols
                               :includes :keywords
                               :excludes :keywords
                               :only :symbols}}}
  (:refer-clojure :exclude [test])
  (:require [cognitect.test-runner.api :as api]))

(defn test [opts]
  (let [only (:only opts)
        opts
        (if only
          (if (qualified-symbol? only)
            (update opts :vars (fnil conj []) only)
            (update opts :nses (fnil conj []) only))
          opts)]
    (api/test opts)))
