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

;; --- opt-in help-aware dispatch ---
;;
;; Bundles three things that belong together for a subcommand CLI:
;;   1. auto `--help`/`-h` at any level (prints format-help for that node)
;;   2. `:restrict true` by default (unknown flags error instead of silent pass)
;;   3. help-on-error (no-match / input-exhausted -> print help + commands)
;;
;; All opt-in: existing `cli/dispatch` callers are untouched.

(def ^:private help-flag
  {:help {:coerce :boolean :alias :h :desc "Show this help"}})

(defn dispatch+help
  "Like cli/dispatch but help-aware. opts must include :prog."
  [table args {:keys [prog inherit] :as opts}]
  (let [tree    (cli/table->tree table)
        node-at (fn [path] (get-in tree (interleave (repeat :cmd) (vec path))))
        prog-at (fn [path] (str/join " " (cons prog path)))
        ;; which of a level's options are inherited by descendants: those marked
        ;; :inherit, or selected by a dispatch-level :inherit (true / set of keys)
        inherit-marked (fn [spec]
                         (let [s (dissoc spec :help :h)]
                           (cond (true? inherit) s
                                 (coll? inherit) (select-keys s (set inherit))
                                 :else (into {} (filter (comp :inherit val) s)))))
        ;; options of ancestor levels usable at `path` (merged down the tree)
        inherited-at (fn [path]
                       (reduce (fn [acc i]
                                 (merge acc (inherit-marked (:spec (node-at (subvec (vec path) 0 i))))))
                               {} (range (count (vec path)))))
        ;; ancestor levels with NON-inherited options (must precede the subcommand)
        parents (fn [path]
                  (let [path (vec path)]
                    (for [i (range (count path))
                          :let [pre  (subvec path 0 i)
                                spec (dissoc (:spec (node-at pre)) :help :h)]
                          :when (seq (apply dissoc spec (keys (inherit-marked spec))))]
                      {:prog (prog-at pre)
                       :name (if (seq pre) (last pre) "global")})))
        print-help (fn [path]
                     (println (format-help (node-at path)
                                           {:prog (prog-at path)
                                            :inherited (inherited-at path)
                                            :parents (parents path)})))
        ;; per entry: inject help flag, default :restrict true, wrap :fn so
        ;; --help short-circuits and group nodes (no real :fn) print help.
        prep (fn [{f :fn :keys [spec] :as entry}]
               (-> entry
                   (assoc :spec (merge help-flag spec))
                   (update :restrict #(if (nil? %) true %))
                   (assoc :fn
                          (fn [{:keys [opts dispatch args] :as m}]
                            (cond
                              (:help opts) (print-help dispatch)
                              f            (f m)
                              ;; group/catch-all with no handler: leftover args
                              ;; mean a mistyped subcommand; otherwise just help.
                              (seq args)
                              (do (println (str "Unknown command: " (first args) "\n"))
                                  (print-help dispatch))
                              :else (print-help dispatch))))))
        ;; ensure root catch-all exists so bare `bb` / unknown prints help
        table   (cond-> table
                  (not (some (comp empty? :cmds) table))
                  (conj {:cmds []}))
        error-fn (fn [{:keys [cause dispatch wrong-input msg] :as data}]
                   (case cause
                     ;; subcommand routing failed: show help (dispatch already halted)
                     (:no-match :input-exhausted)
                     (do (when wrong-input
                           (println (str "Unknown command: " wrong-input "\n")))
                         (print-help (or dispatch [])))
                     ;; flag-level error (restrict/require/validate): report +
                     ;; halt; :dispatch now carries the subcommand path so help
                     ;; is scoped to the right level.
                     (do (println (str "Error: " msg "\n"))
                         (print-help (or dispatch []))
                         (throw (ex-info msg data)))))]
    (cli/dispatch (mapv prep table) args (assoc opts :error-fn error-fn))))

;; --- example dispatch table (ductile-flavoured) ---

(defn- act [label]
  (fn [{:keys [opts]}] (println (str "[run] " label
                                     (when (seq (dissoc opts :help)) (str " " (pr-str (dissoc opts :help))))))))

(def dev-spec
  {:with-transactor {:alias :t :coerce :boolean :desc "Also start the Datomic transactor"}
   :privileged      {:alias :p :coerce :boolean :desc "Run with secrets (unsandboxed)"}
   :sync            {:alias :s :coerce :boolean :desc "Sync local db from production first"}})

(def table
  [{:cmds []     :doc "ductile dev tooling"}
   {:cmds ["dev"] :fn (act "dev") :spec dev-spec
    :doc "Start the full dev system.\n\nWith good defaults bb dev is enough 90% of the time.\nFlags configure transactor, privilege and db sync."}
   {:cmds ["maintenance"]           :doc "Manage maintenance mode"}
   {:cmds ["maintenance" "enable"]  :fn (act "maintenance enable")  :doc "Enable maintenance mode"}
   {:cmds ["maintenance" "disable"] :fn (act "maintenance disable") :doc "Disable maintenance mode"}
   {:cmds ["deps"]              :doc "Dependency tools"
    :spec {:registry {:alias :r :inherit true :desc "Package registry URL"}}}
   {:cmds ["deps" "outdated"]   :fn (act "deps outdated")   :doc "Show outdated dependencies"
    :spec {:format {:alias :f :desc "Output format: table or edn"}}}
   {:cmds ["deps" "vulnerable"] :fn (act "deps vulnerable") :doc "Show vulnerable dependencies"}])

(defn run [args]
  (println (str "$ bb " (str/join " " args)))
  (println "----------------------------------------")
  (try (dispatch+help table args {:prog "bb"})
       (catch Exception e (println "ERR:" (ex-message e))))
  (println))

;; real CLI entrypoint: drive with actual argv (see scratch/duct wrapper)
(defn -main [& args]
  (try (dispatch+help table (vec args) {:prog "duct"})
       (catch Exception _ (System/exit 1))))

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
