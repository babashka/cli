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
  * starts with number, it is coerced as a number (through Clojure's `edn/read-string`)
  * starts with `:`, it is coerced as a keyword (through [[parse-keyword]])"
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
                  (cond-> {:input s
                           :coerce-fn f}
                    implicit-true? (assoc :implicit-true true))
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
  "Parses sub-commands (arguments not starting with an option prefix). Returns a map with:
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
         m-meta (meta m)
         implicit-true-keys (or (::implicit-true-keys opts)
                                (::implicit-true-keys m-meta))
         auto-coerce? (::auto-coerce opts)
         keys-order (or (::keys-order opts)
                        (::keys-order m-meta))
         error-fn (->error-fn spec (:error-fn opts))]
     (if (or (seq coerce-map) auto-coerce?)
       (let [coerce-1 (fn [v cf implicit-true?]
                         (if cf (coerce* v cf implicit-true?) (auto-coerce v)))
             ordered-keys (if (seq keys-order)
                            (concat keys-order
                                    (remove (set keys-order) (keys m)))
                            (keys m))]
         (with-meta
           (reduce
            (fn [acc k]
              (let [v (get m k)]
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
                      (let [data (ex-data e)]
                        (error-fn (cond-> {:cause :coerce
                                           :msg #?(:clj (.getMessage e)
                                                   :cljs (ex-message e))
                                           :option k
                                           :value v
                                           :opts acc}
                                    (:implicit-true data) (assoc :implicit-true true))))
                      acc)))
                (if auto-coerce?
                  (assoc acc k (auto-coerce v))
                  (assoc acc k v)))))
            {} ordered-keys)
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
         ;; options parsed at a parent `dispatch` level (shared options) are
         ;; passed down via ::dispatch-inherited and must not be flagged as
         ;; unknown at child levels. Internal, not a public option.
         inherited (::dispatch-inherited opts)
         error-fn (->error-fn spec (:error-fn opts))]
     (when restrict
       (doseq [k (keys m)]
         (when (and (not (contains? restrict k))
                    (not (contains? inherited k))
                    (not= "babashka.cli" (namespace k)))
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

(defn apply-defaults
  "Fills missing keys in `m` from defaults. Existing keys in `m` win.
  Preserves metadata of `m`.

  Supported options:
  * `:exec-args` - map of defaults.
  * `:spec` - spec; `:default` entries become defaults via `spec->opts`."
  ([m] (apply-defaults m {}))
  ([m opts]
   (let [opts (resolve-opts opts)
         exec-args (:exec-args opts)
         ;; values inherited from parent `dispatch` levels (shared options);
         ;; override spec/`:exec-args` defaults but are overridden by `m`
         inherited (::dispatch-inherited opts)]
     (if (or exec-args inherited)
       (with-meta (merge exec-args inherited m) (meta m))
       m))))

;;
;; Parsing
;;

(defn parse-opts*
  "Parses CLI `args` into a raw opts map. Returns string values unchanged
  (no coercion), does not apply `:exec-args` defaults, does not run
  `:restrict`/`:require`/`:validate`. Result map includes
  `:org.babashka/cli` metadata and internal `::implicit-true-keys` /
  `::keys-order` metadata used by `coerce-opts`.

  Use this when you want to merge other sources (e.g. config files)
  before coerce/validate. Pipeline: `parse-opts*` -> merge -> `apply-defaults`
  -> `coerce-opts` -> `validate-opts`.

  Supported options (subset of `parse-opts`): `:alias`/`:aliases`, `:coerce`,
  `:collect`, `:no-keyword-opts`, `:repeated-opts`, `:args->opts`, `:spec`."
  [args {:keys [coerce collect no-keyword-opts repeated-opts] :as opts}]
  (let [aliases (or (:alias opts) (:aliases opts))
        spec (:spec opts)
        spec-map (if (map? spec) spec (when spec (into {} spec)))
        alias-keys (set (concat (keys aliases) (map :alias (vals spec-map))))
        known-keys (set (concat (keys spec-map) (vals aliases) (keys coerce)))
        bool? (fn [k] (#{:boolean :bool} (coerce-coerce-fn (get coerce k))))
        track-itk (fn [itk current-opt added]
                    (cond-> itk (not= current-opt added) (conj current-opt)))
        track-kpo (fn [kpo k]
                    (if (and k (not (some #{k} kpo)))
                      (conj kpo k)
                      kpo))
        {:keys [cmds args]} (parse-cmds args)
        {new-args :args a->o :args->opts}
        (if-let [a->o (or (:args->opts opts) (:cmds-opts opts))]
          (args->opts cmds a->o (::dispatch-tree-ignored-args opts))
          {:args->opts nil :args args})
        [cmds args] (if (not= new-args args)
                      [nil (concat new-args args)]
                      [cmds args])
        [parsed last-opt added itk kpo]
        (if (and (::dispatch-tree opts) (seq cmds))
          [(vary-meta {} assoc-in [:org.babashka/cli :args] (into (vec cmds) args)) nil nil #{} []]
          (loop [acc {}
                 current-opt nil
                 added nil
                 mode (when no-keyword-opts :hyphens)
                 args (seq args)
                 a->o a->o
                 itk #{}
                 kpo []]
            (if-not args
              [acc current-opt added itk kpo]
              (let [raw-arg (first args)
                    opt? (keyword? raw-arg)]
                (if opt?
                  (recur (process-previous acc current-opt added nil)
                         raw-arg added mode (next args) a->o
                         (track-itk itk current-opt added)
                         (track-kpo kpo raw-arg))
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
                             current-opt added itk kpo])
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
                                     (track-itk itk current-opt added)
                                     (track-kpo kpo k))
                              (let [next-args (next args)
                                    next-arg (first next-args)
                                    m (parse-key next-arg mode current-opt boolean-opt? added known-keys alias-keys)
                                    negative? (when-not (contains? known-keys k)
                                                (str/starts-with? (str k) ":no-"))]
                                (if (or (:hyphen-opt m) (empty? next-args) negative?)
                                  ;; implicit true
                                  (if (and (not alias) composite-opt)
                                    (let [expanded (mapcat (fn [c] [(str "-" c) true]) (name k))]
                                      (recur acc nil nil mode (concat expanded next-args) a->o itk kpo))
                                    (let [k (if negative?
                                              (keyword (str/replace (str k) ":no-" ""))
                                              k)]
                                      (recur (process-previous acc current-opt added cf)
                                             k added mode (cons (not negative?) next-args) a->o
                                             (track-itk itk current-opt added)
                                             (track-kpo kpo k))))
                                  (recur (process-previous acc current-opt added cf)
                                         k nil mode next-args a->o
                                         (track-itk itk current-opt added)
                                         (track-kpo kpo k))))))))
                      (let [the-end? (or
                                      (and boolean-opt?
                                           (not= "true" arg)
                                           (not= "false" arg))
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
                              (recur acc current-opt added mode new-args a->o itk kpo)
                              [(vary-meta acc assoc-in [:org.babashka/cli :args] (vec args)) current-opt added itk kpo]))
                          (let [opt (when-not (and (= :keywords mode) fst-colon) current-opt)]
                            (recur (add-val acc current-opt cf arg)
                                   opt opt mode (next args) a->o
                                   (cond-> itk implicit-true? (conj current-opt))
                                   kpo)))))))))))
        ;; Finalize: process last opt, prepend cmds to args metadata
        itk (track-itk itk last-opt added)
        cf (collect-fn collect coerce last-opt)
        parsed (-> (process-previous parsed last-opt added cf)
                   (cond->
                    (and (seq cmds) (not (::dispatch-tree opts)))
                     (vary-meta update-in [:org.babashka/cli :args]
                                (fn [args] (into (vec cmds) args)))))]
    (vary-meta parsed assoc ::implicit-true-keys itk ::keys-order kpo)))

(defn parse-opts
  "Returns a map of options parsed from command line arguments `args`, a seq of strings.
  Instead of a leading `:` either `--` or `-` may be used as well.

  Metadata on returned map, under `:org.babashka/cli`:
  * `:args` remaining unparsed `args` (not corresponding to any options)

  Supported `opts`:
  * `:coerce` - a map of option (keyword) names to type keywords (optionally wrapped in a collection.)
  * `:alias` - a map of short names to long names.
  * `:spec` - a spec of options. See [spec](https://github.com/babashka/cli#spec).
  * `:restrict` - `true` or coll of keys. Throw on first parsed option not in set of keys or keys of `:spec` and `:coerce` combined.
  * `:require` - a coll of options that are required. See [require](https://github.com/babashka/cli#restrict).
  * `:validate` - a map of validator functions. See [validate](https://github.com/babashka/cli#validate).
  * `:exec-args` - a map of default args. Will be overridden by args specified in `args`. Values from `:exec-args` are NOT coerced or auto-coerced; provide them in their final form.
  * `:no-keyword-opts` - `true`. Support only `--foo`-style opts (i.e. `:foo` will not work).
  * `:repeated-opts` - `true`. Forces writing the option name for every value, e.g. `--foo a --foo b`, rather than `--foo a b`
  * `:args->opts` - consume unparsed commands and args as options
  * `:collect` - a map of collection fns. See [custom collection handling](https://github.com/babashka/cli#custom-collection-handling).

  Examples:

  ```clojure
  (parse-opts [\"foo\" \":bar\" \"1\"])
  ;; => ^{:org.babashka/cli {:args [\"foo\"]}} {:bar 1}
  (parse-opts [\":b\" \"1\"] {:aliases {:b :bar} :coerce {:bar parse-long}})
  ;; => {:bar 1}
  (parse-opts [\"--baz\" \"--qux\"] {:spec {:baz {:desc \"Baz\"}} :restrict true})
  ;; => throws 'Unknown option --qux' exception b/c there is no :qux key in the spec
  ```
  See also: [[parse-args]]"
  ([args] (parse-opts args {}))
  ([args opts]
   (let [opts (resolve-opts opts)
         ;; Step 1: Parse (raw strings, no coercion)
         parsed (parse-opts* args opts)
         ;; Step 2: Coerce
         coerced (coerce-opts parsed {:coerce (:coerce opts)
                                      :spec (:spec opts)
                                      :error-fn (:error-fn opts)
                                      ::auto-coerce true
                                      ::resolved true})
         ;; Step 3: Apply defaults
         coerced (apply-defaults coerced opts)
         ;; Step 4: Validate
         validated (validate-opts coerced opts)]
     (vary-meta validated dissoc ::implicit-true-keys ::keys-order))))

(defn parse-args
  "Same as [[parse-opts]] with return data reshaped.

  Returns a map with:
  * `:opts` parsed opts
  * `:args` remaining unparsed `args`"
  ([args] (parse-args args {}))
  ([args opts]
   (let [opts (parse-opts args opts)
         cli-opts (-> opts meta :org.babashka/cli)]
     (assoc cli-opts :opts (dissoc opts :org.babashka/cli)))))

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

(defn opts->table [{:keys [spec order columns]}]
  (let [columns (set (or columns (mapcat (fn [[_ s]] (keys s)) spec)))]
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

(defn- help-first-line [s]
  (when s (first (str/split-lines s))))

(defn- help-description
  "Full doc string, each line left-trimmed, leading/trailing blank lines dropped."
  [s]
  (when s
    (let [ls (->> (str/split-lines s)
                  (map str/triml)
                  (drop-while str/blank?)
                  reverse (drop-while str/blank?) reverse)]
      (when (seq ls) (str/join "\n" ls)))))

(defn- help-usage-line [prog node any-options?]
  (str "Usage: " prog
       (when any-options? " [options]")
       (cond (seq (:cmd node)) " <command>"
             (:fn node)        " [<args>]"
             :else             "")))

(defn- help-commands-table [node]
  (vec
   (keep (fn [[cmd subnode]]
           (when-not (:no-doc subnode)
             [(str cmd) (or (help-first-line (:doc subnode)) "")]))
         (:cmd node))))

(defn- opt->flag
  "Render an option keyword as the flag a user types: `-x` for a single-char
  option, `--long` otherwise."
  [opt]
  (let [n (name opt)]
    (str (if (= 1 (count n)) "-" "--") n)))

(defn- render-help
  "Render help text for one tree `node`, given a computed `:prog` (full command
  path), `:inherited` spec and `:parents` pointers. See [[format-command-help]]."
  [node {:keys [prog inherited parents]}]
  (let [spec (:spec node)
        inherited (apply dissoc inherited (keys spec))
        desc (help-description (:doc node))
        cmds (help-commands-table node)
        sections
        (cond-> [(help-usage-line prog node (or (seq spec) (seq inherited)))]
          desc
          (conj desc)

          (seq cmds)
          (conj (str "Commands:\n" (format-table {:rows cmds :indent 2})))

          (seq spec)
          (conj (str "Options:\n" (format-opts {:spec spec})))

          (seq inherited)
          (conj (str "Inherited options:\n" (format-opts {:spec inherited})))

          (seq cmds)
          (conj (str "Run \"" prog " <command> --help\" for more information on a command."))

          (seq parents)
          (conj (str/join "\n"
                          (for [{p :prog n :name} parents]
                            (str "Run \"" p " --help\" for " n " options.")))))]
    (str/join "\n\n" sections)))

(defn- split [a b]
  (let [[prefix suffix] (split-at (count a) b)]
    (when (= prefix a)
      suffix)))

(defn table->tree
  "Converts a `dispatch` table into a tree. Each `:cmds` becomes a path of
  nested `:cmd` maps; other entry keys are kept on the node. Empty `:cmds`
  merges onto the root.

  ```clojure
  (table->tree [{:cmds [\"add\"] :fn add} {:cmds [] :fn help}])
  ;; => {:fn help, :cmd {\"add\" {:fn add}}}
  ```"
  [table]
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

(defn- ->spec-map [spec]
  (cond (nil? spec) {}
        (map? spec) spec
        :else (into {} spec)))

(defn- inherited-entries
  "Spec entries of `spec` that propagate to descendant levels: those marked
  `:inherit`, plus those selected by a dispatch-level `:inherit` opt
  (`true` = all of `spec`, or a coll of keys)."
  [spec inherit-opt]
  (let [m (->spec-map spec)]
    (if (true? inherit-opt)
      m
      (merge (into {} (filter (fn [[_ v]] (and (map? v) (:inherit v))) m))
             (when (coll? inherit-opt) (select-keys m (set inherit-opt)))))))

(defn- command-help-context
  "Given a dispatch `tree`, command path `cmds`, `prog` name and dispatch-level
  `inherit` value, compute everything [[render-help]] needs: the target `:node`,
  its full `:prog` path, the `:inherited` options usable here (aggregated from
  ancestors) and the `:parents` pointers (ancestors with non-inherited options
  that must precede the subcommand)."
  [tree cmds prog inherit]
  (let [node-at (fn [path] (get-in tree (interleave (repeat :cmd) path)))
        prog-at (fn [path] (str/join " " (cons prog path)))
        ;; for each strict ancestor prefix: what it contributes downward
        ;; (:inh) and its own non-inherited options (:own)
        ancestors (for [i (range (count cmds))
                        :let [pre  (subvec cmds 0 i)
                              spec (:spec (node-at pre))
                              inh  (inherited-entries spec inherit)]]
                    {:pre pre :inh inh :own (apply dissoc spec (keys inh))})
        inherited (reduce merge {} (map :inh ancestors))
        ;; ancestors with non-inherited options (must precede the subcommand)
        parents (for [{:keys [pre own]} ancestors :when (seq own)]
                  {:prog (prog-at pre)
                   :name (if (seq pre) (peek pre) "global")})]
    {:node (node-at cmds)
     :prog (prog-at cmds)
     :inherited inherited
     :parents (vec parents)}))

(defn format-command-help
  "Render conventional `--help` text (a string) for the command at path `cmds`
  in a `dispatch` table (or the tree from [[table->tree]]):

  ```
  Usage: <prog> [options] <command>

  <description>            ; the entry's :doc (first line, then the rest)

  Commands:                ; child commands with their one-line :doc
    ...
  Options:                 ; the command's own :spec, via format-opts
    ...
  Inherited options:       ; ancestor options usable here (:inherit), deduped
    ...
  Run \"<prog> sub --help\" ...        ; pointer to child commands
  Run \"<prog> --help\" for <x> options ; pointer to non-inherited ancestor opts
  ```

  Takes a single map:
  * `:table`   - a `dispatch` table, or a tree from [[table->tree]] (required)
  * `:cmds`    - the command path, e.g. `[\"deps\" \"outdated\"]` (default `[]`,
                 the top level)
  * `:prog`    - program name shown in the usage line (required)
  * `:inherit` - only needed when you pass a dispatch-level `:inherit` (`true` /
                 coll of keys) to `dispatch`; pass the same value here so the
                 `Inherited options:` section matches what is actually accepted.
                 Per-option `:inherit true` is detected automatically.

  An entry may carry `:no-doc true` to be omitted from `Commands:`."
  [{:keys [table cmds prog inherit] :or {cmds []}}]
  (let [tree (if (map? table) table (table->tree table))
        ctx (command-help-context tree (vec cmds) prog inherit)]
    (render-help (:node ctx) ctx)))

(defn ^:dynamic *exit-fn*
  "Called to terminate the process once help or an error has been printed by
  [[help-error-fn]]. Receives a map with `:exit` (the exit code; 0 when help was
  shown, 1 on error) and, on errors, `:message` / `:dispatch` / `:data` (the
  original error data, which carries `:cause` etc.).

  Rebind this to prevent the process from exiting (tests, REPL):

  ```clojure
  (binding [babashka.cli/*exit-fn* (fn [m] (throw (ex-info \"exit\" m)))]
    ...)
  ```

  The default dispatches on host: `System/exit` on the JVM, `js/process.exit`
  on Node, and a `throw` in a browser (where there is no process to exit)."
  [{:keys [exit]}]
  #?(:clj (System/exit exit)
     :cljs (if (and (exists? js/process) (fn? (.-exit js/process)))
             (js/process.exit exit)
             (throw (ex-info "exit" {:exit exit})))))

(defn help-error-fn
  "Build an `:error-fn` for [[dispatch]] (used with `:restrict true`) that
  renders conventional help and terminates via [[*exit-fn*]].

  `table` is the same dispatch table (or tree) passed to `dispatch`. `opts`:

  * `:prog`    - program name shown in usage / help (required)
  * `:inherit` - the same dispatch-level `:inherit` value you pass to
                 `dispatch`, if any, so `Inherited options:` matches what is
                 accepted

  Use with `:restrict true`, so that `--help` / `-h` arrive as a `:restrict`
  error this function intercepts:

  ```clojure
  (cli/dispatch table args
    {:restrict true :error-fn (cli/help-error-fn table {:prog \"mytool\"})})
  ```

  Behavior, by error `:cause`:

  * `--help` / `-h` anywhere   -> full help for that level, exit 0
  * unknown subcommand         -> message + available commands, exit 1
  * group with no subcommand   -> full help for that group, exit 0
  * flag error (require / ...) -> message + usage line, exit 1

  Terse on misuse (no full options dump); options are rendered as the flag the
  user types (`--foo`, `-x`), not the keyword `:foo`."
  [table {:keys [prog inherit]}]
  (let [tree   (if (map? table) table (table->tree table))
        ctx-at (fn [path] (command-help-context tree (vec path) prog inherit))
        print-help (fn [path]
                     (let [ctx (ctx-at path)]
                       (println (render-help (:node ctx) ctx))))
        hint  (fn [path]
                (str "Run \"" (str/join " " (cons prog path))
                     " --help\" for more information."))
        usage (fn [path]
                (let [{:keys [node prog inherited]} (ctx-at path)]
                  (help-usage-line prog node (or (seq (:spec node))
                                                 (seq inherited)))))]
    (fn [{:keys [cause option dispatch wrong-input msg] :as data}]
      (let [path (or dispatch [])]
        (cond
          ;; --help / -h: under :restrict these arrive as an unknown option
          (and (= :restrict cause) (#{:help :h} option))
          (do (print-help path)
              (*exit-fn* {:exit 0 :dispatch path}))

          ;; mistyped subcommand: terse, but list the available commands
          (= :no-match cause)
          (let [cmds    (help-commands-table (:node (ctx-at path)))
                message (str "Unknown command: " wrong-input)]
            (println (str message "\n"))
            (when (seq cmds)
              (println (str "Commands:\n"
                            (format-table {:rows cmds :indent 2}) "\n")))
            (println (hint path))
            (*exit-fn* {:exit 1 :message message :dispatch path :data data}))

          ;; a group invoked with no subcommand -> full help (shows Commands)
          (= :input-exhausted cause)
          (do (print-help path)
              (*exit-fn* {:exit 0 :dispatch path}))

          ;; genuine flag error (require / validate / unknown flag): terse,
          ;; rendering the option as the flag the user types
          :else
          (let [msg (if option (str/replace msg (str option) (opt->flag option)) msg)]
            (println (str "Error: " msg "\n"))
            (println (usage path))
            (println)
            (println (hint path))
            (*exit-fn* {:exit 1 :message msg :dispatch path :data data})))))))

(defn- dispatch-tree'
  ([tree args]
   (dispatch-tree' tree args nil))
  ([tree args opts]
   (loop [cmds [] all-opts {} args args cmd-info tree inherited {}]
     (let [kwm cmd-info
           ;; capture before the parse-args destructure below shadows `opts`
           inherit-opt (:inherit opts)
           should-parse-args? (or (has-parse-opts? kwm)
                                  (seq inherited)
                                  (is-option? (first args)))
           parse-opts (deep-merge opts kwm)
           ;; options marked `:inherit` at an ancestor level are accepted here
           ;; too (e.g. `prog group --opt val sub` and `prog group sub --opt val`)
           parse-opts (cond-> parse-opts
                        (seq inherited) (update :spec #(merge inherited (->spec-map %))))
           ;; thread the current dispatch path into flag-level errors
           ;; (restrict/require/validate/coerce) so an :error-fn can render
           ;; help for the right subcommand
           user-error-fn (:error-fn parse-opts)
           parse-opts (assoc parse-opts :error-fn
                             (fn [data]
                               (let [data (assoc data :dispatch cmds)]
                                 (if user-error-fn
                                   (user-error-fn data)
                                   (throw (ex-info (:msg data) data))))))
           {:keys [args opts]} (if should-parse-args?
                                 (parse-args args (assoc parse-opts
                                                         ::dispatch-tree true
                                                         ;; shared options parsed at parent levels: seeded as
                                                         ;; values and exempt from this level's :restrict
                                                         ::dispatch-inherited all-opts
                                                         ::dispatch-tree-ignored-args (set (keys (:cmd cmd-info)))))
                                 {:args args
                                  :opts {}})
           [arg & rest] args
           all-opts (-> (merge all-opts opts)
                        (update ::opts-by-cmds (fnil conj []) {:cmds cmds
                                                               :opts opts}))]
       (if-let [subcmd-info (get (:cmd cmd-info) arg)]
         (recur (conj cmds arg) all-opts rest subcmd-info
                (merge inherited (inherited-entries (:spec kwm) inherit-opt)))
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
  [[parse-args]] applied to `args` enhanced with:

  * `:dispatch` - the matching commands
  * `:args` - concatenation of unparsed commands and args
  * `:rest-cmds`: DEPRECATED, this will be removed in a future version

  Use an empty `:cmds` vector to always match or to provide global options.

  Provide an `:error-fn` to deal with non-matches.

  Each entry in the table may have additional [[parse-args]] options.

  For more information and examples, see [README.md](README.md#subcommands)."
  ([table args]
   (dispatch table args {}))
  ([table args opts]
   (let [tree (-> table table->tree)]
     (dispatch-tree tree args opts))))
