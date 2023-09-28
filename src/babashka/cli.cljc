(ns babashka.cli
  (:refer-clojure :exclude [parse-boolean parse-long parse-double])
  (:require
   #?(:clj [clojure.edn :as edn]
      :cljs [cljs.reader :as edn])
   [babashka.cli.internal :as internal]
   [clojure.string :as str])
  #?(:clj (:import (clojure.lang ExceptionInfo))))

#?(:clj (set! *warn-on-reflection* true))

(defn merge-opts
  "Merges babashka CLI options."
  [m & ms]
  (reduce #(merge-with internal/merge* %1 %2) m ms))

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

(defn number-char? [c]
  (try (parse-number (str c))
       (catch #?(:clj Exception :cljs :default) _ nil)))

(defn- first-char ^Character [^String arg]
  (when (string? arg)
    (nth arg 0 nil)))

(defn- second-char ^Character [^String arg]
  (when (string? arg)
    (nth arg 1 nil)))

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
            fst-char (first-char s)
            #?@(:clj [leading-num-char (if (= fst-char \-)
                                         (second-char s)
                                         fst-char)])]
        (cond (or (= "true" s)
                  (= "false" s))
              (parse-boolean s)
              (= "nil" s) nil
              #?(:clj (some-> leading-num-char (Character/isDigit))
                 :cljs (not (js/isNaN s)))
              (parse-number s)
              (and (= \: fst-char) (re-matches #"\:[a-zA-Z][a-zA-Z0-9_/\.-]*" s))
              (parse-keyword s)
              :else s))
      (catch #?(:clj Exception
                :cljs :default) _ s))
    s))

(defn- throw-coerce [s implicit-true? f e]
  (throw (ex-info (str "Coerce failure: cannot transform "
                       (if implicit-true?
                         "(implicit) true"
                         (str "input " (pr-str s)))
                       (if (keyword? f)
                         " to "
                         " with ")
                       (if (keyword? f)
                         (name f)
                         f))
                  {:input s
                   :coerce-fn f}
                  e)))

(defn- coerce*
  [s f implicit-true?]
  (let [f* (case f
             (:boolean :bool) parse-boolean
             (:int :long) parse-long
             :double parse-double
             :number parse-number
             :symbol symbol
             :keyword parse-keyword
             :string identity
             :edn edn/read-string
             :auto auto-coerce
             ;; default
             f)
        res (if (string? s)
              (try (f* s)
                   (catch #?(:clj Exception :cljs :default) e
                     (throw-coerce s implicit-true? f e)))
              s)]
    (if (and implicit-true? (not (true? res)))
      (throw-coerce s implicit-true? f nil)
      res)))

(defn coerce
  "Coerce string `s` using `f`. Does not coerce when `s` is not a string.
  `f` may be a keyword (`:boolean`, `:int`, `:double`, `:symbol`,
  `:keyword`) or a function. When `f` return `nil`, this is
  interpreted as a parse failure and throws."
  [s f]
  (coerce* s f false))

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
    (if-let [[_ curr-val] (find acc current-opt)]
      (assoc acc current-opt (if collect-fn
                               (collect-fn curr-val true)
                               true))
      (assoc acc current-opt
             (if collect-fn
               (collect-fn nil true)
               true)))
    acc))

(defn- add-val [acc current-opt collect-fn coerce-fn arg implicit-true?]
  (let [arg (if (and coerce-fn
                     (not (coll? coerce-fn))) (coerce* arg coerce-fn implicit-true?)
                (auto-coerce arg))]
    (if collect-fn
      (update acc current-opt collect-fn arg)
      (assoc acc current-opt arg))))

(defn spec->opts
  "Converts spec into opts format."
  [spec]
  (reduce
   (fn [acc [k {:keys [coerce alias default require validate]}]]
     (cond-> acc
       coerce (update :coerce assoc k coerce)
       alias (update :alias
                     (fn [aliases]
                       (when (contains? aliases alias)
                         (throw (ex-info (str "Conflicting alias " alias " between " (get aliases alias) " and " k)
                                         {:alias alias})))
                       (assoc aliases alias k)))
       require (update :require (fnil #(conj % k) #{}))
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

(defn- parse-key [arg mode current-opt coerce-opt added]
  (let [fst-char (first-char arg)
        snd-char (second-char arg)
        hyphen-opt? (and (= fst-char \-)
                         (not (number-char? snd-char)))
        mode (or mode (when hyphen-opt? :hyphens))
        fst-colon? (= \: fst-char)
        kwd-opt? (and (not= :hyphens mode)
                      fst-colon?
                      (or (= :boolean coerce-opt)
                          (or (not current-opt)
                              (= added current-opt))))
        mode (or mode
                 (when kwd-opt?
                   :keywords))
        composite-opt? (when hyphen-opt?
                         (and snd-char (not= \- snd-char)
                              (> (count arg) 2)))]
    {:mode mode
     :hyphen-opt hyphen-opt?
     :composite-opt composite-opt?
     :kwd-opt kwd-opt?
     :fst-colon fst-colon?}))

(defn parse-opts
  "Parse the command line arguments `args`, a seq of strings.
  Instead of a leading `:` either `--` or `-` may be used as well.

  Return value: a map with parsed opts.

  Additional data such as arguments (not corresponding to any options)
  are available under the `:org.babashka/cli` key in the metadata.

  Supported options:
  * `:coerce` - a map of option (keyword) names to type keywords (optionally wrapped in a collection.)
  * `:alias` - a map of short names to long names.
  * `:spec` - a spec of options. See [spec](https://github.com/babashka/cli#spec).
  * `:restrict` - `true` or coll of keys. Throw on first parsed option not in set of keys or keys of `:spec` and `:coerce` combined.
  * `:require` - a coll of options that are required. See [require](https://github.com/babashka/cli#restrict).
  * `:validate` - a map of validator functions. See [validate](https://github.com/babashka/cli#validate).
  * `:exec-args` - a map of default args. Will be overridden by args specified in `args`.
  * `:no-keyword-opts` - `true`. Support only `--foo`-style opts (i.e. `:foo` will not work).
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
                (merge-opts
                 opts
                 (spec->opts spec))
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
         known-keys (set (concat (keys (if (map? spec)
                                         spec (into {} spec)))
                                 (vals aliases)
                                 (keys coerce-opts)))
         restrict (if (true? restrict)
                    known-keys
                    (some-> restrict set))
         validate (:validate opts)
         error-fn* (or (:error-fn opts)
                       (fn [{:keys [msg] :as data}]
                         (throw (ex-info msg data))))
         error-fn (fn [data]
                    (-> {:spec spec :type :org.babashka/cli}
                        (merge data)
                        error-fn*))
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
         (loop [acc {}
                current-opt nil
                added nil
                mode (when no-keyword-opts :hyphens)
                args (seq args)
                a->o a->o]
           (if-not args
             [acc current-opt added]
             (let [raw-arg (first args)
                   opt? (keyword? raw-arg)]
               (if opt?
                 (recur (process-previous acc current-opt added nil)
                        raw-arg added mode (next args)
                        a->o)
                 (let [implicit-true? (true? raw-arg)
                       arg (str raw-arg)
                       collect-fn (coerce-collect-fn collect current-opt (get coerce-opts current-opt))
                       coerce-opt (get coerce-opts current-opt)
                       {:keys [hyphen-opt
                               composite-opt
                               kwd-opt
                               mode fst-colon]} (parse-key arg mode current-opt coerce-opt added)]
                   (if (or hyphen-opt
                           kwd-opt)
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
                               raw-k (keyword kname)
                               k (get aliases raw-k raw-k)]
                           (if arg-val
                             (recur (process-previous acc current-opt added collect-fn)
                                    k nil mode (cons arg-val (rest args)) a->o)
                             (let [next-args (next args)
                                   next-arg (first next-args)
                                   m (parse-key next-arg mode current-opt coerce-opt added)]
                               (if (or (:hyphen-opt m)
                                       (empty? next-args))
                                 ;; implicit true
                                 (if composite-opt
                                   (let [chars (name k)
                                         args (mapcat (fn [char]
                                                        [(str "-" char) true])
                                                      chars)
                                         next-args (concat args next-args)]
                                     (recur acc
                                            nil nil mode next-args
                                            a->o))
                                   (let [negative? (when-not (contains? known-keys k)
                                                     (str/starts-with? (str k) ":no-"))
                                         k (if negative?
                                             (keyword (str/replace (str k) ":no-" ""))
                                             k)
                                         next-args (cons (not negative?) #_"true" next-args)]
                                     (recur (process-previous acc current-opt added collect-fn)
                                            k added mode next-args
                                            a->o)))
                                 (recur (process-previous acc current-opt added collect-fn)
                                        k added mode next-args
                                        a->o)))))))
                     (let [the-end? (or
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
                             [(vary-meta acc assoc-in [:org.babashka/cli :args] (vec args)) current-opt added]))
                         (let [opt (when-not (and (= :keywords mode)
                                                  fst-colon)
                                     current-opt)]
                           (recur (try
                                    (add-val acc current-opt collect-fn (coerce-coerce-fn coerce-opt) arg implicit-true?)
                                    (catch #?(:clj ExceptionInfo :cljs :default) e
                                      (error-fn {:cause :coerce
                                                 :msg #?(:clj (.getMessage e)
                                                         :cljs (ex-message e))
                                                 :option current-opt
                                                 :value arg})
                                      ;; Since we've encountered an error, don't add this opt
                                      acc))
                                  opt
                                  opt
                                  mode
                                  (next args)
                                  a->o))))))))))
         collect-fn (coerce-collect-fn collect last-opt (get coerce-opts last-opt))
         opts (-> (process-previous opts last-opt added collect-fn)
                  (cond->
                   (seq cmds)
                    (vary-meta update-in [:org.babashka/cli :args]
                               (fn [args]
                                 (into (vec cmds) args)))))
         opts (if exec-args
                (with-meta (merge exec-args opts)
                  (meta opts))
                opts)]
     (when restrict
       (doseq [k (keys opts)]
         (when-not (contains? restrict k)
           (error-fn {:cause :restrict
                      :msg (str "Unknown option: " k)
                      :restrict restrict
                      :option k}))))
     (when require
       (doseq [k require]
         (when-not (find opts k)
           (error-fn {:cause :require
                      :msg (str "Required option: " k)
                      :require require
                      :option k}))))
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
                 (error-fn {:cause :validate
                            :msg (ex-msg-fn {:option k :value v})
                            :validate validate
                            :option k
                            :value v})))))))
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

(defn pad [len s] (str s (apply str (repeat (- len (count s)) " "))))

(defn pad-cells [rows]
  (let [widths (reduce
                (fn [widths row]
                  (map max (map count row) widths)) (repeat 0) rows)
        pad-row (fn [row]
                  (map (fn [width col] (pad width col)) widths row))]
    (map pad-row rows)))

(defn format-table [{:keys [rows indent]}]
  (let [rows (pad-cells rows)
        fmt-row (fn [leader divider trailer row]
                  (str leader
                       (apply str (interpose divider row))
                       trailer))]
    (->> rows
         (map (fn [row]
                #_(fmt-row "| " " | " " |" row)
                (fmt-row (apply str (repeat indent " ")) " " "" row)))
         (map str/trimr)
         (str/join "\n"))))

(comment
  (def rows [["a" "fooo" "bara" "bazzz"  "aa"]
             ["foo" "bar" "bazzz"]
             ["fooo" "bara" "bazzz"]])
  (pad-cells rows)
  (format-table {:rows rows
                 :indent 2}))

(defn opts->table [{:keys [spec order]}]
  (let [columns (set (mapcat (fn [[_ s]] (keys s)) spec))]
    (mapv (fn [[long-opt {:keys [alias default default-desc ref desc]}]]
            (keep identity
                  [(when (:alias columns)
                     (if alias (str "-" (kw->str alias) ",") ""))
                   (str "--" (kw->str long-opt))
                   (when (:ref columns)
                     (if ref ref ""))
                   (when (or (:default-desc columns)
                             (:default columns))
                     (str (or default-desc default "")))
                   (when (:desc columns)
                     (if desc desc ""))]))
          (if (map? spec)
            (let [order (or order (keys spec))]
              (map (fn [k] [k (spec k)]) order))
            spec))))

(defn format-opts [{:as cfg
                    :keys [indent]
                    :or {indent 2}}]
  (format-table {:rows (opts->table cfg)
                 :indent indent}))

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
