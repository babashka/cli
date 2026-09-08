(ns babashka.cli.fake-var-test
  (:require
   [babashka.cli :as cli]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

;; Stands in for the `sci.lang.Var` a babashka script hands over. It carries
;; metadata and is callable, but it is not a `clojure.lang.Var`, so a `var?`
;; test skips the folding. In babashka `babashka.cli` is compiled, which is why
;; the vars in `dispatch-var-fn-test` cannot catch this.
(deftype FakeVar [m f]
  clojure.lang.IObj
  (meta [_] m)
  (withMeta [_ m] (FakeVar. m f))
  clojure.lang.IFn
  (invoke [_ opts] (f opts)))

(def ^:private a-command
  (->FakeVar {:doc "Does a thing"
              :org.babashka/cli {:spec {:n {:coerce :string :desc "Left as a string"}}}}
             (fn [opts] (assoc opts :ran :a-command))))

(deftest fake-var-fn-test
  (is (not (var? a-command)))
  (let [tree {:cmd {"do" {:exec-fn a-command}}}]
    (testing "its spec drives parsing"
      (is (= {:n "5" :ran :a-command} (cli/dispatch tree ["do" "--n" "5"]))))
    (testing "its docstring and options reach the help"
      (let [help (with-out-str (cli/dispatch tree ["do" "--help"] {:prog "t" :help true}))]
        (is (str/includes? help "Does a thing"))
        (is (str/includes? help "Left as a string"))))))
