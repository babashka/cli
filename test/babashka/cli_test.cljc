(ns babashka.cli-test
  (:require
   [babashka.cli :as cli]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [borkdude.deflet :as d]
   #?(:clj [clojure.edn :as edn]
      :cljs [cljs.reader :as edn])))

(defn normalize-filename [s]
  (str/replace s "\\" "/"))

(defn regex? [x]
  #?(:clj (instance? java.util.regex.Pattern x)
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
           (catch #?(:clj Exception
                     :cljs :default) e
             (= {:type :org.babashka/cli
                 :cause :coerce
                 :msg "Coerce failure: cannot transform input \"dude\" to long"
                 :option :b
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
  (testing "implicit true"
    (is (thrown-with-msg?
         Exception #"cannot transform \(implicit\) true"
         (cli/parse-opts ["--foo" "--bar"] {:coerce {:foo :number}})))
    (is (thrown-with-msg?
         Exception #"cannot transform \(implicit\) true"
         (cli/parse-opts ["--bar" "--foo"] {:coerce {:foo :number}})))
    (is (thrown-with-msg?
         Exception #"cannot transform \(implicit\) true"
         (cli/parse-opts [":foo"] {:coerce {:foo :string}})))
    (is (thrown-with-msg?
         Exception #"cannot transform \(implicit\) true"
         (cli/parse-opts [":foo"] {:coerce {:foo [:string]}}))))
  (testing "composite opts"
    (is (= {:a true, :b true, :c true, :foo true}
           (cli/parse-opts ["--foo" "-abc"]))))
  (testing "--no- prefix"
    (is (= {:option false, :no-this-exists true}
           (cli/parse-opts ["--no-option" "--no-this-exists"] {:coerce {:no-this-exists :bool}})))
    (is (= {:args ["dude"], :opts {:option false}}
           (cli/parse-args ["--no-option" "dude"] {:coerce {:option :bool}})))))

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
             (catch #?(:clj Exception
                       :cljs :default) e
               (= {:type :org.babashka/cli
                   :cause :restrict
                   :msg "Unknown option: :b"
                   :option :b
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
             (catch #?(:clj Exception
                       :cljs :default) e
               (= {:type :org.babashka/cli
                   :cause :restrict
                   :msg "Unknown option: :bar"
                   :option :bar
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
  (let [spec {:from {:ref "<format>"
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
                      :default-desc "src test"}}]
    (is (= (str/trim "
  -i, --from   <format> edn      The input format. <format> can be edn, json or transit.
  -o, --to     <format> json     The output format. <format> can be edn, json or transit.
      --paths           src test Paths of files to transform.
  -p, --pretty                   Pretty-print output.")
           (str/trim (cli/format-opts {:spec spec
                                       :order [:from :to :paths :pretty]}))))
    (is (= {:coerce {:from :keyword,
                     :to :keyword, :paths []},
            :alias {:i :from, :o :to, :p :pretty},
            :exec-args {:from :edn, :to :json, :paths ["src" "test"]}}
           (cli/spec->opts spec nil)))
    (is (= (str/trim "
  -p, --pretty          Pretty-print output.
      --paths  src test Paths of files to transform.
") (str/trim
    (cli/format-opts {:spec [[:pretty {:desc "Pretty-print output."
                                       :alias :p}]
                             [:paths {:desc "Paths of files to transform."
                                      :coerce []
                                      :default ["src" "test"]
                                      :default-desc "src test"}]]}))))
    (is (submap?
         {:opts {:from :edn, :to :json, :paths ["src" "test"]}}
         (cli/parse-args [] {:spec spec})))
    (is (submap? "  --deps/root The root"
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
                        (catch Exception e (ex-data e)))))
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
      (testing "subcommand wins from args->opts"
        (is (= {:dispatch ["foo" "bar"], :opts {:version "2000"}, :args ["some-arg"]}
               (-> (cli/dispatch
                    table
                    ["foo" "bar" "--version" "2000" "some-arg"]))))))))

(deftest table->tree-test
  (testing "internal represenation"
    (is (= {:cmd
            {"foo"
             {:cmd
              {"bar"
               {:spec {:baz {:coerce :boolean}},
                :fn identity
                :cmd
                {"baz"
                 {:spec {:quux {:coerce :keyword}},
                  :fn identity}}}}}}}
           (#'cli/table->tree [{:cmds ["foo" "bar"]
                                :spec {:baz {:coerce :boolean}}
                                :fn identity}
                               {:cmds ["foo" "bar" "baz"]
                                :spec {:quux {:coerce :keyword}}
                                :fn identity}])))))

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
  (testing (str "We want to catch most normal keywords, staying close to the Clojure reader.")
    (is (= "1. This is a title." (cli/auto-coerce "1. This is a title.")))
    (is (= ":1. This is a title." (cli/auto-coerce ":1. This is a title.")))
    (is (= :abc (cli/auto-coerce ":abc")))
    (is (= :abc-def (cli/auto-coerce ":abc-def")))
    (is (= :a/b (cli/auto-coerce ":a/b")))
    (is (= (keyword "a/b/c") (cli/auto-coerce ":a/b/c")))
    (is (= ":a.b c.d" (cli/auto-coerce ":a.b c.d")))
    (is (= ":a.b\tc.d" (cli/auto-coerce ":a.b\tc.d"))))
  (is (= nil (cli/auto-coerce "nil")))
  (is (= -10 (cli/auto-coerce "-10")))
  (is (submap? {:foo -10} (cli/parse-opts ["--foo" "-10"])))
  (is (submap? {:foo -10} (cli/parse-opts ["--foo" "-10"] {:coerce {:foo :number}})))
  (is (submap? {:foo "-10"} (cli/parse-opts ["--foo" "-10"] {:coerce {:foo :string}}))))

(deftest format-opts-test
  (testing "default width with default and default-desc"
    (is (= "  -f, --foo <foo> yupyupyupyup Thingy\n  -b, --bar <bar> Mos def      Barbarbar"
           (cli/format-opts
            {:spec {:foo {:alias :f, :default "yupyupyupyup", :ref "<foo>"
                          :desc "Thingy"}
                    :bar {:alias :b, :default "sure", :ref "<bar>"
                          :desc "Barbarbar" :default-desc "Mos def"}}}))))
  (testing "header"
    (is (= "  alias option ref   default      description\n  -f,   --foo  <foo> yupyupyupyup Thingy\n  -b,   --bar  <bar> Mos def      Barbarbar"
           (cli/format-table
            {:rows (concat [["alias" "option" "ref" "default" "description"]]
                           (cli/opts->table
                            {:spec {:foo {:alias :f, :default "yupyupyupyup", :ref "<foo>"
                                          :desc "Thingy"}
                                    :bar {:alias :b, :default "sure", :ref "<bar>"
                                          :desc "Barbarbar" :default-desc "Mos def"}}}))
             :indent 2})))))

(deftest require-test
  (is (thrown-with-msg?
       Exception #"Required option: :bar"
       (cli/parse-args ["-foo"] {:require [:bar]}))))

(deftest validate-test
  (is (thrown-with-msg? Exception #"Invalid value for option :foo:"
                        (cli/parse-args ["--foo" "0"] {:validate {:foo pos?}})))
  (is (thrown-with-msg? Exception #"Invalid value for option :foo:"
                        (cli/parse-args ["--foo" ":bar"] {:validate {:foo #{:baz}}})))
  (is (thrown-with-msg? Exception #"Invalid value for option :foo:"
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
             (catch #?(:clj Exception
                       :cljs :default) e
               (= {:type :org.babashka/cli
                   :cause :validate
                   :msg "Expected positive number for option :foo but got: 0"
                   :option :foo
                   :spec nil
                   :value 0
                   :validate {:foo {:pred pos?
                                    :ex-msg ex-msg-fn}}
                   :opts {:foo 0}}
                  (ex-data e)))))))

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
             :msg "Coerce failure: cannot transform input \"nope!\" to long", :option :c,
             :value "nope!", :opts {:b 0}}
            {:spec spec, :type :org.babashka/cli, :cause :restrict, :msg "Unknown option: :extra", :restrict #{:c :b :a}, :option :extra, :opts {:b 0, :extra "bad!"}}
            {:spec spec, :type :org.babashka/cli, :cause :require, :msg "Required option: :a", :require #{:a}, :option :a, :opts {:b 0, :extra "bad!"}}
            {:spec spec, :type :org.babashka/cli, :cause :validate, :msg "Invalid value for option :b: 0", :validate {:b pos?}, :option :b, :value 0,
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
