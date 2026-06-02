(ns help-spike
  "Demo: auto-generated help for babashka.cli dispatch tables, now built on the
  library API that grew out of this spike:

    - cli/format-command-help - render conventional --help text for a command
    - cli/help-error-fn        - an :error-fn that prints help on --help / errors
    - cli/*exit-fn*            - dynamic exit hook (rebound here so the REPL demo
                                 doesn't kill the process)

  Run as a real CLI via the scratch/duct wrapper, or call `run`/`-main` from a
  REPL with src on the classpath."
  (:require
   [babashka.cli :as cli]
   [clojure.string :as str]))

;; --- example dispatch table ---

(defn- act [label]
  (fn [{:keys [opts]}]
    (println (str "[run] " label
                  (when (seq (dissoc opts :help)) (str " " (pr-str (dissoc opts :help))))))))

(def dev-spec
  {:with-worker {:alias :t :coerce :boolean :desc "Also start the background worker"}
   :privileged  {:alias :p :coerce :boolean :desc "Run with elevated privileges"}
   :sync        {:alias :s :coerce :boolean :desc "Sync state before starting"}})

(def table
  [{:cmds []     :doc "example command line tool"
    :spec {:verbose {:alias :v :inherit true :desc "Verbose output"}}}
   {:cmds ["dev"] :fn (act "dev") :spec dev-spec
    :doc "Start the full dev system.\n\nWith good defaults `dev` is enough 90% of the time.\nFlags configure the worker, privileges and state sync."}
   {:cmds ["maintenance"]           :doc "Manage maintenance mode"}
   {:cmds ["maintenance" "enable"]  :fn (act "maintenance enable")  :doc "Enable maintenance mode"}
   {:cmds ["maintenance" "disable"] :fn (act "maintenance disable") :doc "Disable maintenance mode"}
   {:cmds ["deps"]              :doc "Dependency tools"
    :spec {:registry {:alias :r :inherit true :desc "Package registry URL"}}}
   {:cmds ["deps" "outdated"]   :fn (act "deps outdated")   :doc "Show outdated dependencies"
    :spec {:format {:alias :f :desc "Output format: table or edn"}
           :registry {:desc "Outdated-specific registry override"}}}
   {:cmds ["deps" "vulnerable"] :fn (act "deps vulnerable") :doc "Show vulnerable dependencies"}])

;; plain cli/dispatch + the library's help error-fn. The REPL demo rebinds
;; cli/*exit-fn* (so it doesn't kill the process); a real CLI uses the default.
(defn run [args]
  (println (str "$ bb " (str/join " " args)))
  (println "----------------------------------------")
  (binding [cli/*exit-fn* (fn [_] (throw (ex-info "exit" {::exit true})))]
    (try (cli/dispatch table args {:restrict true
                                   :error-fn (cli/help-error-fn table {:prog "bb"})})
         (catch clojure.lang.ExceptionInfo e
           (when-not (::exit (ex-data e)) (println "ERR:" (ex-message e))))))
  (println))

;; real CLI entrypoint: drive with actual argv (see scratch/duct wrapper)
(defn -main [& args]
  (cli/dispatch table (vec args)
                {:restrict true :error-fn (cli/help-error-fn table {:prog "duct"})}))

;; auto-run when loaded as a script
(when (= *file* (System/getProperty "babashka.file"))
  (run [])                                                    ; bare -> top help
  (run ["--help"])                                            ; explicit top help
  (run ["dev" "--help"])                                      ; leaf help
  (run ["maintenance"])                                       ; group, no sub -> help
  (run ["maintenance" "--help"])                              ; group help
  (run ["dev" "-tp"])                                         ; short flags, real run
  (run ["dev" "--with-worker" "--sync"])                      ; long flags, real run
  (run ["maintenance" "enable"])                              ; subcommand run
  (run ["dev" "--bogus"])                                     ; restrict -> error
  (run ["nope"]))                                             ; unknown command -> help
