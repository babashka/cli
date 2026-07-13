(ns babashka.cli-test
  (:require
   [babashka.cli :as cli]
   #?@(:cljd [] :squint [] :default [[babashka.cli.test-report]])
   [borkdude.deflet :as d]
   [clojure.string :as str]
   #?(:cljd [cljd.test :refer [deftest is testing]]
      :default [clojure.test :refer [deftest is testing]])
   #?(:cljd [cljd.edn :as edn]
      :clj [clojure.edn :as edn]
      :cljs [cljs.reader :as edn])))

(defn normalize-filename [s]
  (str/replace s "\\" "/"))

(defn regex? [x]
  #?(:clj (instance? java.util.regex.Pattern x)
     :cljd (dart/is? x RegExp)
     :cljs (regexp? x)))

(defn submap?
  "Is m1 a subset of m2? Taken from clj-kondo."
  [m1 m2]
  (cond
    (and (map? m1) (map? m2))
    (every? (fn [[k v]] (and (contains? m2 k)
                             (if (or (identical? k :filename)
                                     (identical? k :file))
                               (if (regex? v)
                                 (re-find v (normalize-filename (get m2 k)))
                                 (= (normalize-filename v)
                                    (normalize-filename (get m2 k))))
                               (submap? v (get m2 k)))))
            m1)
    (regex? m1)
    (re-find m1 m2)
    :else (= m1 m2)))

(defn foo
  {:babashka/cli {:coerce {:b edn/read-string}}}
  [{:keys [b]}]
  {:b b})

#?(:cljs (def Exception js/Error))

(deftest parse-opts-test
  (let [res (cli/parse-opts ["foo" ":b" "1"])]
    (is (submap? '{:b 1} res))
    (is (submap? {:org.babashka/cli {:args ["foo"]}} (meta res)))
    #_(is (submap? ["foo"] (cli/commands res))))
  (is (submap? '{:b 1}
               (cli/parse-opts ["foo" ":b" "1"] {:coerce {:b edn/read-string}})))
  (is (submap? '{:b 1}
               (cli/parse-opts ["foo" "--b" "1"] {:coerce {:b edn/read-string}})))
  (is (submap? '{:boo 1}
               (cli/parse-opts ["foo" ":b" "1"] {:aliases {:b :boo}
                                                 :coerce {:boo edn/read-string}})))
  (is (submap? '{:boo 1 :foo true}
               (cli/parse-opts ["--boo=1" "--foo"]
                               {:coerce {:boo edn/read-string}})))
  (is (try (cli/parse-opts [":b" "dude"] {:coerce {:b :long}})
           false
           (catch #?(:cljd Object :clj Exception
                     :cljs :default) e
             (= {:type :org.babashka/cli
                 :cause :coerce
                 :msg "Invalid value for option :b: cannot transform input \"dude\" to long"
                 :option :b
                 :flag ":b"
                 :value "dude"
                 :spec nil
                 :opts {}}
                (ex-data e)))))
  (is (submap? {:a [1 1]}
               (cli/parse-opts ["-a" "1" "-a" "1"] {:collect {:a []} :coerce {:a :long}})))
  (is (submap? {:foo :bar
                :skip true}
               (cli/parse-opts ["--skip"] {:exec-args {:foo :bar}})))
  (is (submap? {:help true}
               (cli/parse-opts ["--help"] {:exec-args {:help false}})))
  (testing "shorthands"
    (is (submap? '{:foo [a b]
                   :skip true}
                 (cli/parse-opts ["--skip" "--foo=a" "--foo=b"]
                                 {:coerce {:foo [:symbol]}}))))
  (testing "merging spec + other opts"
    (is (submap? '{:foo dude, :exec my/fn}
                 (cli/parse-opts ["--foo" "dude" "--exec" "my/fn"] {:spec {:foo {:coerce :symbol}} :coerce {:exec :symbol}}))))
  (testing "implicit true (option given without a value)"
    (is (thrown-with-msg?
         #?(:cljd Object :default Exception) #"Missing value for option --foo"
         (cli/parse-opts ["--foo" "--bar"] {:coerce {:foo :number}})))
    (is (thrown-with-msg?
         #?(:cljd Object :default Exception) #"Missing value for option --foo"
         (cli/parse-opts ["--bar" "--foo"] {:coerce {:foo :number}})))
    (is (thrown-with-msg?
         #?(:cljd Object :default Exception) #"Missing value for option :foo"
         (cli/parse-opts [":foo"] {:coerce {:foo :string}})))
    (is (thrown-with-msg?
         #?(:cljd Object :default Exception) #"Missing value for option :foo"
         (cli/parse-opts [":foo"] {:coerce {:foo [:string]}})))
    (is (thrown-with-msg?
         #?(:cljd Object :default Exception) #"Missing value for option :foo"
         (cli/parse-opts [":foo"] {:coerce {:foo :edn}})))
    (is (thrown-with-msg?
         #?(:cljd Object :default Exception) #"Missing value for option :foo"
         (cli/parse-opts [":foo"] {:coerce {:foo [:edn]}}))))
  (testing "composite opts"
    (is (= {:a true, :b true, :c true, :foo true}
           (cli/parse-opts ["--foo" "-abc"]))))
  (testing "--no- prefix"
    (is (= {:option false, :no-this-exists true}
           (cli/parse-opts ["--no-option" "--no-this-exists"] {:coerce {:no-this-exists :bool}})))
    (is (= {:args ["dude"], :opts {:option false}}
           (cli/parse-args ["--no-option" "dude"] {:coerce {:option :bool}})))
    (is (= {:opts {:option false}}
           (cli/parse-args ["--no-option"])))
    (is (= {:opts {:option false}}
           (cli/parse-args [":no-option"]))))
  (testing "--no-foo throws on non-boolean"
    (doseq [coerce-fn [:long :number :symbol :keyword :string :edn]]
      (is (thrown-with-msg?
            #?(:cljd Object :default Exception) #"Negation --no-foo invalid for option --foo"
            (cli/parse-opts ["--no-foo"] {:coerce {:foo coerce-fn}}))
          (str "for coerce to: " coerce-fn))
      (is (thrown-with-msg?
            #?(:cljd Object :default Exception) #"Negation --no-foo invalid for option --foo"
            (cli/parse-opts ["--no-foo"] {:coerce {:foo [coerce-fn]}}))
          (str "for coerce to: [" coerce-fn "]"))
      (is (thrown-with-msg?
            #?(:cljd Object :default Exception) #"Negation :no-foo invalid for option :foo"
            (cli/parse-opts [":no-foo"] {:coerce {:foo coerce-fn}}))
          (str "for coerce to: " coerce-fn))
      (is (thrown-with-msg?
            #?(:cljd Object :default Exception) #"Negation :no-foo invalid for option :foo"
            (cli/parse-opts [":no-foo"] {:coerce {:foo [coerce-fn]}}))
          (str "for coerce to: [" coerce-fn "]")))))

(deftest coerce-to-edn-test
  (testing "explicits aren't confused with implicits"
    (is (= {:foo nil} (cli/parse-opts ["--foo" "nil"] {:coerce {:foo :edn}})))
    (is (= {:foo true} (cli/parse-opts ["--foo" "true"] {:coerce {:foo :edn}})))
    (is (= {:foo false} (cli/parse-opts ["--foo" "false"] {:coerce {:foo :edn}}))))
  (testing "show behaviour is just like clojure CLI -X"
    (is (= {:foo "string"} (cli/parse-opts ["-foo" "\"string\""] {:coerce {:foo :edn}})))
    (is (= {:foo 'some-symbol} (cli/parse-opts ["-foo" "some-symbol"] {:coerce {:foo :edn}})))))

(deftest equals-value-test
  (testing "--opt=val splits on the first = only"
    (is (= {:header "k=v"} (cli/parse-opts ["--header=k=v"])))
    (is (= {:foo "bar"} (cli/parse-opts ["--foo=bar"]))))
  (testing "--opt= is an explicit empty value, not a flag"
    (is (= {:foo ""} (cli/parse-opts ["--foo="])))))

(deftest parse-opts-keywords-test
  (is (= {:version "2021a4", :no-git-tag-version true, :deps-file "foo.edn"}
         (cli/parse-opts ["2021a4" ":no-git-tag-version" ":deps-file" "foo.edn"] {:args->opts [:version] :spec {:no-git-tag-version {:coerce :boolean}}}))))

(deftest restrict-test
  (testing ":restrict true w/ spec allows opts & aliases in spec"
    (is (= {:foo "bar" :baz true}
           (cli/parse-opts ["--foo=bar" "-b"] {:spec   {:foo {} :baz {:alias :b}}
                                               :restrict true}))))
  (testing ":restrict true w/ spec throws w/ opt that is not a key nor alias in spec"
    (is (try (cli/parse-opts ["--foo=bar" "-b"]
                             {:spec   {:foo {}}
                              :restrict true})
             false
             (catch #?(:cljd Object :clj Exception
                       :cljs :default) e
               (= {:type :org.babashka/cli
                   :cause :restrict
                   :msg "Unknown option: -b"
                   :option :b
                   :flag "-b"
                   :restrict #{:foo}
                   :spec {:foo {}}
                   :opts {:foo "bar", :b true}}
                  (ex-data e))))))
  (testing ":closed #{:foo} w/ only --foo in opts is allowed"
    (is (= {:foo "bar"} (cli/parse-opts ["--foo=bar"]
                                        {:closed #{:foo}}))))
  (testing ":closed #{:foo :bar} w/ only --bar in opts is allowed"
    (is (= {:bar true} (cli/parse-opts ["--bar"]
                                       {:closed #{:foo :bar}}))))
  (testing ":closed #{:foo} w/ --foo & --bar in opts throws exception"
    (is (try (cli/parse-opts ["--foo" "--bar"]
                             {:closed #{:foo}})
             false
             (catch #?(:cljd Object :clj Exception
                       :cljs :default) e
               (= {:type :org.babashka/cli
                   :cause :restrict
                   :msg "Unknown option: --bar"
                   :option :bar
                   :flag "--bar"
                   :restrict #{:foo}
                   :spec nil
                   :opts {:foo true, :bar true}}
                  (ex-data e))))))
  (testing ":closed true w/ :aliases {:f :foo} w/ only -f in opts is allowed"
    (is (= {:foo true} (cli/parse-opts ["-f"]
                                       {:aliases {:f :foo}
                                        :closed  true}))))
  (testing ":closed true w/ :coerce {:foo :long} w/ only --foo=1 in opts is allowed"
    (is (= {:foo 1} (cli/parse-opts ["--foo=1"] {:coerce {:foo :long}
                                                 :closed true})))))

(deftest parse-opts-collect-test
  (is (submap? '{:paths ["src" "test"]}
               (cli/parse-opts [":paths" "src" "test"] {:collect {:paths []}})))
  (is (submap? '{:paths ["src" "test"]}
               (cli/parse-opts [":paths" "src" "test"] {:coerce {:paths []}})))
  (is (submap? {:paths #{"src" "test"}}
               (cli/parse-opts [":paths" "src" "test"] {:collect {:paths #{}}})))
  (is (submap? {:paths #{"src" "test"}}
               (cli/parse-opts [":paths" "src" "test"] {:coerce {:paths #{}}})))
  (is (submap? {:verbose [true]}
               (cli/parse-opts ["-v"] {:aliases {:v :verbose}
                                       :coerce {:verbose []}})))
  (is (submap? {:verbose [true true true]}
               (cli/parse-opts ["-v" "-v" "-v"] {:aliases {:v :verbose}
                                                 :coerce {:verbose []}}))))

(deftest spec-test
  (let [multi-arg-val-collect (fn [coll arg-value]
                                  (into (or coll [])
                                        (str/split arg-value #":")))
        spec {:from {:ref "<format>"
                     :desc "The input format. <format> can be edn, json or transit."
                     :coerce :keyword
                     :alias :i
                     :default-desc "edn"
                     :default :edn}
              :to {:ref "<format>"
                   :desc "The output format. <format> can be edn, json or transit."
                   :coerce :keyword
                   :alias :o
                   :default-desc "json"
                   :default :json}
              :pretty {:desc "Pretty-print output."
                       :alias :p}
              :paths {:desc "Paths of files to transform."
                      :coerce []
                      :default ["src" "test"]
                      :default-desc "src test"}
              :multi {:desc "Custom multi-arg-val test."
                      :alias :m
                      :collect multi-arg-val-collect}}]
    (is (= (str/trim "
  -i, --from <format>  The input format. <format> can be edn, json or transit.
                       (default: edn)
  -o, --to <format>    The output format. <format> can be edn, json or transit.
                       (default: json)
      --paths          Paths of files to transform. (default: src test)
  -p, --pretty         Pretty-print output.
  -m, --multi          Custom multi-arg-val test.")
           (str/trim (cli/format-opts {:spec spec
                                       :order [:from :to :paths :pretty :multi]
                                       :max-width-fn (constantly 80)}))))
    (is (= {:coerce {:from :keyword,
                     :to :keyword, :paths []},
            :alias {:i :from, :o :to, :p :pretty :m :multi},
            :exec-args {:from :edn, :to :json, :paths ["src" "test"]}
            :collect {:multi multi-arg-val-collect}}
           (cli/spec->opts spec nil)))
    (is (= (str/trim "
  -p, --pretty  Pretty-print output. (default: false)
      --paths   Paths of files to transform. (default: src test)
") (str/trim
    (cli/format-opts {:spec [[:pretty {:desc "Pretty-print output."
                                       :default false
                                       :alias :p}]
                             [:paths {:desc "Paths of files to transform."
                                      :coerce []
                                      :default ["src" "test"]
                                      :default-desc "src test"}]]}))))
    (is (submap?
          {:opts {:from :edn :to :json :paths ["src" "test"]
                  :multi ["a" "b" "c" "d" "e" "f" "g" "h"]}}
          (cli/parse-args ["--multi" "a:b" "--multi" "c:d:e" "--multi" "f" "--multi" "g" "h"]
                          {:spec spec})))
    (is (submap?
         {:opts {:from :edn, :to :json, :paths ["src" "test"]}}
         (cli/parse-args [] {:spec spec})))
    (is (submap? "  --deps/root  The root"
                 (cli/format-opts {:spec [[:deps/root {:desc "The root"}]]})))
    (is (submap?
         #:deps{:root "the-root"}
         (cli/parse-opts ["--deps/root" "the-root"]
                         {:spec [[:deps/root {:desc "The root"}]]})))

    (testing "exec-args wins over spec"
      (is (= 2 (:foo (cli/parse-opts [] {:spec {:foo {:default 1}}
                                         :exec-args {:foo 2}}))))
      (is (nil? (:foo (cli/parse-opts [] {:spec {:foo {:default 1}}
                                          :exec-args {:foo nil}})))))))

(deftest args-test
  (is (submap? {:foo true} (cli/parse-opts ["--foo" "--"])))
  (let [res (cli/parse-opts ["--foo" "--" "a"])]
    (is (submap? {:foo true} res))
    (is (submap? {:org.babashka/cli {:args ["a"]}} (meta res))))
  (is (submap? {:args ["do" "something" "--now"], :opts {:classpath "src"}}
               (cli/parse-args ["--classpath" "src" "do" "something" "--now"])))

  (is (= {:args ["do" "something"], :opts {:now true}}
         (cli/parse-args ["do" "something" "--now"])))
  (is (submap? {:args ["ssh://foo"], :opts {:force true}}
               (cli/parse-args ["--force" "ssh://foo"] {:coerce {:force :boolean}})))
  (is (submap? {:args ["ssh://foo"], :opts {:force true}}
               (cli/parse-args ["ssh://foo" "--force"] {:coerce {:force :boolean}})))
  (is (submap?
       {:args ["ssh://foo"], :opts {:paths ["src" "test"]}}
       (cli/parse-args ["--paths" "src" "test" "--" "ssh://foo"] {:coerce {:paths []}})))
  (is
   (submap?
    {:opts {:foo 'foo, :bar "bar", :baz true}}
    (cli/parse-args ["foo" "bar" "--baz"] {:args->opts [:foo :bar] :coerce {:foo :symbol}})))
  (is
   (submap? {:opts {:foo 'foo, :bar "bar", :baz true}}
            (cli/parse-args ["--baz" "foo" "bar"] {:args->opts [:foo :bar] :coerce {:foo :symbol :baz :boolean}})))
  (is
   (submap?
    {:opts {:foo 'foo, :bar "bar", :baz true}}
    (cli/parse-args ["foo" "--baz" "bar"] {:args->opts [:foo :bar] :coerce {:foo :symbol :baz :boolean}})))
  (is (= {:foo [1 2]} (cli/parse-opts ["1" "2"] {:args->opts [:foo :foo] :coerce {:foo [:int]}}))))

(deftest variadic-args->opts-test
  (testing "a non-collecting variadic :args->opts terminates, last value wins"
    (is (= {:y "c"} (cli/parse-opts ["a" "b" "c"] {:args->opts (repeat :y)})))
    (is (= {:x "a" :y "c"} (cli/parse-opts ["a" "b" "c"] {:args->opts (concat [:x] (repeat :y))}))))
  (testing "a collecting variadic :args->opts gathers the tail"
    (is (= {:y ["a" "b" "c"]}
           (cli/parse-opts ["a" "b" "c"] {:args->opts (repeat :y) :spec {:y {:coerce []}}})))
    (is (= {:first "a" :rest ["b" "c"]}
           (cli/parse-opts ["a" "b" "c"] {:args->opts (cons :first (repeat :rest)) :spec {:rest {:coerce []}}}))))
  (testing "an option token never occupies a positional slot"
    (is (= {:x "a" :force true :y "b" :other "c"}
           (cli/parse-opts ["a" "--force" "b" "--other" "c"]
                           {:args->opts [:x :y :z] :coerce {:force :boolean}})))))

(defn- err-data [f]
  (try (f) nil
       (catch #?(:cljd Object :clj Exception :cljs :default) e
         (ex-data e))))

(deftest positional-test
  (testing "error messages name the argument, not an option"
    (testing "coerce"
      (is (submap? {:cause :coerce
                    :msg "Invalid value for argument <my-arg>: cannot transform input \"x\" to long"
                    :option :my-arg
                    :arg "<my-arg>"}
                   (err-data #(cli/parse-args ["x"] {:args->opts [:my-arg]
                                                     :spec {:my-arg {:coerce :long :positional true}}})))))
    (testing "require"
      (is (submap? {:cause :require
                    :msg "Required argument: <my-arg>"
                    :option :my-arg
                    :arg "<my-arg>"}
                   (err-data #(cli/parse-args [] {:args->opts [:my-arg]
                                                  :spec {:my-arg {:require true :positional true}}})))))
    (testing "validate"
      (is (submap? {:cause :validate
                    :msg "Invalid value for argument <my-arg>: 3"
                    :arg "<my-arg>"}
                   (err-data #(cli/parse-args ["3"] {:args->opts [:my-arg]
                                                     :spec {:my-arg {:coerce :long :positional true
                                                                     :validate neg-int?}}})))))
    (testing ":ref supplies the label"
      (is (submap? {:msg "Required argument: <file>" :arg "<file>"}
                   (err-data #(cli/parse-args [] {:args->opts [:my-arg]
                                                  :spec {:my-arg {:require true :positional true
                                                                  :ref "<file>"}}}))))))
  (testing "a positional binds from a bare value"
    (is (submap? {:opts {:my-arg 42}}
                 (cli/parse-args ["42"] {:args->opts [:my-arg]
                                         :spec {:my-arg {:coerce :long :positional true}}}))))
  (testing "a positional may not be passed as an option"
    (is (submap? {:cause :restrict
                  :msg "Not an option: --my-arg (positional argument <my-arg>)"
                  :option :my-arg
                  :flag "--my-arg"}
                 (err-data #(cli/parse-args ["--my-arg" "x"] {:args->opts [:my-arg]
                                                              :spec {:my-arg {:positional true}}}))))
    (is (submap? {:msg "Not an option: :my-arg (positional argument <my-arg>)"}
                 (err-data #(cli/parse-args [":my-arg" "x"] {:args->opts [:my-arg]
                                                             :spec {:my-arg {:positional true}}}))))
    (testing "without :positional the same key is still accepted as an option"
      (is (submap? {:opts {:my-arg "x"}}
                   (cli/parse-args ["--my-arg" "x"] {:args->opts [:my-arg]
                                                     :spec {:my-arg {}}})))))
  (testing "help renders positionals under Arguments:, not Options:"
    (let [help (cli/format-command-help
                {:prog "mycli"
                 :table [{:cmds [] :fn identity
                          :args->opts [:file]
                          :spec {:file {:positional true :require true :ref "<file>" :desc "File to process"}
                                 :verbose {:coerce :boolean :desc "Print more"}}}]})]
      (is (str/includes? help "Usage: mycli [options] <file>"))
      (is (str/includes? help "Arguments:"))
      (is (str/includes? help "<file>  File to process"))
      (is (str/includes? help "--verbose"))
      ;; the positional is not rendered as an option
      (is (not (str/includes? help "--file")))))
  (testing "usage brackets an optional positional and wraps a bare :ref"
    (let [usage (fn [spec a->o]
                  (first (str/split-lines
                          (cli/format-command-help
                           {:prog "p" :table [{:cmds [] :fn identity :args->opts a->o :spec spec}]}))))]
      (is (= "Usage: p <src> [<dest>]"
             (usage {:src {:positional true :require true} :dest {:positional true}} [:src :dest])))
      (is (= "Usage: p <file>"
             (usage {:f {:positional true :require true :ref "file"}} [:f])))
      (is (= "Usage: p <f>..."
             (usage {:f {:positional true :require true}} (cons :f (repeat :f)))))
      (is (= "Usage: p [<f>...]"
             (usage {:f {:positional true}} (cons :f (repeat :f)))))
      (testing "an :args->opts key honors :ref and is bracketed when not required, with or without :positional"
        (is (= "Usage: p [options] [<source>]" (usage {:src {:ref "source"}} [:src])))
        (is (= "Usage: p [options] [<a>] [<b>]" (usage {:a {} :b {}} [:a :b])))
        (is (= "Usage: p [options] <a>" (usage {:a {:require true}} [:a]))))))
  (testing "variadic required positional label in usage"
    (let [help (cli/format-command-help
                {:prog "mycli"
                 :table [{:cmds [] :fn identity
                          :args->opts (cons :file (repeat :file))
                          :spec {:file {:positional true :require true :desc "Files"}}}]})]
      (is (str/includes? help "Usage: mycli <file>..."))
      (is (str/includes? help "Arguments:")))))

(deftest restrict-args-test
  (testing "extra args beyond :args->opts are rejected"
    (is (submap? {:cause :restrict-args
                  :msg "Unexpected argument: b"
                  :value "b"}
                 (err-data #(cli/parse-args ["a" "b"] {:restrict-args true :args->opts [:x]})))))
  (testing "exactly the declared args are accepted"
    (is (submap? {:opts {:x "a"}}
                 (cli/parse-args ["a"] {:restrict-args true :args->opts [:x]}))))
  (testing "with no :args->opts, any positional arg is unexpected"
    (is (submap? {:cause :restrict-args :msg "Unexpected argument: stray"}
                 (err-data #(cli/parse-args ["stray"] {:restrict-args true})))))
  (testing "without :restrict-args, extra args land in :args"
    (is (submap? {:args ["b" "c"] :opts {:x "a"}}
                 (cli/parse-args ["a" "b" "c"] {:args->opts [:x]})))))

(deftest dispatch-test
  (let [f (fn [m]
            m)
        g (constantly :rest)
        table [{:cmds ["add" "dep"] :fn f :coerce {:overwrite :boolean}}
               {:cmds ["dep" "add"] :fn f :spec {:overwrite {:coerce :boolean}}}
               {:cmds ["dep" "search"]
                :fn f :args->opts [:search-term :precision]
                :coerce {:precision :int}}
               {:cmds [] :fn g}]]
    (is (submap?
         {:args ["cheshire/cheshire"], :opts {}}
         (cli/dispatch table ["add" "dep" "cheshire/cheshire"])))
    (is (submap?
         {:args ["cheshire/cheshire"], :opts {:overwrite true}}
         (cli/dispatch table ["add" "dep" "--overwrite" "cheshire/cheshire"])))
    (is (submap?
         {:args ["cheshire/cheshire"], :opts {:overwrite true}}
         (cli/dispatch table ["dep" "add" "--overwrite" "cheshire/cheshire"])))
    (is (submap?
         {:args ["cheshire/cheshire"], :opts {:force true}}
         (cli/dispatch table ["add" "dep" "--force" "cheshire/cheshire"] {:coerce {:force :boolean}})))
    (is (submap?
         {:dispatch ["dep" "search"]
          :opts {:search-term "cheshire"}}
         (cli/dispatch table ["dep" "search" "cheshire"])))
    (is (submap?
         {:dispatch ["dep" "search"]
          :opts {:search-term "cheshire"
                 :precision 100}}
         (cli/dispatch table ["dep" "search" "cheshire" "100"]))))

  (testing "options of super commands"
    (d/deflet
      (def table [{:cmds ["foo" "bar"]
                   :spec {:baz {:coerce :boolean}}
                   :fn identity}
                  {:cmds ["foo" "bar" "baz"]
                   :spec {:quux {:coerce :keyword}}
                   :fn identity}])
      (is (submap? {:type :org.babashka/cli
                    :cause :input-exhausted
                    :all-commands ["foo"]}
                   (try (cli/dispatch table [])
                        (catch #?(:cljd Object :default Exception) e (ex-data e)))))
      (is (submap? {:dispatch ["foo" "bar"], :opts {:baz true}, :args ["quux"]}
                   (cli/dispatch table ["foo" "bar" "--baz" "quux"])))
      (is (submap? {:dispatch ["foo" "bar" "baz"] , :opts {:baz true :quux :xyzzy}, :args nil}
                   (cli/dispatch table ["foo" "bar" "--baz" "baz" "--quux" "xyzzy"])))))

  (testing "with global opts and conflicting options names"
    (d/deflet
      (def table [{:cmds [] :spec {:global {:coerce :boolean}}}
                  {:cmds ["foo"] :spec {:bar {:coerce :keyword}}}
                  {:cmds ["foo" "bar"]
                   :spec {:bar {:coerce :keyword}}
                   :fn identity}])
      (is (submap?
           {:dispatch ["foo" "bar"]
            :opts {:bar :bar
                   :global true}
            :args ["arg1"]}
           (cli/dispatch table ["--global" "foo" "--bar" "bar" "bar" "arg1"])))))

  (testing "distinguish options at every level"
    (d/deflet
      (def spec {:foo {:coerce :keyword}})
      (def table [{:spec spec}
                  {:cmds ["foo"]
                   :spec spec
                   :fn identity}
                  {:cmds ["foo" "bar"]
                   :fn identity
                   :spec spec}
                  {:cmds ["foo" "bar" "baz"]
                   :spec spec
                   :fn identity}])
      (is (submap?
           {:dispatch ["foo" "bar"],
            :opts {:foo :dude3},
            #_#_:opts-by-cmds
            [{:cmds [], :opts {:foo :dude1}}
             {:cmds ["foo"], :opts {:foo :dude2}}
             {:cmds ["foo" "bar"], :opts {:foo :dude3}}],
            :args ["bar" "arg1"]}
           (cli/dispatch
            table
            ["--foo" "dude1" "foo" "--foo" "dude2" "bar" "--foo" "dude3" "bar" "arg1"])))))

  (testing "with colon options"
    (d/deflet
      (def table [{:cmds ["foo"] :fn identity}])
      (is (= "my-file.edn" (-> (cli/dispatch
                                table
                                ["foo" ":deps-file" "my-file.edn"])
                               :opts :deps-file)))))

  (testing "choose most specific"
    (d/deflet
      (def table [{:cmds ["foo" "bar"] :fn identity}
                  {:cmds ["foo" "baz"] :fn identity}
                  {:cmds ["foo"] :fn identity}])
      (is (= ["foo" "bar"] (-> (cli/dispatch
                                table
                                ["foo" "bar" "baz" "--dude" "1"])
                               :dispatch)))))

  (testing "spec can be overriden"
    (d/deflet
      (def table [{:cmds ["foo" "bar"] :fn identity :spec {:version {:coerce :string}
                                                           }}
                  {:cmds ["foo"] :fn identity :spec {:version {:coerce :boolean}
                                                     :dude {:coerce :boolean}}}])
      (is (submap? {:opts {:version true}, :args ["2010"]}
                   (cli/dispatch
                    table
                    ["foo" "--version" "2010"])))
      (is (= "2010" (-> (cli/dispatch
                         table
                         ["foo" "bar" "--version" "2010"])
                        :opts :version)))
      (is (= {:dude true :version "2010"}
             (-> (cli/dispatch
                  table
                  ["foo" "--dude" "bar" "--version" "2010"])
                 :opts)))
      (testing "specific spec replaces less specific spec (no merge)"
        (is (= {:dude "some-value"}
               (-> (cli/dispatch
                    table
                    ["foo" "bar" "--dude" "some-value"])
                   :opts)))
        (testing "even if the more specific spec doesn't have a spec at all"
          (d/deflet
            (def table [{:cmds ["foo"] :fn identity
                         :spec {:version {:coerce :boolean}}}
                        {:cmds ["foo" "bar"]
                         :fn identity}])
            (is (submap?
                 {:dispatch ["foo" "bar"], :opts {:version "dude"}}
                 (cli/dispatch table ["foo" "bar" "--version" "dude"]))))))
      (def table [{:cmds ["foo"] :fn identity
                   :spec {:version {:coerce :boolean}}
                   :args->opts [:some-option]}
                  {:cmds ["foo" "bar"]
                   :fn identity
                   :spec {:version {:coerce :string}}}])
      (testing "command wins from args->opts"
        (is (= {:dispatch ["foo" "bar"], :opts {:version "2000"}, :args ["some-arg"]}
               (-> (cli/dispatch
                    table
                    ["foo" "bar" "--version" "2000" "some-arg"])))))
      (testing "dispatch errors return :dispatch key"
        ;; submap?: dispatch also enriches error data with :tree (and :prog/:inherit when set)
        (is (submap? {:type :org.babashka/cli, :dispatch ["foo" "bar"], :all-commands '("baz"), :cause :input-exhausted, :opts {}}
                     (cli/dispatch [{:cmds ["foo" "bar" "baz"] :fn identity}] ["foo" "bar"] {:error-fn identity})))
        (is (submap? {:type :org.babashka/cli, :dispatch ["foo" "bar"], :wrong-input "wrong", :all-commands '("baz"), :cause :no-match, :opts {}}
                     (cli/dispatch [{:cmds ["foo" "bar" "baz"] :fn identity}] ["foo" "bar" "wrong"] {:error-fn identity})))))))

(deftest table->tree-test
  (testing "internal represenation"
    (is (= {:babashka.cli/cmd-order ["foo"]
            :cmd
            {"foo"
             {:babashka.cli/cmd-order ["bar"]
              :cmd
              {"bar"
               {:spec {:baz {:coerce :boolean}},
                :fn identity
                :babashka.cli/cmd-order ["baz"]
                :cmd
                {"baz"
                 {:spec {:quux {:coerce :keyword}},
                  :fn identity}}}}}}}
           (cli/table->tree [{:cmds ["foo" "bar"]
                              :spec {:baz {:coerce :boolean}}
                              :fn identity}
                             {:cmds ["foo" "bar" "baz"]
                              :spec {:quux {:coerce :keyword}}
                              :fn identity}]))))
  (testing "extra entry keys (e.g. :doc) survive on the node, for help rendering"
    (is (= {:doc "root"
            :babashka.cli/cmd-order ["foo"]
            :cmd {"foo" {:doc "a foo" :fn identity}}}
           (cli/table->tree [{:cmds [] :doc "root"}
                             {:cmds ["foo"] :doc "a foo" :fn identity}]))))
  (testing "repeated mentions and repeated names at different depths: order
            recorded per node, deduped"
    (is (= {:babashka.cli/cmd-order ["foo"]
            :cmd {"foo" {:babashka.cli/cmd-order ["foo" "baz"]
                         :cmd {"foo" {:babashka.cli/cmd-order ["bar"]
                                      :cmd {"bar" {:fn identity}}}
                               "baz" {:fn identity}}}}}
           (cli/table->tree [{:cmds ["foo" "foo" "bar"] :fn identity}
                             {:cmds ["foo" "baz"] :fn identity}]))))
  (testing "idempotent: converting a tree again is a no-op that shares structure"
    (let [tree (cli/table->tree [{:cmds ["a" "b"] :fn identity}
                                 {:cmds ["c"] :fn identity}])]
      (is (= tree (cli/table->tree tree)))
      (is (identical? tree (cli/table->tree tree)))))
  (testing "a single table entry (map with :cmds) throws instead of being taken
            for a tree"
    (is (thrown? #?(:cljd cljd.core/ExceptionInfo :clj clojure.lang.ExceptionInfo :cljs :default)
                 (cli/table->tree {:cmds ["add"] :fn identity})))
    (is (thrown? #?(:cljd cljd.core/ExceptionInfo :clj clojure.lang.ExceptionInfo :cljs :default)
                 (cli/dispatch {:cmds ["add"] :fn identity} ["add"]))))
  (testing ":cmds on a nested node (table syntax inside a tree) throws too"
    (is (thrown? #?(:cljd cljd.core/ExceptionInfo :clj clojure.lang.ExceptionInfo :cljs :default)
                 (cli/table->tree {:cmd {"sub" {:cmds ["deep"] :fn identity}}}))))
  (testing "a literal :cmd on the catch-all entry merges with path-declared
            siblings, in table order"
    (let [tree (cli/table->tree [{:cmds ["a"] :fn identity :doc "A"}
                                 {:cmds [] :cmd {"b" {:fn identity :doc "B"}}}])]
      (is (= ["a" "b"] (mapv first (#?(:cljd cli/cmd-children :squint cli/cmd-children :default #'cli/cmd-children) tree))))
      (is (submap? {:dispatch ["a"]} (cli/dispatch tree ["a"])))
      (is (submap? {:dispatch ["b"]} (cli/dispatch tree ["b"]))))
    (testing "catch-all first: its children list first"
      (let [tree (cli/table->tree [{:cmds [] :cmd {"b" {:fn identity :doc "B"}}}
                                   {:cmds ["a"] :fn identity :doc "A"}])]
        (is (= ["b" "a"] (mapv first (#?(:cljd cli/cmd-children :squint cli/cmd-children :default #'cli/cmd-children) tree))))))))

(defn- run-dispatch
  "Run [[cli/dispatch]] capturing stdout+stderr and *exit-fn* calls. Returns
  `{:out <captured output> :exit <*exit-fn* arg or nil>}`."
  [table args opts]
  (let [exit (atom nil)
        out (with-out-str
              (binding [cli/*exit-fn*
                        (fn [m] (reset! exit m) (throw (ex-info "exit" {::exit true})))
                        #?@(:cljd [*err* *out*] :clj [*err* *out*] :cljs [*print-err-fn* *print-fn*])]
                (try
                  (cli/dispatch table args opts)
                  (catch #?(:cljd cljd.core/ExceptionInfo :clj clojure.lang.ExceptionInfo :cljs :default) e
                    (when-not (::exit (ex-data e)) (throw e))))))]
    {:out out :exit @exit}))

(deftest dispatch-tree-input-test
  ;; dispatch accepts a tree (the table->tree shape) directly
  (let [tree {:doc "tool"
              :spec {:verbose {:alias :v :coerce :boolean :inherit true :desc "Verbose"}}
              :cmd {"dev" {:fn identity :doc "Start dev."
                           :spec {:port {:coerce :long :desc "Port"}}}
                    "deps" {:doc "Dep tools"
                            :cmd {"outdated" {:fn identity :doc "Show outdated"}}}}}
        run (fn [args] (run-dispatch tree args {:prog "tool" :help true}))]
    (testing "nested dispatch with options at each level"
      (is (submap? {:dispatch ["dev"] :opts {:port 1234}}
                   (cli/dispatch tree ["dev" "--port" "1234"])))
      (is (submap? {:dispatch ["deps" "outdated"] :opts {}}
                   (cli/dispatch tree ["deps" "outdated"]))))
    (testing "a root :inherit option is accepted after a command"
      (is (submap? {:dispatch ["dev"] :opts {:verbose true :port 80}}
                   (cli/dispatch tree ["dev" "--verbose" "--port" "80"]))))
    (testing "--help at every level"
      (let [{:keys [out exit]} (run ["--help"])]
        (is (str/includes? out "Usage: tool [options] <command>"))
        (is (str/includes? out "Commands:"))
        (is (nil? exit)))
      (is (str/includes? (:out (run ["deps" "--help"])) "Usage: tool deps"))
      (is (str/includes? (:out (run ["deps" "outdated" "--help"])) "Usage: tool deps outdated")))
    (testing "unknown command exits via the :error-fn"
      (let [{:keys [out exit]} (run ["nope"])]
        (is (str/includes? out "Unknown command: nope"))
        (is (submap? {:exit 1 :cause :no-match} exit))))
    (testing "bare group exits via the :error-fn"
      (let [{:keys [out exit]} (run ["deps"])]
        (is (str/includes? out "No command given."))
        (is (submap? {:exit 1 :cause :input-exhausted} exit))))
    (testing "flag error exits via the :error-fn"
      (let [{:keys [out exit]} (run ["dev" "--port" "nan"])]
        (is (str/includes? out "Error:"))
        (is (submap? {:exit 1 :cause :coerce} exit))))
    (testing "a table and its equivalent tree dispatch and render help the same"
      (let [table [{:cmds [] :doc "tool"
                    :spec {:verbose {:alias :v :coerce :boolean :inherit true :desc "Verbose"}}}
                   {:cmds ["dev"] :fn identity :doc "Start dev."
                    :spec {:port {:coerce :long :desc "Port"}}}
                   {:cmds ["deps"] :doc "Dep tools"}
                   {:cmds ["deps" "outdated"] :fn identity :doc "Show outdated"}]]
        (is (= (cli/dispatch table ["dev" "-v" "--port" "1"])
               (cli/dispatch tree ["dev" "-v" "--port" "1"])))
        (is (= (cli/format-command-help {:table table :prog "tool"})
               (cli/format-command-help {:table tree :prog "tool"})))
        (is (= (cli/format-command-help {:table table :cmds ["dev"] :prog "tool"})
               (cli/format-command-help {:table tree :cmds ["dev"] :prog "tool"})))))))

;; symbol command names are a bb/JVM convenience; squint's symbol? is true for
;; strings and cljd has no runtime symbol?, so both keep string keys and skip this
#?(:squint nil :cljd nil :default
   (deftest dispatch-symbol-cmd-test
  (testing "symbol :cmd keys are stringified and dispatch like string keys"
    (let [tree {:cmd {'lock {:fn identity} 'unlock {:fn identity}}}]
      (is (= ["lock" "unlock"] (keys (:cmd (cli/table->tree tree)))))
      (is (submap? {:dispatch ["lock"] :opts {:force true}}
                   (cli/dispatch tree ["lock" "--force"])))
      (is (str/includes? (cli/format-command-help {:table tree :prog "t"}) "lock"))))
  (testing "symbol :cmds in a table are stringified too"
    (is (submap? {:dispatch ["add"]}
                 (cli/dispatch [{:cmds ['add] :fn identity} {:cmds [] :fn identity}]
                               ["add"]))))
  (testing "a symbol :cmd-order matches the stringified keys"
    (let [tree {:cmd-order ['b 'a] :cmd {'a {:fn identity} 'b {:fn identity}}}]
      (is (= ["b" "a"]
             (->> (str/split-lines (cli/format-command-help {:table tree :prog "t"}))
                  (drop-while #(not= "Commands:" %))
                  rest
                  (take-while #(str/starts-with? % "  "))
                  (mapv #(str/trim %)))))))))

;; keyword command names are a bb/JVM convenience, like symbol names above
#?(:squint nil :cljd nil :default
   (deftest dispatch-keyword-cmd-test
     (testing "keyword :cmd keys are stringified and dispatch like string keys"
       (let [tree {:cmd {:lock {:fn identity} :unlock {:fn identity}}}]
         (is (= ["lock" "unlock"] (keys (:cmd (cli/table->tree tree)))))
         (is (submap? {:dispatch ["lock"] :opts {:force true}}
                      (cli/dispatch tree ["lock" "--force"])))
         (is (str/includes? (cli/format-command-help {:table tree :prog "t"}) "lock"))))
     (testing "a namespaced keyword :cmd key keeps its namespace"
       (let [tree {:cmd {:git/push {:fn identity}}}]
         (is (= ["git/push"] (keys (:cmd (cli/table->tree tree)))))
         (is (submap? {:dispatch ["git/push"]} (cli/dispatch tree ["git/push"])))))
     (testing "keyword :cmds in a table are stringified too"
       (is (submap? {:dispatch ["add"]}
                    (cli/dispatch [{:cmds [:add] :fn identity} {:cmds [] :fn identity}]
                                  ["add"]))))
     (testing "a keyword :cmd-order matches the stringified keys"
       (let [tree {:cmd-order [:b :a] :cmd {:a {:fn identity} :b {:fn identity}}}]
         (is (= ["b" "a"]
                (->> (str/split-lines (cli/format-command-help {:table tree :prog "t"}))
                     (drop-while #(not= "Commands:" %))
                     rest
                     (take-while #(str/starts-with? % "  "))
                     (mapv #(str/trim %)))))))))

(deftest dispatch-exec-fn-test
  (testing ":exec-fn is called with just the parsed opts; :fn with the whole map"
    (is (= {:foo 1 :bar true}
           (cli/dispatch {:exec-fn identity} ["--foo" "1" "--bar"])))
    (is (= [:args :dispatch :opts]
           (sort (keys (cli/dispatch {:fn identity} ["--foo" "1"]))))))
  (testing ":exec-fn works as a subcommand and honors its :spec"
    (is (= {:x 2}
           (cli/dispatch {:cmd {"add" {:exec-fn identity :spec {:x {:coerce :long}}}}}
                         ["add" "--x" "2"]))))
  (testing "--help on an :exec-fn node renders its spec, does not call the fn"
    (let [called (atom false)
          out (with-out-str
                (cli/dispatch {:cmd {"add" {:exec-fn (fn [_] (reset! called true))
                                            :spec {:x {:desc "the x"}}}}}
                              ["add" "--help"] {:prog "t" :help true}))]
      (is (str/includes? out "Usage: t add"))
      (is (str/includes? out "the x"))
      (is (false? @called))))
  (testing ":exec-fn wins if a node has both"
    (is (= {:foo true}
           (cli/dispatch {:exec-fn identity :fn (fn [_] :never)} ["--foo"]))))
  (testing "the opts map carries the dispatch path and leftover args on its meta"
    (let [opts (cli/dispatch {:cmd {"add" {:exec-fn identity
                                           :args->opts [:y]}}}
                             ["add" "5" "extra"])]
      (is (= {:dispatch ["add"] :args ["extra"]}
             (:org.babashka/cli (meta opts)))))))

;; vars: squint has none, cljd has no var? predicate, so the #'a-command
;; literal below is clj/cljs only
#?(:squint nil :cljd nil :default
   (defn a-command
     "Does a thing"
     {:org.babashka/cli {:spec {:force {:coerce :boolean :desc "Force it"}}}}
     [opts]
     (assoc opts :ran :a-command)))

#?(:squint nil :cljd nil :default
   (deftest dispatch-var-fn-test
  (testing "a var :fn / :exec-fn contributes its spec and docstring to the node"
    (let [tree {:cmd {"do" {:exec-fn #'a-command}}}]
      (testing "spec from the var's :org.babashka/cli meta drives parsing"
        (is (= {:force true :ran :a-command}
               (cli/dispatch tree ["do" "--force"]))))
      (testing "--help shows the option from the var spec and the docstring"
        (let [help (with-out-str (cli/dispatch tree ["do" "--help"] {:prog "t" :help true}))]
          (is (str/includes? help "Does a thing"))
          (is (str/includes? help "Force it"))))
      (testing "the command listing uses the var's docstring"
        (let [help (with-out-str (cli/dispatch tree ["--help"] {:prog "t" :help true}))]
          (is (str/includes? help "Does a thing"))))))
  (testing "an explicit node :doc wins over the var docstring"
    (let [help (with-out-str
                 (cli/dispatch {:cmd {"do" {:exec-fn #'a-command :doc "Custom"}}}
                               ["--help"] {:prog "t" :help true}))]
      (is (str/includes? help "Custom"))
      (is (not (str/includes? help "Does a thing")))))))

(deftest set-validate-test
  (testing "a set :validate of strings validates, and lists the values on error"
    (is (submap? {:env "staging"}
                 (cli/parse-opts ["--env" "staging"]
                                 {:spec {:env {:validate #{"production" "staging"}}}})))
    (is (thrown-with-msg?
         #?(:cljd Object :default Exception)
         #"Invalid value for option --env: foo\. Expected one of: production, staging"
         (cli/parse-opts ["--env" "foo"]
                         {:spec {:env {:validate #{"production" "staging"}}}}))))
  (testing "a set :validate of numbers (coerced) validates, and lists the values on error"
    (is (submap? {:n 2}
                 (cli/parse-opts ["--n" "2"]
                                 {:spec {:n {:coerce :long :validate #{1 2 3}}}})))
    (is (thrown-with-msg?
         #?(:cljd Object :default Exception)
         #"Invalid value for option --n: 5\. Expected one of: 1, 2, 3"
         (cli/parse-opts ["--n" "5"]
                         {:spec {:n {:coerce :long :validate #{1 2 3}}}})))))

(defn- listed-command-names
  "Command names from the `Commands:` section of help/error output, in order."
  [s]
  (->> (str/split-lines s)
       (drop-while #(not= "Commands:" %))
       rest
       (take-while #(str/starts-with? % "  "))
       (mapv #(first (str/split (str/trim %) #" +")))))

(deftest cmd-order-test
  (testing "a table with more than 8 commands keeps entry order in help"
    (let [table (mapv (fn [i] {:cmds [(str "cmd" i)] :fn identity :doc (str "Cmd " i)})
                      (range 10))
          help (cli/format-command-help {:table table :prog "p"})]
      (is (= (mapv #(str "cmd" %) (range 10)) (listed-command-names help)))))
  (testing ":cmd-order on a tree node: sets display order, hides unlisted children"
    (let [tree {:cmd-order ["c" "a"]
                :cmd {"a" {:fn identity :doc "A"}
                      "b" {:fn identity :doc "B"}
                      "c" {:fn identity :doc "C"}}}]
      (is (= ["c" "a"] (listed-command-names (cli/format-command-help {:table tree :prog "p"}))))
      (testing "error suggestions follow the same order"
        (is (= ["c" "a"] (listed-command-names
                          (cli/format-command-error {:cause :no-match :wrong-input "nope"
                                                     :dispatch [] :prog "p" :tree tree})))))
      (testing "a hidden child still dispatches"
        (is (submap? {:dispatch ["b"]} (cli/dispatch tree ["b"]))))))
  (testing "an explicit :cmd-order on a table entry is honored, regardless of
            where sibling entries sit in the table"
    (let [group {:cmds ["g"] :cmd-order ["pub"]}
          pub {:cmds ["g" "pub"] :fn identity :doc "Pub"}
          secret {:cmds ["g" "secret"] :fn identity :doc "Secret"}
          names (fn [table]
                  (listed-command-names
                   (cli/format-command-help {:table table :cmds ["g"] :prog "p"})))]
      (is (= ["pub"] (names [group pub secret])))
      (is (= ["pub"] (names [pub secret group])))))
  (testing "duplicate names in a hand-written :cmd-order render once"
    (let [tree {:cmd-order ["a" "a"] :cmd {"a" {:fn identity :doc "A"}}}]
      (is (= ["a"] (listed-command-names (cli/format-command-help {:table tree :prog "p"}))))
      (testing "also in error suggestions (format-command-error normalizes too)"
        (is (= ["a"] (listed-command-names
                      (cli/format-command-error {:cause :no-match :wrong-input "x"
                                                 :dispatch [] :prog "p" :tree tree})))))))
  (testing "hiding all children does not change the usage line: a command is
            still expected at runtime"
    (is (= "Usage: p <command>"
           (cli/format-command-help {:table {:cmd-order [] :cmd {"a" {:fn identity}}}
                                     :prog "p"}))))
  (testing ":help true does not change command order (sorted tree, >8 children)"
    (let [tree {:cmd (into #?(:squint {} :default (sorted-map))
                           (map (fn [i] [(str "c" i) {:fn identity}]))
                           (range 10))}
          expected (mapv #(str "c" %) (range 10))]
      (is (= expected (listed-command-names
                       (:out (run-dispatch tree ["--help"] {:prog "p" :help true}))))))))

(deftest cmd-tree-vector-test
  (testing "a vector :cmd (pairs) preserves child order in help"
    (let [tree {:cmd [["zebra" {:fn identity :doc "Z"}]
                      ["apple" {:fn identity :doc "A"}]
                      ["mango" {:fn identity :doc "M"}]]}]
      (is (= ["zebra" "apple" "mango"]
             (listed-command-names (cli/format-command-help {:table tree :prog "p"}))))))
  (testing "a vector :cmd dispatches like a map"
    (let [tree {:cmd [["apple" {:fn identity}] ["mango" {:fn identity}]]}]
      (is (submap? {:dispatch ["mango"]} (cli/dispatch tree ["mango"])))))
  (testing "symbol command names in a vector :cmd are stringified"
    (let [tree {:cmd [['add {:fn identity :doc "Add"}]
                      ['sub {:fn identity :doc "Sub"}]]}]
      (is (= ["add" "sub"]
             (listed-command-names (cli/format-command-help {:table tree :prog "p"}))))))
  (testing "a nested vector :cmd preserves order"
    (let [tree {:cmd [["a" {:cmd [["a2" {:fn identity :doc "A2"}]
                                  ["a1" {:fn identity :doc "A1"}]]}]]}]
      (is (= ["a2" "a1"]
             (listed-command-names (cli/format-command-help {:table tree :cmds ["a"] :prog "p"})))))))

(deftest no-keyword-opts-test (is (= {:query [:a :b :c]}
                                     (cli/parse-opts
                                      ["--query" ":a" ":b" ":c"]
                                      {:no-keyword-opts true
                                       :coerce {:query [:edn]}}))))

(deftest auto-coerce-test
  (is (submap? {:foo true} (cli/parse-opts ["--foo" "true"])))
  (is (submap? {:foo false} (cli/parse-opts ["--foo" "false"])))
  (is (submap? {:foo 123} (cli/parse-opts ["--foo" "123"])))
  (is (submap? {:foo :bar} (cli/parse-opts ["--foo" ":bar"])))
  (is (submap? {:foo :bar} (cli/parse-opts ["--foo" "bar"] {:coerce {:foo :keyword}})))
  (is (submap? {:foo :bar} (cli/parse-opts ["--foo" ":bar"] {:coerce {:foo :keyword}})))
  (is (submap? {:foo [:bar :baz]} (cli/parse-opts ["--foo" ":bar" ":baz"] {:coerce {:foo []}})))
  (is (submap? {:foo [:bar :baz]} (cli/parse-opts ["--foo" ":bar" ":baz"] {:coerce {:foo [:keyword]}})))
  (testing "auto-coerce multiple keywords in keywords mode"
    (is (submap? {:foo [:bar :baz]} (cli/parse-opts [":foo" ":bar" ":foo" ":baz"] {:coerce {:foo []}}))))
  (is (= 1 (cli/auto-coerce 1)))
  (testing "We want to catch most normal keywords, staying close to the Clojure reader."
    (is (= "1. This is a title." (cli/auto-coerce "1. This is a title.")))
    (is (= ":1. This is a title." (cli/auto-coerce ":1. This is a title.")))
    (is (= :abc (cli/auto-coerce ":abc")))
    (is (= :abc-def (cli/auto-coerce ":abc-def")))
    (is (= :a/b (cli/auto-coerce ":a/b")))
    (is (= (keyword "a/b/c") (cli/auto-coerce ":a/b/c")))
    (is (= ":a.b c.d" (cli/auto-coerce ":a.b c.d")))
    (is (= ":a.b\tc.d" (cli/auto-coerce ":a.b\tc.d"))))
  (is (nil? (cli/auto-coerce "nil")))
  (is (= -10 (cli/auto-coerce "-10")))
  (is (submap? {:foo -10} (cli/parse-opts ["--foo" "-10"])))
  (is (submap? {:foo -10} (cli/parse-opts ["--foo" "-10"] {:coerce {:foo :number}})))
  (is (submap? {:foo "-10"} (cli/parse-opts ["--foo" "-10"] {:coerce {:foo :string}})))
  (is (submap? {:6 true} (cli/parse-opts ["-6"] {:spec {:6 {}}})))
  (is (submap? {:6 true} (cli/parse-opts ["-6"] {:coerce {:6 :boolean}})))
  (is (submap? {:ipv6 true} (cli/parse-opts ["-6"] {:aliases {:6 :ipv6}}))))

(deftest format-opts-test
  (testing ":order is honored for a vec-of-pairs spec"
    (is (= "  --b  B\n  --a  A"
           (cli/format-opts {:spec [[:a {:desc "A"}] [:b {:desc "B"}]]
                             :order [:b :a]}))))
  (testing "default width with default and default-desc"
    (is (= "  -f, --foo <foo>  Thingy (default: yupyupyupyup)\n  -b, --bar <bar>  Barbarbar (default: Mos def)"
           (cli/format-opts
            {:spec {:foo {:alias :f, :default "yupyupyupyup", :ref "<foo>"
                          :desc "Thingy"}
                    :bar {:alias :b, :default "sure", :ref "<bar>"
                          :desc "Barbarbar" :default-desc "Mos def"}}}))))
  (testing "required options show (required) in the description (in the default slot)"
    (let [expected (str "  --token <token>  API token (required)\n"
                        "  --paths <path>   Search paths (default: ., src)")
          paths {:ref "<path>" :desc "Search paths"
                 :default ["." "src"] :default-desc "., src"}]
      (testing "via per-option :require true"
        (is (= expected
               (cli/format-opts
                {:spec {:token {:ref "<token>" :desc "API token" :require true}
                        :paths paths}
                 :order [:token :paths]}))))
      (testing "via the top-level :required coll (same effective set)"
        (is (= expected
               (cli/format-opts
                {:spec {:token {:ref "<token>" :desc "API token"}
                        :paths paths}
                 :required [:token]
                 :order [:token :paths]}))))))
  (testing ":negatable opts in to showing --[no-]name; others stay --name"
    (is (= "  --[no-]color  Use color\n  --verbose     Be verbose"
           (cli/format-opts
            {:spec {:color   {:coerce :boolean :negatable true :desc "Use color"}
                    :verbose {:coerce :boolean :desc "Be verbose"}}
             :order [:color :verbose]}))))
  (testing "header"
    (is (= "  alias option ref   default      description\n  -f,   --foo  <foo> yupyupyupyup Thingy\n  -b,   --bar  <bar> Mos def      Barbarbar"
           (cli/format-table
            {:rows (concat [["alias" "option" "ref" "default" "description"]]
                           (cli/opts->table
                            {:spec {:foo {:alias :f, :default "yupyupyupyup", :ref "<foo>"
                                          :desc "Thingy"}
                                    :bar {:alias :b, :default "sure", :ref "<bar>"
                                          :desc "Barbarbar" :default-desc "Mos def"}}}))
             :indent 2}))))
  (testing "custom columns exclusion"
    (is (= '[("--foo" "<foo>" "yupyupyupyup" "Thingy") ("--bar" "<bar>" "Mos def" "Barbarbar")]
           (cli/opts->table
            {:spec {:foo {:alias :f, :default "yupyupyupyup", :ref "<foo>"
                          :desc "Thingy"}
                    :bar {:alias :b, :default "sure", :ref "<bar>"
                          :desc "Barbarbar" :default-desc "Mos def"}}
             ;; No alias column.
             :columns [:default :ref :desc]}))))
  (testing "custom columns forced inclusion"
    (is (= '[("--foo" "" "yupyupyupyup" "Thingy") ("--bar" "" "Mos def" "Barbarbar")]
           (cli/opts->table
            {:spec {:foo {:alias :f, :default "yupyupyupyup"
                          :desc "Thingy"}
                    :bar {:alias :b, :default "sure"
                          :desc "Barbarbar" :default-desc "Mos def"}}
             ;; Include ref column, although not present in spec.
             :columns [:default :ref :desc]})))))

(deftest format-command-help-test
  (let [table [{:cmds [] :spec {:verbose {:alias :v :inherit true :desc "Verbose output"}}}
               {:cmds ["copy"]   :fn identity :doc "Copy a file"
                :spec {:dry-run {:desc "Do a dry run"}}}
               {:cmds ["delete"] :fn identity :doc "Delete a file"
                :spec {:recursive {:alias :r :desc "Delete recursively"}}}]]
    (testing "top level: usage, commands, options, pointer"
      (is (= (str "Usage: example [options] <command>\n\n"
                  "Commands:\n  copy   Copy a file\n  delete Delete a file\n\n"
                  "Options:\n  -v, --verbose  Verbose output\n\n"
                  "Run \"example <command> --help\" for more information on a command.")
             (cli/format-command-help {:table table :prog "example"}))))
    (testing "leaf: own options + option inherited from an ancestor"
      (is (= (str "Usage: example copy [options]\n\n"
                  "Copy a file\n\n"
                  "Options:\n  --dry-run  Do a dry run\n\n"
                  "Inherited options:\n  -v, --verbose  Verbose output")
             (cli/format-command-help {:table table :cmds ["copy"] :prog "example"}))))
    (testing "a table or a prebuilt tree both work"
      (is (= (cli/format-command-help {:table table :cmds ["copy"] :prog "example"})
             (cli/format-command-help {:table (cli/table->tree table) :cmds ["copy"] :prog "example"}))))
    (testing "a redefined inherited option shows only under Options (child wins)"
      (let [t [{:cmds [] :spec {:x {:inherit true :desc "global x"}}}
               {:cmds ["sub"] :fn identity :spec {:x {:desc "local x"}}}]]
        (is (= (str "Usage: p sub [options]\n\n"
                    "Options:\n  --x  local x")
               (cli/format-command-help {:table t :cmds ["sub"] :prog "p"})))))
    (testing ":args->opts renders labeled positionals in the usage line"
      (let [t [{:cmds ["copy"] :fn identity :doc "Copy" :args->opts [:src :dest]
                :spec {:force {:desc "Force"}}}]]
        (is (str/starts-with? (cli/format-command-help {:table t :cmds ["copy"] :prog "p"})
                              "Usage: p copy [options] [<src>] [<dest>]\n")))
      (testing "the (cons k (repeat k')) variadic form renders <k'>..."
        (let [t [{:cmds ["rm"] :fn identity :doc "Remove" :args->opts (cons :first (repeat :file))}]]
          (is (str/starts-with? (cli/format-command-help {:table t :cmds ["rm"] :prog "p"})
                                "Usage: p rm [<first>] [<file>...]\n"))))
      (testing "no :args->opts shows no positional placeholder"
        (let [t [{:cmds ["ls"] :fn identity :doc "List" :spec {:all {:desc "All"}}}]]
          (is (str/starts-with? (cli/format-command-help {:table t :cmds ["ls"] :prog "p"})
                                "Usage: p ls [options]\n")))))
    (testing ":epilog is rendered verbatim after the options"
      (let [t [{:cmds ["search"] :fn identity :doc "Search" :spec {:limit {:desc "Max"}}
                :epilog "Examples:\n\n  p search foo"}]]
        (is (= (str "Usage: p search [options]\n\n"
                    "Search\n\n"
                    "Options:\n  --limit  Max\n\n"
                    "Examples:\n\n  p search foo")
               (cli/format-command-help {:table t :cmds ["search"] :prog "p"})))))
    (testing "an entry :order sets the Options order; a vec-of-pairs spec keeps its order"
      (let [t [{:cmds [] :spec {:a {:desc "A"} :b {:desc "B"} :c {:desc "C"}} :order [:c :a :b]}]]
        (is (= (str "Usage: p [options]\n\n"
                    "Options:\n  --c  C\n  --a  A\n  --b  B")
               (cli/format-command-help {:table t :prog "p"}))))
      (let [t [{:cmds [] :spec [[:c {:desc "C"}] [:a {:desc "A"}] [:b {:desc "B"}]]}]]
        (is (= (str "Usage: p [options]\n\n"
                    "Options:\n  --c  C\n  --a  A\n  --b  B")
               (cli/format-command-help {:table t :prog "p"})))))
    (testing "a custom :help-fn can call format-command-help and add to it"
      (let [t [{:cmds [] :fn identity :doc "t" :spec {:a {:desc "A"}}}]
            {:keys [out]} (run-dispatch t ["--help"]
                                        {:prog "p" :help true
                                         :help-fn (fn [{:keys [tree dispatch prog inherit]}]
                                                    (println "BANNER")
                                                    (println (cli/format-command-help
                                                              {:table tree :cmds dispatch :prog prog :inherit inherit})))})]
        (is (str/includes? out "BANNER"))
        (is (str/includes? out "Usage: p"))))))

(deftest format-command-error-test
  (let [table [{:cmds [] :doc "tool"}
               {:cmds ["dev"] :fn identity :doc "Start dev."
                :spec {:port {:coerce :long :require true :desc "Port"}}}
               {:cmds ["deps"] :doc "Dep tools"}
               {:cmds ["deps" "outdated"] :fn identity :doc "Show outdated"}]]
    (testing ":no-match renders the bad input, the commands, and a hint"
      (let [s (cli/format-command-error {:cause :no-match :wrong-input "nope"
                                         :dispatch [] :prog "tool"
                                         :tree (cli/table->tree table)})]
        (is (= (str "Unknown command: nope\n\n"
                    "Commands:\n  dev  Start dev.\n  deps Dep tools\n\n"
                    "Run \"tool --help\" for more information.")
               s))))
    (testing ":input-exhausted (incomplete multi-word command) renders the group's commands"
      (let [s (cli/format-command-error {:cause :input-exhausted
                                         :dispatch ["deps"] :prog "tool"
                                         :tree (cli/table->tree table)})]
        (is (str/includes? s "No command given."))
        (is (str/includes? s "outdated"))
        (is (str/includes? s "Run \"tool deps --help\" for more information."))))
    (testing "a flag error renders Error + usage + hint"
      (let [s (cli/format-command-error {:cause :require :msg "Missing option: --port"
                                         :dispatch ["dev"] :prog "tool"
                                         :tree (cli/table->tree table)})]
        (is (str/includes? s "Error: Missing option: --port"))
        (is (str/includes? s "Usage: tool dev"))
        (is (str/includes? s "Run \"tool dev --help\" for more information."))))
    (testing "a custom :error-fn can call format-command-error and add to it"
      (let [{:keys [out exit]}
            (run-dispatch table ["nope"]
                          {:prog "tool" :help true
                           :error-fn (fn [data]
                                       (println (cli/format-command-error data))
                                       (println "See https://example.com/docs")
                                       (cli/*exit-fn* {:exit 1 :cause (:cause data)}))})]
        (is (str/includes? out "Unknown command: nope"))
        (is (str/includes? out "See https://example.com/docs"))
        (is (= {:exit 1 :cause :no-match} exit))))))

(deftest help-option-test
  ;; `:help` on dispatch: help without :restrict, native --help interception
  (let [table [{:cmds [] :doc "tool"
                :spec {:verbose {:alias :v :inherit true :desc "Verbose"}}}
               {:cmds ["dev"] :fn identity :doc "Start dev."
                :spec {:sync {:coerce :boolean :desc "Sync"}}}
               {:cmds ["deps"] :doc "Dep tools"}
               {:cmds ["deps" "outdated"] :fn identity :doc "Show outdated"}]
        run (fn [args]
              (let [ran (atom nil)
                    table (mapv (fn [e] (cond-> e (:fn e) (assoc :fn (fn [m] (reset! ran m))))) table)]
                ;; NOTE: no :restrict
                (assoc (run-dispatch table args {:prog "tool" :help true})
                       :ran @ran)))]
    (testing "--help at root, no :restrict needed (success: prints, no *exit-fn*)"
      (let [{:keys [out exit]} (run ["--help"])]
        (is (str/includes? out "Usage: tool [options] <command>"))
        (is (str/includes? out "Commands:"))
        (is (nil? exit))))                  ; help is not an error - *exit-fn* not called
    (testing "--help after a command renders that command's help"
      (let [{:keys [out exit]} (run ["deps" "outdated" "--help"])]
        (is (str/includes? out "Usage: tool deps outdated"))
        (is (nil? exit))))
    (testing "-h alias works"
      (let [{:keys [out exit]} (run ["dev" "-h"])]
        (is (str/includes? out "Usage: tool dev"))
        (is (nil? exit))))
    (testing "a normal run is not intercepted"
      (let [{:keys [ran exit]} (run ["dev" "--sync"])]
        (is (nil? exit))
        (is (= {:sync true} (:opts ran)))))
    (testing "bad command renders help via the installed error-fn, exit 1"
      (let [{:keys [out exit]} (run ["nope"])]
        (is (str/includes? out "Unknown command: nope"))
        (is (submap? {:exit 1 :cause :no-match} exit))))
    (testing "bare group is a usage error, exit 1"
      (let [{:keys [out exit]} (run ["deps"])]
        (is (str/includes? out "Commands:"))
        (is (submap? {:exit 1 :cause :input-exhausted :dispatch ["deps"]} exit))))
    (testing ":help true works (no :prog; usage line omits it)"
      (let [{:keys [out exit]} (run-dispatch [{:cmds [] :doc "t"} {:cmds ["go"] :fn identity :doc "Go"}]
                                             ["--help"] {:help true})]
        (is (str/includes? out "Commands:"))
        (is (nil? exit))))
    (testing "*exit-fn* codes can be remapped by :cause (e.g. group -> 0)"
      (let [calls (atom [])]
        (binding [cli/*exit-fn* (fn [m] (swap! calls conj m))]
          (with-out-str
            (cli/dispatch table ["deps"] {:prog "tool" :help true})))
        (is (= :input-exhausted (:cause (first @calls))))
        (is (= 1 (:exit (first @calls))))))
    (testing "--help shows in the Options output"
      (let [{:keys [out]} (run ["dev" "--help"])]
        (is (str/includes? out "-h, --help"))))
    (testing "user controls --help position with an ordered (vec-of-pairs) spec"
      (let [t [{:cmds [] :fn identity :doc "t"
                :spec [[:help {}] [:verbose {:coerce :boolean :desc "Verbose"}]]}]
            out (:out (run-dispatch t ["--help"] {:prog "tool" :help true}))
            lines (str/split-lines out)
            idx (fn [s] (first (keep-indexed (fn [i l] (when (str/includes? l s) i)) lines)))]
        ;; :help {} placeholder -> --help rendered with defaults, before --verbose
        (is (str/includes? out "-h, --help"))
        (is (< (idx "--help") (idx "--verbose")))))
    (testing "an explicit :order is left untouched: omit :help to hide it (still works)"
      (let [t [{:cmds [] :fn identity :doc "t"
                :spec {:a {:desc "A"} :b {:desc "B"}} :order [:b :a]}]
            {:keys [out exit]} (run-dispatch t ["--help"] {:prog "tool" :help true})]
        ;; order honored verbatim, --help NOT listed (not in :order)...
        (is (str/includes? out "--b"))
        (is (str/includes? out "--a"))
        (is (not (str/includes? out "--help")))
        ;; ...but --help still triggers help (prints, returns; no *exit-fn*)
        (is (nil? exit))))
    (testing "a command that redefines an inherited option shows it under Options, not Inherited"
      (let [t [{:cmds [] :spec {:x {:inherit true :desc "global x"}}}
               {:cmds ["sub"] :fn identity :spec {:x {:desc "local x"}}}]
            out (:out (run-dispatch t ["sub" "--help"] {:prog "p" :help true}))]
        ;; child wins: --x shows the local desc; the ancestor's version is deduped out
        (is (re-find #"--x\s+local x" out))
        (is (not (str/includes? out "global x")))))))

(deftest help-wins-over-required-test
  ;; `--help` must win over flag errors (require/validate), at any level, even
  ;; when the failing option is at an ANCESTOR of the level --help follows:
  ;; `foo bar --help` with `foo` requiring `--opt` shows `foo bar` help, not the
  ;; "Required option" error. (`--opt` is unparsed at the `foo` level when its
  ;; require fires, so it can't be detected from the parsed opts there.)
  (let [table [{:cmds ["foo"]       :spec {:opt {:require true :coerce :long :desc "Opt"}}}
               {:cmds ["foo" "bar"] :fn identity :spec {:baz {:desc "Baz"}}}]
        run (fn [args]
              (let [ran (atom nil)
                    table (mapv (fn [e] (cond-> e (:fn e) (assoc :fn (fn [m] (reset! ran m))))) table)]
                (assoc (run-dispatch table args {:prog "tool" :help true})
                       :ran @ran)))]
    (testing "foo bar --help: help wins over an ancestor's missing required option"
      (let [{:keys [out exit ran]} (run ["foo" "bar" "--help"])]
        (is (str/includes? out "Usage: tool foo bar"))
        (is (nil? exit))                    ; help is not an error
        (is (nil? ran))))                   ; the :fn did not run
    (testing "foo --help: help wins at the level that declares the required option"
      (let [{:keys [out exit]} (run ["foo" "--help"])]
        (is (str/includes? out "Usage: tool foo"))
        (is (nil? exit))))
    (testing "foo bar (no help): the missing required option still errors, exit 1"
      (let [{:keys [exit ran]} (run ["foo" "bar"])]
        (is (submap? {:exit 1 :cause :require} exit))
        (is (nil? ran))))
    (testing "foo --opt 2 bar (no help): runs normally"
      (let [{:keys [exit ran]} (run ["foo" "--opt" "2" "bar"])]
        (is (nil? exit))
        (is (= {:opt 2} (:opts ran)))))))

#?(:cljd/clj-host nil
   :clj
   (deftest help-error-streams-test
     ;; --help is requested output -> stdout; errors -> stderr
     (let [t [{:cmds [] :doc "t"} {:cmds ["go"] :fn identity :doc "Go"}]
           run (fn [args]
                 (let [err (java.io.StringWriter.)
                       out (with-out-str
                             (binding [*err* err cli/*exit-fn* (fn [_])]
                               (cli/dispatch t args {:prog "tool" :help true})))]
                   {:out out :err (str err)}))]
       (testing "--help prints to stdout, stderr stays empty"
         (let [{:keys [out err]} (run ["--help"])]
           (is (str/includes? out "Usage: tool"))
           (is (= "" err))))
       (testing "unknown command prints to stderr, stdout stays empty"
         (let [{:keys [out err]} (run ["nope"])]
           (is (= "" out))
           (is (str/includes? err "Unknown command: nope")))))))

(deftest default-width-fn-test
  ;; returns terminal width or nil, never throws (cljd: dart:io stdout/env)
  (let [w (cli/default-width-fn {})]
    (is (or (nil? w) (pos-int? w)))))

(deftest format-table-test
  (let [contains-row-matching (fn [re table]
                                (let [rows (str/split-lines table)]
                                  (is (some #(re-find re %) rows)
                                      (str "expected " (pr-str rows)
                                           " to contain a row matching " (pr-str re)))))]
    (testing "ANSI escape codes don't count towards a cell's width"
      (let [table (cli/format-table {:rows [["widest" "<- sets column width to 6"]
                                            ["\033[31mfoo\033[0m" "<- needs 3+1 padding"]
                                            ["bar" "<- needs 3+1 padding"]]})]
        (contains-row-matching #"\033\[31mfoo\033\[0m    <-"
                               table)
        (contains-row-matching #"bar    <-"
                               table)))))

(deftest format-multiline-celled-table-test
  (is (= ["  r1c1       r1c2       r1c3"
          "  r1c1 l2"
          "  r2c1 wider r2c2       r2c3"
          "             r2c2 l2    r2c3 l2"
          "             r2c2 l3"
          "  r3c1       r3c2 wider r3c3"
          "                        r3c3 l2"
          "                        r3c3 l3"]
         (-> (cli/format-table {:rows [["r1c1\nr1c1 l2" "r1c2" "r1c3"]
                                       ["r2c1 wider" "r2c2\nr2c2 l2\nr2c2 l3" "r2c3\nr2c3 l2"]
                                       ["r3c1" "r3c2 wider" "r3c3\nr3c3 l2\nr3c3 l3"]]})
             str/split-lines))))

(deftest format-table-wrap-test
  (testing "the last column wraps at word boundaries to :max-width-fn, continuation aligned"
    (is (= ["  --foo one two three"
            "        four five"]
           (-> (cli/format-table {:rows [["--foo" "one two three four five"]]
                                  :max-width-fn (constantly 22)})
               str/split-lines))))
  (testing ":wrap false leaves long cells untouched"
    (is (= ["  --foo one two three four five"]
           (-> (cli/format-table {:rows [["--foo" "one two three four five"]]
                                  :max-width-fn (constantly 22) :wrap false})
               str/split-lines))))
  (testing "existing newlines are preserved as hard breaks while long lines wrap"
    (is (= ["  --foo one two"
            "        three"
            "        four"]
           (-> (cli/format-table {:rows [["--foo" "one two three\nfour"]]
                                  :max-width-fn (constantly 17)})
               str/split-lines))))
  (testing ":max-width-fn is called lazily (only when wrapping) with the cfg map"
    (let [calls (atom 0)]
      (cli/format-table {:rows [["--foo" "one two three four five"]]
                         :wrap false
                         :max-width-fn (fn [_] (swap! calls inc) 22)})
      (is (zero? @calls) ":wrap false must not call max-width-fn"))
    (let [seen (atom nil)]
      (cli/format-table {:rows [["--foo" "one two three four five"]]
                         :max-width-fn (fn [m] (reset! seen m) 22)})
      (is (map? @seen) "max-width-fn receives the cfg map"))))

(deftest require-test
  (is (thrown-with-msg?
       #?(:cljd Object :default Exception) #"Required option: --bar"
       (cli/parse-args ["-foo"] {:require [:bar]}))))

(deftest validate-test
  (is (thrown-with-msg? #?(:cljd Object :default Exception) #"Invalid value for option --foo:"
                        (cli/parse-args ["--foo" "0"] {:validate {:foo pos?}})))
  (is (thrown-with-msg? #?(:cljd Object :default Exception) #"Invalid value for option --foo:"
                        (cli/parse-args ["--foo" ":bar"] {:validate {:foo #{:baz}}})))
  (is (thrown-with-msg? #?(:cljd Object :default Exception) #"Invalid value for option --foo:"
                        (cli/parse-args ["--foo" ":bar"] {:spec {:foo {:validate #{:baz}}}})))
  (let [ex-msg-fn (fn
                    [{:keys [option value]}]
                    (str "Expected positive number for option "
                         option " but got: " value))]
    (is (try (cli/parse-args
              ["--foo" "0"]
              {:validate {:foo {:pred pos?
                                :ex-msg ex-msg-fn}}})
             false
             (catch #?(:cljd Object :clj Exception
                       :cljs :default) e
               (= {:type :org.babashka/cli
                   :cause :validate
                   :msg #?(:squint "Expected positive number for option foo but got: 0"
                           :default "Expected positive number for option :foo but got: 0")
                   :option :foo
                   :flag "--foo"
                   :spec nil
                   :value 0
                   :validate {:foo {:pred pos?
                                    :ex-msg ex-msg-fn}}
                   :opts {:foo 0}}
                  (ex-data e)))))))

(deftest flag-token-test
  (testing ":flag echoes the literal option token, not a guess from name length"
    (let [flag (fn [args opts] (try (cli/parse-opts args opts)
                                    (catch #?(:cljd Object :clj Exception :cljs :default) e
                                      (:flag (ex-data e)))))]
      ;; single-char long option: a name-length guess would say "-x"; exact now
      (is (= "--x" (flag ["--x"] {:spec {:foo {}} :restrict true})))
      (is (= "-x"  (flag ["-x"]  {:spec {:foo {}} :restrict true})))
      (is (= "--bogus" (flag ["--bogus"] {:spec {:foo {}} :restrict true})))
      ;; short alias: echo what was typed, though :option is the long key :format
      (is (= "-f" (flag ["-f" "0"] {:spec {:format {:alias :f :validate pos?}}})))
      ;; single-char long coerce failure: a guess would say "-n"
      (is (= "--n" (flag ["--n" "x"] {:coerce {:n :long}})))
      ;; keyword syntax echoes as typed
      (is (= ":x" (flag [":x"] {:spec {:foo {}} :restrict true})))))
  (testing ":require carries no :flag (option was never typed)"
    (is (nil? (try (cli/parse-opts [] {:spec {:foo {}} :require [:foo]})
                   (catch #?(:cljd Object :clj Exception :cljs :default) e (:flag (ex-data e))))))))

(deftest error-fn-test
  (let [errors (atom [])
        spec {:a {:require true}
              :b {:validate pos?}
              :c {:coerce :long}}]
    (cli/parse-args ["--b" "0"
                     "--c" "nope!"
                     "--extra" "bad!"]
                    {:error-fn (fn [error] (swap! errors conj error))
                     :restrict true
                     :spec spec})
    (is (= [{:spec spec, :type :org.babashka/cli, :cause :coerce,
             :msg "Invalid value for option --c: cannot transform input \"nope!\" to long", :option :c, :flag "--c",
             :value "nope!", :opts {:b 0}}
            {:spec spec, :type :org.babashka/cli, :cause :restrict, :msg "Unknown option: --extra", :restrict #{:c :b :a}, :option :extra, :flag "--extra", :opts {:b 0, :extra "bad!"}}
            ;; :require has no :flag in data (never typed); the message uses the canonical --a
            {:spec spec, :type :org.babashka/cli, :cause :require, :msg "Required option: --a", :require #{:a}, :option :a, :opts {:b 0, :extra "bad!"}}
            {:spec spec, :type :org.babashka/cli, :cause :validate, :msg "Invalid value for option --b: 0", :validate {:b pos?}, :option :b, :flag "--b", :value 0,
             :opts {:b 0, :extra "bad!"}}]
           @errors))))

(deftest exec-args-replaced-test
  (is (= {:foo [:bar] :dude [:baz]} (cli/parse-opts ["--foo" ":bar"] {:coerce {:foo [] :dude []}
                                                                      :exec-args {:dude [:baz]}})))
  (is (= {:foo [:bar]} (cli/parse-opts ["--foo" ":bar"] {:coerce {:foo []}
                                                         :exec-args {:foo [:baz]}}))))

(deftest issue-82-alias-preference
  (is (= {:opts {:verbose2 true}}
         (cli/parse-args ["-vv"] {:spec {:verbose1 {:alias :v}
                                         :verbose2 {:alias :vv}}}))))

(deftest issue-89-alias-only-for-short-opt
  (is (= {:f "dude"} (cli/parse-opts ["--f" "dude"] {:spec {:foo {:alias :f}}}))))

(deftest issue-91-keyword-mode-overrides-hypens-mode
  (is (= {:args ["--baz"], :opts {:foo 1}}
         (cli/parse-args [":foo" 1 "--baz"] {}))))

(deftest issue-98-dispatch+restrict-test
  (is (thrown? #?(:cljd Object :default Exception)
         (cli/dispatch [{:cmds ["foo"]
                         :fn identity
                         :spec {:x {:coerce :boolean}}}]
                       ["foo" "--y"]
                       {:restrict true})))
  (is (= {:dispatch ["foo"], :opts {}, :args nil}
         (cli/dispatch [{:cmds ["foo"]
                         :fn identity
                         :spec {:x {:coerce :boolean}}}]
                       ["foo"]
                       {:restrict true}))))

(deftest dispatch-flag-error-includes-dispatch-path-test
  (testing "flag-level errors during dispatch carry the :dispatch path so an
            :error-fn can render help for the right command"
    ;; capture the first error and halt (mirrors a real error-fn that exits)
    (let [capture (fn [table args opts]
                    (let [err (atom nil)]
                      (try
                        (cli/dispatch table args
                                      (assoc opts :error-fn
                                             (fn [e] (reset! err e)
                                               (throw (ex-info "stop" {})))))
                        (catch #?(:cljd Object :clj Exception :cljs :default) _ nil))
                      @err))
          table [{:cmds [] :spec {:g {:coerce :boolean}}}
                 {:cmds ["foo"] :fn identity :spec {:x {:coerce :boolean}}}
                 {:cmds ["foo" "bar"] :fn identity :spec {:y {:coerce :boolean}}}]]
      (testing ":restrict at top level -> empty :dispatch"
        (let [e (capture table ["--bogus"] {:restrict true})]
          (is (= :restrict (:cause e)))
          (is (= [] (:dispatch e)))))
      (testing ":restrict at nested command -> full :dispatch path"
        (let [e (capture table ["foo" "bar" "--bogus"] {:restrict true})]
          (is (= :restrict (:cause e)))
          (is (= ["foo" "bar"] (:dispatch e)))))
      (testing ":require error also carries :dispatch path"
        (let [e (capture [{:cmds ["foo"] :fn identity
                           :spec {:req {:require true}}}]
                         ["foo"] {})]
          (is (= :require (:cause e)))
          (is (= ["foo"] (:dispatch e))))))))

(deftest restrict-with-shared-options-test
  (testing ":restrict does not reject options parsed at a parent dispatch level"
    (let [table [{:cmds ["deps"]            :spec {:registry {}}}
                 {:cmds ["deps" "outdated"] :fn identity :spec {:format {}}}]]
      (is (= {:dispatch ["deps" "outdated"]
              :opts {:registry "X" :format "edn"}
              :args nil}
             (cli/dispatch table
                           ["deps" "--registry" "X" "outdated" "--format" "edn"]
                           {:restrict true})))
      (testing "genuinely unknown options are still rejected"
        (is (thrown-with-msg?
             #?(:cljd Object :default Exception) #"Unknown option: --bogus"
             (cli/dispatch table ["deps" "outdated" "--bogus"] {:restrict true}))))))
  (testing ":exec-args are programmatic defaults, not user input, so :restrict never flags them"
    (is (= {:foo true :bar 1}
           (cli/parse-opts ["--foo"] {:spec {:foo {:coerce :boolean}}
                                      :exec-args {:bar 1}
                                      :restrict true})))
    (testing "but a user-typed unknown option is still rejected"
      (is (thrown-with-msg?
           #?(:cljd Object :default Exception) #"Unknown option: --baz"
           (cli/parse-opts ["--foo" "--baz"] {:spec {:foo {:coerce :boolean}}
                                              :exec-args {:bar 1}
                                              :restrict true}))))))

(deftest inherit-flags-test
  (testing ":inherit options propagate to descendant levels"
    (let [table [{:cmds ["deps"]            :spec {:registry {:alias :r :inherit true}}}
                 {:cmds ["deps" "outdated"] :fn identity :spec {:format {}}}]]
      (testing "accepted after the command"
        (is (= {:dispatch ["deps" "outdated"] :opts {:registry "X" :format "edn"} :args nil}
               (cli/dispatch table ["deps" "outdated" "--registry" "X" "--format" "edn"] {:restrict true}))))
      (testing "still accepted before the command"
        (is (= {:dispatch ["deps" "outdated"] :opts {:registry "X"} :args nil}
               (cli/dispatch table ["deps" "--registry" "X" "outdated"] {:restrict true}))))
      (testing "alias propagates too"
        (is (= {:dispatch ["deps" "outdated"] :opts {:registry "X"} :args nil}
               (cli/dispatch table ["deps" "outdated" "-r" "X"] {:restrict true}))))))
  (testing "coercion propagates across levels"
    (let [table [{:cmds ["a"]     :spec {:n {:coerce :int :inherit true}}}
                 {:cmds ["a" "b"] :fn identity}]]
      (is (= {:n 7} (:opts (cli/dispatch table ["a" "b" "--n" "7"] {:restrict true}))))))
  (testing "a child spec may override an inherited option"
    (let [table [{:cmds ["a"]     :spec {:x {:coerce :int :inherit true}}}
                 {:cmds ["a" "b"] :fn identity :spec {:x {:coerce :keyword}}}]]
      (is (= {:x :hi} (:opts (cli/dispatch table ["a" "b" "--x" "hi"] {:restrict true}))))))
  (testing "an inherited option beats a colliding dispatch-level (global) :spec key"
    (let [table [{:cmds ["ver"]       :spec {:tag {:coerce :boolean :inherit true}}}
                 {:cmds ["ver" "set"] :fn identity}]]
      ;; global :spec also defines :tag (as :string); inherited :boolean must win
      (is (= {:tag false}
             (:opts (cli/dispatch table ["ver" "set" "--tag" "false"]
                                  {:spec {:tag {:coerce :string}}}))))))
  (testing "options without :inherit do NOT propagate (rejected after command)"
    (let [table [{:cmds ["deps"]            :spec {:registry {}}}
                 {:cmds ["deps" "outdated"] :fn identity :spec {:format {}}}]]
      (is (thrown-with-msg?
           #?(:cljd Object :default Exception) #"Unknown option: --registry"
           (cli/dispatch table ["deps" "outdated" "--registry" "X"] {:restrict true})))))
  (testing "dispatch-level :inherit makes options inherit without per-option marking"
    (let [table [{:cmds ["deps"]            :spec {:registry {} :token {}}}
                 {:cmds ["deps" "outdated"] :fn identity :spec {:format {}}}]]
      (testing ":inherit true -> all ancestor options inherit"
        (is (= {:registry "X" :token "T" :format "edn"}
               (:opts (cli/dispatch table
                                    ["deps" "outdated" "--registry" "X" "--token" "T" "--format" "edn"]
                                    {:inherit true :restrict true})))))
      (testing ":inherit #{ks} -> only listed options inherit"
        (is (= {:registry "X"}
               (:opts (cli/dispatch table ["deps" "outdated" "--registry" "X"]
                                    {:inherit #{:registry} :restrict true}))))
        (is (thrown-with-msg?
             #?(:cljd Object :default Exception) #"Unknown option: --token"
             (cli/dispatch table ["deps" "outdated" "--token" "T"]
                           {:inherit #{:registry} :restrict true})))))))

(deftest issue-106-test
  (d/deflet
    (def global-spec {:config  {:desc "Config edn file to use"
                                :coerce []}
                      :verbose {:coerce :boolean}})
    (def dns-spec {})
    (def dns-get-spec {})
    (def table
      [{:cmds []            :fn identity :spec global-spec}
       {:cmds ["dns"]       :fn identity :spec dns-spec}
       {:cmds ["dns" "get"] :fn identity :spec dns-get-spec}])
    (is (submap?
         {:dispatch ["dns"], :opts {:config ["config-dev.edn" "other.edn"]}, :args nil}
         (cli/dispatch table ["--config" "config-dev.edn" "--config" "other.edn" "dns"])))
    (is (submap?
         {:dispatch ["dns" "get"],
          :opts {:config ["config-dev.edn" "other.edn"]},
          :args nil}
         (cli/dispatch table ["--config" "config-dev.edn" "--config" "other.edn" "dns" "get"])))
    (is (submap?
         {:dispatch ["dns" "get"],
          :opts {:config ["config-dev.edn" "other.edn"], :verbose true},
          :args nil}
         (cli/dispatch table ["--config" "config-dev.edn" "--verbose" "--config" "other.edn" "dns" "get"])))
    (is (submap?
         {:dispatch ["dns" "get"],
          :opts {:config ["config-dev.edn" "other.edn"]},
          :args nil}
         (cli/dispatch table ["--config" "config-dev.edn" "--config=other.edn" "dns" "get"])))))

(deftest repeated-opts-test
  (is (= {:opts {:foo [1 2]}}
         (cli/parse-args ["--foo" "1" "--foo" "2"] {:repeated-opts true :spec {:foo {:coerce []}}})))
  (is (= {:args ["2"], :opts {:foo [1]}}
         (cli/parse-args ["--foo" "1" "2"] {:repeated-opts true :spec {:foo {:coerce []}}}))))

(deftest issue-126-test
  (is (= {:file "-"} (cli/parse-opts ["--file" "-"])))
  (is (= {:file "-"} (cli/parse-opts ["-"] {:args->opts [:file]}))))

(deftest coerce-test
  (is (= 42 (cli/coerce "42" :int)))
  (is (= 1.5 (cli/coerce "1.5" :double)))
  (is (= :kw (cli/coerce ":kw" :keyword)))
  (is (= true (cli/coerce "true" :boolean)))
  (is (= "x" (cli/coerce "x" identity))))

(deftest coerce-opts-test
  (testing "simple coercion"
    (is (= {:foo 1 :bar "hello"}
           (cli/coerce-opts {:foo "1" :bar "hello"} {:coerce {:foo :long}}))))
  (testing "multiple coercions"
    (is (= {:foo 1 :bar :baz}
           (cli/coerce-opts {:foo "1" :bar "baz"} {:coerce {:foo :long :bar :keyword}}))))
  (testing "non-string values pass through"
    (is (= {:foo 1} (cli/coerce-opts {:foo 1} {:coerce {:foo :long}}))))
  (testing "collection coerce on sequential value"
    (is (= {:foo [1 2 3]}
           (cli/coerce-opts {:foo ["1" "2" "3"]} {:coerce {:foo [:long]}}))))
  (testing "collection coerce on single value"
    (is (= {:foo [1]}
           (cli/coerce-opts {:foo "1"} {:coerce {:foo [:long]}}))))
  (testing "collection coerce with set"
    (is (= {:foo #{1 2 3}}
           (cli/coerce-opts {:foo ["1" "2" "3"]} {:coerce {:foo #{:long}}}))))
  (testing "non-string collection elements pass through"
    (is (= {:foo [1 2 3]}
           (cli/coerce-opts {:foo [1 2 3]} {:coerce {:foo [:long]}}))))
  (testing "auto-coerce without coerce fn"
    (is (= {:foo [1 :bar true]}
           (cli/coerce-opts {:foo ["1" ":bar" "true"]} {:coerce {:foo []}}))))
  (testing "using spec"
    (is (= {:foo :bar}
           (cli/coerce-opts {:foo "bar"} {:spec {:foo {:coerce :keyword}}}))))
  (testing "error-fn on coercion failure"
    (let [errors (atom [])]
      (cli/coerce-opts {:foo "not-a-number"} {:coerce {:foo :long}
                                              :error-fn (fn [e] (swap! errors conj e))})
      (is (= :coerce (:cause (first @errors))))))
  (testing "error data includes :implicit-value for implicit true coerce failures"
    ;; `--foo` with no value parses to (implicit) `true`. If `:foo` has a
    ;; coerce that rejects boolean true (e.g. `:string`), error data
    ;; should expose `:implicit-value true` so downstream error mappers
    ;; can distinguish "user typed --foo alone" from a real coerce failure.
    (let [errors (atom [])]
      (cli/parse-opts ["--foo"] {:coerce {:foo :string}
                                 :error-fn (fn [e] (swap! errors conj e))})
      (is (= true (:implicit-value (first @errors))))
      (is (= :coerce (:cause (first @errors))))))
  (testing "error data does NOT include :implicit-value for explicit value coerce failures"
    (let [errors (atom [])]
      (cli/parse-opts ["--foo" "abc"] {:coerce {:foo :long}
                                       :error-fn (fn [e] (swap! errors conj e))})
      (is (nil? (:implicit-value (first @errors))))
      (is (= :coerce (:cause (first @errors))))))
  (testing "keys without coerce spec pass through unchanged"
    (is (= {:foo "1" :bar "hello"}
           (cli/coerce-opts {:foo "1" :bar "hello"} {:coerce {}})))))

(deftest validate-opts-test
  (testing "restrict"
    (is (thrown-with-msg?
         #?(:cljd Object :default Exception) #"Unknown option: --bar"
         (cli/validate-opts {:foo 1 :bar 2} {:restrict #{:foo}}))))
  (testing "restrict with true and spec"
    (is (thrown-with-msg?
         #?(:cljd Object :default Exception) #"Unknown option: --bar"
         (cli/validate-opts {:foo 1 :bar 2} {:spec {:foo {:coerce :long}} :restrict true}))))
  (testing "restrict passes for known keys"
    (is (= {:foo 1}
           (cli/validate-opts {:foo 1} {:restrict #{:foo}}))))
  (testing "require"
    (is (thrown-with-msg?
         #?(:cljd Object :default Exception) #"Required option: --bar"
         (cli/validate-opts {:foo 1} {:require [:bar]}))))
  (testing "require passes when present"
    (is (= {:foo 1 :bar 2}
           (cli/validate-opts {:foo 1 :bar 2} {:require [:bar]}))))
  (testing "validate"
    (is (thrown-with-msg?
         #?(:cljd Object :default Exception) #"Invalid value for option --foo"
         (cli/validate-opts {:foo 0} {:validate {:foo pos?}}))))
  (testing "validate passes"
    (is (= {:foo 1}
           (cli/validate-opts {:foo 1} {:validate {:foo pos?}}))))
  (testing "validate with pred and ex-msg"
    (is (thrown-with-msg?
         #?(:cljd Object :default Exception) #"Expected positive"
         (cli/validate-opts {:foo 0} {:validate {:foo {:pred pos?
                                                       :ex-msg (fn [{:keys [option value]}]
                                                                 (str "Expected positive for " option ": " value))}}}))))
  (testing "using spec"
    (is (thrown-with-msg?
         #?(:cljd Object :default Exception) #"Required option: --foo"
         (cli/validate-opts {} {:spec {:foo {:require true}}}))))
  (testing "error-fn"
    (let [errors (atom [])]
      (cli/validate-opts {:foo 0}
                         {:require [:bar]
                          :validate {:foo pos?}
                          :error-fn (fn [e] (swap! errors conj e))})
      (is (= 2 (count @errors)))
      (is (= :require (:cause (first @errors))))
      (is (= :validate (:cause (second @errors))))))
  (testing "returns the input map"
    (is (= {:foo 1} (cli/validate-opts {:foo 1} {}))))
  (testing "composing coerce-opts and validate-opts"
    (is (= {:foo 1}
           (-> {:foo "1"}
               (cli/coerce-opts {:coerce {:foo :long}})
               (cli/validate-opts {:validate {:foo pos?}}))))))

(deftest internal-meta-not-leaked-test
  (testing "::implicit-values not in parse-opts result meta"
    (is (nil? (:babashka.cli/implicit-values (meta (cli/parse-opts ["--foo"]))))))
  (testing "::keys-order not in parse-opts result meta"
    (is (nil? (:babashka.cli/keys-order (meta (cli/parse-opts ["--foo" "--bar" "1"]))))))
  (testing "::opt->flag not in parse-opts result meta"
    (is (nil? (:babashka.cli/opt->flag (meta (cli/parse-opts ["--foo" "--bar" "1"])))))))

(deftest coerce-error-order-test
  (testing "coerce errors fire in parse order, not hash order, for >8 keys"
    (let [keys-list (mapv #(keyword (str "k" %)) (range 12))
          args (vec (mapcat (fn [k] [(str "--" (name k)) "notanumber"]) keys-list))
          coerce-spec (into {} (map (fn [k] [k :long]) keys-list))
          errs (atom [])]
      (cli/parse-opts args {:coerce coerce-spec
                            :error-fn (fn [e] (swap! errs conj (:option e)))})
      (is (= keys-list @errs)))))

(deftest parse-opts-star-test
  (testing "parse-opts* returns raw strings (no coercion)"
    (is (= {:foo "1"} (cli/parse-opts* ["--foo" "1"] {}))))
  (testing "parse-opts* exposes ::implicit-values + ::keys-order + ::opt->flag in meta"
    (let [r (cli/parse-opts* ["--foo" "--bar" "1" "--no-baz"] {})]
      (is (= {:foo true :baz false} (:babashka.cli/implicit-values (meta r))))
      (is (= [:foo :bar :baz] (:babashka.cli/keys-order (meta r))))
      (is (= {:foo "--foo" :bar "--bar" :baz "--no-baz"} (:babashka.cli/opt->flag (meta r))))))
  (testing "parse-opts* skips :restrict / :require / :validate"
    (is (= {:bar "1"} (cli/parse-opts* ["--bar" "1"]
                                       {:restrict #{:foo} :require [:foo]}))))
  (testing "spec :coerce steers parsing like in parse-opts, values still raw"
    (let [r (cli/parse-opts* ["--foo" "bar"] {:spec {:foo {:coerce :boolean}}})]
      (is (= {:foo true} r))
      (is (= ["bar"] (-> r meta :org.babashka/cli :args))))
    (is (= {:paths ["a" "b"]} (cli/parse-opts* ["--paths" "a" "b"] {:spec {:paths {:coerce []}}})))
    (is (= {:n "5"} (cli/parse-opts* ["--n" "5"] {:spec {:n {:coerce :long}}})))))

(deftest apply-defaults-test
  (testing "spec :default fills missing keys"
    (is (= {:foo 1 :bar 2}
           (cli/apply-defaults {:bar 2} {:spec {:foo {:default 1}}}))))
  (testing "existing keys win over defaults"
    (is (= {:foo 9}
           (cli/apply-defaults {:foo 9} {:spec {:foo {:default 1}}}))))
  (testing ":exec-args directly"
    (is (= {:foo 1 :bar 2}
           (cli/apply-defaults {:bar 2} {:exec-args {:foo 1}}))))
  (testing "preserves meta"
    (let [m (with-meta {:bar 2} {:keep :this})]
      (is (= {:keep :this} (meta (cli/apply-defaults m {:exec-args {:foo 1}})))))))

(deftest squint-style-pipeline-test
  (testing "parse* -> external merge -> apply-defaults -> coerce -> validate"
    (let [spec {:paths {:coerce [:string] :default ["." "src"]}
                :output-dir {:coerce :string :default "."}
                :verbose {:coerce :boolean}}
          ext-config {:output-dir "/tmp/custom"}
          parsed (cli/parse-opts* ["--paths" "lib" "--verbose"] {:spec spec})
          ;; cli wins over external config
          merged (with-meta (merge ext-config parsed) (meta parsed))
          with-defaults (cli/apply-defaults merged {:spec spec})
          coerced (cli/coerce-opts with-defaults {:spec spec
                                                  :babashka.cli/auto-coerce true})
          validated (cli/validate-opts coerced {:spec spec :restrict true})]
      (is (= {:paths ["lib"] :output-dir "/tmp/custom" :verbose true} validated)))))

(deftest bool-coerce-parse-key-pinning-test
  (testing "coll-wrapped :boolean: implicit true wrapped in coll"
    (is (= {:foo [true]} (cli/parse-opts ["--foo"] {:coerce {:foo [:boolean]}}))))
  (testing "coll-wrapped :boolean: explicit value coerced and wrapped"
    (is (= {:foo [true]} (cli/parse-opts ["--foo" "true"] {:coerce {:foo [:boolean]}}))))
  (testing ":bool keyword treated like :boolean"
    (is (= {:foo true} (cli/parse-opts ["--foo"] {:coerce {:foo :bool}}))))
  (testing ":bool with explicit false"
    (is (= {:foo false} (cli/parse-opts ["--foo" "false"] {:coerce {:foo :bool}})))))
