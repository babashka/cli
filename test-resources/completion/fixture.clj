#!/usr/bin/env bb
;; Fixture CLI for the shell-completion integration smoke tests
;; (script/completion-smoke.*). Run via a wrapper that puts the local
;; babashka.cli src on the classpath; (require ... :reload) overrides the bb
;; built-in. See script/completion-smoke.bash etc.
(require '[babashka.cli :as cli] :reload)

(defn- run [m] (prn m))

(def table
  [{:cmds ["deploy"] :fn run
    :doc "Deploy the app"
    :spec {:env   {:coerce :string :alias :e :desc "Target environment"
                   :complete ["dev" "staging" "prod"]}
           ;; glob chars must stay literal in the stubs
           :glob  {:coerce :string :desc "File pattern"
                   :complete ["*.txt" "*.md"]}
           ;; no :desc: a bare "-n" line is what fish's echo would eat as a flag
           :dry-run {:coerce :boolean :alias :n}
           :force {:coerce :boolean :desc "Skip confirmation"}}}
   {:cmds ["status"] :fn run :doc "Show status"}
   ;; a positional file arg with no value completion -> shell file completion
   {:cmds ["cat"] :fn run :doc "Print a file" :args->opts [:file]}])

(cli/dispatch table *command-line-args* {:prog "bbtest" :help true})
