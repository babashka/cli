(ns babashka.cli.sci-var-test
  "In babashka `babashka.cli` is compiled, so `var?` asks for a
  `clojure.lang.Var` while a script hands over a `sci.lang.Var` and the var
  folding silently does nothing. Run it here against a real sci var, which is
  the case babashka users hit, without needing a babashka build."
  (:require
   [babashka.cli :as cli]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [sci.core :as sci]))

(def ^:private a-command
  (sci/eval-string*
   (sci/init {})
   "(defn a-command
      \"Does a thing\"
      {:org.babashka/cli {:spec {:force {:coerce :boolean :desc \"Force it\"}}}}
      [opts] (assoc opts :ran :a-command))
    #'a-command"))

(deftest sci-var-fn-test
  (testing "a sci var is not a clojure var, which is the whole problem"
    (is (= "sci.lang.Var" (.getName (class a-command))))
    (is (not (var? a-command))))
  (let [tree {:cmd {"do" {:exec-fn a-command}}}]
    (testing "its spec drives parsing all the same"
      (is (= {:force true :ran :a-command} (cli/dispatch tree ["do" "--force"]))))
    (testing "and its docstring and options reach the help"
      (let [help (with-out-str (cli/dispatch tree ["do" "--help"] {:prog "t" :help true}))]
        (is (str/includes? help "Does a thing"))
        (is (str/includes? help "Force it"))))))
