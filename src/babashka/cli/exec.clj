(ns babashka.cli.exec
  (:require
   [babashka.cli :refer [coerce parse-opts]]
   [clojure.edn :as edn]
   [clojure.string :as str]))

(defn merge-opts [m & ms]
  (reduce #(merge-with merge %1 %2) m ms))

(defn -main
  "Main entrypoint for command line usage.
  Expects a namespace and var name followed by zero or more key value
  pair arguments.  If the first argument is map-shaped, it is read as
  an EDN map containing a `:org.babashka/cli` key with parse
  instructions.

  Example when used as a clojure CLI alias:
  ``` clojure
  clojure -M:exec clojure.core prn :a 1 :b 2
  ;;=> {:a \"1\" :b \"2\"}
  ```"
  [& args]
  (let [[f & args] args
        [cli-opts f args] (if (str/starts-with? f "{")
                        [(edn/read-string f) (first args) (rest args)]
                        [nil f args])
        basis (some-> (System/getProperty "clojure.basis")
                      slurp
                      edn/read-string)
        resolve-args (:resolve-args basis)
        exec-args (merge-opts
                   (:exec-args cli-opts)
                   (:exec-args resolve-args))
        f (coerce f symbol)
        ns (namespace f)
        fq? (some? ns)
        ns (or ns f)
        ns (coerce ns symbol)
        [f args] (if fq?
                   [f args]
                   [(symbol (str ns) (first args)) (rest args)])
        f (requiring-resolve f)
        opts (:org.babashka/cli (meta f))
        opts (merge-opts opts cli-opts (:org.babashka/cli resolve-args))
        opts (parse-opts args opts)
        opts (merge-opts exec-args opts)]
    (try (f opts)
         (finally (shutdown-agents)))))
