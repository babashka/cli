(ns babashka.cli.exec
  (:require
   [babashka.cli :refer [coerce parse-opts merge-opts]]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [babashka.cli :as cli]))

#_:clj-kondo/ignore
(def ^:private ^:dynamic *basis* "For testing" nil)

(defmacro ^:private req-resolve [f]
  (if (resolve 'clojure.core/requiring-resolve)
    ;; in bb, requiring-resolve must be used in function position currently
    `(clojure.core/requiring-resolve ~f)
    `(do (require (symbol (namespace ~f)))
         (resolve ~f))))

(defn- resolve-exec-fn [ns-default exec-fn]
  (if (simple-symbol? exec-fn)
    (symbol (str ns-default) (str exec-fn))
    exec-fn))

(defn- parse-exec-opts [args]
  (let [basis (or *basis* (some->> (System/getProperty "clojure.basis")
                                   slurp
                                   (edn/read-string {:default tagged-literal})))
        resolve-args (:resolve-args basis)
        exec-fn (:exec-fn resolve-args)
        ns-default (:ns-default resolve-args)
        {:keys [cmds args]} (cli/parse-cmds args)
        [f & cmds] cmds
        [cli-opts cmds] (cond (not f) nil
                              (str/starts-with? f "{")
                              [(edn/read-string f) cmds]
                              :else [nil (cons f cmds)])
        f (case (count cmds)
            0 (resolve-exec-fn ns-default exec-fn)
            1 (let [f (first cmds)]
                (if (str/includes? f "/")
                  (symbol f)
                  (resolve-exec-fn ns-default (symbol f))))
            2 (let [[ns-default f] cmds]
                (if (str/includes? f "/")
                  (symbol f)
                  (resolve-exec-fn (symbol ns-default) (symbol f)))))
        f* f
        f (req-resolve f)
        _ (assert (ifn? f) (str "Could not resolve function: " f*))
        ns-opts (:org.babashka/cli (meta (:ns (meta f))))
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
    [f opts]))

(defn main [& args]
  (let [[f opts] (parse-exec-opts args)]
    (f (vary-meta opts assoc-in [:org.babashka/cli :exec] true))))

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
  (try (apply main args)
       (finally (shutdown-agents))))
