(ns babashka.cli.exec
  (:require
   [babashka.cli :as cli :refer [merge-opts parse-opts]]
   [clojure.edn :as edn]
   [clojure.string :as str])
  (:import [java.util.concurrent Executors ThreadFactory]))

(set! *warn-on-reflection* true)

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

(defn- set-daemon-agent-executor
  "Set Clojure's send-off agent executor (also affects futures). This is almost
  an exact rewrite of the Clojure's executor, but the Threads are created as
  daemons.

  From https://github.com/clojure/brew-install/blob/271c2c5dd45ed87eccf7e7844b079355297d0974/src/main/clojure/clojure/run/exec.clj#L171"
  []
  (let [thread-counter (atom 0)
        thread-factory (reify ThreadFactory
                         (newThread [_ runnable]
                           (doto (Thread. runnable)
                             (.setDaemon true) ;; DIFFERENT
                             (.setName (format "CLI-agent-send-off-pool-%d"
                                               (first (swap-vals! thread-counter inc)))))))
        executor (Executors/newCachedThreadPool thread-factory)]
    (set-agent-send-off-executor! executor)))

(defn- parse-exec-opts [args]
  (let [basis (or *basis* (some->> (System/getProperty "clojure.basis")
                                   slurp
                                   (edn/read-string {:default tagged-literal})))
        argmap (or (:argmap basis)
                   ;; older versions of the clojure CLI
                   (:resolve-args basis))
        exec-fn (:exec-fn argmap)
        ns-default (:ns-default argmap)
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
                   (:exec-args argmap))
        opts (merge-opts ns-opts
                         fn-opts
                         cli-opts
                         (:org.babashka/cli argmap)
                         (when exec-args {:exec-args exec-args}))
        opts (parse-opts args opts)]
    [f opts]))

(defn main [& args]
  (let [[f opts] (parse-exec-opts args)]
    (set-daemon-agent-executor)
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
  (apply main args))
