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
  (System/clearProperty "clojure.basis") ;; this needed to clear the test runner basis
  (is (submap? {:foo :bar :b 1} (-main "babashka.cli.exec-test/foo" ":b" "1")))
  (is (submap? {:a "1" :b 2} (-main "babashka.cli.exec-test/foo" ":a" "1" ":b" "2")))
  (is (submap? {:b 1} (-main "babashka.cli.exec-test" "foo" ":b" "1")))
  (is (submap? {:a "1" :b 2} (-main "babashka.cli.exec-test" "foo" ":a" "1" ":b" "2")))
  (is (submap? {:a 1 :b 2} (-main
                            "{:coerce {:a :long}}"
                            "babashka.cli.exec-test" "foo" ":a" "1" ":b" "2")))
  (is (submap? {:a 1 :b 2} (binding [babashka.cli.exec/*basis* '{:resolve-args {:exec-fn babashka.cli.exec-test/foo}}]
                             (-main
                              "{:coerce {:a :long}}" ":a" "1" ":b" "2"))))
  (is (submap? {:a "1" :b 2} (binding [babashka.cli.exec/*basis* '{:resolve-args {:exec-fn babashka.cli.exec-test/foo}}]
                             (-main
                              ":a" "1" ":b" "2"))))
  (is (submap? {:a 1 :b 2}
               (binding [babashka.cli.exec/*basis* '{:resolve-args {:org.babashka/cli {:coerce {:a :long}}
                                                                    :exec-fn babashka.cli.exec-test/foo}}]
                 (-main
                  ":a" "1" ":b" "2"))))
  (is (submap? {:a 1 :b 2}
               (binding [babashka.cli.exec/*basis*
                         '{:resolve-args {:org.babashka/cli {:coerce {:a :long}}
                                          :ns-default babashka.cli.exec-test}}]
                 (-main
                  "foo" ":a" "1" ":b" "2"))))
  (is (submap? {:a 1 :b 2}
               (binding [babashka.cli.exec/*basis*
                         '{:resolve-args {:org.babashka/cli {:coerce {:a :long}}
                                          :ns-default babashka.cli.exec-test
                                          :exec-fn foo}}]
                 (-main ":a" "1" ":b" "2")))))
