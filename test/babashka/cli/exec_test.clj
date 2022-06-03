(ns babashka.cli.exec-test
  (:require [babashka.cli.exec :refer [-main]]
            [clojure.test :refer [deftest is]]))

(defn foo
  {:org.babashka/cli {:coerce {:b parse-long}}}
  ;; map argument:
  [m]
  ;; return map argument:
  m)

(deftest parse-args-test
  (is (= {:b 1} (-main "babashka.cli.exec-test/foo" ":b" "1")))
  (is (= {:a "1" :b 2} (-main "babashka.cli.exec-test/foo" ":a" "1" ":b" "2")))
  (is (= {:b 1} (-main "babashka.cli.exec-test" "foo" ":b" "1")))
  (is (= {:a "1" :b 2} (-main "babashka.cli.exec-test" "foo" ":a" "1" ":b" "2"))))
