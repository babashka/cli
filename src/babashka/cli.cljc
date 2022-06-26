(ns babashka.cli
  (:refer-clojure :exclude [parse-boolean parse-long parse-double])
  (:require
   #?(:clj [clojure.edn :as edn]
      :cljs [cljs.reader :as edn])
   [clojure.string :as str]))

#?(:clj (set! *warn-on-reflection* true))

(defn- nil->error [x]
  (if (nil? x) ::error x))

(defn- parse-with-pred [x pred]
  (let [v (edn/read-string x)]
    (when (pred v)
      v)))

(defn- parse-boolean [x]
  (parse-with-pred x boolean?))

(defn- parse-long [x]
  (parse-with-pred x int?))

(defn- parse-double [x]
  (parse-with-pred x double?))

(defn coerce
  "Coerce string `s` using `f`. Does not coerce when `s` is not a string.
  `f` may be a keyword (`:boolean`, `:int`, `:double`, `:symbol`,
  `:keyword`) or a function. When `f` return `nil`, this is
  interpreted as a parse failure and throws."
  [s f]
  (let [f (if (coll? f)
            (or (first f)
                :string) f)
        f* (case f
             :boolean (comp nil->error parse-boolean)
             (:int :long) (comp nil->error parse-long)
             :double (comp nil->error parse-double)
             :symbol symbol
             :keyword keyword
             :string identity
             :edn edn/read-string
             ;; default
             f)]
    (if (string? s)
      (let [v (f* s)]
        (if (= ::error v)
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
  (when (coll? k)
    (empty k)))

(defn- coerce-collect-fn [collect-opts opt coercek]
  (let [collect-fn (or (get collect-opts opt)
                       (coerce->collect coercek))
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

(defn- first-char ^Character [^String arg]
  (when (pos? #?(:clj (.length arg)
                 :cljs (.-length arg)))
    (.charAt arg 0)))

(defn auto-coerce
  "Auto-coerces `arg` to data according to the following scheme:
  If `arg` is:
  * `true` and `false`, it is coerced as boolean
  * starts with number, it is coerced as a number (through `edn/read-string`)
  * starts with `:`, it is coerced as a keyword (through `edn/read-string`)"
  [^String arg]
  (try
    (let [fst-char (first-char arg)]
      (cond (or (= "true" arg)
                (= "false" arg))
            (edn/read-string arg)
            #?(:clj (some-> fst-char (Character/isDigit))
               :cljs (not (js/isNaN arg)))
            (edn/read-string arg)
            (= \: fst-char)
            (edn/read-string arg)
            :else arg))
    (catch #?(:clj Exception
              :cljs :default) _ arg)))

(defn- add-val [acc current-opt collect-fn coerce-fn arg]
  (let [arg (if coerce-fn (coerce arg coerce-fn)
                (auto-coerce arg))]
    (if collect-fn
      (update acc current-opt collect-fn arg)
      (assoc acc current-opt arg))))

(defn spec->opts
  "Converts spec into opts format."
  [spec]
  (reduce
   (fn [acc [k {:keys [coerce alias default]}]]
     (cond-> acc
       coerce (update :coerce assoc k coerce)
       alias (update :aliases
                     (fn [aliases]
                       (when (contains? aliases alias)
                         (throw (ex-info (str "Conflicting alias " alias " between " (get aliases alias) " and " k)
                                         {:alias alias})))
                       (assoc aliases alias k)))
       default (update :exec-args assoc k default)))
   {}
   spec))

(defn parse-opts
  "Parse the command line arguments `args`, a seq of strings.
  Expected format: `[\"cmd_1\" ... \"cmd_n\" \":k_1\" \"v_1\" .. \":k_n\" \"v_n\"]`.
  Instead of a leading `:` either `--` or `-` may be used as well.

  Return value: a map with parsed opts. Additional data such as
  initial subcommands and remaining args after `--` are available
  under the `:org.babashka/cli` key in the metadata.

  Supported options:
  - `:coerce`: a map of option (keyword) names to type keywords (optionally wrapped in a collection.)
  - `:aliases`: a map of short names to long names.
  - `:spec`: a spec of options. See [spec]().

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
   (let [spec (:spec opts)
         opts (if spec
                (merge opts
                       (when spec (spec->opts spec)))
                opts)
         coerce-opts (:coerce opts)
         aliases (:aliases opts)
         collect (:collect opts)
         exec-args (:exec-args opts)
         no-keyword-opts (:no-keyword-opts opts)
         [cmds opts] (split-with #(not (or (when-not no-keyword-opts (str/starts-with? % ":"))
                                           (str/starts-with? % "-"))) args)
         cmds (some-> (seq cmds) vec)
         [opts last-opt added]
         (loop [acc (or exec-args {})
                current-opt nil
                added nil
                no-keyword-opts no-keyword-opts
                args (seq opts)]
           (if-not args
             [acc current-opt added]
             (let [^String arg (first args)
                   collect-fn (coerce-collect-fn collect current-opt (get coerce-opts current-opt))
                   char
                   (when (pos? #?(:clj (.length arg)
                                  :cljs (.-length arg)))
                     (str (.charAt arg 0)))
                   hyphen-opt? (= char "-")
                   no-keyword-opts (or no-keyword-opts hyphen-opt?)]
               (if (or hyphen-opt?
                       (when-not no-keyword-opts (= char ":")))
                 (let [long-opt? (str/starts-with? arg "--")
                       the-end? (and long-opt? (= "--" arg))]
                   (if the-end?
                     (let [nargs (next args)]
                       [(cond-> acc
                          nargs (vary-meta assoc-in [:org.babashka/cli :args] (vec nargs)))
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
                         (recur (process-previous acc current-opt added collect-fn)
                                k nil no-keyword-opts (cons arg (rest args)))
                         (recur (process-previous acc current-opt added collect-fn)
                                k added no-keyword-opts (next args))))))
                 (let [coerce-opt (get coerce-opts current-opt)
                       the-end? (or
                                 (and (= :boolean coerce-opt)
                                      (not added)
                                      (not= arg "true")
                                      (not= arg "false"))
                                 (and (= added current-opt)
                                      (not collect-fn)))]
                   (if the-end?
                     [(vary-meta acc assoc-in [:org.babashka/cli :args] (vec args)) current-opt nil]
                     (recur (add-val acc current-opt collect-fn coerce-opt arg)
                            current-opt
                            current-opt
                            no-keyword-opts
                            (next args))))))))
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

(defn- kw->str [kw]
  (subs (str kw) 1))

(defn format-opts [{:keys [spec
                           indent
                           order]
                    :or {indent 2}}]
  (let [{:keys [alias-width long-opt-width default-width
                ref-width]}
        (reduce (fn [{:keys [alias-width long-opt-width default-width description-width
                             ref-width]}
                     [option {:keys [ref default default-desc desc alias]}]]
                  (let [alias-width (max alias-width (if alias (count (kw->str alias)) 0))
                        long-opt-width (max long-opt-width (count (kw->str option)))
                        ref-width (max ref-width (if ref (count (str ref)) 0))
                        default-width (max default-width (if default (count (or (str default-desc)
                                                                                (str default))) 0))
                        description-width (max description-width (if desc (count (str desc)) 0))]
                    {:alias-width alias-width
                     :long-opt-width long-opt-width
                     :default-width default-width
                     :description-width description-width
                     :ref-width ref-width}))
                {:alias-width 0
                 :long-opt-width 0
                 :default-width 0
                 :description-width 0
                 :ref-width 0}
                spec)]
    (str/join "\n"
              (map (fn [[option {:keys [ref default default-desc desc alias]}]]
                     (with-out-str
                       (run! print (repeat indent " "))
                       (when alias (print (str "-" (kw->str alias) ", ")))
                       (run! print (repeat (- (+ (if (pos? alias-width)
                                                   3 0)
                                                 alias-width) (if alias
                                                                (+ 3 (count (kw->str alias)))
                                                                0)) " "))
                       (print (str "--" (kw->str option)))
                       (run! print (repeat (+ 1 (- long-opt-width (count (kw->str option)))) " "))
                       (when ref (print ref))
                       (let [spaces (+ (if (pos? ref-width)
                                         1
                                         0) (- ref-width (count (str ref))))]
                         (run! print (repeat spaces " ")))
                       (print (or default-desc (str default)))
                       (let [spaces (+ (if (pos? default-width)
                                         1
                                         0) (- default-width (count (or default-desc
                                                                        (str default)))))]
                         ;; (prn :spaces spaces)
                         (run! print (repeat spaces " ")))
                       (print (str desc))))
                   (if order
                     (map (fn [k]
                            [k (get spec k)])
                          order)
                     spec)))))

#_(println
   (format-opts
    {:spec {:from {:placeholder "FORMAT"
                   :description "The input format"
                   :coerce :keyword
                   :alias :i}
            :force {:coerce :boolean
                    :alias :f}}
     :order [:force :from]}))

#_(format-opts {:spec ... :order [:from :to] :indent 3})

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
