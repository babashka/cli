(ns help-spike
  "Spike: auto-generated help for babashka.cli dispatch tables.

  Goal: given a dispatch table (with :doc on entries + :spec for flags),
  render NAME / SYNOPSIS / DESCRIPTION / COMMANDS / FLAGS for any node,
  reusing babashka.cli's format-table / opts->table.

  The section layout follows standard man-page convention. All code here is
  written against babashka.cli's API; no code is taken from other CLI
  libraries.

  Run as a real CLI via the scratch/duct wrapper, or call `run`/`-main` from
  a REPL with src on the classpath."
  (:require
   [babashka.cli :as cli]
   [clojure.string :as str]))

;; tree building: cli/table->tree is now public; it keeps every key except
;; :cmds on the node, so :doc and :spec survive for help rendering.

;; --- helpers ---

(defn- first-line [s]
  (when s (first (str/split-lines s))))

(defn- description [s]
  ;; full doc, each line left-trimmed, leading/trailing blank lines dropped
  (when s
    (let [ls (->> (str/split-lines s)
                  (map str/triml)
                  (drop-while str/blank?)
                  reverse (drop-while str/blank?) reverse)]
      (when (seq ls) (str/join "\n" ls)))))

(defn- opt->flag
  "Render an option keyword as the flag a user types: `-x` for single-char,
  `--long` otherwise."
  [opt]
  (let [n (name opt)]
    (str (if (= 1 (count n)) "-" "--") n)))

(defn- usage-line [prog node any-options?]
  (let [children? (seq (:cmd node))]
    (str "Usage: " prog
         (when any-options? " [options]")
         (cond children?  " <command>"
               (:fn node) " [<args>]"
               :else      ""))))

(defn- commands-table [node]
  (vec
   (keep (fn [[cmd subnode]]
           (when-not (:no-doc subnode)
             [(name cmd) (or (first-line (:doc subnode)) "")]))
         (:cmd node))))

;; --- the thing ---

(defn format-help
  "Render help for a node of a dispatch tree, conventional --help style:

      Usage: <prog> [options] <command>

      <description>

      Commands:
        ...
      Options:
        ...

  opts:
    :prog      program/command name shown in the usage line (required)
    :inherited spec of options inherited from ancestor levels (`:inherit`),
               usable at this level; rendered as an `Inherited options:` section
    :parents   ancestor levels with non-inherited options, as a seq of
               {:prog <full path> :name <level name>}; rendered as pointers,
               since those options must be given before the subcommand"
  [node {:keys [prog parents inherited]}]
  (let [spec (dissoc (:spec node) :help :h)        ; hide auto-injected --help
        ;; a redefined option shows in Options (it wins); drop it from inherited
        inherited (apply dissoc inherited (keys spec))
        cmds (commands-table node)
        sections
        (cond-> [(usage-line prog node (or (seq spec) (seq inherited)))]
          (description (:doc node))
          (conj (description (:doc node)))

          (seq cmds)
          (conj (str "Commands:\n" (cli/format-table {:rows cmds :indent 2})))

          (seq spec)
          (conj (str "Options:\n"
                     (cli/format-opts {:spec spec :order (vec (keys spec))})))

          ;; inherited (`:inherit`) options are usable right here
          (seq inherited)
          (conj (str "Inherited options:\n"
                     (cli/format-opts {:spec inherited :order (vec (keys inherited))})))

          (seq cmds)
          (conj (str "Run \"" prog " <command> --help\" for more information on a command."))

          ;; non-inherited parent options must be given before the subcommand
          ;; (e.g. `duct deps --registry X outdated`)
          (seq parents)
          (conj (str/join "\n"
                          (for [{p :prog n :name} parents]
                            (str "Run \"" p " --help\" for " n " options.")))))]
    (str/join "\n\n" sections)))

;; --- help as a pluggable :error-fn ---
;;
;; No new dispatch variant. Help is delivered through `cli/dispatch`'s existing
;; `:error-fn` extension point, given `:restrict true` (so that `--help`/`-h`
;; surface as a `:restrict` "unknown option" error that the error-fn intercepts).
;; Use:
;;   (cli/dispatch table args
;;     {:restrict true :error-fn (help-error-fn table {:prog "duct"})})
;; What it handles, from the dispatch error data (note: needs babashka.cli's
;; `:dispatch` in error data, public `table->tree`):
;;   - --help / -h anywhere       -> print that level's help, exit 0
;;   - unknown subcommand         -> message + parent help, exit 1
;;   - group with no subcommand   -> that group's help, exit 0
;;   - flag error (require/...)   -> message + scoped help, exit 1

(defn ^:dynamic *exit-fn*
  "Called to terminate once help/errors are printed. Receives a map with
  `:exit` (code), `:message`, `:reason`, `:cause`. Rebind to prevent exiting
  (tests, REPL, browser) - cf. borkdude/deps.clj and cli-tools. In a real cljc
  library the default dispatches on host: System/exit (JVM), js/process.exit
  (node), throw (browser)."
  [{:keys [exit]}]
  (System/exit exit))

(defn help-error-fn
  "Build an `:error-fn` (for `cli/dispatch` with `:restrict true`) that renders
  conventional help. `opts`: :prog (program name), :inherit (same value passed
  to dispatch, for the Inherited options section). Termination goes through
  the dynamic [[*exit-fn*]]; rebind that to avoid exiting."
  [table {:keys [prog inherit]}]
  (let [tree    (cli/table->tree table)
        node-at (fn [path] (get-in tree (interleave (repeat :cmd) (vec path))))
        prog-at (fn [path] (str/join " " (cons prog path)))
        ;; which of a level's options descendants inherit: marked :inherit, or
        ;; selected by a dispatch-level :inherit (true / set of keys)
        inherit-marked (fn [spec]
                         (let [s (dissoc spec :help :h)]
                           (cond (true? inherit) s
                                 (coll? inherit) (select-keys s (set inherit))
                                 :else (into {} (filter (comp :inherit val) s)))))
        inherited-at (fn [path]
                       (reduce (fn [acc i]
                                 (merge acc (inherit-marked (:spec (node-at (subvec (vec path) 0 i))))))
                               {} (range (count (vec path)))))
        parents (fn [path]
                  (let [path (vec path)]
                    (for [i (range (count path))
                          :let [pre  (subvec path 0 i)
                                spec (dissoc (:spec (node-at pre)) :help :h)]
                          :when (seq (apply dissoc spec (keys (inherit-marked spec))))]
                      {:prog (prog-at pre)
                       :name (if (seq pre) (last pre) "global")})))
        ;; full help: for explicit --help and for a bare group
        print-help (fn [path]
                     (println (format-help (node-at path)
                                           {:prog (prog-at path)
                                            :inherited (inherited-at path)
                                            :parents (parents path)})))
        ;; terse output on misuse (cf. git / clap): no full Options dump
        hint (fn [path] (str "Run \"" (prog-at path) " --help\" for more information."))
        usage (fn [path]
                (let [node (node-at path)
                      spec (dissoc (:spec node) :help :h)]
                  (usage-line (prog-at path) node (or (seq spec) (seq (inherited-at path))))))]
    (fn [{:keys [cause option dispatch wrong-input msg] :as data}]
      (let [path (or dispatch [])]
        (cond
          ;; --help / -h: under :restrict these arrive as an unknown option
          (and (= :restrict cause) (#{:help :h} option))
          (do (print-help path) (*exit-fn* {:exit 0 :reason :help :dispatch path}))
          ;; mistyped subcommand: terse, but list the available commands
          (= :no-match cause)
          (let [cmds (commands-table (node-at path))
                message (str "Unknown command: " wrong-input)]
            (println (str message "\n"))
            (when (seq cmds)
              (println (str "Commands:\n" (cli/format-table {:rows cmds :indent 2}) "\n")))
            (println (hint path))
            (*exit-fn* {:exit 1 :reason :unknown-command :message message
                        :cause cause :dispatch path :data data}))
          ;; a group invoked with no subcommand -> full help (shows Commands)
          (= :input-exhausted cause)
          (do (print-help path) (*exit-fn* {:exit 0 :reason :help :dispatch path}))
          ;; genuine flag error (require / validate / unknown flag): terse.
          ;; render the option as the flag the user types (--foo), not :foo
          :else
          (let [msg (if option (str/replace msg (str option) (opt->flag option)) msg)]
            (println (str "Error: " msg "\n"))
            (println (usage path))
            (println)
            (println (hint path))
            (*exit-fn* {:exit 1 :reason :error :message msg
                        :cause cause :dispatch path :data data})))))))

;; --- example dispatch table (ductile-flavoured) ---

(defn- act [label]
  (fn [{:keys [opts]}] (println (str "[run] " label
                                     (when (seq (dissoc opts :help)) (str " " (pr-str (dissoc opts :help))))))))

(def dev-spec
  {:with-transactor {:alias :t :coerce :boolean :desc "Also start the Datomic transactor"}
   :privileged      {:alias :p :coerce :boolean :desc "Run with secrets (unsandboxed)"}
   :sync            {:alias :s :coerce :boolean :desc "Sync local db from production first"}})

(def table
  [{:cmds []     :doc "ductile dev tooling"
    :spec {:verbose {:alias :v :inherit true :desc "Verbose output"}}}
   {:cmds ["dev"] :fn (act "dev") :spec dev-spec
    :doc "Start the full dev system.\n\nWith good defaults bb dev is enough 90% of the time.\nFlags configure transactor, privilege and db sync."}
   {:cmds ["maintenance"]           :doc "Manage maintenance mode"}
   {:cmds ["maintenance" "enable"]  :fn (act "maintenance enable")  :doc "Enable maintenance mode"}
   {:cmds ["maintenance" "disable"] :fn (act "maintenance disable") :doc "Disable maintenance mode"}
   {:cmds ["deps"]              :doc "Dependency tools"
    :spec {:registry {:alias :r :inherit true :desc "Package registry URL"}}}
   {:cmds ["deps" "outdated"]   :fn (act "deps outdated")   :doc "Show outdated dependencies"
    :spec {:format {:alias :f :desc "Output format: table or edn"}
           :registry {:desc "Outdated-specific registry override"}}}
   {:cmds ["deps" "vulnerable"] :fn (act "deps vulnerable") :doc "Show vulnerable dependencies"}])

;; just plain cli/dispatch + the help error-fn. The REPL demo rebinds *exit-fn*
;; (so it doesn't kill the process); a real CLI uses the default System/exit.
(defn run [args]
  (println (str "$ bb " (str/join " " args)))
  (println "----------------------------------------")
  (binding [*exit-fn* (fn [_] (throw (ex-info "exit" {::exit true})))]
    (try (cli/dispatch table args {:restrict true
                                   :error-fn (help-error-fn table {:prog "bb"})})
         (catch clojure.lang.ExceptionInfo e
           (when-not (::exit (ex-data e)) (println "ERR:" (ex-message e))))))
  (println))

;; real CLI entrypoint: drive with actual argv (see scratch/duct wrapper)
(defn -main [& args]
  (cli/dispatch table (vec args)
                {:restrict true :error-fn (help-error-fn table {:prog "duct"})}))

;; auto-run when loaded as a script
(when (= *file* (System/getProperty "babashka.file"))
  (run [])                                                    ; bare -> top help
  (run ["--help"])                                            ; explicit top help
  (run ["dev" "--help"])                                      ; leaf help
  (run ["maintenance"])                                       ; group, no sub -> help
  (run ["maintenance" "--help"])                              ; group help
  (run ["dev" "-tp"])                                         ; short flags, real run
  (run ["dev" "--with-transactor" "--sync"])                 ; long flags, real run
  (run ["maintenance" "enable"])                              ; subcommand run
  (run ["dev" "--bogus"])                                     ; restrict -> error
  (run ["nope"]))                                             ; unknown command -> help
