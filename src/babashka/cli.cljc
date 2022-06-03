(ns babashka.cli
  (:require [clojure.string :as str]))

#?(:clj (set! *warn-on-reflection* true))

(defn coerce
  "Coerce string `s` using `f`. Does not coerce when `s` is not a string.
  `f` may be a keyword (`:boolean`, `:int`, `:double`, `:symbol`,
  `:keyword`) or a function. When `f` return `nil`, this is
  interpreted as a parse failure and throws."
  [s f]
  (let [f* (if (keyword? f)
            (case f
              :boolean parse-boolean
              (:int :long) parse-long
              :double parse-double
              :symbol symbol
              :keyword keyword)
            f)]
    (if (string? s)
      (let [v (f* s)]
        (if (nil? v)
          (throw (ex-info (str "Coerce failure: cannot transform input " (pr-str s)
                               (if (keyword? f)
                                 " to "
                                 " with ")
                               (if (keyword? f)
                                 (name f)
                                 f))
                          {:input s
                           :coerce-fn f}))
          v))
      s)))

(defn coerce-vals
  "Coerce vals of map `m` using `mapping`, a map of keys to functions.
  Uses `coerce` to coerce values."
  [m mapping]
  (reduce-kv (fn [m k f]
               (if-let [v (get m k)]
                 (assoc m k (coerce v f))
                 m)) m mapping))

#_{:aliases {:f :foo} ;; representing like this takes care of potential conflicts
   :coerce {:f :boolean}
   :collect {:f []}}

(defn parse-args
  "Parse the command line arguments `args`, a seq of strings.
  Expected format: `[\"cmd_1\" ... \"cmd_n\" \":k_1\" \"v_1\" .. \":k_n\" \"v_n\"]`.
  Instead of a leading `:` either `--` or `-` may be used as well.

  Return value: a map of `:cmds` and `:opts`

  Supported options:
  - `:coerce`: a map of keys to coercion functions that will be applied to parsed `:opts`. See `coerce-vals`.
  - `:aliases`: a map of short names to long names.

  Examples:
  ``` clojure
  (parse-args [\"foo\" \":bar\" \"1])
  ;; => {:cmds [\"foo\"] :opts {:bar \"1\"}}
  (parse-args [\":b\" \"1] {:aliases {:b :bar} :coerce {:bar parse-long}})
  ;; => {:cmds [] :opts {:bar 1}}
  ```
  "
  ([args] (parse-args args {}))
  ([args opts]
   (let [coerce-opts (:coerce opts)
         aliases (:aliases opts)
         collect (:collect opts)
         [cmds opts] (split-with #(not (or (str/starts-with? % ":")
                                           (str/starts-with? % "-"))) args)]
     {:cmds (vec cmds)
      :opts
      (-> (reduce (fn [acc ^String arg]
                    (let [char
                          (when (pos? #?(:clj (.length arg)
                                         :cljs (.-length arg)))
                            (str (.charAt arg 0)))]
                      (if (or (= char ":")
                              (= char "-"))
                        (let [k (let [k (-> (str/replace arg #"^(:|--|-|)" "")
                                            keyword)]
                                  (get aliases k k))]
                          (assoc acc :--current-opt
                                 k
                                 k true))
                        (let [k (:--current-opt acc)]
                          (if-let [collect-fn (get collect k)]
                            (if (coll? collect-fn)
                              (update acc k (fnil conj collect-fn) arg)
                              (update acc k collect-fn arg))
                            (assoc acc k arg))))))
                  {}
                  opts)
          (coerce-vals coerce-opts)
          (dissoc :--current-opt))})))
