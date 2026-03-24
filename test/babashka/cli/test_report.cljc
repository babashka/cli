(ns babashka.cli.test-report
  (:require [clojure.test :as test]))

(def platform
  #?(:bb (str "bb " (System/getProperty "babashka.version"))
     :cljs (str "cljs " *clojurescript-version*)
     :clj  (str "jdk " (System/getProperty "java.version") " clj " (clojure-version))))

(defmethod test/report
  #?@(:cljs [[:cljs.test/default :begin-test-var]]
      :default [:begin-test-var]) [m]
  (let [test-name (-> m :var meta :name)
        line (str "=== " test-name " [" platform "]")]
    #?(:cljs (js/process.stdout.write (str line "\n"))
       :default (println line))))
