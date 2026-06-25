(ns babashka.run-tests
  (:require [clojure.test :as t]
            [babashka.cli-test]
            [babashka.cli.completion-test]))
(defn -main [& _]
  (let [{:keys [fail error]} (t/run-tests 'babashka.cli-test 'babashka.cli.completion-test)]
    (when (pos? (+ (or fail 0) (or error 0))) (js/process.exit 1))))
(-main)
