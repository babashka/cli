(ns scratch
  (:require [babashka.cli :as cli]))

(defn copy [m]
  (assoc m :fn :copy))

(defn delete [m]
  (assoc m :fn :copy))

(defn help [m]
  (assoc m :fn :help))

(def dispatch-table
  [{:cmds ["copy"] :cmds-opts [:file] :fn copy}
   {:cmds ["delete"] :cmds-opts [:file] :fn delete}
   {:cmds [] :fn help}])

(defn -main [& args]
  (cli/dispatch dispatch-table args {:coerce {:depth :long}}))
