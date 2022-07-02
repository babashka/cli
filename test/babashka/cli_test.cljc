(ns babashka.cli-test
  (:require
   [babashka.cli :as cli]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
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

(deftest parse-opts-test
  (let [res (cli/parse-opts ["foo" ":b" "1"])]
    (is (submap? '{:b 1} res))
    (is (submap? {:org.babashka/cli {:cmds ["foo"]}} (meta res)))
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
             (= {:input "dude", :coerce-fn :long} (ex-data e)))))
  (is (submap? {:a [1 1]}
               (cli/parse-opts ["-a" "1" "-a" "1"] {:collect {:a []} :coerce {:a :long}})))
  (is (submap? {:foo :bar
                :skip true}
               (cli/parse-opts ["--skip"] {:exec-args {:foo :bar}})))
  (testing "shorthands"
    (is (submap? '{:foo [a b]
                   :skip true}
                 (cli/parse-opts ["--skip" "--foo=a" "--foo=b"]
                                 {:coerce {:foo [:symbol]}})))))

(deftest parse-opts-collect-test
  (is (submap? '{:paths ["src" "test"]}
               (cli/parse-opts [":paths" "src" "test"] {:collect {:paths []}})))
  (is (submap? '{:paths ["src" "test"]}
               (cli/parse-opts [":paths" "src" "test"] {:coerce {:paths []}})))
  (is (submap? {:paths #{"src" "test"}}
               (cli/parse-opts [":paths" "src" "test"] {:collect {:paths #{}}})))
  (is (submap? {:paths #{"src" "test"}}
               (cli/parse-opts [":paths" "src" "test"] {:coerce {:paths #{}}})))
  (is (submap? {:verbose [true true true]}
               (cli/parse-opts ["-v" "-v" "-v"] {:aliases {:v :verbose}
                                                 :collect {:verbose []}}))))

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
    (is (= (str/trim "  -i, --from   <format> edn      The input format. <format> can be edn, json or transit.
  -o, --to     <format> json     The output format. <format> can be edn, json or transit.
      --paths           src test Paths of files to transform.
  -p, --pretty                   Pretty-print output.")
           (str/trim (cli/format-opts {:spec spec
                                      :order [:from :to :paths :pretty]}))))
    (is (= {:coerce {:from :keyword,
                     :to :keyword, :paths []},
            :aliases {:i :from, :o :to, :p :pretty},
            :exec-args {:from :edn, :to :json, :paths ["src" "test"]}}
           (cli/spec->opts spec)))
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
    (is (= {:opts {:from :edn, :to :json, :paths ["src" "test"]}}
           (cli/parse-args [] {:spec spec})))
    (is (= "  --deps/root The root"
           (cli/format-opts {:spec [[:deps/root {:desc "The root"}]]})))
    (is (= #:deps{:root "the-root"}
           (cli/parse-opts ["--deps/root" "the-root"]
                           {:spec [[:deps/root {:desc "The root"}]]})))))

(deftest args-test
  (is (submap? {:foo true} (cli/parse-opts ["--foo" "--"])))
  (let [res (cli/parse-opts ["--foo" "--" "a"])]
    (is (submap? {:foo true} res))
    (is (submap? {:org.babashka/cli {:args ["a"]}} (meta res))))
  (is (= {:args ["do" "something" "--now"], :opts {:classpath "src"}}
         (cli/parse-args ["--classpath" "src" "do" "something" "--now"]
                         )))
  (is (= {:cmds ["do" "something"], :opts {:now true}}
         (cli/parse-args ["do" "something" "--now"])))
  (is (= {:args ["ssh://foo"], :cmds ["git" "push"], :opts {:force true}}
         (cli/parse-args ["git" "push" "--force" "ssh://foo"] {:coerce {:force :boolean}})))
  (is (= {:args ["ssh://foo"], :opts {:paths ["src" "test"]}}
         (cli/parse-args ["--paths" "src" "test" "--" "ssh://foo"] {:coerce {:paths []}}))))

(deftest dispatch-test
  (let [f (fn [m]
            m)
        g (constantly :rest)
        disp-table [{:cmds ["add" "dep"] :fn f}
                    {:cmds ["dep" "add"] :fn f}
                    {:cmds ["dep" "search"] :fn f :cmds-opts [:search-term]}
                    {:cmds [] :fn g}]]
    (is (submap?
         {:rest-cmds ["cheshire/cheshire"], :opts {}}
         (cli/dispatch disp-table ["add" "dep" "cheshire/cheshire"])))
    (is (submap?
         {:dispatch ["dep" "search"]
          :opts {:search-term "cheshire"}}
         (cli/dispatch disp-table ["dep" "search" "cheshire"])))))

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
  (is (= "1. This is a title." (cli/auto-coerce "1. This is a title.")))
  (is (= ":1. This is a title." (cli/auto-coerce ":1. This is a title.")))
)
