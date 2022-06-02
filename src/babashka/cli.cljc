(ns babashka.cli
  (:require [clojure.string :as str]))

(defn coerce-vals [m mapping]
  (reduce-kv (fn [m k f]
               (if-let [v (get m k)]
                 (if (string? v)
                   (assoc m k (f v))
                   m)
                 m)) m mapping))

(defn parse-args
  ([args] (parse-args args {}))
  ([args opts]
   (let [coerce-opts (:coerce opts)
         [cmds opts] (split-with #(not (str/starts-with? % ":")) args)]
     {:cmds cmds
      :opts
      (-> (into {}
                (for [[arg-name arg-val] (partition 2 opts)]
                  (let [k (keyword (subs arg-name 1))]
                    [k arg-val])))
          (coerce-vals coerce-opts))})))
