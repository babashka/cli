(ns babashka.cli.exec
  (:require [babashka.cli :refer [coerce parse-args]]))

(defn -main
  "``
  (ns my-ns (:require [babashka.cli.exec :refer [-main]]))

  (defn foo
    {:babashka/cli {:coerce {:b parse-long}}}
    [{:keys [b]}] {:b b})

  (-main \"my-ns/foo\" \":b\" \"1\") ;;=> {:b 1}
  ```"
  [& [f & args]]
  (let [f (coerce f symbol)
        ns (namespace f)
        _ (require (symbol ns))
        f (resolve f)
        opts (:babashka/cli (meta f))
        opts (:opts (parse-args args opts))]
    (f opts)))
