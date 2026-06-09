(ns babashka.cli.completion-test
  (:require
   [babashka.cli :as cli]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   #?@(:clj [[babashka.fs :as fs]
             [clojure.java.io :as io]])))

(defn- windows? []
  #?(:clj (fs/windows?)
     :cljs (= "win32" (.-platform js/process))))

(defn- read-snippet [shell]
  #?(:clj (slurp (io/resource (str "resources/completion/completion." shell)))
     :cljs (.readFileSync (js/require "fs")
                          (str "test/resources/completion/completion." shell) "utf8")))

;; test helpers over the private completion fns: return the candidate value strings
(defn complete [table args]
  (mapv :value (#'cli/complete-tree* (cli/table->tree table) args)))
(defn complete-options [opts args]
  (mapv :value (#'cli/complete-tree* (cli/table->tree [(assoc opts :cmds [])]) args)))

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
                         :foo-flag {:coerce :boolean
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
  (is (= #{} (set (complete-options opts ["--aopt" ""]))))
  (is (= #{} (set (complete-options opts ["--aopt" "aval"]))))
  (is (= #{"--aopt2" "--bflag" "-b"} (set (complete-options opts ["--aopt" "aval" ""]))))
  (is (= #{"--aopt" "--bflag" "-b" "-a"} (set (complete-options opts ["--aopt2" "aval2" ""]))))
  (testing "failing options"
    (is (= #{} (set (complete-options opts ["--aopt" "-"]))))
    (is (= #{} (set (complete-options opts ["--aopt" "--bflag"]))))
    ;;FIXME
    #_(is (= #{} (set (complete-options opts ["--aopt" "--bflag" ""])))))
  (testing "invalid option value"
    ;;FIXME
    #_(is (= #{} (set (complete-options opts ["--aopt2" "invalid" ""])))))
  (testing "complete option with same prefix"
    (is (= #{"--aopt" "--aopt2"} (set (complete-options opts ["--a"]))))
    (is (= #{"--aopt2"} (set (complete-options opts ["--aopt"]))))))

(deftest completion-test
  (testing "complete commands"
    (is (= #{"foo" "bar" "bar-baz"} (set (complete cmd-table [""]))))
    (is (= #{"bar" "bar-baz"} (set (complete cmd-table ["ba"]))))
    (is (= #{"bar-baz"} (set (complete cmd-table ["bar"]))))
    (is (= #{"foo"} (set (complete cmd-table ["f"])))))

  (testing "no completions for full command"
    (is (= #{} (set (complete cmd-table ["foo"])))))

  (testing "complete subcommands and options"
    (is (= #{"bar" "-f" "--foo-opt" "--foo-opt2" "-l" "--foo-flag"} (set (complete cmd-table ["foo" ""])))))

  (testing "complete suboption"
    (is (= #{"-f" "--foo-opt" "--foo-opt2" "-l" "--foo-flag"} (set (complete cmd-table ["foo" "-"])))))

  (testing "complete short-opt"
    (is (= #{} (set (complete cmd-table ["foo" "-f"]))))
    (is (= #{} (set (complete cmd-table ["foo" "-f" ""]))))
    (is (= #{} (set (complete cmd-table ["foo" "-f" "foo-val"]))))
    (is (= #{} (set (complete cmd-table ["foo" "-f" "bar"]))))
    (is (= #{} (set (complete cmd-table ["foo" "-f" "foo-flag"]))))
    (is (= #{} (set (complete cmd-table ["foo" "-f" "foo-opt2"]))))
    (is (= #{} (set (complete cmd-table ["foo" "-f" "123"]))))
    (is (= #{} (set (complete cmd-table ["foo" "-f" ":foo"]))))
    (is (= #{} (set (complete cmd-table ["foo" "-f" "true"]))))
    (is (= #{"bar" "--foo-opt2" "-l" "--foo-flag"} (set (complete cmd-table ["foo" "-f" "foo-val" ""])))))

  (testing "complete option with same prefix"
    (is (= #{"--foo-opt" "--foo-opt2" "--foo-flag"} (set (complete cmd-table ["foo" "--foo"]))))
    (is (= #{"--foo-opt2"} (set (complete cmd-table ["foo" "--foo-opt"])))))

  (testing "complete long-opt"
    (is (= #{} (set (complete cmd-table ["foo" "--foo-opt2"]))))
    (is (= #{} (set (complete cmd-table ["foo" "--foo-opt" ""]))))
    (is (= #{} (set (complete cmd-table ["foo" "--foo-opt" "foo-val"]))))
    (is (= #{} (set (complete cmd-table ["foo" "--foo-opt" "bar"]))))
    (is (= #{} (set (complete cmd-table ["foo" "--foo-opt" "foo-flag"]))))
    (is (= #{} (set (complete cmd-table ["foo" "--foo-opt" "foo-opt2"]))))
    (is (= #{} (set (complete cmd-table ["foo" "--foo-opt" "123"]))))
    (is (= #{} (set (complete cmd-table ["foo" "--foo-opt" ":foo"]))))
    (is (= #{} (set (complete cmd-table ["foo" "--foo-opt" "true"]))))
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

  (testing "complete subcommand"
    (is (= #{"--bar-opt" "--bar-flag"} (set (complete cmd-table ["foo" "--foo-flag" "bar" ""]))))
    (is (= #{"--bar-opt" "--bar-flag"} (set (complete cmd-table ["foo" "--foo-flag" "bar" "-"]))))
    (is (= #{"--bar-opt" "--bar-flag"} (set (complete cmd-table ["foo" "--foo-flag" "bar" "--"]))))
    (is (= #{"--bar-opt" "--bar-flag"} (set (complete cmd-table ["foo" "--foo-flag" "bar" "--bar-"]))))
    (is (= #{"--bar-opt"} (set (complete cmd-table ["foo" "--foo-flag" "bar" "--bar-o"]))))
    (is (= #{} (set (complete cmd-table ["foo" "--foo-flag" "bar" "--bar-opt" "a"]))))
    (is (= #{"--bar-flag"} (set (complete cmd-table ["foo" "--foo-flag" "bar" "--bar-opt" "bar-val" ""]))))))


(deftest dispatch-completion-snippet-test
  (when-not (windows?)
    (doseq [shell ["bash" "zsh" "fish" "powershell"]]
      (is (= (read-snippet shell)
             (snippet-via-cmd cmd-table {:prog "myprogram"} shell))
          shell))))

(defn- complete-out
  "Run the completion handler for `cmdline` and return its emitted
  `value\\tdescription` lines as a set of strings."
  [cmdline]
  (->> (complete-via-cmd cmd-table {:prog "myprogram"} cmdline)
       str/split-lines
       (remove str/blank?)
       set))

(deftest dispatch-completion-test
  (testing "output is shell-agnostic value<TAB>description data"
    (is (= #{"foo"} (complete-out "myprogram f"))))
  (testing "descriptions: subcommand :doc and option :desc are surfaced"
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
                  str/split-lines (remove str/blank?) set)))))
  (testing "set-valued :validate auto-completes its values (keywords -> names)"
    (is (= #{"a" "b"}
           (set (complete-options {:spec {:mode {:coerce :keyword :validate #{:a :b}}}}
                                  ["--mode" ""])))))
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
  (testing "no :complete/:complete-fn/:validate -> no value candidates"
    (is (= #{} (set (complete-options {:spec {:env {:coerce :string}}} ["--env" ""]))))))

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
                    str/split-lines
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
                str/split-lines (remove str/blank?)
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
                         "_babashka_cli_complete_node_cli_js")))))

(deftest equals-form-test
  ;; babashka.cli parses --opt=val, so completion handles it too
  (let [t [{:cmds ["deploy"]
            :spec {:env   {:coerce :string :complete ["dev" "prod"]}
                   :force {:coerce :boolean}}}]]
    (testing "value completion within the = token"
      (is (= #{"dev"} (set (complete t ["deploy" "--env=d"]))))
      (is (= #{"dev" "prod"} (set (complete t ["deploy" "--env="])))))
    (testing "a completed --opt=val does not consume the next token"
      (is (= #{"--force"} (set (complete t ["deploy" "--env=dev" ""])))))))
