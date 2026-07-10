(ns babashka.cli.completion-test
  (:require
   [babashka.cli :as cli]
   [clojure.string :as str]
   #?(:cljd [cljd.test :refer [deftest is testing]]
      :default [clojure.test :refer [deftest is testing]])
   #?@(:cljd [["dart:io" :as io]]
       :cljs [["fs" :as fs]]
       :clj [[babashka.fs :as fs]
             [clojure.java.io :as io]])))

(defn- windows? []
  #?(:cljd io/Platform.isWindows
     :clj (fs/windows?)
     :cljs (= "win32" (.-platform js/process))))

(defn- read-snippet [shell]
  #?(:cljd (.readAsStringSync (io/File. (str "test/resources/completion/completion." shell)))
     :clj (slurp (io/resource (str "resources/completion/completion." shell)))
     :cljs (fs/readFileSync (str "test/resources/completion/completion." shell) "utf8")))

;; clojure.string/split-lines drops trailing empty lines. cljd keeps them
(defn- lines* [s]
  (let [v (vec (str/split-lines s))]
    #?(:cljd (loop [v v] (if (and (seq v) (= "" (peek v))) (recur (pop v)) v))
       :default v)))

;; test helpers over the private completion fns: return the candidate value strings
(defn complete [table args]
  (mapv :value (#?(:cljd cli/complete-tree* :squint cli/complete-tree* :default #'cli/complete-tree*) (cli/table->tree table) args)))
(defn complete-options [opts args]
  (mapv :value (#?(:cljd cli/complete-tree* :squint cli/complete-tree* :default #'cli/complete-tree*) (cli/table->tree [(assoc opts :cmds [])]) args)))
;; an unconfigured value position defaults to the shell's file completion
(defn- files? [table args]
  (= [{:file-completion true}] (#?(:cljd cli/complete-tree* :squint cli/complete-tree* :default #'cli/complete-tree*) (cli/table->tree table) args)))
(defn- files-options? [opts args]
  (files? [(assoc opts :cmds [])] args))

;; drive the hidden `org.babashka.cli/completions` group: `snippet` prints the
;; install snippet, `complete` completes the shell-tokenized words after `--`. The
;; stub passes tokens; here we stand in for the shell by splitting the test line
;; (dropping the program name, keeping a trailing empty for a fresh word).
(defn- line->tokens [line]
  (vec (rest (str/split (str/triml line) #" +" -1))))
(defn- complete-via-cmd [table opts line]
  (with-out-str
    (cli/dispatch table (into ["org.babashka.cli/completions" "complete" "--shell" "zsh" "--"]
                              (line->tokens line))
                  opts)))
(defn- snippet-via-cmd [table opts shell & extra]
  (with-out-str
    (cli/dispatch table (into ["org.babashka.cli/completions" "snippet" "--shell" shell] extra) opts)))

(def cmd-table
  [{:cmds ["foo"] :spec {:foo-opt {:coerce :string
                                   :alias :f
                                   :desc "The foo option"}
                         :foo-opt2 {:coerce :string}
                         :foo-flag {:coerce :bool ; :bool and :boolean both accepted
                                    :alias :l
                                    :desc "Enable foo"}}}
   {:cmds ["foo" "bar"] :spec {:bar-opt {:coerce :keyword}
                               :bar-flag {:coerce :boolean}}}
   {:cmds ["bar"] :doc "The bar command"}
   {:cmds ["bar-baz"]}])

(def opts {:spec {:aopt {:alias :a
                         :coerce :string}
                  :aopt2 {:coerce :string
                          :validate #{"aval2"}}
                  :bflag {:alias :b
                          :coerce :boolean}}})

(deftest complete-options-test
  (is (= #{"--aopt" "--aopt2" "--bflag" "-b" "-a"} (set (complete-options opts [""]))))
  (is (= #{"--aopt" "--aopt2" "--bflag" "-b" "-a"} (set (complete-options opts ["-"]))))
  (is (= #{"--aopt" "--aopt2" "--bflag"} (set (complete-options opts ["--"]))))
  (is (= #{"--aopt" "--aopt2"} (set (complete-options opts ["--a"]))))
  (is (= #{"--bflag"} (set (complete-options opts ["--b"]))))
  (is (= #{} (set (complete-options opts ["--bflag"]))))
  (is (= #{"--aopt" "--aopt2" "-a"} (set (complete-options opts ["--bflag" ""]))))
  (testing "an unconfigured option value defaults to file completion"
    (is (files-options? opts ["--aopt" ""]))
    (is (files-options? opts ["--aopt" "aval"])))
  (is (= #{"--aopt2" "--bflag" "-b"} (set (complete-options opts ["--aopt" "aval" ""]))))
  (is (= #{"--aopt" "--bflag" "-b" "-a"} (set (complete-options opts ["--aopt2" "aval2" ""]))))
  (testing "failing options"
    (is (files-options? opts ["--aopt" "-"]))
    (is (files-options? opts ["--aopt" "--bflag"]))
    ;;FIXME
    #_(is (= #{} (set (complete-options opts ["--aopt" "--bflag" ""])))))
  (testing "invalid option value"
    ;;FIXME
    #_(is (= #{} (set (complete-options opts ["--aopt2" "invalid" ""])))))
  (testing "completing a prefix that is itself a full option still offers the longer one"
    (is (= #{"--aopt2"} (set (complete-options opts ["--aopt"]))))))

(deftest completion-test
  (testing "complete commands"
    (is (= #{"foo" "bar" "bar-baz"} (set (complete cmd-table [""]))))
    (is (= #{"bar" "bar-baz"} (set (complete cmd-table ["ba"]))))
    (is (= #{"bar-baz"} (set (complete cmd-table ["bar"]))))
    (is (= #{"foo"} (set (complete cmd-table ["f"])))))

  (testing "no completions for full command"
    (is (= #{} (set (complete cmd-table ["foo"])))))

  (testing "complete commands and options"
    (is (= #{"bar" "-f" "--foo-opt" "--foo-opt2" "-l" "--foo-flag"} (set (complete cmd-table ["foo" ""])))))

  (testing "complete suboption"
    (is (= #{"-f" "--foo-opt" "--foo-opt2" "-l" "--foo-flag"} (set (complete cmd-table ["foo" "-"])))))

  (testing "completing an option's value: files without :complete, and the partial is not mis-read as an option/number/keyword/boolean"
    (is (= #{} (set (complete cmd-table ["foo" "-f"]))))
    (is (files? cmd-table ["foo" "-f" "123"]))
    (is (files? cmd-table ["foo" "-f" ":foo"]))
    (is (files? cmd-table ["foo" "-f" "true"]))
    (testing "value consumed, the next token completes options again"
      (is (= #{"bar" "--foo-opt2" "-l" "--foo-flag"} (set (complete cmd-table ["foo" "-f" "foo-val" ""]))))))

  (testing "complete option with same prefix"
    (is (= #{"--foo-opt" "--foo-opt2" "--foo-flag"} (set (complete cmd-table ["foo" "--foo"]))))
    (is (= #{"--foo-opt2"} (set (complete cmd-table ["foo" "--foo-opt"])))))

  (testing "the long --opt form awaits a value the same way (shares the option-key path)"
    (is (files? cmd-table ["foo" "--foo-opt" ""]))
    (is (= #{"bar" "--foo-opt2" "-l" "--foo-flag"} (set (complete cmd-table ["foo" "--foo-opt" "foo-val" ""])))))

  (is (= #{"--foo-flag"} (set (complete cmd-table ["foo" "--foo-f"]))))

  (testing "complete short flag"
    (is (= #{} (set (complete cmd-table ["foo" "-l"]))))
    (is (= #{"bar" "-f" "--foo-opt" "--foo-opt2"} (set (complete cmd-table ["foo" "-l" ""])))))

  (testing "complete long flag"
    (is (= #{} (set (complete cmd-table ["foo" "--foo-flag"]))))
    (is (= #{"bar" "-f" "--foo-opt" "--foo-opt2"} (set (complete cmd-table ["foo" "--foo-flag" ""])))))

  (is (= #{"-f" "--foo-opt" "--foo-opt2"} (set (complete cmd-table ["foo" "--foo-flag" "-"]))))
  (is (= #{"bar"} (set (complete cmd-table ["foo" "--foo-flag" "b"]))))

  (testing "complete command"
    (is (= #{"--bar-opt" "--bar-flag"} (set (complete cmd-table ["foo" "--foo-flag" "bar" ""]))))
    (is (= #{"--bar-opt" "--bar-flag"} (set (complete cmd-table ["foo" "--foo-flag" "bar" "-"]))))
    (is (= #{"--bar-opt" "--bar-flag"} (set (complete cmd-table ["foo" "--foo-flag" "bar" "--"]))))
    (is (= #{"--bar-opt" "--bar-flag"} (set (complete cmd-table ["foo" "--foo-flag" "bar" "--bar-"]))))
    (is (= #{"--bar-opt"} (set (complete cmd-table ["foo" "--foo-flag" "bar" "--bar-o"]))))
    (is (files? cmd-table ["foo" "--foo-flag" "bar" "--bar-opt" "a"]))
    (is (= #{"--bar-flag"} (set (complete cmd-table ["foo" "--foo-flag" "bar" "--bar-opt" "bar-val" ""]))))))


(deftest dispatch-completion-snippet-test
  (when-not (windows?)
    (doseq [shell ["bash" "zsh" "fish" "powershell" "nushell"]]
      (is (= (read-snippet shell)
             (snippet-via-cmd cmd-table {:prog "myprogram"} shell))
          shell))))

(defn- complete-out
  "Run the completion handler for `cmdline` and return its emitted
  `value\\tdescription` lines as a set of strings."
  [cmdline]
  (->> (complete-via-cmd cmd-table {:prog "myprogram"} cmdline)
       lines*
       (remove str/blank?)
       set))

(deftest dispatch-completion-test
  (testing "output is shell-agnostic value<TAB>description data"
    (is (= #{"foo"} (complete-out "myprogram f"))))
  (testing "descriptions: command :doc and option :desc are surfaced"
    (is (= #{"bar\tThe bar command" "bar-baz"} (complete-out "myprogram ba")))
    (is (= #{"--foo-opt\tThe foo option" "--foo-opt2" "--foo-flag\tEnable foo"}
           (complete-out "myprogram foo --foo")))
    (testing "short-option aliases carry the long option's description"
      (is (= #{"--foo-opt\tThe foo option" "--foo-opt2" "--foo-flag\tEnable foo"
               "-f\tThe foo option" "-l\tEnable foo"}
             (complete-out "myprogram foo -"))))
    (testing "options with no :desc come out as a bare value (no trailing tab)"
      ;; --foo-opt2 above appears as bare \"--foo-opt2\", proving the no-desc case
      (is (contains? (complete-out "myprogram foo --foo") "--foo-opt2")))))

(deftest tree-completion-test
  (testing "a tree passed directly to dispatch completes end-to-end"
    (let [tree {:cmd {"outdated" {:fn identity :doc "Show outdated"}
                      "cache" {:doc "Manage cache"
                               :cmd {"clean" {:fn identity}}}}}]
      (is (= ["outdated\tShow outdated"]
             (-> (complete-via-cmd tree {:prog "deps"} "deps out")
                 lines*)))
      (is (= ["clean"]
             (-> (complete-via-cmd tree {:prog "deps"} "deps cache ")
                 lines*)))))
  (testing ":cmd-order on a tree node: candidates in order, unlisted hidden"
    (let [tree {:cmd-order ["c" "a"]
                :cmd {"a" {:fn identity}
                      "b" {:fn identity}
                      "c" {:fn identity}}}]
      (is (= ["c" "a"]
             (-> (complete-via-cmd tree {:prog "p"} "p ")
                 lines*)))))
  (testing "a table with more than 8 commands completes in entry order"
    (let [table (mapv (fn [i] {:cmds [(str "cmd" i)] :fn identity}) (range 10))]
      (is (= (mapv #(str "cmd" %) (range 10))
             (complete table [""]))))))

(deftest value-completion-test
  (testing ":complete as a static coll of strings"
    (let [o {:spec {:env {:coerce :string :complete ["dev" "prod"]}}}]
      (is (= #{"dev" "prod"} (set (complete-options o ["--env" ""]))))
      (testing "prefix-filtered against the partial value"
        (is (= #{"prod"} (set (complete-options o ["--env" "pr"])))))))
  (testing ":complete coll of {:value :description} maps surfaces descriptions"
    (let [t [{:cmds []
              :spec {:env {:coerce :string
                           :complete [{:value "dev" :description "Development"}
                                      {:value "prod" :description "Production"}]}}}]]
      (is (= #{"dev\tDevelopment" "prod\tProduction"}
             (->> (complete-via-cmd t {:prog "deploy"} "deploy --env ")
                  lines* (remove str/blank?) set)))))
  (testing "set-valued :validate auto-completes its values (keywords -> names)"
    (is (= #{"a" "b"}
           (set (complete-options {:spec {:mode {:coerce :keyword :validate #{:a :b}}}}
                                  ["--mode" ""])))))
  (testing "set-valued :validate auto-completes strings"
    (is (= #{"production" "staging"}
           (set (complete-options {:spec {:env {:validate #{"production" "staging"}}}}
                                  ["--env" ""])))))
  (testing "set-valued :validate auto-completes numbers"
    (is (= #{"1" "2" "3"}
           (set (complete-options {:spec {:n {:coerce :long :validate #{1 2 3}}}}
                                  ["--n" ""])))))
  (testing ":complete-fn receives :to-complete and parsed :opts (dependent completion)"
    (let [spec {:from {:coerce :string :complete ["x" "y"]}
                :to {:coerce :string
                     :complete-fn (fn [{:keys [opts]}]
                                    (when (= "x" (:from opts)) ["x1" "x2"]))}}]
      (is (= #{"x1" "x2"} (set (complete-options {:spec spec} ["--from" "x" "--to" ""]))))
      (testing "fn returns nothing when its dependency is absent"
        (is (= #{} (set (complete-options {:spec spec} ["--to" ""])))))))
  (testing ":complete-fn output is prefix-filtered by the lib (powershell has no shell-side filter)"
    ;; the fn returns an UNFILTERED list; the lib must filter it against the partial
    (let [o {:spec {:env {:coerce :string
                          :complete-fn (constantly ["dev" "staging" "prod"])}}}]
      (is (= #{"dev" "staging" "prod"} (set (complete-options o ["--env" ""]))))
      (is (= #{"staging"} (set (complete-options o ["--env" "st"]))))))
  (testing "no :complete/:complete-fn/:validate -> the shell's file completion"
    (is (files-options? {:spec {:env {:coerce :string}}} ["--env" ""])))
  (testing ":complete false opts out of the file default"
    (is (empty? (#?(:cljd cli/complete-tree* :squint cli/complete-tree* :default #'cli/complete-tree*)
                 (cli/table->tree [{:cmds [] :spec {:msg {:complete false}}}])
                 ["--msg" ""])))))

(deftest repeatable-option-test
  (testing "a single-value option drops out once used"
    (let [o {:spec {:env {:coerce :string} :verbose {:coerce :boolean}}}]
      (is (= #{"--verbose"} (set (complete-options o ["--env" "x" ""]))))))
  (testing "a list option (:coerce [...]) stays suggestable after use"
    (let [o {:spec {:file {:coerce [:string]} :verbose {:coerce :boolean}}}]
      (is (= #{"--file" "--verbose"} (set (complete-options o ["--file" "a" ""]))))))
  (testing "a :collect option stays suggestable after use"
    (let [o {:spec {:tag {:collect [] :coerce :string} :x {:coerce :boolean}}}]
      (is (= #{"--tag" "--x"} (set (complete-options o ["--tag" "a" ""])))))))

(deftest restrict-completion-test
  ;; completion works under :restrict: it is env-driven and ignores args, and the
  ;; internal completion parse does not inherit :restrict, so partial/in-progress
  ;; input never throws on "unknown option"
  (let [t [{:cmds ["deploy"] :fn identity
            :spec {:env {:coerce :string} :force {:coerce :boolean}}}]
        vals (fn [cmdline]
               (->> (complete-via-cmd t {:prog "p" :restrict true} cmdline)
                    lines*
                    (remove str/blank?)
                    (map #(first (str/split % #"\t")))
                    set))]
    (is (= #{"deploy"} (vals "p de")))
    (is (= #{"--env" "--force"} (vals "p deploy --")))
    (testing "a consumed option drops out, no throw despite :restrict"
      (is (= #{"--force"} (vals "p deploy --env x --"))))))

(deftest namespaced-keyword-value-test
  ;; kw->str keeps the namespace; name would drop it
  (let [o {:spec {:k {:coerce :keyword :validate #{:a.b/local :a.b/global}}}}]
    (is (= #{"a.b/local" "a.b/global"} (set (complete-options o ["--k" ""]))))
    (is (= #{"a.b/local"} (set (complete-options o ["--k" "a.b/lo"]))))))

(deftest completion-robustness-test
  (testing "unknown --shell for the snippet: no crash, nothing on stdout"
    (is (= "" (snippet-via-cmd cmd-table {:prog "p"} "bogus"))))
  (testing "an empty line completes the top level, no NPE"
    (is (= #{"foo" "bar" "bar-baz"}
           (->> (complete-via-cmd cmd-table {:prog "p"} "")
                lines* (remove str/blank?)
                (map #(first (str/split % #"\t"))) set)))))

(deftest completion-prog-override-test
  ;; the function name is namespaced per prog so multiple CLIs do not collide
  (let [registers? (fn [s nm]
                     (str/includes? s (str "complete -F _babashka_cli_complete_" nm " " nm)))]
    (testing ":prog is the default registered name"
      (is (registers? (snippet-via-cmd cmd-table {:prog "squint"} "bash") "squint")))
    (testing "--prog overrides :prog (renamed binary)"
      (is (registers? (snippet-via-cmd cmd-table {:prog "squint"} "bash" "--prog" "sq") "sq")))
    (testing "non-identifier chars in the name are sanitized in the function name"
      (is (str/includes? (snippet-via-cmd cmd-table {:prog "x"} "bash" "--prog" "node_cli.js")
                         "_babashka_cli_complete_node_cli_js")))
    (testing "--prog repeats to register several names (aliases)"
      ;; function named after the first name; every name registered
      (is (str/includes? (snippet-via-cmd cmd-table {:prog "x"} "bash" "--prog" "sq" "--prog" "squint")
                         "complete -F _babashka_cli_complete_sq sq squint"))
      (is (str/includes? (snippet-via-cmd cmd-table {:prog "x"} "zsh" "--prog" "sq" "--prog" "squint")
                         "compdef _babashka_cli_complete_sq sq squint")))
    #?(:cljd nil
       :clj
       (testing "the running script's file name is also registered (dev/path invocation)"
         (let [prev (System/getProperty "babashka.file")]
           (try
             (System/setProperty "babashka.file" "/some/dir/my-cli.clj")
             (is (str/includes? (snippet-via-cmd cmd-table {:prog "my-cli"} "bash")
                                "complete -F _babashka_cli_complete_my_cli my-cli my-cli.clj"))
             (testing "explicit --prog suppresses the auto file name"
               (is (not (str/includes? (snippet-via-cmd cmd-table {:prog "my-cli"} "bash" "--prog" "my-cli")
                                       "my-cli.clj"))))
             (finally
               (if prev
                 (System/setProperty "babashka.file" prev)
                 (System/clearProperty "babashka.file")))))))))

(deftest positional-completion-test
  ;; :args->opts maps positionals to spec keys, so a positional completes that
  ;; key's values (the same :complete / :validate the option form uses)
  (let [t [{:cmds ["deploy"] :args->opts [:env :region]
            :spec {:env {:complete ["dev" "prod"]}
                   :region {:validate #{:us :eu}}
                   :force {:coerce :boolean}}}]]
    (testing "first positional completes its :complete values"
      (is (= #{"prod"} (set (complete t ["deploy" "pr"])))))
    (testing "second positional completes the next key's set :validate"
      (is (= #{"eu"} (set (complete t ["deploy" "dev" "e"])))))
    (testing "a flag among the args does not count as a positional (index unshifted)"
      (is (= #{"prod"} (set (complete t ["deploy" "--force" "pr"])))))
    (testing "no positional candidates past the :args->opts mapping"
      (is (= #{} (set (complete t ["deploy" "dev" "us" "x"])))))))

(deftest positional-file-completion-test
  ;; a declared positional with no value completion -> file-completion marker line,
  ;; which the stub turns into the shell's own file completer
  (let [marker "org.babashka.cli/file-completion"
        t [{:cmds ["cat"] :args->opts [:file] :spec {:v {:coerce :boolean}}}]
        lines (fn [line] (set (lines* (complete-via-cmd t {:prog "p"} line))))]
    (testing "positional with no value-config emits the marker"
      (is (contains? (lines "p cat ") marker)))
    (testing "completing an option does not emit the marker"
      (is (not (contains? (lines "p cat --") marker))))
    (testing "a positional with value-config completes values, not files"
      (let [v [{:cmds ["deploy"] :args->opts [:env] :spec {:env {:complete ["dev"]}}}]]
        (is (not (contains? (set (lines* (complete-via-cmd v {:prog "p"} "p deploy ")))
                            marker)))))
    (testing ":complete false opts a positional out of the file default"
      (let [v [{:cmds ["run"] :args->opts [:name] :spec {:name {:complete false}}}]]
        (is (not-any? :file-completion (#?(:cljd cli/complete-tree* :squint cli/complete-tree* :default #'cli/complete-tree*) (cli/table->tree v) ["run" ""])))))
    (testing "variadic :args->opts does not hang and emits the marker"
      (let [v [{:cmds ["x"] :args->opts (cons :a (repeat :b))}]]
        (is (contains? (set (lines* (complete-via-cmd v {:prog "p"} "p x one two ")))
                       marker))))
    (testing "after a literal -- only positionals complete (no options, no commands)"
      (is (= #{marker} (lines "p cat -- "))))))

(deftest no-doc-option-test
  ;; a :no-doc option still parses but is hidden from completion and --help, like a
  ;; :no-doc command is hidden from the command list
  (let [t [{:cmds ["deploy"] :fn identity
            :spec {:env {:coerce :string} :secret {:coerce :string :no-doc true :alias :s}}}]]
    (testing "hidden from completion (long form and alias)"
      (is (= #{"--env"} (set (complete t ["deploy" "-"])))))
    (testing "hidden from --help"
      (is (not (str/includes?
                (with-out-str (cli/dispatch t ["deploy" "--help"] {:prog "p" :help true}))
                "secret"))))
    (testing "still parses"
      (is (= {:secret "x"} (cli/parse-opts ["--secret" "x"] {:spec {:secret {:no-doc true}}}))))))

(deftest description-sanitize-test
  ;; a newline/tab in a description must not break the value<TAB>desc wire protocol
  (let [t [{:cmds ["deploy"] :fn identity
            :doc "First line\nsecond line dropped"
            :spec {:env {:coerce :string :desc "Tab\there" :complete ["dev"]}}}]]
    (testing "newline in :doc is reduced to the first line"
      (is (= #{"deploy\tFirst line"}
             (->> (complete-via-cmd t {:prog "p"} "p dep") lines* (remove str/blank?) set))))
    (testing "tab in :desc is replaced with a space"
      (is (= #{"--env\tTab here"}
             (->> (complete-via-cmd t {:prog "p"} "p deploy --e") lines* (remove str/blank?) set))))))

(deftest equals-form-test
  ;; babashka.cli parses --opt=val, so completion handles it too. Candidates are
  ;; emitted as the full --opt=val token: zsh/fish/powershell match and replace
  ;; the whole current word (bash strips the wordbreak prefix in the stub)
  (let [t [{:cmds ["deploy"]
            :spec {:env   {:coerce :string :complete ["dev" "prod"]}
                   :force {:coerce :boolean}}}]]
    (testing "value completion within the = token"
      (is (= #{"--env=dev"} (set (complete t ["deploy" "--env=d"]))))
      (is (= #{"--env=dev" "--env=prod"} (set (complete t ["deploy" "--env="])))))
    (testing "a completed --opt=val does not consume the next token"
      (is (= #{"--force"} (set (complete t ["deploy" "--env=dev" ""])))))
    (testing "no file fallback inside --opt= (shells match files against the whole token)"
      (let [u [{:cmds ["deploy"] :spec {:out {:coerce :string}}}]]
        (is (empty? (#?(:cljd cli/complete-tree* :squint cli/complete-tree* :default #'cli/complete-tree*) (cli/table->tree u) ["deploy" "--out="])))))
    (testing "bash wordbreak splitting (--opt = val arrives as three tokens)"
      (let [out (fn [& toks]
                  (with-out-str
                    (cli/dispatch t (into ["org.babashka.cli/completions" "complete"
                                           "--shell" "bash" "--" "deploy"] toks)
                                  {:prog "p"})))]
        (is (= ["dev"] (lines* (out "--env" "=" "d"))))
        (is (= #{"dev" "prod"} (set (lines* (out "--env" "=")))))))))

(deftest default-option-test
  ;; a :default value must not mark the option as already used
  (let [o {:spec {:port {:default 8080} :host {}}}]
    (is (= #{"--port" "--host"} (set (complete-options o ["--"]))))))

(deftest require-completion-test
  (testing "a :require'd option does not disable used-option filtering"
    (let [o {:spec {:level {:require true} :host {}}}]
      (is (= #{"--level"} (set (complete-options o ["--host" "h" "--"]))))))
  (testing "dispatch-level :require does not kill the completion callback"
    (is (= #{"sub" "--env"}
           (->> (complete-via-cmd [{:cmds ["sub"] :fn identity}]
                                  {:require [:env] :spec {:env {}}}
                                  "p ")
                lines* (remove str/blank?) set)))))

(deftest negation-completion-test
  ;; the parser accepts --no-foo as {:foo false}, consuming no value; completion
  ;; must not treat it as an option awaiting a value
  (let [o {:spec {:verbose {:coerce :boolean} :host {}}}]
    (is (= #{"--host"} (set (complete-options o ["--no-verbose" ""]))))))

(deftest coerce-vector-boolean-test
  ;; :coerce [:boolean] is a (repeatable) flag for the parser, so for completion too
  (let [o {:spec {:verbose {:coerce [:boolean] :alias :v} :host {}}}]
    (is (= #{"--verbose" "-v" "--host"} (set (complete-options o ["--verbose" ""]))))))

(deftest inherit-completion-test
  (let [t [{:cmds [] :fn identity :spec {:verbose {:coerce :boolean :inherit true}}}
           {:cmds ["sub"] :fn identity :spec {:opt {}}}]]
    (testing "inherited options are offered at command levels"
      (is (= #{"--opt" "--verbose"} (set (complete t ["sub" "--"])))))
    (testing "a typed inherited flag is recognized as a flag"
      (is (= #{"--opt"} (set (complete t ["sub" "--verbose" "--"]))))))
  (testing "dispatch-level :inherit true"
    (is (= ["--env"]
           (mapv :value (#?(:cljd cli/complete-tree* :squint cli/complete-tree* :default #'cli/complete-tree*)
                         (cli/table->tree [{:cmds [] :spec {:env {}}}
                                           {:cmds ["sub"] :fn identity}])
                         ["sub" "--"]
                         {:inherit true}))))))

(deftest spec-shapes-test
  (testing "vec-of-pairs spec: options without :coerce/:alias still complete"
    (let [o {:spec [[:plain {:desc "No coerce"}] [:other {:coerce :string}]]}]
      (is (= #{"--plain" "--other"} (set (complete-options o ["--"]))))))
  (testing "entry-level :coerce/:alias (no :spec) completes and detects flags"
    (let [t [{:cmds ["deploy"] :fn identity
              :coerce {:force :boolean :env :string} :alias {:f :force}}]]
      (is (= #{"--force" "--env" "-f"} (set (complete t ["deploy" "-"]))))
      ;; --force is a flag: it must not swallow the next position
      (is (= #{"--env"} (set (complete t ["deploy" "--force" ""])))))))

(deftest dispatch-level-spec-test
  ;; dispatch-level :spec options parse at every level, so they complete there too
  (is (= #{"--local" "--glob"}
         (set (mapv :value (#?(:cljd cli/complete-tree* :squint cli/complete-tree* :default #'cli/complete-tree*)
                            (cli/table->tree [{:cmds ["sub"] :fn identity :spec {:local {}}}])
                            ["sub" "--"]
                            {:spec {:glob {}}}))))))

(deftest fresh-word-flag-test
  ;; powershell signals a fresh word via --fresh true (it cannot pass an empty
  ;; token: PS 5.1 / legacy argument passing drops empty-string args)
  (let [t [{:cmds ["deploy"] :fn identity :spec {:env {}}}]
        out (fn [fresh]
              (with-out-str
                (cli/dispatch t ["org.babashka.cli/completions" "complete"
                                 "--shell" "powershell" "--fresh" fresh "--" "deploy"]
                              {:prog "p"})))]
    (is (= ["--env"] (lines* (out "true"))))
    (testing "without a fresh word, deploy itself is the token being completed"
      (is (= "" (out "false"))))))

(deftest value-sanitize-test
  ;; tabs/newlines in a candidate value would corrupt the line/field wire protocol
  (let [t [{:cmds ["deploy"] :fn identity :spec {:env {:complete ["a\tb\nc"]}}}]]
    (is (= #{"a b c"}
           (->> (complete-via-cmd t {:prog "p"} "p deploy --env ")
                lines* (remove str/blank?) set)))))

(deftest prog-name-test
  ;; the program name is used as-is for shell registration (like cobra/clap/
  ;; argcomplete); only the derived completion function name is sanitized. So a
  ;; non-ASCII program name registers fine. (No injection concern: generating a
  ;; snippet requires running the program, so a hostile name already executed.)
  (let [s (snippet-via-cmd cmd-table {:prog "工具"} "zsh")]
    (is (str/includes? s "compdef _babashka_cli_complete__ 工具"))
    (is (str/includes? s "#compdef 工具"))))

(deftest all-no-doc-help-test
  ;; an all-:no-doc spec must not render a dangling empty Options: header
  (is (= "Usage: x"
         (cli/format-command-help {:table [{:cmds [] :fn identity :spec {:secret {:no-doc true}}}]
                                   :prog "x"}))))
