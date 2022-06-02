(ns babashka.cli-test
  (:require [babashka.cli :as cli]
            [clojure.test :refer [deftest is]]))

(defn foo
  {:argslists '([{:keys [b]}])}
  [m]
  (let [{:keys [b]} (cli/coerce-vals m {:b parse-long})]
    {:b b}))

(deftest parse-args-test
  (is (= '{:cmds ("foo"), :opts {:b "1"}}
         (cli/parse-args ["foo" ":b" "1"])))
  (is (= '{:cmds ("foo"), :opts {:b 1}}
         (cli/parse-args ["foo" ":b" "1"] {:coerce {:b parse-long}})))
  (is (= '{:cmds ("foo"), :opts {:b 1}}
         (cli/parse-args ["foo" ":b" 1] {:coerce {:b parse-long}})))
  (is (= {:b 1} (foo {:b 1})))
  (is (= {:b 1} (foo {:b "1"})))
  (is (= {:b 1} (foo (:opts (cli/parse-args [":b" "1"]))))))


