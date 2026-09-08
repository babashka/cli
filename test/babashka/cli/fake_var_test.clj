(ns babashka.cli.fake-var-test
  (:require
   [babashka.cli :as cli]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(deftype FakeVar [m f]
  clojure.lang.IObj
  (meta [_] m)
  (withMeta [_ m] (FakeVar. m f))
  clojure.lang.IFn
  (invoke [_ opts] (f opts)))

(def ^:private a-command
  (->FakeVar {:doc "Run command"
              :org.babashka/cli {:spec {:n {:coerce :string :desc "String value"}}}}
             (fn [opts] (assoc opts :ran :a-command))))

(deftest fake-var-fn-test
  (is (not (var? a-command)))
  (let [tree {:cmd {"do" {:exec-fn a-command}}}]
    (testing "metadata spec controls coercion"
      (is (= {:n "5" :ran :a-command} (cli/dispatch tree ["do" "--n" "5"]))))
    (testing "help includes metadata docstring and options"
      (let [help (with-out-str (cli/dispatch tree ["do" "--help"] {:prog "t" :help true}))]
        (is (str/includes? help "Run command"))
        (is (str/includes? help "String value"))))))
