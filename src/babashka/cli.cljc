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
            #?@(:clj [leading-num-char (if (= \- fst-char)
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

(defn- collect-fn
  "Returns the collection function for opt, derived from collect-opts and coerce-map."
  [collect-opts coerce-map opt]
  (let [f (or (get collect-opts opt)
              (let [k (get coerce-map opt)]
                (when (coll? k) (empty k))))]
    (when f
      (if (coll? f) (fnil conj f) f))))

(defn- process-previous [acc current-opt added cf]
  (if (not= current-opt added)
    (let [v (if cf (cf (get acc current-opt) true) true)]
      (assoc acc current-opt v))
    acc))

(defn- add-val [acc current-opt cf arg]
  (if cf
    (update acc current-opt cf arg)
    (assoc acc current-opt arg)))

(defn spec->opts
  "Converts spec into opts format. Pass existing opts as optional second argument."
  ([spec] (spec->opts spec nil))
  ([spec {:keys [exec-args]}]
   (reduce
    (fn [acc [k {:keys [coerce collect alias default require validate]}]]
      (cond-> acc
        coerce (update :coerce assoc k coerce)
        collect (update :collect assoc k collect)
        alias (update :alias
                      (fn [aliases]
                        (when (contains? aliases alias)
                          (throw (ex-info (str "Conflicting alias " alias " between " (get aliases alias) " and " k)
                                          {:alias alias})))
                        (assoc aliases alias k)))
        require (update :require (fnil #(conj % k) #{}))
        validate (update :validate assoc k validate)
        (some? default) (update :exec-args
                                (fn [new-exec-args]
                                  (assoc new-exec-args k (get exec-args k default))))))
    {}
    spec)))

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
  ([args args->opts-map] (args->opts args args->opts-map #{}))
  ([args args->opts-map ignored-args]
   (let [[new-args args->opts]
         (if args->opts-map
           (if (and (seq args)
                    (not (contains? ignored-args (first args))))
             (let [arg-count (count args)
                   cnt (min arg-count
                            (bounded-count arg-count args->opts-map))]
               [(concat (interleave args->opts-map args)
                        (drop cnt args))
                (drop cnt args->opts-map)])
             [args args->opts-map])
           [args args->opts-map])]
     {:args new-args
      :args->opts args->opts})))

(defn- parse-key [arg mode current-opt boolean-opt? added known-keys alias-keys]
  (let [fst-char (first-char arg)
        snd-char (second-char arg)
        hyphen-opt? (and (not= :keywords mode)
                         (= \- fst-char)
                         (> (count arg) 1)
                         (let [k (keyword (subs arg 1))]
                           (or
                            (contains? known-keys k)
                            (contains? alias-keys k)
                            (not (number-char? snd-char)))))
        mode (or mode (when hyphen-opt? :hyphens))
        fst-colon? (= \: fst-char)
        kwd-opt? (and (not= :hyphens mode)
                      fst-colon?
                      (or boolean-opt?
                          (not current-opt)
                          (= added current-opt)))
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

(defn- ->error-fn [spec error-fn-opt]
  (let [f (or error-fn-opt
              (fn [data]
                (throw (ex-info (:msg data) data))))]
    (fn [data]
      (f (merge {:spec spec :type :org.babashka/cli} data)))))

(defn- resolve-opts [opts]
  (if (::resolved opts)
    opts
    (let [spec (:spec opts)]
      (assoc (if spec (merge-opts opts (spec->opts spec opts)) opts)
             ::resolved true))))

(defn coerce-opts
  "Coerces values in the map `m` using the provided configuration.
  Does not coerce values that are not strings.
  Returns a new map with coerced values.

  Supported options:
  * `:coerce` - a map of option (keyword) names to type keywords (optionally wrapped in a collection).
  * `:spec` - a spec of options. See [spec](https://github.com/babashka/cli#spec).
  * `:error-fn` - error handler, called with a map containing `:cause` (`:coerce`), `:msg`, `:option`, `:value`, and `:opts`."
  ([m] (coerce-opts m {}))
  ([m opts]
   (let [spec (:spec opts)
         opts (resolve-opts opts)
         coerce-map (:coerce opts)
         implicit-true-keys (::implicit-true-keys opts)
         auto-coerce? (::auto-coerce opts)
         error-fn (->error-fn spec (:error-fn opts))]
     (if (or (seq coerce-map) auto-coerce?)
       (let [coerce-1 (fn [v cf implicit-true?]
                         (if cf (coerce* v cf implicit-true?) (auto-coerce v)))]
         (with-meta
           (reduce-kv
            (fn [acc k v]
              (if-let [coerce-k (get coerce-map k)]
                (let [coll-coerce? (coll? coerce-k)
                      empty-coll (when coll-coerce? (or (empty coerce-k) []))
                      cf (coerce-coerce-fn coerce-k)
                      it? (and implicit-true-keys (contains? implicit-true-keys k))]
                  (try
                    (cond
                      (and coll-coerce? (coll? v))
                      (assoc acc k (reduce (fn [coll elem] (conj coll (coerce-1 elem cf it?))) empty-coll v))
                      coll-coerce?
                      (assoc acc k (conj empty-coll (coerce-1 v cf it?)))
                      (coll? v)
                      (assoc acc k (into (empty v) (map #(coerce-1 % cf it?)) v))
                      :else
                      (assoc acc k (coerce-1 v cf it?)))
                    (catch #?(:clj ExceptionInfo :cljs :default) e
                      (error-fn {:cause :coerce
                                 :msg #?(:clj (.getMessage e)
                                         :cljs (ex-message e))
                                 :option k
                                 :value v
                                 :opts acc})
                      acc)))
                (if auto-coerce?
                  (assoc acc k (auto-coerce v))
                  (assoc acc k v))))
            {} m)
           (meta m)))
       m))))

(defn validate-opts
  "Validates the map `m` using the provided configuration. Returns `m`.

  Supported options:
  * `:restrict` - `true` or coll of keys. Error on keys in `m` not in the restrict set or not derivable from `:spec` and `:coerce`.
  * `:require` - a coll of options that are required.
  * `:validate` - a map of option keys to validator functions (or maps with `:pred` and `:ex-msg`).
  * `:spec` - a spec of options (restrict, require, validate extracted from it).
  * `:coerce` - used with `:restrict true` to derive the set of known keys.
  * `:error-fn` - error handler, called with a map containing `:cause`, `:msg`, `:option`, and `:opts`."
  ([m] (validate-opts m {}))
  ([m opts]
   (let [spec (:spec opts)
         opts (resolve-opts opts)
         coerce-map (:coerce opts)
         aliases (or (:alias opts)
                     (:aliases opts))
         spec-map (if (map? spec)
                    spec (when spec (into {} spec)))
         known-keys (set (concat (keys spec-map)
                                 (vals aliases)
                                 (keys coerce-map)))
         restrict (or (:restrict opts)
                      (:closed opts))
         restrict (if (true? restrict)
                    known-keys
                    (some-> restrict set))
         require (:require opts)
         validate (:validate opts)
         error-fn (->error-fn spec (:error-fn opts))]
     (when restrict
       (doseq [k (keys m)]
         (when (and (not (contains? restrict k))
                    (not= (namespace k) "babashka.cli"))
           (error-fn {:cause :restrict
                      :msg (str "Unknown option: " k)
                      :restrict restrict
                      :option k
                      :opts m}))))
     (when require
       (doseq [k require]
         (when-not (find m k)
           (error-fn {:cause :require
                      :msg (str "Required option: " k)
                      :require require
                      :option k
                      :opts m}))))
     (when validate
       (doseq [[k vf] validate]
         (let [f (or (and
                      ;; we allow sets (typically of keywords) as predicates,
                      ;; but maps are less commmon
                      (map? vf)
                      (:pred vf))
                     vf)]
           (when-let [[_ v] (find m k)]
             (when-not (f v)
               (let [ex-msg-fn (or (:ex-msg vf)
                                   (fn [{:keys [option value]}]
                                     (str "Invalid value for option " option ": " value)))]
                 (error-fn {:cause :validate
                            :msg (ex-msg-fn {:option k :value v})
                            :validate validate
                            :option k
                            :value v
                            :opts m})))))))
     m)))

;;
;; Parsing
;;

(defn- parse-opts*
  "Parses CLI args into a raw opts map (string values, no coercion).
  Returns the raw map with :org.babashka/cli metadata.
  ::implicit-true-keys in metadata tracks which keys got implicit true."
  [args {:keys [coerce collect no-keyword-opts repeated-opts] :as opts}]
  (let [aliases (or (:alias opts) (:aliases opts))
        spec (:spec opts)
        spec-map (if (map? spec) spec (when spec (into {} spec)))
        alias-keys (set (concat (keys aliases) (map :alias (vals spec-map))))
        known-keys (set (concat (keys spec-map) (vals aliases) (keys coerce)))
        bool? (fn [k] (#{:boolean :bool} (coerce-coerce-fn (get coerce k))))
        track-itk (fn [itk current-opt added]
                    (cond-> itk (not= current-opt added) (conj current-opt)))
        {:keys [cmds args]} (parse-cmds args)
        {new-args :args a->o :args->opts}
        (if-let [a->o (or (:args->opts opts) (:cmds-opts opts))]
          (args->opts cmds a->o (::dispatch-tree-ignored-args opts))
          {:args->opts nil :args args})
        [cmds args] (if (not= new-args args)
                      [nil (concat new-args args)]
                      [cmds args])
        [parsed last-opt added itk]
        (if (and (::dispatch-tree opts) (seq cmds))
          [(vary-meta {} assoc-in [:org.babashka/cli :args] (into (vec cmds) args)) nil nil #{}]
          (loop [acc {}
                 current-opt nil
                 added nil
                 mode (when no-keyword-opts :hyphens)
                 args (seq args)
                 a->o a->o
                 itk #{}]
            (if-not args
              [acc current-opt added itk]
              (let [raw-arg (first args)
                    opt? (keyword? raw-arg)]
                (if opt?
                  (recur (process-previous acc current-opt added nil)
                         raw-arg added mode (next args) a->o
                         (track-itk itk current-opt added))
                  (let [implicit-true? (true? raw-arg)
                        arg (str raw-arg)
                        cf (collect-fn collect coerce current-opt)
                        boolean-opt? (bool? current-opt)
                        {:keys [hyphen-opt composite-opt kwd-opt mode fst-colon]}
                        (parse-key arg mode current-opt boolean-opt? added known-keys alias-keys)]
                    (if (or hyphen-opt kwd-opt)
                      (let [long-opt? (str/starts-with? arg "--")
                            the-end? (and long-opt? (= "--" arg))]
                        (if the-end?
                          (let [nargs (next args)]
                            [(cond-> acc
                               nargs (vary-meta assoc-in [:org.babashka/cli :args] (vec nargs)))
                             current-opt added itk])
                          (let [kname (if long-opt?
                                        (subs arg 2)
                                        (str/replace arg #"^(:|-|)" ""))
                                [kname arg-val] (if long-opt?
                                                  (str/split kname #"=")
                                                  [kname])
                                raw-k (keyword kname)
                                alias (when-not long-opt? (get aliases raw-k))
                                k (or alias raw-k)]
                            (if arg-val
                              (recur (process-previous acc current-opt added cf)
                                     k nil mode (cons arg-val (rest args)) a->o
                                     (track-itk itk current-opt added))
                              (let [next-args (next args)
                                    next-arg (first next-args)
                                    m (parse-key next-arg mode current-opt boolean-opt? added known-keys alias-keys)
                                    negative? (when-not (contains? known-keys k)
                                                (str/starts-with? (str k) ":no-"))]
                                (if (or (:hyphen-opt m) (empty? next-args) negative?)
                                  ;; implicit true
                                  (if (and (not alias) composite-opt)
                                    (let [expanded (mapcat (fn [c] [(str "-" c) true]) (name k))]
                                      (recur acc nil nil mode (concat expanded next-args) a->o itk))
                                    (let [k (if negative?
                                              (keyword (str/replace (str k) ":no-" ""))
                                              k)]
                                      (recur (process-previous acc current-opt added cf)
                                             k added mode (cons (not negative?) next-args) a->o
                                             (track-itk itk current-opt added))))
                                  (recur (process-previous acc current-opt added cf)
                                         k nil mode next-args a->o
                                         (track-itk itk current-opt added))))))))
                      (let [the-end? (or
                                      (and boolean-opt?
                                           (not= arg "true")
                                           (not= arg "false"))
                                      (and (= added current-opt)
                                           (or (not cf)
                                               repeated-opts
                                               (contains? (::dispatch-tree-ignored-args opts) (first args)))))]
                        (if the-end?
                          (let [{new-args :args a->o :args->opts}
                                (if (and args a->o)
                                  (args->opts args a->o (::dispatch-tree-ignored-args opts))
                                  {:args args})
                                new-args? (not= args new-args)]
                            (if new-args?
                              (recur acc current-opt added mode new-args a->o itk)
                              [(vary-meta acc assoc-in [:org.babashka/cli :args] (vec args)) current-opt added itk]))
                          (let [opt (when-not (and (= :keywords mode) fst-colon) current-opt)]
                            (recur (add-val acc current-opt cf arg)
                                   opt opt mode (next args) a->o
                                   (cond-> itk implicit-true? (conj current-opt)))))))))))))]
    ;; Finalize: process last opt, prepend cmds to args metadata
    (let [itk (track-itk itk last-opt added)
          cf (collect-fn collect coerce last-opt)
          parsed (-> (process-previous parsed last-opt added cf)
                     (cond->
                         (and (seq cmds) (not (::dispatch-tree opts)))
                       (vary-meta update-in [:org.babashka/cli :args]
                                  (fn [args] (into (vec cmds) args)))))]
      (vary-meta parsed assoc ::implicit-true-keys itk))))

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
  * `:repeated-opts` - `true`. Forces writing the option name for every value, e.g. `--foo a --foo b`, rather than `--foo a b`
  * `:args->opts` - consume unparsed commands and args as options
  * `:collect` - a map of collection fns. See [custom collection handling](https://github.com/babashka/cli#custom-collection-handling).

  Examples:

  ```clojure
  (parse-opts [\"foo\" \":bar\" \"1\"])
  ;; => {:bar \"1\", :org.babashka/cli {:cmds [\"foo\"]}}
  (parse-args [\":b\" \"1\"] {:aliases {:b :bar} :coerce {:bar parse-long}})
  ;; => {:bar 1}
  (parse-args [\"--baz\" \"--qux\"] {:spec {:baz {:desc \"Baz\"}} :restrict true})
  ;; => throws 'Unknown option --qux' exception b/c there is no :qux key in the spec
  ```
  "
  ([args] (parse-opts args {}))
  ([args opts]
   (let [opts (resolve-opts opts)
         ;; Step 1: Parse (raw strings, no coercion)
         parsed (parse-opts* args opts)
         ;; Step 2: Coerce
         coerced (coerce-opts parsed {:coerce (:coerce opts)
                                      :spec (:spec opts)
                                      :error-fn (:error-fn opts)
                                      ::implicit-true-keys (::implicit-true-keys (meta parsed))
                                      ::auto-coerce true
                                      ::resolved true})
         ;; Step 3: Apply defaults
         coerced (if-let [exec-args (:exec-args opts)]
                   (with-meta (merge exec-args coerced) (meta coerced))
                   coerced)]
     ;; Step 4: Validate
     (validate-opts coerced opts))))

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

(defn- str-width
  "Width of `s` when printed, i.e. without ANSI escape codes."
  [s]
  (let [strip-escape-codes #(str/replace %
                                         (re-pattern "(\\x9B|\\x1B\\[)[0-?]*[ -\\/]*[@-~]") "")]
    (count (strip-escape-codes s))))

(defn pad [len s] (str s (apply str (repeat (- len (str-width s)) " "))))

(defn pad-cells [rows]
  (let [widths  (reduce
                 (fn [widths row]
                   (map max (map str-width row) widths)) (repeat 0) rows)
        pad-row (fn [row]
                  (map pad widths row))]
    (map pad-row rows)))

(defn- expand-multiline-cells [rows]
  (if (empty? rows)
    []
    (let [col-cnt (count (first rows))]
      (mapcat (fn [row]
                (let [row-lines (mapv #(str/split-lines (str %)) row)
                      max-lines (reduce max (map count row-lines))]
                  (map (fn [line-idx]
                         (map #(get-in row-lines [% line-idx] "") (range col-cnt)))
                       (range max-lines))))
              rows))))

(defn format-table [{:keys [rows indent] :or {indent 2}}]
  (let [rows (-> rows
                 expand-multiline-cells
                 pad-cells)
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
  (-> (format-table {:rows rows
                     :indent 2})
      str/split-lines)
  ;; => ["  a    fooo bara  bazzz aa"
  ;;     "  foo  bar  bazzz"
  ;;     "  fooo bara bazzz"]

  (-> (format-table {:rows [["r1c1\nr1c1 l2" "r1c2" "r1c3"]
                            ["r2c1 wider" "r2c2\nr2c2 l2\nr2c2 l3" "r2c3\nr2c3 l2"]
                            ["r3c1" "r3c2 wider" "r3c3\nr3c3 l2\nr3c3 l3"]]
                     :indent 5})
      str/split-lines)
  ;; => ["     r1c1       r1c2       r1c3"
  ;;     "     r1c1 l2"
  ;;     "     r2c1 wider r2c2       r2c3"
  ;;     "                r2c2 l2    r2c3 l2"
  ;;     "                r2c2 l3"
  ;;     "     r3c1       r3c2 wider r3c3"
  ;;     "                           r3c3 l2"
  ;;     "                           r3c3 l3"]
  )

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
                             (some? (:default columns)))
                     (str (or default-desc (str default) "")))
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

(defn- table->tree [table]
  (reduce (fn [tree {:as cfg :keys [cmds]}]
            (let [ks (interleave (repeat :cmd) cmds)]
              (if (seq ks)
                (update-in tree ks merge (dissoc cfg :cmds))
                ;; catch-all
                (merge tree (dissoc cfg :cmds)))))
          {} table))

(comment
  (table->tree [{:cmds [] :fn identity}])
 )

(defn- deep-merge [a b]
  (reduce (fn [acc k] (update acc k (fn [v]
                                      (if (map? v)
                                        (deep-merge v (b k))
                                        (b k)))))
          a (keys b)))

(defn- has-parse-opts? [m]
  (some #{:spec :coerce :require :restrict :validate :args->opts :exec-args} (keys m)))

(defn- is-option? [s]
  (and s
       (or (str/starts-with? s "-")
           (str/starts-with? s ":"))))

(defn- dispatch-tree'
  ([tree args]
   (dispatch-tree' tree args nil))
  ([tree args opts]
   (loop [cmds [] all-opts {} args args cmd-info tree]
     (let [kwm cmd-info
           should-parse-args? (or (has-parse-opts? kwm)
                                  (is-option? (first args)))
           parse-opts (deep-merge opts kwm)
           {:keys [args opts]} (if should-parse-args?
                                 (parse-args args (assoc (update parse-opts :exec-args merge all-opts)
                                                         ::dispatch-tree true
                                                         ::dispatch-tree-ignored-args (set (keys (:cmd cmd-info)))))
                                 {:args args
                                  :opts {}})
           [arg & rest] args
           all-opts (-> (merge all-opts opts)
                        (update ::opts-by-cmds (fnil conj []) {:cmds cmds
                                                               :opts opts}))]
       (if-let [subcmd-info (get (:cmd cmd-info) arg)]
         (recur (conj cmds arg) all-opts rest subcmd-info)
         (if (:fn cmd-info)
           {:cmd-info cmd-info
            :dispatch cmds
            :opts (dissoc all-opts ::opts-by-cmds)
            :args args}
           (if arg
             {:error :no-match
              :wrong-input arg
              :available-commands (keys (:cmd cmd-info))
              :dispatch cmds
              :opts (dissoc all-opts ::opts-by-cmds)}
             {:error :input-exhausted
              :available-commands (keys (:cmd cmd-info))
              :dispatch cmds
              :opts (dissoc all-opts ::opts-by-cmds)})))))))

(defn- dispatch-tree
  ([tree args]
   (dispatch-tree tree args nil))
  ([tree args opts]
   (let [{:as res :keys [cmd-info error available-commands]}
         (dispatch-tree' tree args opts)
         error-fn (or (:error-fn opts)
                       (fn [{:keys [msg] :as data}]
                         (throw (ex-info msg data))))]
     (case error
       (:no-match :input-exhausted)
       (error-fn (merge
                  {:type :org.babashka/cli
                   :cause error
                   :all-commands available-commands}
                  (select-keys res [:wrong-input :opts :dispatch])))
       nil ((:fn cmd-info) (dissoc res :cmd-info))))))

(defn dispatch
  "Subcommand dispatcher.

  Dispatches on longest matching command entry in `table` by matching
  subcommands to the `:cmds` vector and invoking the correspondig `:fn`.

  Table is in the form:

  ```clojure
  [{:cmds [\"sub_1\" .. \"sub_n\"] :fn f :args->opts [:lib]}
   ...
   {:cmds [] :fn f}]
  ```

  When a match is found, `:fn` called with the return value of
  `parse-args` applied to `args` enhanced with:

  * `:dispatch` - the matching commands
  * `:args` - concatenation of unparsed commands and args
  * `:rest-cmds`: DEPRECATED, this will be removed in a future version

  Use an empty `:cmds` vector to always match or to provide global options.

  Provide an `:error-fn` to deal with non-matches.

  Each entry in the table may have additional `parse-args` options.

  For more information and examples, see [README.md](README.md#subcommands)."
  ([table args]
   (dispatch table args {}))
  ([table args opts]
   (let [tree (-> table table->tree)]
     (dispatch-tree tree args opts))))
