(ns babashka.cli.exec
  (:require [babashka.cli :refer [coerce parse-args]]))

(defn -main
  "Main entrypoint for command line usage.
  Expects a fully qualified symbol and zero or more key value pairs.

  Example when used as a clojure CLI alias: ``` clojure -M:exec
  clojure.core/prn :a 1 :b 2 ```"
  [& [f & args]]
  (let [f (coerce f symbol)
        ns (namespace f)
        _ (require (symbol ns))
        f (resolve f)
        opts (:babashka/cli (meta f))
        opts (:opts (parse-args args opts))]
    (try (f opts)
         (finally (shutdown-agents)))))
