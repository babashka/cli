(ns babashka.cli
  (:refer-clojure :exclude [parse-boolean parse-long parse-double])
  (:require
   #?(:clj [clojure.edn :as edn]
      :cljs [cljs.reader :as edn])
   [clojure.string :as str]
   [babashka.cli.internal :refer [merge-opts]]))

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
  [args args->opts coerce-opts]
  (let [[args opts args->opts]
        (if args->opts
          (if (seq args)
            (let [cnt (min (count args)
                           (count args->opts))]
              [(drop cnt args)
               (zipmap args->opts (map (fn [k v]
                                         (if-let [cf (get coerce-opts k)]
                                           (coerce v cf)
                                           v))
                                       args->opts
                                       args))
               (drop cnt args->opts)])
            [args nil args->opts])
          [args nil args->opts])]
    {:args args
     :opts opts
     :args->opts args->opts}))


(defn parse-opts
  "Parse the command line arguments `args`, a seq of strings.
  Expected format: `[\"cmd_1\" ... \"cmd_n\" \":k_1\" \"v_1\" .. \":k_n\" \"v_n\"]`.
  Instead of a leading `:` either `--` or `-` may be used as well.

  Return value: a map with parsed opts. Additional data such as
  initial subcommands and remaining args after `--` are available
  under the `:org.babashka/cli` key in the metadata.

  Supported options:
  *`:coerce`: a map of option (keyword) names to type keywords (optionally wrapped in a collection.)
  * `:aliases` - a map of short names to long names.
  * `:spec` - a spec of options. See [spec](https://github.com/babashka/cli#spec).
  * `:closed` - `true` or set of keys. Throw on first parsed option not in set of keys or keys of `:spec`, `:coerce` and `:aliases` combined.
  * `:args->opts` - consume unparsed commands and args as options

  Examples:

  ```clojure
  (parse-opts [\"foo\" \":bar\" \"1\"])
  ;; => {:bar \"1\", :org.babashka/cli {:cmds [\"foo\"]}}
  (parse-args [\":b\" \"1\"] {:aliases {:b :bar} :coerce {:bar parse-long}})
  ;; => {:bar 1}
  (parse-args [\"--baz\" \"--qux\"] {:spec {:baz {:desc \"Baz\"} :closed true})
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
         aliases (:aliases opts)
         collect (:collect opts)
         exec-args (:exec-args opts)
         no-keyword-opts (:no-keyword-opts opts)
         closed (if (= true (:closed opts))
                  (some-> spec keys (concat (keys aliases)) (concat (keys coerce-opts)) set)
                  (:closed opts))
         {:keys [cmds args]} (parse-cmds args)
         {extra-opts :opts
          cmds :args
          a->o :args->opts}
         (if-let [a->o (or (:args->opts opts)
                           ;; DEPRECATED:
                           (:cmds-opts opts))]
           (args->opts cmds a->o (:coerce opts))
           {:opts nil
            :args cmds})
         cmds (some-> (seq cmds) vec)
         [opts last-opt added]
         (loop [acc (merge-opts (or exec-args {}) extra-opts)
                current-opt nil
                added nil
                mode (when no-keyword-opts :hyphens)
                args (seq args)]
           (if-not args
             [acc current-opt added]
             (let [^String arg (first args)
                   collect-fn (coerce-collect-fn collect current-opt (get coerce-opts current-opt))
                   fst-char (first-char arg)
                   hyphen-opt? (= fst-char \-)
                   mode (or mode (when hyphen-opt? :hyphens))
                   ;; _ (prn :current-opt current-opt arg)
                   fst-colon? (= \: fst-char)
                   kwd-opt? (and (not= :hyphens mode)
                                 fst-colon?
                                 (or (not current-opt)
                                     (= added current-opt)))
                   mode (or mode
                            (when kwd-opt?
                              :keywords))]
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
                       (when (and closed (not (get closed k)))
                         (throw (ex-info (str "Unknown option " arg)
                                         {:closed closed})))
                       (if arg-val
                         (recur (process-previous acc current-opt added collect-fn)
                                k nil mode (cons arg-val (rest args)))
                         (recur (process-previous acc current-opt added collect-fn)
                                k added mode (next args))))))
                 (let [coerce-opt (get coerce-opts current-opt)
                       the-end? (or
                                 (and (= :boolean coerce-opt)
                                      (not added)
                                      (not= arg "true")
                                      (not= arg "false"))
                                 (and (= added current-opt)
                                      (not collect-fn)))]
                   (if the-end?
                     (let [{extra-opts :opts
                            args :args} (if args
                                          (if a->o
                                            (args->opts args a->o (:coerce opts))
                                            {:args args})
                                          {:args args})
                           acc (if extra-opts
                                 (merge-opts acc extra-opts)
                                 acc)]
                       [(vary-meta acc assoc-in [:org.babashka/cli :args] (vec args)) current-opt nil])
                     (recur (add-val acc current-opt collect-fn (coerce-coerce-fn coerce-opt) arg)
                            (if (and (= :keywords mode)
                                     fst-colon?)
                              nil current-opt)
                            (if (and (= :keywords mode)
                                     fst-colon?)
                              nil current-opt)
                            mode
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
