(ns babashka.cli.exec-test
  {:org.babashka/cli {:exec-args {:foo :bar}}}
  (:require
   [babashka.cli-test :refer [submap?]]
   [babashka.cli.exec :refer [main]]
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.test :refer [deftest testing is]]))

(defn foo
  {:org.babashka/cli {:coerce {:b edn/read-string}}}
  ;; map argument:
  [m]
  ;; return map argument:
  m)

(deftest parse-opts-test
  (System/clearProperty "clojure.basis") ;; this needed to clear the test runner basis
  (is (submap? {:foo :bar :b 1} (main "babashka.cli.exec-test/foo" ":b" "1")))
  (is (submap? {:a 1 :b 2} (main "babashka.cli.exec-test/foo" ":a" "1" ":b" "2")))
  (is (submap? {:b 1} (main "babashka.cli.exec-test" "foo" ":b" "1")))
  (is (submap? {:a 1 :b 2} (main "babashka.cli.exec-test" "foo" ":a" "1" ":b" "2")))
  (is (submap? {:a 1 :b 2} (main
                            "{:coerce {:a :long}}"
                            "babashka.cli.exec-test" "foo" ":a" "1" ":b" "2")))
  (testing "old :resolve-args map"
    (is (submap? {:a 1 :b 2} (binding [babashka.cli.exec/*basis* '{:resolve-args {:exec-fn babashka.cli.exec-test/foo}}]
                               (main
                                "{:coerce {:a :long}}" ":a" "1" ":b" "2")))))
  (is (submap? {:a 1 :b 2} (binding [babashka.cli.exec/*basis* '{:argmap {:exec-fn babashka.cli.exec-test/foo}}]
                             (main
                              "{:coerce {:a :long}}" ":a" "1" ":b" "2"))))
  (is (submap? {:a 1 :b 2} (binding [babashka.cli.exec/*basis* '{:argmap {:exec-fn babashka.cli.exec-test/foo}}]
                             (main
                              ":a" "1" ":b" "2"))))
  (is (submap? {:a 1 :b 2}
               (binding [babashka.cli.exec/*basis* '{:argmap {:org.babashka/cli {:coerce {:a :long}}
                                                                    :exec-fn babashka.cli.exec-test/foo}}]
                 (main
                  ":a" "1" ":b" "2"))))
  (is (submap? {:a 1 :b 2}
               (binding [babashka.cli.exec/*basis*
                         '{:argmap {:org.babashka/cli {:coerce {:a :long}}
                                          :ns-default babashka.cli.exec-test}}]
                 (main
                  "foo" ":a" "1" ":b" "2"))))
  (is (submap? {:a 1 :b 2}
               (binding [babashka.cli.exec/*basis*
                         '{:argmap {:org.babashka/cli {:coerce {:a :long}}
                                          :ns-default babashka.cli.exec-test
                                          :exec-fn foo}}]
                 (main ":a" "1" ":b" "2"))))
  (let [basis "{:argmap {:org.babashka/cli {:coerce {:a :long}}
                               :ns-default babashka.cli.exec-test
                               :exec-fn foo
                               :env #env \"FOO\"}}"
        basis-file (fs/file (fs/temp-dir) "basis.txt")]
    (spit basis-file basis)
    (System/setProperty "clojure.basis" (str  basis-file))
    (is (submap? {:foo :bar, :a 123}
                 (main "--a" "123"))))
  (is (submap? {:a 1 :b 2}
               (edn/read-string
                (with-out-str (binding [babashka.cli.exec/*basis*
                                        '{:argmap {:org.babashka/cli {:coerce {:a :long}}
                                                         :ns-default babashka.cli.exec-test
                                                         :exec-fn foo}}]
                                (main "clojure.core/prn" ":a" "1" ":b" "2"))))))
  (is (submap? {:a 1 :b 2}
               (edn/read-string
                (with-out-str (binding [babashka.cli.exec/*basis*
                                        '{:argmap {:ns-default clojure.pprint
                                                         :exec-fn foo}}]
                                (main "pprint" ":a" "1" ":b" "2"))))))
  (is (:exec (:org.babashka/cli
              (meta (binding [babashka.cli.exec/*basis*
                              '{:argmap {:org.babashka/cli {:coerce {:a :long}}
                                        :ns-default babashka.cli.exec-test
                                        :exec-fn foo}}]
                      (main ":a" "1" ":b" "2")))))))
