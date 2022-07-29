(ns babashka.cli
  (:refer-clojure :exclude [parse-boolean parse-long parse-double])
  (:require
   #?(:clj [clojure.edn :as edn]
      :cljs [cljs.reader :as edn])
   [babashka.cli.internal :refer [merge-opts]]
   [clojure.string :as str]))

#?(:clj (set! *warn-on-reflection* true))

(defn- throw-unexpected [s]
  (throw (ex-info (str "Unexpected format: " s) {:s s})))

(defn- parse-boolean [x]
  #?(:clj (Boolean/parseBoolean x)
     :cljs (let [v (js/JSON.parse x)]
             (if (boolean? v)
               v
               (throw-unexpected x)))))

(defn- parse-long [x]
  #?(:clj (Long/parseLong x)
     :cljs (let [v (js/JSON.parse x)]
             (if (int? v)
               v
               (throw-unexpected x)))))

(defn- parse-double [x]
  #?(:clj (Double/parseDouble x)
     :cljs (let [v (js/JSON.parse x)]
             (if (double? v)
               v
               (throw-unexpected x)))))

(defn- parse-number [x]
  #?(:clj (let [rdr (java.io.PushbackReader. (java.io.StringReader. x))
                v (edn/read {:eof ::eof} rdr)
                eof? (identical? ::eof (edn/read  {:eof ::eof} rdr))]
            (if (and eof? (number? v))
              v
              (throw-unexpected x)))
     :cljs (let [v (js/JSON.parse x)]
             (if (number? v)
               v
               (throw-unexpected x)))))

(defn- first-char ^Character [^String arg]
  (when (pos? #?(:clj (.length arg)
                 :cljs (.-length arg)))
    (.charAt arg 0)))

(defn parse-keyword
  "Parse keyword from `s`. Ignores leading `:`."
  [s]
  (if (= \: (first-char s))
    (keyword (subs s 1))
    (keyword s)))

(defn- coerce-coerce-fn [f]
  (if (coll? f)
    (first f)
    f))

(defn auto-coerce
  "Auto-coerces `s` to data. Does not coerce when `s` is not a string.
  If `s`:
  * is `true` or `false`, it is coerced as boolean
  * starts with number, it is coerced as a number (through `edn/read-string`)
  * starts with `:`, it is coerced as a keyword (through `parse-keyword`)"
  [s]
  (if (string? s)
    (try
      (let [s ^String s
            fst-char (first-char s)]
        (cond (or (= "true" s)
                  (= "false" s))
              (parse-boolean s)
              #?(:clj (some-> fst-char (Character/isDigit))
                 :cljs (not (js/isNaN s)))
              (parse-number s)
              (and (= \: fst-char) (re-matches #"\:?[a-zA-Z0-9]+" s))
              (parse-keyword s)
              :else s))
      (catch #?(:clj Exception
                :cljs :default) _ s))
    s))

(defn coerce
  "Coerce string `s` using `f`. Does not coerce when `s` is not a string.
  `f` may be a keyword (`:boolean`, `:int`, `:double`, `:symbol`,
  `:keyword`) or a function. When `f` return `nil`, this is
  interpreted as a parse failure and throws."
  [s f]
  (let [f* (case f
             :boolean parse-boolean
             (:int :long) parse-long
             :double parse-double
             :number parse-number
             :symbol symbol
             :keyword parse-keyword
             :string identity
             :edn edn/read-string
             :auto auto-coerce
             ;; default
             f)]
    (if (string? s)
      (let [v (try (f* s)
                   (catch #?(:clj Exception :cljs :default) _ ::error))]
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

(defn- add-val [acc current-opt collect-fn coerce-fn arg]
  (let [arg (if (and coerce-fn
                     (not (coll? coerce-fn))) (coerce arg coerce-fn)
                (auto-coerce arg))]
    (if collect-fn
      (update acc current-opt collect-fn arg)
      (assoc acc current-opt arg))))

(defn spec->opts
  "Converts spec into opts format."
  [spec]
  (reduce
   (fn [acc [k {:keys [coerce alias default validate]}]]
     (cond-> acc
       coerce (update :coerce assoc k coerce)
       alias (update :alias
                     (fn [aliases]
                       (when (contains? aliases alias)
                         (throw (ex-info (str "Conflicting alias " alias " between " (get aliases alias) " and " k)
                                         {:alias alias})))
                       (assoc aliases alias k)))
       validate (update :validate assoc k validate)
       default (update :exec-args assoc k default)))
   {}
   spec))

(defn parse-cmds
  "Parses sub-commands (arguments not starting with an option prefix) and returns a map with:
  * `:cmds` - The parsed subcommands
  * `:args` - The remaining (unparsed) arguments"
  ([args] (parse-cmds args nil))
  ([args {:keys [no-keyword-opts]}]
   (let [[cmds args]
         (split-with #(not (or (when-not no-keyword-opts (str/starts-with? % ":"))
                               (str/starts-with? % "-"))) args)]
     {:cmds cmds
      :args args})))

(defn- args->opts
  [args args->opts]
  (let [[new-args args->opts]
        (if args->opts
          (if (seq args)
            (let [arg-count (count args)
                  cnt (min arg-count
                           (bounded-count arg-count args->opts))]
              [(concat (interleave args->opts args)
                       (drop cnt args))
               (drop cnt args->opts)])
            [args args->opts])
          [args args->opts])]
    {:args new-args
     :args->opts args->opts}))

(defn parse-opts
  "Parse the command line arguments `args`, a seq of strings.
  Instead of a leading `:` either `--` or `-` may be used as well.

  Return value: a map with parsed opts.

  Additional data such as arguments (not corresponding to any options)
  are available under the `:org.babashka/cli` key in the metadata.

  Supported options:
  * `:coerce`: a map of option (keyword) names to type keywords (optionally wrapped in a collection.)
  * `:alias` - a map of short names to long names.
  * `:spec` - a spec of options. See [spec](https://github.com/babashka/cli#spec).
  * `:restrict` - `true` or coll of keys. Throw on first parsed option not in set of keys or keys of `:spec` and `:coerce` combined.
  * `:require`: a coll of options that are required
  * `:args->opts` - consume unparsed commands and args as options

  Examples:

  ```clojure
  (parse-opts [\"foo\" \":bar\" \"1\"])
  ;; => {:bar \"1\", :org.babashka/cli {:cmds [\"foo\"]}}
  (parse-args [\":b\" \"1\"] {:aliases {:b :bar} :coerce {:bar parse-long}})
  ;; => {:bar 1}
  (parse-args [\"--baz\" \"--qux\"] {:spec {:baz {:desc \"Baz\"} :restrict true})
  ;; => throws 'Unknown option --qux' exception b/c there is no :qux key in the spec
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
         aliases (or
                  (:alias opts)
                  (:aliases opts))
         collect (:collect opts)
         require (:require opts)
         exec-args (:exec-args opts)
         no-keyword-opts (:no-keyword-opts opts)
         restrict (or (:restrict opts)
                      (:closed opts))
         restrict (if (= true restrict)
                    (some-> spec keys (concat (keys coerce-opts)) set)
                    (some-> restrict set))
         validate (:validate opts)
         {:keys [cmds args]} (parse-cmds args)
         {new-args :args
          a->o :args->opts}
         (if-let [a->o (or (:args->opts opts)
                           ;; DEPRECATED:
                           (:cmds-opts opts))]
           (args->opts cmds a->o)
           {:args->opts nil
            :args args})
         [cmds args] (if (not= new-args args)
                       [nil (concat new-args args)]
                       [cmds args])
         [opts last-opt added]
         (loop [acc (or exec-args {})
                current-opt nil
                added nil
                mode (when no-keyword-opts :hyphens)
                args (seq args)
                a->o a->o]
           (if-not args
             [acc current-opt added]
             (let [^String arg (first args)
                   opt? (keyword? arg)]
               (if opt?
                 (recur (process-previous acc current-opt added nil)
                        arg added mode (next args)
                        a->o)
                 (let [collect-fn (when-not opt?
                                    (coerce-collect-fn collect current-opt (get coerce-opts current-opt)))
                       fst-char (when-not opt?
                                  (first-char arg))
                       hyphen-opt? (when-not opt? (= fst-char \-))
                       mode (or mode (when hyphen-opt? :hyphens))
                       ;; _ (prn :current-opt current-opt arg)
                       fst-colon? (when-not opt?
                                    (= \: fst-char))
                       kwd-opt? (when-not opt?
                                  (and (not= :hyphens mode)
                                       fst-colon?
                                       (or (not current-opt)
                                           (= added current-opt))))
                       mode (or mode
                                (when-not opt?
                                  (when kwd-opt?
                                    :keywords)))]
                   (if (or hyphen-opt?
                           kwd-opt?)
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
                               [kname arg-val] (if long-opt?
                                                 (str/split kname #"=")
                                                 [kname])
                               k     (keyword kname)
                               k     (get aliases k k)]
                           (if arg-val
                             (recur (process-previous acc current-opt added collect-fn)
                                    k nil mode (cons arg-val (rest args)) a->o)
                             (recur (process-previous acc current-opt added collect-fn)
                                    k added mode (next args)
                                    a->o)))))
                     (let [coerce-opt (get coerce-opts current-opt)
                           the-end? (or
                                     (and (= :boolean coerce-opt)
                                          (not= arg "true")
                                          (not= arg "false"))
                                     (and (= added current-opt)
                                          (not collect-fn)))]
                       (if the-end?
                         (let [{new-args :args
                                a->o :args->opts}
                               (if args
                                 (if a->o
                                   (args->opts args a->o)
                                   {:args args})
                                 {:args args})
                               new-args? (not= args new-args)]
                           (if new-args?
                             (recur acc current-opt added mode new-args a->o)
                             [(vary-meta acc assoc-in [:org.babashka/cli :args] (vec args)) current-opt nil]))
                         (recur (add-val acc current-opt collect-fn (coerce-coerce-fn coerce-opt) arg)
                                (if (and (= :keywords mode)
                                         fst-colon?)
                                  nil current-opt)
                                (if (and (= :keywords mode)
                                         fst-colon?)
                                  nil current-opt)
                                mode
                                (next args)
                                a->o)))))))))
         collect-fn (coerce-collect-fn collect last-opt (get coerce last-opt))
         opts (-> (process-previous opts last-opt added collect-fn)
                  (cond->
                      (seq cmds)
                    (vary-meta update-in [:org.babashka/cli :args]
                               (fn [args]
                                 (into (vec cmds) args)))))]
     (when restrict
       (doseq [k (keys opts)]
         (when-not (contains? restrict k)
           (throw (ex-info (str "Unknown option: " k)
                           {:restrict restrict
                            :option k})))))
     (when require
       (doseq [k require]
         (when-not (find opts k)
           (throw (ex-info (str "Required option: " k) {:require require
                                                        :option k})))))
     (when validate
       (doseq [[k vf] validate]
         (let [f (or (and
                      ;; we allow sets (typically of keywords) as predicates,
                      ;; but maps are less commmon
                      (map? vf)
                      (:pred vf))
                     vf)]
           (when-let [[_ v] (find opts k)]
             (when-not (f v)
               (let [ex-msg-fn (or (:ex-msg vf)
                                   (fn [{:keys [option value]}]
                                     (str "Invalid value for option " option ": " value)))]
                 (throw (ex-info (ex-msg-fn {:option k :value v})
                                 {:validate validate
                                  :option k
                                  :value v}))))))))
     opts)))

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
                        default? (or default-desc default)
                        default-width (max default-width (if default? (count (or default-desc
                                                                                 (-> default str not-empty))) 0))
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
  * `:args` - concatenation of unparsed commands and args
  * `:rest-cmds`: DEPRECATED, this will be removed in a future version

  This function does not throw. Use an empty `:cmds` vector to always match.

  Each entry in the table may have additional `parse-args` options.

  Examples: see [README.md](README.md#subcommands)."
  ([table args] (dispatch table args nil))
  ([table args opts]
   (let [{:keys [cmds args] :as m} (parse-cmds args opts)]
     (reduce (fn [_ {dispatch :cmds
                     f :fn
                     :as sub-opts}]
               (when-let [suffix (split dispatch cmds)]
                 (let [rest-cmds (some-> suffix seq vec)
                       args (concat rest-cmds args)
                       {:keys [opts args cmds]} (parse-args args (merge-opts opts sub-opts))
                       args (concat cmds args)]
                   (reduced (f (assoc m
                                      :args args
                                      ;; deprecated name: will be removed in the future!
                                      :rest-cmds args
                                      :opts opts
                                      :dispatch dispatch))))))
             nil table))))
