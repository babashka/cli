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

;; but note in neil we have this:
#_{:coerce {:deps-deploy parse-boolean
            :as symbol
            :alias keyword
            :limit parse-long}}
;; which confirms my belief that this is the optimal format for common use cases!

(defn- coerce-collect-fn [collect-opts opt]
  (let [collect-fn (get collect-opts opt)
        collect-fn (when collect-fn
                     (if (coll? collect-fn)
                       (fnil conj collect-fn)
                       collect-fn))]
    collect-fn))

(defn- process-previous [acc current-opt added collect-fn]
  (if (not= current-opt added)
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
    (if collect-fn
      (update acc current-opt collect-fn arg)
      (assoc acc current-opt arg))))

(defn parse-opts
  "Parse the command line arguments `args`, a seq of strings.
  Expected format: `[\"cmd_1\" ... \"cmd_n\" \":k_1\" \"v_1\" .. \":k_n\" \"v_n\"]`.
  Instead of a leading `:` either `--` or `-` may be used as well.

  Return value: a map with parsed opts. Additional data such as
  initial subcommands and remaining args after `--` are available
  under the `:org.babashka/cli` key in the metadata.

  Supported options:
  - `:coerce`: a map of keys to coercion functions that will be applied to parsed `:opts`. See `coerce-vals`.
  - `:aliases`: a map of short names to long names.

  Examples:

  ```clojure
  (parse-opts [\"foo\" \":bar\" \"1])
  ;; => {:bar \"1\", :org.babashka/cli {:cmds [\"foo\"]}}
  (parse-args [\":b\" \"1] {:aliases {:b :bar} :coerce {:bar parse-long}})
  ;; => {:bar 1}
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
         [opts last-opt added]
         (loop [acc {}
                current-opt nil
                added nil
                args (seq opts)]
           (if-not args
             [acc current-opt added]
             (let [^String arg (first args)
                   collect-fn (coerce-collect-fn collect current-opt)
                   char
                   (when (pos? #?(:clj (.length arg)
                                  :cljs (.-length arg)))
                     (str (.charAt arg 0)))]
               (if (or (= char ":")
                       (= char "-"))
                 (let [long-opt? (str/starts-with? arg "--")
                       the-end? (and long-opt? (= "--" arg))]
                   (if the-end?
                     (let [nargs (next args)]
                       [(cond-> acc
                          nargs (vary-meta assoc-in [:org.babashka/cli :remaining] (vec nargs)))
                        current-opt added])
                     (let [kname (if long-opt?
                                   (subs arg 2)
                                   (str/replace arg #"^(:|-|)" ""))
                           [kname arg] (if long-opt?
                                         (str/split kname #"=")
                                         [kname])
                           k (keyword kname)
                           k (get aliases k k)]
                       (recur (cond-> (process-previous acc current-opt added collect-fn)
                                arg (add-val k collect-fn (get coerce-opts k) arg))
                              k
                              (if arg k added)
                              (next args)))))
                 (recur (add-val acc current-opt collect-fn (get coerce-opts current-opt) arg)
                        current-opt
                        current-opt
                        (next args))))))
         collect-fn (coerce-collect-fn collect last-opt)]
     (-> (process-previous opts last-opt added collect-fn)
         (cond->
             cmds (vary-meta assoc :org.babashka/cli {:cmds cmds}))))))

(defn parse-args
  "Same as `parse-opts` but separates parsed opts into `:opts` and adds
  `:cmds` and `:remaining` on the top level."
  ([args] (parse-args args {}))
  ([args opts]
   (let [opts (parse-opts args opts)
         cli-opts (-> opts meta :org.babashka/cli)]
     (assoc cli-opts :opts (dissoc opts :org.babashka/cli)))))

(defn commands
  "Returns commands, i.e. non-option arguments passed before the first option argument."
  [parsed-opts]
  (-> parsed-opts meta :org.babashka/cli :cmds))

(defn remaining
  "Returns remaining arguments, i.e. arguments after `--`"
  [parsed-opts]
  (-> parsed-opts meta :org.babashka/cli :remaining))
