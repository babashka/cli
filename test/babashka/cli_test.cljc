(ns babashka.cli-test
  (:require [babashka.cli :as cli]
            [clojure.test :refer [deftest is]]))

(defn foo
  {:babashka/cli {:coerce {:b parse-long}}}
  [{:keys [b]}]
  {:b b})

(deftest parse-args-test
  (is (= '{:cmds ("foo"), :opts {:b "1"}}
         (cli/parse-args ["foo" ":b" "1"])))
  (is (= '{:cmds ("foo"), :opts {:b 1}}
         (cli/parse-args ["foo" ":b" "1"] {:coerce {:b parse-long}})))
  (is (= '{:cmds ("foo"), :opts {:b 1}}
         (cli/parse-args ["foo" "--b" "1"] {:coerce {:b parse-long}})))
  (is (= '{:cmds ("foo"), :opts {:boo 1}}
         (cli/parse-args ["foo" ":b" "1"] {:aliases {:b :boo}
                                           :coerce {:boo parse-long}})))
  (is (try (cli/parse-args [":b" "dude"] {:coerce {:b :long}})
           false
           (catch Exception e
             (= {:input "dude", :coerce-fn :long} (ex-data e))))))

(deftest coerce-test
  (is (= {:b 1} (cli/coerce-vals {:b 1} {:b parse-long})))
  (is (= {:b 1} (cli/coerce-vals {:b "1"} {:b parse-long}))))

(deftest parse-args-collect-test
  (is (= '{:cmds [], :opts {:paths ["src" "test"]}}
         (cli/parse-args [":paths" "src" "test"] {:collect {:paths []}})))
  (is (= {:paths #{"src" "test"}}
         (:opts (cli/parse-args [":paths" "src" "test"] {:collect {:paths #{}}})))))
