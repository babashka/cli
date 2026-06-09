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

(defn- coerce-failure-reason
  "The reason part of a coerce failure, e.g. `cannot transform input \"x\" to long`."
  [s implicit-true? f]
  (str "cannot transform "
       (if implicit-true?
         "(implicit) true"
         (str "input " (pr-str s)))
       (if (keyword? f)
         " to "
         " with ")
       (if (keyword? f)
         (name f)
         f)))

(defn- throw-coerce [s implicit-true? f e]
  (throw (ex-info (str "Coerce failure: " (coerce-failure-reason s implicit-true? f))
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

(defn- kw->str [kw]
  (subs (str kw) 1))

(defn- option-label
  "User-facing name for option `k` in an error message: the literal flag the
  user typed (from `key->flag`, e.g. `\"-f\"` or `\":foo\"`), else the canonical
  `--name` (a required or standalone-checked option was never typed). Uses
  `kw->str` so a namespaced key like `:foo/bar` renders as `--foo/bar`."
  [key->flag k]
  (or (get key->flag k) (str "--" (kw->str k))))

(defn coerce-opts
  "Coerces values in the map `m` using the provided configuration.
  Does not coerce values that are not strings.
  Returns a new map with coerced values.

  Supported options:
  * `:coerce` - a map of option (keyword) names to type keywords (optionally wrapped in a collection).
  * `:spec` - a spec of options. See [spec](https://github.com/babashka/cli#spec).
  * `:error-fn` - error handler, called with a map containing `:cause` (`:coerce`), `:msg`, `:option`, `:value`, `:opts`, and `:flag` (when the option was typed).

  `:flag` is the literal option token as it appeared on the command line (e.g.
  `\"--foo\"`, `\"-f\"`, or `\":foo\"`), as opposed to `:option`, the normalized
  keyword (`:foo`). It lets a handler echo what the user actually typed rather
  than reconstruct it. It is omitted when no originating token is known."
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
                      (let [data (ex-data e)
                            km (::key->flag m-meta)
                            flag (get km k)]
                        (error-fn (cond-> {:cause :coerce
                                           ;; same shape as validate: name the option, then the reason.
                                           ;; implicit-true = option given without a value: say so plainly
                                           ;; instead of "cannot transform (implicit) true to ..."
                                           :msg (if (:implicit-true data)
                                                  (str "Missing value for option " (option-label km k))
                                                  (str "Invalid value for option " (option-label km k) ": "
                                                       (coerce-failure-reason (:input data) (:implicit-true data) (:coerce-fn data))))
                                           :option k
                                           :value v
                                           :opts acc}
                                    flag (assoc :flag flag)
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
  * `:error-fn` - error handler, called with a map containing `:cause`, `:msg`, `:option`, `:opts`, and `:flag`.

  `:flag` is the literal option token as it appeared on the command line (e.g.
  `\"--foo\"`, `\"-f\"`, or `\":foo\"`), as opposed to `:option`, the normalized
  keyword (`:foo`). It lets a handler echo what the user actually typed rather
  than reconstruct it. It is present for `:restrict` and `:validate`, and absent
  for `:require` (a missing required option was never typed, so it has no token)."
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
         ;; values supplied programmatically via `:exec-args` (author-provided
         ;; defaults, e.g. from a config file) are not user-typed tokens, so
         ;; `:restrict` must not flag them as unknown options.
         exec-args (:exec-args opts)
         ;; literal flag tokens the user typed, by key (see parse-opts*)
         key->flag (::key->flag (meta m))
         flag-for (fn [k] (option-label key->flag k))
         error-fn (->error-fn spec (:error-fn opts))]
     (when restrict
       (doseq [k (keys m)]
         (when (and (not (contains? restrict k))
                    (not (contains? inherited k))
                    (not (contains? exec-args k))
                    (not= "babashka.cli" (namespace k)))
           (let [flag (get key->flag k)]
             (error-fn (cond-> {:cause :restrict
                                :msg (str "Unknown option: " (flag-for k))
                                :restrict restrict
                                :option k
                                :opts m}
                         flag (assoc :flag flag)))))))
     (when require
       (doseq [k require]
         (when-not (find m k)
           (let [flag (get key->flag k)]
             (error-fn (cond-> {:cause :require
                                :msg (str "Required option: " (flag-for k))
                                :require require
                                :option k
                                :opts m}
                         flag (assoc :flag flag)))))))
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
                                   (fn [{:keys [flag value]}]
                                     (str "Invalid value for option " flag ": " value)))
                     flag (get key->flag k)]
                 (error-fn (cond-> {:cause :validate
                                    :msg (ex-msg-fn {:option k :value v :flag (flag-for k)})
                                    :validate validate
                                    :option k
                                    :value v
                                    :opts m}
                             flag (assoc :flag flag)))))))))
     m)))

(defn apply-defaults
  "Fills missing keys in `m` from defaults. Existing keys in `m` win.
  Preserves metadata of `m`.

  Supported options:
  * `:exec-args` - map of defaults. Not subject to `:restrict`.
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
        ;; remember each key's `:flag`: the literal option token as typed (e.g.
        ;; "--foo", "-f", ":foo"), in `::key->flag` metadata, so error messages
        ;; can echo what the user typed instead of reconstructing it from the
        ;; normalized keyword (`-x` and `--x` both parse to `:x`)
        stamp (fn [m k lit] (if lit (vary-meta m assoc-in [::key->flag k] lit) m))
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
                                k (or alias raw-k)
                                ;; the literal flag the user typed (sans any =value)
                                literal (if long-opt? (str "--" kname) arg)]
                            (if arg-val
                              (recur (stamp (process-previous acc current-opt added cf) k literal)
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
                                      (recur (stamp (process-previous acc current-opt added cf) k literal)
                                             k added mode (cons (not negative?) next-args) a->o
                                             (track-itk itk current-opt added)
                                             (track-kpo kpo k))))
                                  (recur (stamp (process-previous acc current-opt added cf) k literal)
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
  * `:exec-args` - a map of default args. Will be overridden by args specified in `args`. Values from `:exec-args` are NOT coerced or auto-coerced; provide them in their final form. Not subject to `:restrict`.
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
     (vary-meta validated dissoc ::implicit-true-keys ::keys-order ::key->flag))))

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

(defn- str-width
  "Width of `s` when printed, i.e. without ANSI escape codes."
  [s]
  (let [strip-escape-codes #(str/replace %
                                         (re-pattern "(\\x9B|\\x1B\\[)[0-?]*[ -\\/]*[@-~]") "")]
    (count (strip-escape-codes s))))

(defn ^:no-doc pad [len s] (str s (apply str (repeat (- len (str-width s)) " "))))

(defn ^:no-doc pad-cells [rows]
  (let [widths  (reduce
                 (fn [widths row]
                   (map max (map str-width row) widths)) (repeat 0) rows)
        pad-row (fn [row]
                  (map pad widths row))]
    (map pad-row rows)))

#?(:clj
   (def ^:private jline-provider
     ;; Cached: provider discovery/load is the only cost (a few ms, once); the
     ;; width query itself is a cheap ioctl. Reflective - no compile-time dep on
     ;; org.jline.*, so this is nil when JLine is not on the classpath.
     (delay
       (try
         (let [tb (Class/forName "org.jline.terminal.TerminalBuilder")
               b  (.invoke (.getMethod tb "builder" (into-array Class [])) nil (object-array []))
               gp (.getMethod tb "getProviders"
                              (into-array Class [String java.lang.IllegalStateException]))]
           (first (.invoke gp b (object-array [nil (IllegalStateException.)]))))
         (catch Throwable _ nil)))))

#?(:clj
   (defn- jline-width
     "Terminal width via the JLine provider's `systemStreamWidth` (an `ioctl`, no
     `Terminal` built - so no tty grab, no warnings). nil when JLine is absent or
     stdout is not a terminal."
     []
     (try
       (when-let [p @jline-provider]
         (let [tp  (Class/forName "org.jline.terminal.spi.TerminalProvider")
               ssc (Class/forName "org.jline.terminal.spi.SystemStream")
               out (.get (.getField ssc "Output") nil)]
           (when (.invoke (.getMethod tp "isSystemStream" (into-array Class [ssc])) p (object-array [out]))
             (let [w (.invoke (.getMethod tp "systemStreamWidth" (into-array Class [ssc])) p (object-array [out]))]
               (when (and (integer? w) (pos? w)) w)))))
       (catch Throwable _ nil))))

(defn default-width-fn
  "The default `:max-width-fn` for [[format-table]]/[[format-opts]]. Receives the
  table cfg map (currently unused, reserved for extension) and returns the terminal
  width or nil: node `process.stdout.columns`, else `$COLUMNS`, else a JLine
  provider probe (clj, when JLine is on the classpath, e.g. babashka), else nil
  (the caller then falls back to 80)."
  [_cfg]
  #?(:cljs (when (and (exists? js/process) js/process.stdout
                      (pos-int? (.-columns js/process.stdout)))
             (.-columns js/process.stdout))
     :clj (or (when-let [c (System/getenv "COLUMNS")]
                (try (Long/parseLong c) (catch Exception _ nil)))
              (jline-width))))

(defn- word-wrap
  "Wrap `s` to `width` columns at spaces, keeping existing newlines as hard breaks.
  A single word longer than `width` overflows on its own line (matches argparse)."
  [width s]
  (->> (str/split-lines (str s))
       (mapcat (fn [line]
                 (if (<= (str-width line) width)
                   [line]
                   (reduce (fn [lines word]
                             (let [cur (peek lines)]
                               (if (or (nil? cur) (= "" cur))
                                 (conj (pop lines) word)
                                 (if (<= (+ (str-width cur) 1 (str-width word)) width)
                                   (conj (pop lines) (str cur " " word))
                                   (conj lines word)))))
                           [""]
                           (str/split line #" ")))))
       (str/join "\n")))

(defn- wrap-last-column
  "Word-wrap each row's last cell so the table fits `max-width`, inserting newlines
  (which [[expand-multiline-cells]] then aligns under the column). Earlier (short)
  columns are left as-is."
  [rows indent divider max-width]
  (let [col-cnt (count (first rows))]
    (if (zero? col-cnt)
      rows
      (let [last-idx (dec col-cnt)
            fixed-widths (reduce (fn [ws row]
                                   (mapv max ws (map str-width (take last-idx row))))
                                 (vec (repeat last-idx 0))
                                 rows)
            start (+ indent (reduce + 0 fixed-widths) (* last-idx (count divider)))
            avail (max 10 (- max-width start))]
        (mapv (fn [row]
                (let [v (vec row)]
                  (assoc v last-idx (word-wrap avail (str (nth v last-idx))))))
              rows)))))

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

(defn format-table [{:keys [rows indent divider wrap max-width-fn]
                     :or {indent 2 divider " " wrap true max-width-fn default-width-fn}
                     :as cfg}]
  (let [rows (cond-> rows
               (and wrap (seq rows))
               ;; max-width-fn is called only here (lazy): no detection when not wrapping
               (wrap-last-column indent divider (or (max-width-fn cfg) 80)))
        rows (-> rows
                 expand-multiline-cells
                 pad-cells)
        fmt-row (fn [leader divider trailer row]
                  (str leader
                       (apply str (interpose divider row))
                       trailer))]
    (->> rows
         (map (fn [row]
                #_(fmt-row "| " " | " " |" row)
                (fmt-row (apply str (repeat indent " ")) divider "" row)))
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
    (mapv (fn [[long-opt {:keys [alias default default-desc ref desc negatable]}]]
            (keep identity
                  [(when (:alias columns)
                     (if alias (str "-" (kw->str alias) ",") ""))
                   ;; `:negatable true` opts in to showing `--[no-]<name>`, for
                   ;; boolean options where the always-available `--no-<name>`
                   ;; form (sets it false) is meaningful
                   (str "--" (when negatable "[no-]") (kw->str long-opt))
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

(defn- opts->help-rows
  "Rows for [[format-opts]]: the conventional two-column layout `option | desc`.
  The alias, `--option` and `:ref` are one invocation column (`-f, --foo <ref>`,
  short left-padded so the `--` long forms line up); the default is folded into
  the description as `(default: ...)`. Matches argparse/clap/click/picocli.
  Honors `:order`. The two columns are joined with a 2-space divider by
  `format-opts`."
  [{:keys [spec order required]}]
  (let [entries (if (map? spec)
                  (map (fn [k] [k (spec k)]) (or order (keys spec)))
                  spec)
        ;; `:no-doc` options still parse but are hidden from help, like `:no-doc`
        ;; subcommands are hidden from the command list
        entries (remove (fn [[_ v]] (:no-doc v)) entries)
        ;; effective required set, matching validation: per-option `:require true`
        ;; or membership in a top-level `:require` coll (both fold into one set)
        required (set required)
        short (fn [[_ {:keys [alias]}]] (if alias (str "-" (kw->str alias) ", ") ""))
        sw (transduce (map (comp count short)) max 0 entries)]
    (mapv (fn [[long-opt {:keys [default default-desc ref desc negatable] req :require}] sh]
            (let [dflt (or default-desc (when (some? default) (str default)))
                  ;; folded into the description, in the same slot: a required
                  ;; option has no default, so `(required)` / `(default: ...)`
                  ;; are mutually exclusive
                  note (cond (or req (contains? required long-opt)) "(required)"
                             dflt (str "(default: " dflt ")"))
                  inv (str sh (apply str (repeat (- sw (count sh)) \space))
                           "--" (when negatable "[no-]") (kw->str long-opt)
                           (when ref (str " " ref)))
                  desc (str desc (when note
                                   (str (when (seq desc) " ") note)))]
              [inv desc]))
          entries (map short entries))))

(defn format-opts [{:as cfg
                    :keys [indent wrap max-width-fn]
                    :or {indent 2 wrap true max-width-fn default-width-fn}}]
  (format-table {:rows (opts->help-rows cfg)
                 :indent indent
                 :divider "  "
                 :wrap wrap
                 :max-width-fn max-width-fn}))

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

(defn- args->opts-labels
  "Render a command's `:args->opts` as usage labels: each key as `<key>`, and a
  key repeated at the tail (the `(cons :foo (repeat :bar))` variadic form) as
  `<key>...`. Returns a vector of label strings, or nil when there are none.
  Bounded so an unrealizable infinite seq can't hang."
  [args->opts]
  (when (seq args->opts)
    (loop [s (seq args->opts), acc [], prev nil, n 0]
      (let [k (first s)]
        (cond
          (or (nil? s) (>= n 64)) acc
          (= k prev) (conj (pop acc) (str "<" (name prev) ">..."))
          :else (recur (next s) (conj acc (str "<" (name k) ">")) k (inc n)))))))

(defn- help-usage-line [prog node any-options?]
  (str "Usage: " prog
       (when any-options? " [options]")
       (cond (seq (:cmd node)) " <command>"
             ;; a runnable command: show labeled positionals from :args->opts, if
             ;; any. We don't show a generic `[<args>]` placeholder otherwise
             ;; (matches argparse/clap/click/picocli/cli-tools).
             (:fn node)        (when-let [labels (args->opts-labels (:args->opts node))]
                                 (str " " (str/join " " labels)))
             :else             "")))

(defn- help-commands-table [node]
  (vec
   (keep (fn [[cmd subnode]]
           (when-not (:no-doc subnode)
             [(str cmd) (or (help-first-line (:doc subnode)) "")]))
         (:cmd node))))

(defn- ->spec-map [spec]
  (cond (nil? spec) {}
        (map? spec) spec
        :else (into {} spec)))

(defn- render-help
  "Render help text for one tree `node` (built by [[command-help-context]]):
  given a computed `:prog` (full command path), `:inherited` spec and `:parents`
  pointers. Renders Options in the node's `:order` (see [[node-with-help]])."
  [node {:keys [prog inherited parents]}]
  (let [spec (:spec node)                       ; map or vec-of-pairs
        order (:order node)                     ; display order (see node-with-help)
        ;; drop inherited options this node redefines (child wins); mapify for the
        ;; key set, since a standalone format-command-help spec may be a vec
        inherited (apply dissoc inherited (keys (->spec-map spec)))
        desc (help-description (:doc node))
        cmds (help-commands-table node)
        sections
        (cond-> [(help-usage-line prog node (or (seq spec) (seq inherited)))]
          desc
          (conj desc)

          (seq cmds)
          (conj (str "Commands:\n" (format-table {:rows cmds :indent 2})))

          (seq spec)
          (conj (str "Options:\n" (format-opts (cond-> {:spec spec :required (:require node)}
                                                 order (assoc :order order)))))

          (seq inherited)
          (conj (str "Inherited options:\n" (format-opts {:spec inherited})))

          (seq cmds)
          (conj (str "Run \"" prog " <command> --help\" for more information on a command."))

          (seq parents)
          (conj (str/join "\n"
                          (for [{p :prog n :name} parents]
                            (str "Run \"" p " --help\" for " n " options."))))

          ;; free-text the entry supplies (examples, notes), rendered verbatim
          ;; last, as a closing footer (argparse epilog / picocli footer convention)
          (:epilog node)
          (conj (str/trim (:epilog node))))]
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

;;;; Shell completion

(defn- format-long-opt [k] (str "--" (kw->str k)))
(defn- format-short-opt [k] (str "-" (kw->str k)))

(defn- true-prefix?
  "True when `s` starts with `prefix` and is strictly longer (a real completion,
  not the already-typed token itself)."
  [prefix s]
  (and (< (count prefix) (count s))
       (str/starts-with? s prefix)))

(defn- strip-prefix [prefix s]
  (if (str/starts-with? s prefix) (subs s (count prefix)) s))

(defn- gnu-option? [s]
  (and s (str/starts-with? s "-")))

(defn- option-key
  "Resolve an option token (long `--foo` or short `-f`) to its keyword, given a
  resolved `opts` (with `:alias`)."
  [token opts]
  (if (str/starts-with? token "--")
    (keyword (strip-prefix "--" token))
    (get-in opts [:alias (keyword (strip-prefix "-" token))])))

(defn- bool-opt? [o opts]
  ;; `:coerce` accepts both `:boolean` and `:bool`, matching the parser's own check
  (#{:boolean :bool} (get-in opts [:coerce (option-key o opts)])))

(defn- normalize-value-candidate [c]
  (cond
    (map? c) (update c :value (fn [v] (if (keyword? v) (kw->str v) (str v))))
    (keyword? c) {:value (kw->str c)}
    :else {:value (str c)}))

(defn- candidates-for-entry
  "Value candidates from a single spec `entry` for option/positional key `k`, from:
  * `:complete-fn` - a fn of a context map `{:to-complete :opts :option}` for
    dynamic/dependent completion (gets `:to-complete` so it may filter at the
    source), or
  * `:complete` - a coll of values (or `{:value .. :description ..}` maps), or,
  * failing both, a set-valued `:validate` (its members double as completions).

  All sources are normalized to `{:value :description}` maps and prefix-filtered
  against `to-complete` (uniform - the shell does not filter for us on powershell).
  `parsed` are the opts parsed from the completed prefix (for dependent completion)."
  [entry k to-complete parsed]
  (let [candidates (cond
                     (:complete-fn entry) ((:complete-fn entry)
                                           {:to-complete to-complete :opts parsed :option k})
                     (:complete entry) (:complete entry)
                     (set? (:validate entry)) (:validate entry))]
    (->> candidates
         (map normalize-value-candidate)
         (filter #(str/starts-with? (:value %) to-complete)))))

(defn- value-candidates
  "Candidates for the value of option token `prev` (the token before the cursor).
  Resolves `prev` to its spec key and delegates to [[candidates-for-entry]]."
  [spec opts prev to-complete parsed]
  (let [k (option-key prev opts)]
    (candidates-for-entry (get (->spec-map spec) k) k to-complete parsed)))

(defn- resolve-completion-opts
  "Normalize an opts/spec map to a resolved opts map (with `:coerce`/`:alias`)
  plus the set of known option keys. Returns `[opts aliases known-keys]`."
  [opts]
  (let [spec (:spec opts)
        opts (if spec (merge-opts opts (spec->opts spec opts)) opts)
        aliases (or (:alias opts) (:aliases opts))
        known (set (concat (when (map? spec) (keys spec))
                           (keys (:coerce opts))
                           (vals aliases)))]
    [opts aliases known]))

(defn- repeatable-opt?
  "True when option `k` may appear more than once: a collection-valued `:coerce`
  (`[:string]`, `#{:string}`) or a `:collect` fn. Such options stay suggestable
  after they have been used."
  [opts k]
  (or (coll? (get-in opts [:coerce k]))
      (contains? (:collect opts) k)))

(defn- safe-parse
  "Parse `args` for completion, swallowing the errors partial input provokes.
  Returns the `parse-args` result, or `{:args nil :opts nil}` if it throws."
  [args opts]
  (try (parse-args args opts)
       (catch #?(:clj ExceptionInfo :cljs :default) _ {:args nil :opts nil})))

(defn- option-candidates
  "Option candidates at one level completing `to-complete`, excluding the
  single-value options already in `parsed` (the opts parsed from the completed
  prefix; repeatable options stay). `spec` (raw, for `:desc`) may be a map, a
  vec-of-pairs, or nil. Returns candidate maps."
  [spec opts aliases known parsed to-complete]
  (let [used (set (remove #(repeatable-opt? opts %) (keys parsed)))
        smap (->spec-map spec)
        hidden? (fn [k] (:no-doc (get smap k)))   ; `:no-doc` option: hide from completion
        desc (fn [k] (help-first-line (:desc (get smap k))))
        long-cands (keep (fn [k]
                           (let [v (format-long-opt k)]
                             (when (and (not (hidden? k)) (not (used k)) (true-prefix? to-complete v))
                               {:value v :description (desc k)})))
                         known)
        short-cands (keep (fn [[a l]]
                            (let [v (format-short-opt a)]
                              (when (and (not (hidden? l)) (not (used l)) (true-prefix? to-complete v))
                                {:value v :description (desc l)})))
                          aliases)]
    (concat long-cands short-cands)))

(defn- command-candidates
  "Subcommand candidates of `node` completing `to-complete` (descriptions from
  each subcommand's `:doc`; `:no-doc` commands are hidden)."
  [node to-complete]
  (keep (fn [[cmd subnode]]
          (when (and (not (:no-doc subnode)) (true-prefix? to-complete cmd))
            {:value cmd :description (help-first-line (:doc subnode))}))
        (:cmd node)))

(defn- positional-candidates
  "Candidates for the positional argument being completed at `node`. The node's
  `:args->opts` maps positional index -> spec key; the count of positionals already
  in `pos-args` gives the current index. If that key has value completion
  (`:complete`/`:complete-fn`/set `:validate`), complete its values. A declared
  positional without value completion yields a single `{:file-completion true}`
  marker, so the stub defers to the shell's own file completer."
  [node spec pos-args parsed to-complete]
  (when-let [a->o (seq (:args->opts node))]
    ;; nth on the seq directly: `:args->opts` may be infinite (variadic
    ;; `(cons :foo (repeat :bar))`), so it must not be `vec`'d
    (let [k (nth a->o (count pos-args) nil)
          entry (when k (get (->spec-map spec) k))]
      (when k
        (if (or (:complete entry) (:complete-fn entry) (set? (:validate entry)))
          (candidates-for-entry entry k to-complete parsed)
          [{:file-completion true}])))))

(defn- descend
  "Walk the completed prefix `tokens` down dispatch tree `node`, consuming
  subcommands and this-level options (with their values). Returns
  `[deepest-node tokens-at-deepest-level end-of-options?]`. `level` feeds option
  exclusion; `end-of-options?` is true once a literal `--` has been seen, after
  which only positionals are completed."
  [node tokens]
  (loop [node node, toks (seq tokens), level [], eoo? false]
    (let [head (first toks)]
      (cond
        (nil? head) [node level eoo?]
        ;; literal `--`: end of options. Everything after is positional; stop
        ;; matching subcommands and options
        (= "--" head) (recur node (next toks) (conj level head) true)
        (and (not eoo?) (get-in node [:cmd head])) (recur (get-in node [:cmd head]) (next toks) [] false)
        (and (not eoo?) (gnu-option? head))
        (let [n (if (bool-opt? head (spec->opts (:spec node)))
                  1   ; boolean flag: no value
                  2)] ; option plus its value
          (recur node (drop n toks) (into level (take n toks)) eoo?))
        ;; stray positional (e.g. a leftover value) - keep at this level
        :else (recur node (next toks) (conj level head) eoo?)))))

(defn- split-eq
  "Split a long `--opt=val` token into `[\"--opt\" \"val\"]` so the inline value is
  treated like a separate token; other tokens pass through unchanged."
  [token]
  (if (and (str/starts-with? token "--") (str/includes? token "="))
    (str/split token #"=" 2)
    [token]))

(defn- complete-tree*
  "Returns completion candidate maps (`{:value :description}`) for dispatch tree
  `cmd-tree` and `args` (a vector of tokens, last = the token being completed):
  matching subcommands of the node reached by the earlier tokens, plus that
  node's options."
  [cmd-tree args]
  (let [args (vec (mapcat split-eq args))
        done (vec (butlast args))
        to-complete (or (last args) "")
        [node level eoo?] (descend cmd-tree done)
        spec (:spec node)
        [opts aliases known] (resolve-completion-opts {:spec spec})
        previous (peek done)]
    (if (and (not eoo?) (gnu-option? previous) (not (bool-opt? previous opts)))
      ;; preceding option awaits a value -> complete the value, not commands/options.
      ;; Parse the tokens before that option (no value yet) for dependent completion.
      (let [{parsed :opts} (safe-parse (vec (butlast level)) opts)]
        (value-candidates spec opts previous to-complete parsed))
      ;; parse the completed level once, shared by option exclusion and the
      ;; positional index
      (let [{parsed :opts pos-args :args} (safe-parse level opts)]
        (if eoo?
          ;; past a literal `--`: only positionals (values or file completion)
          (positional-candidates node spec pos-args parsed to-complete)
          (concat (when-not (gnu-option? to-complete)
                    (command-candidates node to-complete))
                  (option-candidates spec opts aliases known parsed to-complete)
                  (when-not (gnu-option? to-complete)
                    (positional-candidates node spec pos-args parsed to-complete))))))))

;; The stub a user installs. On each TAB it calls the program back with the hidden
;; `org.babashka.cli/completions complete` subcommand, passing the shell-tokenized
;; words up to the cursor (after `--`), so quoting is handled by the shell, not us.
;; The program prints `value<TAB>description` lines, which the stub renders
;; (zsh/fish/powershell show descriptions; bash completes values only).
(defn- completion-shell-snippet [shell program-name]
  ;; name the function after the program (sanitized), so installing completions for
  ;; several babashka.cli CLIs in one shell does not collide on a shared function
  (let [fn (str "_babashka_cli_complete_" (str/replace program-name #"[^a-zA-Z0-9_]+" "_"))]
   (case shell
    :bash (str fn "()
{
    local cur words cword
    # -n =: keeps --opt=val and ns:val as single words (bash splits on = and :
    # via COMP_WORDBREAKS); the no-bash-completion fallback below cannot, so a : in
    # a value is only fully handled when bash-completion is installed
    if declare -F _init_completion >/dev/null 2>&1; then
        _init_completion -n =: || return
    else
        words=(\"${COMP_WORDS[@]}\"); cword=$COMP_CWORD; cur=\"${COMP_WORDS[COMP_CWORD]}\"
    fi
    compopt -o nosort 2>/dev/null   # keep our candidate order (bash 4.4+; ignored on 3.2)
    local out
    out=$(\"${words[0]}\" org.babashka.cli/completions complete --shell bash -- \"${words[@]:1:cword}\" 2>/dev/null)
    local IFS=$'\\n'
    case \"$out\" in
        *org.babashka.cli/file-completion*)
            compopt -o filenames 2>/dev/null
            COMPREPLY+=( $(compgen -f -- \"$cur\") ) ;;
    esac
    local values
    values=$(grep -v '^org.babashka.cli/file-completion$' <<< \"$out\" | cut -f1)
    COMPREPLY+=( $(compgen -W \"$values\" -- \"$cur\") )
    # bash re-inserts from the last COMP_WORDBREAKS char (e.g. : or =); strip that
    # prefix from each candidate so colon/equals values complete without duplication
    local wb pre i
    for wb in : = ; do
        if [[ \"$cur\" == *\"$wb\"* && \"$COMP_WORDBREAKS\" == *\"$wb\"* ]]; then
            pre=\"${cur%\"${cur##*$wb}\"}\"
            for ((i=0; i<${#COMPREPLY[@]}; i++)); do COMPREPLY[$i]=\"${COMPREPLY[$i]#\"$pre\"}\"; done
        fi
    done
}
complete -F " fn " " program-name "
")
    :zsh (str "#compdef " program-name "
" fn "() {
    local -a completions described
    completions=(\"${(@f)$(\"${words[1]}\" org.babashka.cli/completions complete --shell zsh -- \"${(@)words[2,CURRENT]}\" 2>/dev/null)}\")
    local do_files=
    if (( ${completions[(I)org.babashka.cli/file-completion]} )); then do_files=1; fi
    completions=(${completions:#org.babashka.cli/file-completion})
    local c
    for c in $completions; do described+=(\"${c//$'\\t'/:}\"); done
    _describe -t commands " program-name " described
    [[ -n $do_files ]] && _files
}
# register for the bare name and for path invocations (./prog, /abs/prog)
compdef " fn " '*/" program-name "' " program-name "
")
    :fish (str "function " fn "
    set -l toks (commandline --tokenize --cut-at-cursor)
    set -l prog $toks[1]
    set -e toks[1]
    set -l cur (commandline --current-token)
    for line in ($prog org.babashka.cli/completions complete --shell fish -- $toks \"$cur\" 2>/dev/null)
        if test \"$line\" = org.babashka.cli/file-completion
            __fish_complete_path \"$cur\"
        else
            echo $line
        end
    end
end
complete --command " program-name " --no-files --arguments \"(" fn ")\"
")
    :powershell (str "Register-ArgumentCompleter -Native -CommandName " program-name " -ScriptBlock {
    param($wordToComplete, $commandAst, $cursorPosition)
    $exe = $commandAst.CommandElements[0].Value
    $toks = @()
    for ($i = 1; $i -lt $commandAst.CommandElements.Count; $i++) {
        $el = $commandAst.CommandElements[$i]
        if ($el.Extent.StartOffset -ge $cursorPosition) { break }
        $toks += $el.Extent.Text
    }
    if (-not $wordToComplete) { $toks += '' }
    $lines = @(& $exe org.babashka.cli/completions complete --shell powershell -- $toks 2>$null)
    $lines | Where-Object { $_ -ne 'org.babashka.cli/file-completion' } | ForEach-Object {
        $parts = $_ -split \"`t\", 2
        $tip = if ($parts.Length -gt 1) { $parts[1] } else { $parts[0] }
        [System.Management.Automation.CompletionResult]::new($parts[0], $parts[0], 'ParameterValue', $tip)
    }
    if ($lines -contains 'org.babashka.cli/file-completion') {
        [System.Management.Automation.CompletionCompleters]::CompleteFilename($wordToComplete)
    }
}
"))))

(defn- print-completions
  "Print one line per candidate: `value`, or `value<TAB>description` when the
  candidate has a description. Shell-agnostic, the stub renders per shell. The
  description is reduced to its first line with tabs stripped, so it can't break
  the line/field wire protocol."
  [candidates]
  (doseq [{:keys [value description]} candidates]
    (let [desc (when-not (str/blank? description)
                 (str/replace (first (str/split-lines description)) "\t" " "))]
      (println (if desc (str value \tab desc) value)))))

(defn- eprintln [s]
  #?(:clj (binding [*out* *err*] (println s))
     :cljs (binding [*print-fn* *print-err-fn*] (println s))))

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
  that must precede the subcommand).

  Specs are mapified here for set reasoning (a standalone `format-command-help`
  spec may be a vec-of-pairs); display order is handled by `render-help`."
  [tree cmds prog inherit]
  (let [node-at (fn [path] (get-in tree (interleave (repeat :cmd) path)))
        prog-at (fn [path] (str/join " " (cons prog path)))
        ;; options available at the target level itself (e.g. an injected --help,
        ;; or a redefined option) - those never need a "must precede" pointer
        here (set (keys (->spec-map (:spec (node-at cmds)))))
        ;; for each strict ancestor prefix: what it contributes downward
        ;; (:inh) and its own non-inherited options (:own)
        ancestors (for [i (range (count cmds))
                        :let [pre  (subvec cmds 0 i)
                              spec (->spec-map (:spec (node-at pre)))
                              inh  (inherited-entries spec inherit)]]
                    {:pre pre :inh inh :own (apply dissoc spec (keys inh))})
        inherited (reduce merge {} (map :inh ancestors))
        ;; ancestors with non-inherited options that aren't also available here
        ;; (those must be given before the subcommand)
        parents (for [{:keys [pre own]} ancestors
                      :let [own (apply dissoc own here)]
                      :when (seq own)]
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
  Options:                 ; the command's own :spec
    ...
  Inherited options:       ; ancestor options usable here (:inherit), deduped
    ...
  <epilog>                 ; the entry's :epilog free-text, rendered verbatim
  ```

  An entry's `:epilog` (a string) is rendered verbatim after the options - use it
  for examples, notes or links. Put it on the root entry (`:cmds []`) for the
  top-level help.

  Takes a single map:
  * `:table`   - a `dispatch` table, or a tree from [[table->tree]] (required)
  * `:cmds`    - the command path, e.g. `[\"deps\" \"outdated\"]` (default `[]`)
  * `:prog`    - program name shown in the usage line (required)
  * `:inherit` - only needed when you pass a dispatch-level `:inherit` to
                 `dispatch`; pass the same value so `Inherited options:` matches.
                 Per-option `:inherit true` is detected automatically.

  Options are listed in the entry's `:order` when it has one, else in spec order
  (a vec-of-pairs `:spec` keeps its order; a map follows key order, unreliable
  beyond a few keys - use a vec-of-pairs spec or `:order`).

  This is the renderer the `:help` option uses; call it from a custom `:help-fn`
  to render the standard help and then add your own output. An entry may carry
  `:no-doc true` to be omitted from `Commands:`."
  [{:keys [table cmds prog inherit] :or {cmds []}}]
  (let [tree (if (map? table) table (table->tree table))
        ctx (command-help-context tree (vec cmds) prog inherit)]
    (render-help (:node ctx) ctx)))

(defn ^:dynamic *exit-fn*
  "Terminates the process after `dispatch`'s `:help` option prints an *error*
  (unknown/missing subcommand, flag error). `--help`/`-h` is not an error - it
  prints help and returns, so it does not call this. Called with a map:

  * `:exit` - exit code (always non-zero, `1`)
  * `:cause` - the dispatch error cause: `:no-match` (unknown subcommand),
    `:input-exhausted` (group with no subcommand), or the flag cause
    (`:restrict` / `:require` / `:validate` / `:coerce`)
  * `:dispatch` - the command path
  * `:data` - the original `dispatch` error data

  Rebind to use your own exit codes (switch on `:cause`), or to not exit at all
  (tests, REPL):

  ```clojure
  (binding [babashka.cli/*exit-fn* (fn [m] (throw (ex-info \"exit\" m)))]
    ...)
  ```

  Must exit or throw.

  Default: `System/exit` (JVM), `js/process.exit` (Node), `throw` (browser)."
  [{:keys [exit]}]
  #?(:clj (System/exit exit)
     :cljs (if (and (exists? js/process) (fn? (.-exit js/process)))
             (js/process.exit exit)
             (throw (ex-info "exit" {:exit exit})))))

(defn- print-command-help
  "The `:help-fn` installed by `dispatch`'s `:help` option: print full help for
  the command at `:dispatch`, then return. Asking for help is not an error - the
  caller does not exit (it returns like a normal `:fn`, so the process ends with
  status 0). Reads the command tree, `:prog` and `:inherit` from the data
  dispatch threads in; renders via [[format-command-help]]."
  [{:keys [tree dispatch prog inherit]}]
  (println (format-command-help {:table tree :cmds (or dispatch []) :prog prog :inherit inherit})))

(defn format-command-error
  "Render a terse, helpful message (a string) for a dispatch error, given the
  data `dispatch` passes to its `:error-fn`:

  * `:no-match` (unknown subcommand) -> message + commands + hint
  * `:input-exhausted` (group, no subcommand) -> message + commands + hint
  * flag error (`:restrict` / `:require` / `:validate` / `:coerce`) -> message
    + usage + hint

  Reads the command tree, `:prog`, `:inherit`, `:dispatch` (the path), and for
  flag errors `:msg` (and for `:no-match`, `:wrong-input`) from the data.
  Messages name the flag as typed (`--foo`/`-x`), not `:foo`.

  This is the renderer the `:help` option's default `:error-fn` uses (it prints
  this, then calls [[*exit-fn*]]). Call it from a custom `:error-fn` to keep the
  standard message and add your own output. `--help`/`-h` is not an error - it
  goes to the `:help-fn`, rendered by [[format-command-help]]."
  [{:keys [cause dispatch wrong-input msg prog inherit tree]}]
  (let [path   (or dispatch [])
        ctx-at (fn [p] (command-help-context tree (vec p) prog inherit))
        hint  (str "Run \"" (str/join " " (cons prog path))
                   " --help\" for more information.")
        usage (fn [p]
                (let [{:keys [node prog inherited]} (ctx-at p)]
                  (help-usage-line prog node (or (seq (:spec node))
                                                 (seq inherited)))))
        ;; subcommand-level error (unknown / missing): terse message + the
        ;; available commands + a pointer to --help
        subcommand-error
        (fn [message]
          (let [cmds (help-commands-table (:node (ctx-at path)))]
            (str/join "\n"
                      (concat [message ""]
                              (when (seq cmds)
                                [(str "Commands:\n" (format-table {:rows cmds :indent 2})) ""])
                              [hint]))))]
    (cond
      (= :no-match cause)
      (subcommand-error (str "Unknown command: " wrong-input))

      (= :input-exhausted cause)
      (subcommand-error "No subcommand given.")

      ;; genuine flag error (restrict / require / validate / coerce): terse.
      ;; The lib message already names the flag the user typed.
      :else
      (str/join "\n" [(str "Error: " msg) "" (usage path) "" hint]))))

(defn- print-command-error
  "Print the terse [[format-command-error]] message for a dispatch error, to
  stderr (errors and usage-on-error go to stderr; explicit `--help` goes to
  stdout). Only prints - does not exit (the default `:error-fn` prints, then
  calls [[*exit-fn*]] itself)."
  [data]
  (eprintln (format-command-error data)))

(defn- thread-dispatch-context
  "Add the dispatch-level `:prog` and `:inherit` (when set) to error/help `data`,
  so an `:error-fn` / `:help-fn` can render without being handed them."
  [data {:keys [prog inherit]}]
  (cond-> data
    prog    (assoc :prog prog)
    inherit (assoc :inherit inherit)))

;; command names to suggest in errors: skip `:no-doc` (e.g. the injected
;; `org.babashka.cli/completions`), same as help and completion hide them
(defn- visible-command-names [cmd-info]
  (into [] (comp (remove (comp :no-doc val)) (map key)) (:cmd cmd-info)))

(defn- dispatch-tree'
  ([tree args]
   (dispatch-tree' tree args nil))
  ([tree args opts]
   (loop [cmds [] all-opts {} args args cmd-info tree inherited {}]
     (let [kwm cmd-info
           ;; capture before the parse-args destructure below shadows `opts`
           inherit-opt (:inherit opts)
           prog (:prog opts)
           help? (::help opts)
           should-parse-args? (or (has-parse-opts? kwm)
                                  (seq inherited)
                                  (is-option? (first args)))
           parse-opts (deep-merge opts kwm)
           ;; options marked `:inherit` at an ancestor level are accepted here
           ;; too (e.g. `prog group --opt val sub` and `prog group sub --opt val`)
           parse-opts (cond-> parse-opts
                        ;; precedence: dispatch-level (global) :spec < inherited <
                        ;; this node's own :spec. Rebuild from the still-separate
                        ;; global (:spec opts) and node-own (:spec kwm) so an
                        ;; inherited option isn't clobbered by a colliding global key.
                        (seq inherited)
                        (assoc :spec (deep-merge (deep-merge (->spec-map (:spec opts))
                                                             inherited)
                                                 (->spec-map (:spec kwm)))))
           ;; thread dispatch context into flag-level errors
           ;; (restrict/require/validate/coerce) so an :error-fn can render help:
           ;; the current path, the program name, dispatch-level :inherit, and the
           ;; command tree
           user-error-fn (:error-fn parse-opts)
           ;; With the `:help` option on, a flag error (require/validate/restrict/
           ;; coerce) is stashed rather than fired: a `--help`/`-h` further along
           ;; (e.g. `foo bar --help` where `foo` requires an option) parses at its
           ;; own level and routes to help, and `dispatch-tree` discards the stash.
           ;; If no help is reached, `dispatch-tree` fires the first stashed error.
           ;; Without `:help` (no stash) errors fire immediately, as before.
           error-stash (::error-stash opts)
           parse-opts (assoc parse-opts :error-fn
                             (fn [data]
                               (let [data (thread-dispatch-context
                                           (assoc data :dispatch cmds :tree tree)
                                           {:prog prog :inherit inherit-opt})
                                     fire #(if user-error-fn
                                             (user-error-fn data)
                                             (throw (ex-info (:msg data) data)))]
                                 (if error-stash
                                   (swap! error-stash conj fire)
                                   (fire)))))
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
       (if (and help? (or (:help opts) (:h opts)))
         ;; --help / -h seen at this level: render help for the current path
         {:error :help
          :dispatch cmds
          :opts (dissoc all-opts ::opts-by-cmds)}
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
                :available-commands (visible-command-names cmd-info)
                :dispatch cmds
                :opts (dissoc all-opts ::opts-by-cmds)}
               {:error :input-exhausted
                :available-commands (visible-command-names cmd-info)
                :dispatch cmds
                :opts (dissoc all-opts ::opts-by-cmds)}))))))))

(defn- dispatch-tree
  ([tree args]
   (dispatch-tree tree args nil))
  ([tree args opts]
   (let [;; with `:help` on, flag errors are deferred (stashed) during the walk
         ;; so a `--help`/`-h` deeper in the args can win - see dispatch-tree'
         error-stash (when (::help opts) (atom []))
         opts (cond-> opts error-stash (assoc ::error-stash error-stash))
         {:as res :keys [cmd-info error available-commands]}
         (dispatch-tree' tree args opts)
         error-fn (or (:error-fn opts)
                       (fn [{:keys [msg] :as data}]
                         (throw (ex-info msg data))))]
     (cond
       ;; --help/-h: success - print help via the :help-fn and return (no exit).
       ;; Help wins over any stashed flag error.
       (= :help error)
       ((::help-fn opts) (thread-dispatch-context
                          (assoc (select-keys res [:dispatch]) :tree tree)
                          opts))
       ;; a flag error was stashed during the walk and help did not win: fire the
       ;; first one (terse message via :error-fn, which exits non-zero)
       (and error-stash (seq @error-stash))
       ((first @error-stash))
       :else
       (case error
         ;; real errors: terse message via the :error-fn, which exits non-zero
         (:no-match :input-exhausted)
         (error-fn (thread-dispatch-context
                    (merge {:type :org.babashka/cli
                            :cause error
                            :all-commands available-commands
                            :tree tree}
                           (select-keys res [:wrong-input :opts :dispatch]))
                    opts))
         nil ((:fn cmd-info) (dissoc res :cmd-info)))))))

(defn- node-with-help
  "Give one node a `--help`/`-h` option: add `:help` to its `:spec` (so it parses
  and triggers help), and ensure a display `:order` that doesn't depend on
  (unguaranteed) map order.

  An explicit node `:order` is left untouched - you choose the order, which keys
  to list, and whether to list `--help` at all (leave `:help` out to hide it from
  the options; it still works). When there is no `:order`, one is constructed
  from the spec as written (vec-of-pairs order, or map keys) and `--help` is
  appended. The `:h` alias is only added when `:h` is free."
  [{:keys [spec order] :as node}]
  (let [as-map (->spec-map spec)
        h-free? (and (not (contains? as-map :h))
                     (not (some (fn [[_ v]] (and (map? v) (= :h (:alias v)))) as-map)))
        default (cond-> {:coerce :boolean :desc "Show this help"}
                  h-free? (assoc :alias :h))]
    (assoc node
           :spec (update as-map :help #(merge default %))
           :order (if order
                    (vec order)
                    (let [c (vec (if (sequential? spec) (map first spec) (keys as-map)))]
                      (if (some #{:help} c) c (conj c :help)))))))

(defn- inject-help
  "Add the `--help` option to every node of a dispatch `tree` (used by the
  `:help` option), so it parses as a real option and shows up in help."
  [node]
  (cond-> (node-with-help node)
    (:cmd node) (update :cmd (fn [m]
                               (reduce-kv (fn [acc k v] (assoc acc k (inject-help v))) {} m)))))

(defn- inject-completion
  "Add the hidden `org.babashka.cli/completions` subcommand group to the tree root.
  `--shell` is shared (`:inherit`) by its two leaves. `snippet` prints the install
  snippet for `--shell`, with `--prog` overriding the registered name for a renamed
  binary. `complete` completes the tokens after `--`: the stub passes the
  shell-tokenized words up to the cursor, so quoting is the shell's job. Both
  complete against `tree`, captured before this injection, so the hidden command
  never appears as a candidate."
  [tree opts]
  (assoc-in tree [:cmd "org.babashka.cli/completions"]
            {:no-doc true
             :spec {:shell {:coerce :keyword :inherit true}}
             :cmd {"snippet"
                   {:spec {:prog {}}
                    :fn (fn [{{:keys [shell prog]} :opts}]
                          (let [prog (or prog (:prog opts))]
                            (cond
                              (not prog)
                              (eprintln (str "babashka.cli: set :prog in opts, or pass --prog,"
                                             " to generate a completion snippet"))
                              (not (#{:bash :zsh :fish :powershell} shell))
                              (eprintln (str "babashka.cli: unknown --shell " (pr-str shell)
                                             ", expected one of: bash zsh fish powershell"))
                              :else (print (completion-shell-snippet shell prog)))))}
                   "complete"
                   {:fn (fn [{:keys [args]}]
                          (let [cands (complete-tree* tree (vec args))]
                            (print-completions (remove :file-completion cands))
                            ;; reserved marker line: the stub sees it and defers to
                            ;; the shell's own file completer for this position
                            (when (some :file-completion cands)
                              (println "org.babashka.cli/file-completion"))))}}}))

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

  For a single-command CLI (no subcommands), use a one-entry table whose `:cmds`
  is `[]`:

  ```clojure
  (dispatch [{:cmds [] :fn f :spec spec}] args {:prog \"tool\" :help true})
  ```

  Provide an `:error-fn` to deal with non-matches.

  Set `:prog` to the program name shown in help. Provide `:help true` to wire up
  help without `:restrict`:

  * `--help`/`-h` print help for the command they precede and return (no error,
    so the process ends with status 0). This goes through a `:help-fn`.
  * an unknown/missing subcommand or a flag error prints a terse message and
    exits non-zero (via [[*exit-fn*]]). This goes through the `:error-fn`.

  Both default handlers can be overridden: pass your own `:help-fn` (called with
  `{:tree :dispatch :prog :inherit}`) and/or `:error-fn`. `dispatch` threads
  `:prog`, `:inherit` and the command tree into the data either receives, so
  they can render without being handed them separately.

  Each entry in the table may have additional [[parse-args]] options.

  For more information and examples, see [README.md](README.md#subcommands)."
  ([table args]
   (dispatch table args {}))
  ([table args opts]
   (let [base-tree (table->tree table)
         ;; complete against the same tree the user dispatches with, so
         ;; `--help`/`-h` (injected by `:help`) also show up as completions
         tree (cond-> base-tree (:help opts) inject-help)
         ;; the hidden `org.babashka.cli/completions` subcommand carries completion;
         ;; it routes through dispatch-tree like any command. A caller that
         ;; preprocesses argv before `dispatch` must pass this command through.
         tree (inject-completion tree opts)]
     (if (:help opts)
       (dispatch-tree tree args
                      (assoc opts ::help true
                             ;; default :error-fn = print the message, then exit;
                             ;; :cause is the dispatch cause as-is (:no-match /
                             ;; :input-exhausted / a flag cause)
                             :error-fn (or (:error-fn opts)
                                           (fn [{:keys [cause dispatch] :as data}]
                                             (print-command-error data)
                                             (*exit-fn* {:exit 1 :cause cause
                                                         :dispatch dispatch :data data})))
                             ::help-fn (or (:help-fn opts) print-command-help)))
       (dispatch-tree tree args opts)))))
