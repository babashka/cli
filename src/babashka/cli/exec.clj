(ns babashka.cli.exec
  (:require
   [babashka.cli :refer [coerce parse-opts]]
   [babashka.cli.internal :refer [merge-opts]]
   [clojure.edn :as edn]
   [clojure.string :as str]))

#_:clj-kondo/ignore
(def ^:private ^:dynamic *basis* "For testing" nil)

(defn -main
  "Main entrypoint for command line usage.
  Expects a namespace and var name followed by zero or more key value
  pair arguments that will be parsed and passed to the var. If the
  first argument is map-shaped, it is read as an EDN map containing
  parse instructions.

  Example when used as a clojure CLI alias:
  ``` clojure
  clojure -M:exec clojure.core prn :a 1 :b 2
  ;;=> {:a \"1\" :b \"2\"}
  ```"
  [& args]
  (let [basis (or *basis* (some-> (System/getProperty "clojure.basis")
                                  slurp
                                  edn/read-string))
        resolve-args (:resolve-args basis)
        exec-fn (:exec-fn resolve-args)
        ns-default (:ns-default resolve-args)
        [f & args] args
        [cli-opts f args] (if (str/starts-with? f "{")
                            [(edn/read-string f) (first args) (rest args)]
                            [nil f args])
        [f args] (cond exec-fn
                       [(let [exec-sym (symbol exec-fn)]
                          (if (namespace exec-sym)
                            exec-sym
                            (symbol (str ns-default) (str exec-fn)))) (cons f args)]
                       ns-default [ns-default (cons f args)]
                       :else [f args])
        f (coerce f symbol)
        ns (namespace f)
        fq? (some? ns)
        ns (or ns f)
        ns (coerce ns symbol)
        [f args] (if fq?
                   [f args]
                   [(symbol (str ns) (first args)) (rest args)])
        f (requiring-resolve f)
        _ (assert f (str "Could not find var: " f))
        ns-opts (:org.babashka/cli (meta (find-ns ns)))
        fn-opts (:org.babashka/cli (meta f))
        exec-args (merge-opts
                   (:exec-args cli-opts)
                   (:exec-args resolve-args))
        opts (merge-opts ns-opts
                         fn-opts
                         cli-opts
                         (:org.babashka/cli resolve-args)
                         (when exec-args {:exec-args exec-args}))
        opts (parse-opts args opts)]
    (try (f opts)
         (finally (shutdown-agents)))))
