(ns babashka.cli-squint-test
  "Squint smoke test for babashka.cli. Squint has no keyword type, so literal
  :foo keys compile to plain strings and match the string keys parse-opts
  returns. Runs as a script: compile with squint, then node the output, which
  exits non-zero on failure."
  (:require
   [babashka.cli :as cli]
   [clojure.string :as str]
   [clojure.test :as t :refer [deftest is testing]]))

(deftest parse-opts-test
  (testing "default number coercion"
    (is (= {:foo 1 :bar true} (cli/parse-opts ["--foo" "1" "--bar"]))))
  (testing "string stays string"
    (is (= {:foo "abc"} (cli/parse-opts ["--foo" "abc"]))))
  (testing "explicit coerce"
    (is (= {:x 1 :y 1.5} (cli/parse-opts ["--x" "1" "--y" "1.5"] {:coerce {:x :int :y :double}})))
    (is (= {:k "foo"} (cli/parse-opts ["--k" ":foo"] {:coerce {:k :keyword}}))))
  (testing "negation"
    (is (= {:foo false} (cli/parse-opts ["--no-foo"] {:coerce {:foo :boolean}}))))
  (testing "alias"
    (is (= {:foo "bar"} (cli/parse-opts ["-f" "bar"] {:alias {:f :foo}}))))
  (testing "collect"
    (is (= {:e ["a" "b"]} (cli/parse-opts ["--e" "a" "--e" "b"] {:collect {:e []}})))))

(deftest keyword-args-test
  (is (= {:foo "bar" :baz "quux"} (cli/parse-opts [":foo" "bar" ":baz" "quux"]))))

(deftest auto-coerce-test
  (is (= {:foo :bar} (cli/parse-opts ["--foo" ":bar"] {:coerce {:foo :auto}})))
  (is (= {:n 42} (cli/parse-opts ["--n" "42"] {:coerce {:n :auto}}))))

(deftest parse-args-test
  (is (= {:opts {:foo 1} :args ["x" "y"]}
         (select-keys (cli/parse-args ["--foo" "1" "x" "y"]) [:opts :args])))
  (testing "args->opts injection"
    (is (= {:a 1 :b 2}
           (:opts (cli/parse-args ["1" "2"] {:args->opts [:a :b] :coerce {:a :int :b :int}}))))))

(deftest dispatch-test
  (let [table [{:cmds ["add"] :fn (fn [m] [:add (:opts m)])}
               {:cmds ["sub"] :fn (fn [m] [:sub (:opts m)])}
               {:cmds [] :fn (fn [_] :root)}]]
    (is (= [:add {:x 1}] (cli/dispatch table ["add" "--x" "1"] {:coerce {:x :int}})))
    (is (= [:sub {}] (cli/dispatch table ["sub"])))
    (is (= :root (cli/dispatch table [])))))

(deftest format-opts-test
  (let [s (cli/format-opts {:spec {:foo {:desc "the foo" :alias :f :ref "<n>"}
                                   :verbose {:desc "be loud" :coerce :boolean}}})]
    (is (string? s))
    (is (str/includes? s "--foo"))
    (is (str/includes? s "the foo"))
    (is (str/includes? s "-f,"))))

(deftest coerce-error-test
  (is (thrown-with-msg? js/Error #"cannot transform input \"abc\" to int"
        (cli/parse-opts ["--port" "abc"] {:coerce {:port :int}}))))

(defn -main [& _]
  (let [{:keys [fail error]} (t/run-tests)]
    (when (pos? (+ (or fail 0) (or error 0)))
      (js/process.exit 1))))

(-main)
