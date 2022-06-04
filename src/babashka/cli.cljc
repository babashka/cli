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

#_{:aliases {:f :foo} ;; representing like this takes care of potential conflicts
   :coerce {:f :boolean}
   :collect {:f []}
   :doc {:f "A cool options"}}

;; possibly we could have gone with, is there any benefit in doing so?
;; we could support this in the future, if it turns out to be more beneficial
;; by detecting the `:org.babashka/cli` key which indicates the "by options" schema
#_{:foo {:coerce :boolean
         :collect []
         :doc "A cool option"}
   :org.babashka/cli {:aliases {:s :syms}
                      :other :options}}

(defn- coerce-collect-fn [collect-opts opt]
  (let [collect-fn (get collect-opts opt)
        collect-fn (when collect-fn
                     (if (coll? collect-fn)
                       (fnil conj collect-fn)
                       collect-fn))]
    collect-fn))

(defn- process-previous [acc current-opt collect-fn]
  (if (not= current-opt (:--added acc))
    (update acc current-opt (fn [curr-val]
                              (if (nil? curr-val)
                                (if collect-fn
                                  (collect-fn curr-val true)
                                  true)
                                (if collect-fn
                                  (collect-fn curr-val true)
                                  curr-val))))
    acc))

(defn- add-val [acc current-opt collect-fn coerce-fn arg]
  (let [arg (if coerce-fn (coerce arg coerce-fn)
                arg)]
    (-> (if collect-fn
          (update acc current-opt collect-fn arg)
          (assoc acc current-opt arg))
        (assoc :--added current-opt))))

(defn parse-opts
  "Parse the command line arguments `args`, a seq of strings.
  Expected format: `[\"cmd_1\" ... \"cmd_n\" \":k_1\" \"v_1\" .. \":k_n\" \"v_n\"]`.
  Instead of a leading `:` either `--` or `-` may be used as well.

  Return value: a map of `:cmds` and `:opts`

  Supported options:
  - `:coerce`: a map of keys to coercion functions that will be applied to parsed `:opts`. See `coerce-vals`.
  - `:aliases`: a map of short names to long names.

  Examples:

  ```clojure
  (parse-args [\"foo\" \":bar\" \"1])
  ;; => {:cmds [\"foo\"] :opts {:bar \"1\"}}
  (parse-args [\":b\" \"1] {:aliases {:b :bar} :coerce {:bar parse-long}})
  ;; => {:cmds [] :opts {:bar 1}}
  ```
  "
  ([args] (parse-opts args {}))
  ([args opts]
   (let [coerce-opts (:coerce opts)
         aliases (:aliases opts)
         collect (:collect opts)
         [cmds opts] (split-with #(not (or (str/starts-with? % ":")
                                           (str/starts-with? % "-"))) args)
         cmds (some-> (seq cmds) vec)
         opts
           (reduce (fn [acc ^String arg]
                     (let [current-opt (:--current-opt acc)
                           collect-fn (coerce-collect-fn collect current-opt)
                           char
                           (when (pos? #?(:clj (.length arg)
                                          :cljs (.-length arg)))
                             (str (.charAt arg 0)))]
                       (if (or (= char ":")
                               (= char "-"))
                         (let [long-opt? (str/starts-with? arg "--")
                               kname (if long-opt?
                                       (subs arg 2)
                                       (str/replace arg #"^(:|-|)" ""))
                               [kname arg] (if long-opt?
                                             (str/split kname #"=")
                                             [kname])
                               k (keyword kname)
                               k (get aliases k k)]
                           (cond-> (-> (assoc acc :--current-opt k)
                                       (process-previous current-opt collect-fn)
                                       (dissoc :--added))
                             arg (add-val k collect-fn (get coerce-opts k) arg)))
                         (add-val acc current-opt collect-fn (get coerce-opts current-opt) arg))))
                   {}
                   opts)
         last-opt (get opts :--current-opt)
         collect-fn (coerce-collect-fn collect last-opt)]
     (-> (process-previous opts last-opt collect-fn)
         (dissoc :--current-opt :--added)
         (cond->
             cmds (assoc :org.babashka/cli {:cmds cmds}))))))

(defn parse-args
  "Here for backwards compatibility, will be removed in 1.0.0."
  {:no-doc true}
  ([args] (parse-args args {}))
  ([args opts]
   (let [opts (parse-opts args opts)]
     {:cmds (-> opts :org.babashka/cli :cmds vec)
      :opts (dissoc opts :org.babashka/cli)})))
