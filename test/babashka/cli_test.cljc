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
         (cli/parse-args ["foo" ":b" 1] {:coerce {:b parse-long}})))
  (is (= {:b 1} (cli/coerce-vals {:b 1} {:b parse-long})))
  (is (= {:b 1} (cli/coerce-vals {:b "1"} {:b parse-long})))
  (is (= {:b 1} (cli/exec #'foo [":b" "1"])))
  (is (= {:b 1} (cli/exec #'foo {:b 1}))))
