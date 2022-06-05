(ns babashka.cli.exec-test
  {:org.babashka/cli {:exec-args {:foo :bar}}}
  (:require
   [babashka.cli-test :refer [submap?]]
   [babashka.cli.exec :refer [-main]]
   [clojure.test :refer [deftest is]]))

(defn foo
  {:org.babashka/cli {:coerce {:b parse-long}}}
  ;; map argument:
  [m]
  ;; return map argument:
  m)

(deftest parse-opts-test
  (is (submap? {:foo :bar :b 1} (-main "babashka.cli.exec-test/foo" ":b" "1")))
  (is (submap? {:a "1" :b 2} (-main "babashka.cli.exec-test/foo" ":a" "1" ":b" "2")))
  (is (submap? {:b 1} (-main "babashka.cli.exec-test" "foo" ":b" "1")))
  (is (submap? {:a "1" :b 2} (-main "babashka.cli.exec-test" "foo" ":a" "1" ":b" "2")))
  (is (submap? {:a 1 :b 2} (-main
                            "{:coerce {:a :long}}"
                            "babashka.cli.exec-test" "foo" ":a" "1" ":b" "2"))))
