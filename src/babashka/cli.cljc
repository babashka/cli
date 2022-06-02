(ns babashka.cli
  (:require [clojure.string :as str]))

(defn coerce
  "Coerce string `s` using `f`. Does not coerce when `s` is not a string."
  [s f]
  (if (string? s)
    (f s)
    s))

(defn coerce-vals
  "Coerce vals of map `m` using `mapping`, a map of keys to functions, using `coerce`."
  [m mapping]
  (reduce-kv (fn [m k f]
               (if-let [v (get m k)]
                 (assoc m k (coerce v f))
                 m)) m mapping))

(defn parse-args
  "Parse the command line arguments `args`, a seq of strings.
  Expected format: `[\"cmd_1\" ... \"cmd_n\" \":k_1\" \"v_1\" .. \":k_n\" \"v_n\"]`.

  Return value: a map of `:cmds` and `:opts`

  Supported options:
  - `:coerce`: a map of keys to coercion functions that will be applied to parsed `:opts`. See `coerce-vals`.

  Examples:
  ``` clojure
  (parse-args [\"foo\" \":bar\" \"1])
  ;; => {:cmds [\"foo\"] :opts {:bar \"1\"}}
  (parse-args [\"foo\" \":bar\" \"1] {:coerce {:b parse-long}})
  ;; => {:cmds [\"foo\"] :opts {:bar 1}}
  ```
"
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
