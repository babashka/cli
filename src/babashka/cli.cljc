(ns babashka.cli
  (:require [clojure.string :as str]
            [babashka.cli.internal :refer [merge-opts]]))

#?(:clj (set! *warn-on-reflection* true))

(defn coerce
  "Coerce string `s` using `f`. Does not coerce when `s` is not a string.
  `f` may be a keyword (`:boolean`, `:int`, `:double`, `:symbol`,
  `:keyword`) or a function. When `f` return `nil`, this is
  interpreted as a parse failure and throws."
  [s f]
  (let [f* (if (keyword? f)
             (case f
               (:boolean :booleans) parse-boolean
               (:int :ints :long :longs) parse-long
               (:double :doubles) parse-double
               (:symbol :symbols) symbol
               (:keyword :keywords) keyword
               (:string :strings) identity)
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

(defn- coerce->collect [k]
  (case k
    (:strings :booleans :ints :longs :doubles :symbols :keywords) []
    nil))

(defn- coerce-collect-fn [collect-opts opt coercek]
  (let [collect-fn (or (get collect-opts opt)
                       (coerce->collect coercek))
        collect-fn (when collect-fn
                     (if (coll? collect-fn)
                       (fnil conj collect-fn)
                       collect-fn))]
    collect-fn))

(defn- process-previous
  "Adds true for trailing --foo boolean flag or leaves previous value as is."
  [acc current-opt added collect-fn]
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
         exec-args (:exec-args opts)
         mode (:mode opts)
         [cmds opts] (split-with #(not (or (str/starts-with? % ":")
                                           (str/starts-with? % "-"))) args)
         cmds (some-> (seq cmds) vec)
         [opts last-opt added]
         (loop [acc (or exec-args {})
                current-opt nil
                added nil
                args (seq opts)]
           (if-not args
             [acc current-opt added]
             (let [^String arg (first args)
                   collect-fn (coerce-collect-fn collect current-opt (get coerce-opts current-opt))
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
                          nargs (vary-meta assoc-in [:org.babashka/cli :rest-args] (vec nargs)))
                        current-opt added])
                     (let [kname (if long-opt?
                                   (subs arg 2)
                                   (str/replace arg #"^(:|-|)" ""))
                           [kname arg] (if long-opt?
                                         (str/split kname #"=")
                                         [kname])
                           k (keyword kname)
                           k (get aliases k k)]
                       (if arg
                         (recur (process-previous acc current-opt added collect-fn) k nil (cons arg (rest args)))
                         (recur (process-previous acc (if (= :strict mode)
                                                        k current-opt)
                                                  (if (= :strict mode)
                                                    nil added) collect-fn) k added (next args))))))
                 (if (= :strict mode)
                   [(vary-meta acc assoc-in [:org.babashka/cli :args] (vec args)) nil nil]
                   (recur (add-val acc current-opt collect-fn (get coerce-opts current-opt) arg)
                          current-opt
                          current-opt
                          (next args)))))))
         collect-fn (coerce-collect-fn collect last-opt (get coerce last-opt))]
     (-> (process-previous opts last-opt added collect-fn)
         (cond->
             cmds (vary-meta assoc-in [:org.babashka/cli :cmds] cmds))))))

(defn parse-args
  "Same as `parse-opts` but separates parsed opts into `:opts` and adds
  `:cmds` and `:rest-args` on the top level instead of metadata."
  ([args] (parse-args args {}))
  ([args opts]
   (let [opts (parse-opts args opts)
         cli-opts (-> opts meta :org.babashka/cli)]
     (assoc cli-opts :opts (dissoc opts :org.babashka/cli)))))

#_(defn commands
  "Returns commands, i.e. non-option arguments passed before the first option argument."
  [parsed-opts]
  (-> parsed-opts meta :org.babashka/cli :cmds))

#_(defn remaining
  "Returns remaining arguments, i.e. arguments after `--`"
  [parsed-opts]
  (-> parsed-opts meta :org.babashka/cli :rest-args))

(defn- split [a b]
  (let [[prefix suffix] (split-at (count a) b)]
    (when (= prefix a)
      suffix)))

(defn dispatch
  "Subcommand dispatcher.

  Dispatches on first matching command entry in `table`. A match is
  determines by whether `:cmds`, a vector of strings, is a subsequence
  (matching from the start) of the invoked commands.

  Table is in the form:

  ```clojure
  [{:cmds [\"sub_1\" .. \"sub_n\"] :fn f :cmds-opts [:lib]}
   ...
   {:cmds [] :fn f}]
  ```

  When a match is found, `:fn` called with the return value of
  `parse-args` applied to `args` enhanced with:

  * `:dispatch` - the matching commands
  * `:rest-cmds` - any remaining cmds

  Any trailing commands can be matched as options using `:cmds-opts`.

  This function does not throw. Use an empty `:cmds` vector to always match.

  Examples: see [README.md](README.md#subcommands)."
  ([table args] (dispatch table args nil))
  ([table args opts]
   (let [{:keys [cmds opts] :as m} (parse-args args opts)]
     (reduce (fn [_ {dispatch :cmds
                     f :fn
                     cmds-opts :cmds-opts}]
               (when-let [suffix (split dispatch cmds)]
                 (let [rest-cmds (some-> suffix seq vec)
                       [rest-cmds extra-opts] (if (and rest-cmds cmds-opts)
                                                (let [cnt (min (count rest-cmds)
                                                               (count cmds-opts))]
                                                  [(drop cnt rest-cmds)
                                                   (zipmap cmds-opts rest-cmds)])
                                                [rest-cmds nil])
                       opts (if extra-opts
                              (merge opts extra-opts)
                              opts)]
                   (reduced (f (assoc m
                                      :rest-cmds rest-cmds
                                      :opts opts
                                      :dispatch dispatch))))))
             nil table))))
