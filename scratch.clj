(ns scratch
  (:require [babashka.cli :as cli]
            [clojure.string :as str]))

(defn copy [m]
  (assoc m :fn :copy))

(defn delete [m]
  (assoc m :fn :delete))

(defn help [m]
  (assoc m :fn :help))

(def table
  [{:cmds ["copy"]   :fn copy   :args->opts [:file] :desc "Copy a file"}
   {:cmds ["delete"] :fn delete :args->opts [:file] :desc "Delete a file"}
   {:cmds []         :fn help}])

(defn -main [& args]
  (cli/dispatch table args {:coerce {:depth :long}}))

(comment
  (cli/format-table {:rows [["dude" "foo"]]})
  )

(defn format-commands [{:keys [table]}]
  (let [table (mapv (fn [{:keys [cmds desc]}]
                      (cond-> [(str/join " " cmds)]
                        desc (conj desc)))
                    table)]
    (cli/format-table {:rows table})))

(println (format-commands {:table table}))
