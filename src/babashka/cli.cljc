(ns babashka.cli
  (:refer-clojure :exclude [parse-boolean parse-long parse-double])
  (:require
   #?(:clj [clojure.edn :as edn]
      :cljd [cljd.edn :as edn]
      :cljs [cljs.reader :as edn])
   #?@(:cljd [["dart:io" :as io]])
   [babashka.cli.internal :as internal]
   [clojure.string :as str])
  #?@(:cljd [] :clj [(:import (clojure.lang ExceptionInfo))]))

#?(:clj (set! *warn-on-reflection* true))

;; squint can't tell an injected keyword from a string arg, so wrap injected opts
#?(:squint (deftype Injected [opt]))

(defn merge-opts
  "Merges babashka CLI options."
  [m & ms]
  (reduce #(merge-with internal/merge* %1 %2) m ms))

(defn- throw-unexpected [s]
  (throw (ex-info (str "Unexpected format: " s) {:s s})))

(defn- parse-boolean [x]
  #?(:clj (Boolean/parseBoolean x)
     :cljd (= "true" x)
     :cljs (let [v (js/JSON.parse x)]
             (if (boolean? v)
               v
               (throw-unexpected x)))))

(defn- parse-long [x]
  #?(:clj (Long/parseLong x)
     :cljd (or (dart:core/int.tryParse x .radix 10) (throw-unexpected x))
     :cljs (let [v (js/JSON.parse x)]
             (if (int? v)
               v
               (throw-unexpected x)))))

(defn- parse-double [x]
  #?(:clj (Double/parseDouble x)
     :cljd (or (dart:core/double.tryParse x) (throw-unexpected x))
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
     :cljd (or (dart:core/int.tryParse x .radix 10)
               (dart:core/double.tryParse x)
               (throw-unexpected x))
     :cljs (let [v (js/JSON.parse x)]
             (if (number? v)
               v
               (throw-unexpected x)))))

(defn ^:no-doc ;; was accidentally left in the public API for a long while. Mark as no-doc to hide (but avoid breaking anyone who might be using it).
  number-char? [c]
  (try (parse-number (str c))
       (catch #?(:clj Exception :cljd Object :cljs :default) _ nil)))

(defn- first-char #?(:cljd [arg] :default ^Character [^String arg])
  (when (string? arg)
    (nth arg 0 nil)))

(defn- second-char #?(:cljd [arg] :default ^Character [^String arg])
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
      (let [s #?(:cljd s :default ^String s)
            fst-char (first-char s)
            #?@(:cljd [leading-num-char (if (= \- fst-char)
                                          (second-char s)
                                          fst-char)]
                :clj [leading-num-char (if (= \- fst-char)
                                         (second-char s)
                                         fst-char)])]
        (cond (or (= "true" s)
                  (= "false" s))
              (parse-boolean s)
              (= "nil" s) nil
              #?(:cljd (and leading-num-char (re-matches #"[0-9]" leading-num-char))
                 :clj (some-> leading-num-char (Character/isDigit))
                 :cljs (not (js/isNaN s)))
              (parse-number s)
              (and (= \: fst-char) (re-matches #"\:[a-zA-Z][a-zA-Z0-9_/\.-]*" s))
              (parse-keyword s)
              :else s))
      (catch #?(:clj Exception
                :cljd Object
                :cljs :default) _ s))
    s))

(defn- coerce-failure-reason
  "The reason part of a coerce failure, e.g. `cannot transform input \"x\" to long`."
  [s implicit-value f]
  (str "cannot transform "
       (if implicit-value
         (str "(implicit) " implicit-value)
         (str "input " (pr-str s)))
       (if (keyword? f)
         " to "
         " with ")
       (if (keyword? f)
         (name f)
         f)))

(defn- throw-coerce [s implicit-value f e]
  (throw (ex-info (str "Coerce failure: " (coerce-failure-reason s implicit-value f))
                  (cond-> {:input s
                           :coerce-fn f}
                    (some? implicit-value) (assoc :implicit-value implicit-value))
                  e)))

(defn- coerce*
  "Coerce string `s` using coercer `f`.
  `implicit-value` can be:
  - `nil` not an implicit value
  - `true` for implicit true, e.g., --foo
  - `false` for implict false, e.g., --no-foo"
  [s f implicit-value]
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
                   (catch #?(:clj Exception :cljd Object :cljs :default) e
                     (throw-coerce s implicit-value f e)))
              s)]
    (if (and (some? implicit-value) (not= implicit-value res))
      (throw-coerce s implicit-value f nil)
      res)))

(defn coerce
  "Coerce string `s` using `f`. Does not coerce when `s` is not a string.
  `f` may be a keyword (`:boolean`, `:int`, `:double`, `:symbol`,
  `:keyword`) or a function. When `f` return `nil`, this is
  interpreted as a parse failure and throws."
  [s f]
  (coerce* s f nil))

(defn- collect-fn
  "Returns the collection function for opt, derived from collect-opts and coerce-map."
  [collect-opts coerce-map opt]
  (let [f (or (get collect-opts opt)
              (let [k (get coerce-map opt)]
                (when (coll? k) (empty k))))]
    (when f
      (if (coll? f) (fnil conj f) f))))

(defn- maybe-close-open-opt
  "Give `open-opt` an implicit `true` if we've moved on to a different opt"
  [acc open-opt valued-opt opt-val-collector]
  (if (not= open-opt valued-opt)
    (let [v (if opt-val-collector
              (opt-val-collector (get acc open-opt) true)
              true)]
      (assoc acc open-opt v))
    acc))

(defn- add-val-to-opt [acc opt opt-val-collector val]
  (if opt-val-collector
    (update acc opt opt-val-collector val)
    (assoc acc opt val)))

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
  * `:cmds` - The parsed commands
  * `:args` - The remaining (unparsed) arguments"
  ([args] (parse-cmds args nil))
  ([args {:keys [no-keyword-opts]}]
   (let [[cmds args]
         (split-with #(not (or (when-not no-keyword-opts (str/starts-with? % ":"))
                               (str/starts-with? % "-"))) args)]
     {:cmds cmds
      :args args})))

(defn- args->opts
  ([args args->opt-keys] (args->opts args args->opt-keys #{}))
  ([args args->opt-keys ignored-args]
   (let [[new-args args->opts]
         (if args->opt-keys
           (if (and (seq args)
                    (not (contains? ignored-args (first args))))
             (let [arg-count (count args)
                   cnt (min arg-count
                            (bounded-count arg-count args->opt-keys))]
               [(concat (interleave #?(:squint (map ->Injected args->opt-keys)
                                       :default args->opt-keys)
                                    args)
                        (drop cnt args))
                (drop cnt args->opt-keys)])
             [args args->opt-keys])
           [args args->opt-keys])]
     {:args new-args
      :args->opts args->opts})))

(defn- analyze-arg [arg mode open-opt boolean-opt? valued-opt known-keys alias-keys]
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
                          (not open-opt)
                          (= valued-opt open-opt)))
        mode (or mode
                 (when kwd-opt?
                   :keywords))
        composite-opt? (when hyphen-opt?
                         (and snd-char (not= \- snd-char)
                              (> (count arg) 2)))]
    {:mode mode                    ;; :hyphen, :keywords, nil when undetermined
     :hyphen-opt hyphen-opt?       ;; --foo/-f
     :composite-opt composite-opt? ;; -abc
     :kwd-opt kwd-opt?             ;; :foo
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
  #?(:squint (str kw)
     :default (subs (str kw) 1)))

(defn- option-label
  "User-facing name for option `opt` in an error message: the literal flag the
  user typed (from `opt->flag`, e.g. `\"-f\"` or `\":foo\"`), else the canonical
  `--name` (a required or standalone-checked option was never typed). Uses
  `kw->str` so a namespaced key like `:foo/bar` renders as `--foo/bar`."
  [opt->flag opt]
  (or (get opt->flag opt) (str "--" (kw->str opt))))

(defn coerce-opts
  "Coerces values in the map `m` using the provided configuration.
  Does not coerce values that are not strings.
  Returns a new map with coerced values.

  Supported options:
  * `:coerce` - a map of option (keyword) names to type keywords (optionally wrapped in a collection).
  * `:spec` - a spec of options. See [spec](/README.md#spec).
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
         implicit-values (or (::implicit-values opts)
                             (::implicit-values m-meta))
         auto-coerce? (::auto-coerce opts)
         keys-order (or (::keys-order opts)
                        (::keys-order m-meta))
         error-fn (->error-fn spec (:error-fn opts))]
     (if (or (seq coerce-map) auto-coerce?)
       (let [coerce-1 (fn [v cf implicit-value]
                         (if cf (coerce* v cf implicit-value) (auto-coerce v)))
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
                      iv (and implicit-values (get implicit-values k))]
                  (try
                    (cond
                      (and coll-coerce? (coll? v))
                      (assoc acc k (reduce (fn [coll elem] (conj coll (coerce-1 elem cf iv))) empty-coll v))
                      coll-coerce?
                      (assoc acc k (conj empty-coll (coerce-1 v cf iv)))
                      (coll? v)
                      (assoc acc k (into (empty v) (map #(coerce-1 % cf iv)) v))
                      :else
                      (assoc acc k (coerce-1 v cf iv)))
                    (catch #?(:cljd cljd.core/ExceptionInfo :clj ExceptionInfo :cljs :default) e
                      (let [data (ex-data e)
                            km (::opt->flag m-meta)
                            flag (get km k)
                            iv (:implicit-value data)
                            label (option-label km k)]
                        (error-fn (cond-> {:cause :coerce
                                           ;; same shape as validate: name the option, then the reason.
                                           ;; when implicit-value, give a more nuanced error message
                                           ;; instead of "cannot transform (implicit) true to ..."
                                           :msg (case iv
                                                  true (str "Missing value for option " label)
                                                  ;; NOTE: squint lacks clojure.string/replace-first until > 0.14.196; use JS interop for now
                                                  false (str "Negation " label " invalid for option "
                                                             #?(:squint (.replace label "no-" "")
                                                                :default (str/replace-first label "no-" "")))
                                                  (str "Invalid value for option " label ": "
                                                       (coerce-failure-reason (:input data) iv (:coerce-fn data))))
                                           :option k
                                           :value v
                                           :opts acc}
                                    flag (assoc :flag flag)
                                    iv (assoc :implicit-value (:implicit-value data)))))
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
         opt->flag (::opt->flag (meta m))
         flag-for (fn [k] (option-label opt->flag k))
         error-fn (->error-fn spec (:error-fn opts))]
     (when restrict
       (doseq [k (keys m)]
         (when (and (not (contains? restrict k))
                    (not (contains? inherited k))
                    (not (contains? exec-args k))
                    (not= "babashka.cli" (namespace k)))
           (let [flag (get opt->flag k)]
             (error-fn (cond-> {:cause :restrict
                                :msg (str "Unknown option: " (flag-for k))
                                :restrict restrict
                                :option k
                                :opts m}
                         flag (assoc :flag flag)))))))
     (when require
       (doseq [k require]
         (when-not (find m k)
           (let [flag (get opt->flag k)]
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
             (when-not (if (set? f) (contains? f v) (f v))
               (let [ex-msg-fn (or (:ex-msg vf)
                                   (fn [{:keys [flag value]}]
                                     (str "Invalid value for option " flag ": " value)))
                     flag (get opt->flag k)]
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
  `:org.babashka/cli` metadata and internal `::implicit-values` /
  `::keys-order` metadata used by `coerce-opts`.

  Use this when you want to merge other sources (e.g. config files)
  before coerce/validate. Pipeline: `parse-opts*` -> merge -> `apply-defaults`
  -> `coerce-opts` -> `validate-opts`.

  Supported options (subset of `parse-opts`): `:alias`/`:aliases`, `:coerce`,
  `:collect`, `:no-keyword-opts`, `:repeated-opts`, `:args->opts`, `:spec`."
  [args {:keys [coerce collect no-keyword-opts repeated-opts] :as opts}]
  ;; terminology: given cli args ["--foo" "x" "bar"]
  ;;  --foo becomes an "opt"
  ;;  x becomes an option "value"
  ;;  bar remains an "arg"
  ;; parsed "arg"s can only be leading or trailing.
  (let [parse-opts opts ;; disambiguate from cli opts (without making fn sig odd-looking)
        aliases (or (:alias parse-opts) (:aliases parse-opts))
        spec (:spec parse-opts)
        spec-map (if (map? spec) spec (when spec (into {} spec)))
        alias-keys (set (concat (keys aliases) (keep :alias (vals spec-map))))
        known-keys (set (concat (keys spec-map) (vals aliases) (keys coerce)))
        expects-bool-val? (fn [opt-key] (#{:boolean :bool} (coerce-coerce-fn (get coerce opt-key))))
        track-ivs (fn [implicit-values current-opt added]
                    ;; we handle implicit trues here only, :add-opt-and-val below covers implicit false
                    (if (not= current-opt added)
                      (assoc implicit-values current-opt true)
                      implicit-values))
        track-kpo (fn [kpo opt]
                    (if (and opt (not (some #{opt} kpo)))
                      (conj kpo opt)
                      kpo))
        ;; remember each parsed option as it was provided/typed by the user in `::opt->flag`
        ;; metadata (e.g., parsed option :foo might have been typed as :foo, --foo or -f),
        ;; so error messages can echo what the user actually typed
        stamp (fn [m k lit] (if lit (vary-meta m assoc-in [::opt->flag k] lit) m))
        ;; inject leading positional args (in CLIs these are typically commands)
        ;; as options as per :args->opts
        {leading-pos-args :cmds args :args} (parse-cmds args)
        {new-leading-pos-args :args a->o :args->opts}
        (if-let [a->o (or (:args->opts parse-opts)
                          ;; :cmd-opts is the old name for :args->opts, left in for backward compat
                          (:cmds-opts parse-opts))]
          (args->opts leading-pos-args a->o (::dispatch-tree-ignored-args parse-opts))
          {:args->opts nil :args args})
        [leading-pos-args args] (if (not= new-leading-pos-args args)
                                  [nil (concat new-leading-pos-args args)]
                                  [leading-pos-args args])
        [parsed last-open-opt last-valued-opt implicit-values key-parse-order]
        (if (and (::dispatch-tree parse-opts) (seq leading-pos-args))
          [(vary-meta {} assoc-in [:org.babashka/cli :args] (into (vec leading-pos-args) args)) nil nil #{} []]
          (loop [acc {}
                 #_{:clj-kondo/ignore [:unused-binding]}
                 recur-action nil                     ;; for debugging only
                 open-opt nil                         ;; the cli option keyword we are working on
                 valued-opt nil                       ;; the cli option keyword that has been given value(s) (but not necessarily all values)
                 mode (when no-keyword-opts :hyphens) ;; :hyphens --foo/-f else :keywords :foo
                 args (seq args)                      ;; remaining cli args
                 a->o a->o                            ;; requested args->opts
                 implicit-values {}
                 opt-parse-order []]
            #_(println (format "loop %-31s o: %-10s v: %-10s a: %s" recur-action open-opt valued-opt args))
            (if-not args
              ;; exit loop: no command line args left
              [acc open-opt valued-opt implicit-values opt-parse-order]
              (let [raw-arg (first args)
                    opt-injected? #?(:squint (instance? Injected raw-arg)
                                     :default (keyword? raw-arg))
                    #?@(:squint [raw-arg (if opt-injected? (.-opt raw-arg) raw-arg)])]
                (if opt-injected?
                  ;; continue loop: this opt and its value was injected by args->opts
                  ;; opt-val-collector does not apply for injected opts, so is `nil`
                  (recur (maybe-close-open-opt acc open-opt valued-opt nil)
                         :found-injected-opt
                         raw-arg valued-opt
                         mode (next args) a->o
                         (track-ivs implicit-values open-opt valued-opt)
                         (track-kpo opt-parse-order raw-arg))
                  (let [arg (str raw-arg)
                        opt-val-collector (collect-fn collect coerce open-opt)
                        boolean-opt? (expects-bool-val? open-opt)
                        {:keys [hyphen-opt composite-opt kwd-opt mode fst-colon]}
                        (analyze-arg arg mode open-opt boolean-opt? valued-opt known-keys alias-keys)]
                    (if (or hyphen-opt kwd-opt)
                      ;; arg is -f/-foo or :foo
                      (let [long-opt? (str/starts-with? arg "--")
                            eo-all-opts? (and long-opt? (= "--" arg))]
                        (if eo-all-opts?
                          ;; exit loop: only args left
                          (let [nargs (next args)]
                            [(cond-> acc
                               nargs (vary-meta assoc-in [:org.babashka/cli :args] (vec nargs)))
                             open-opt valued-opt implicit-values opt-parse-order])
                          (let [opt-name (if long-opt?
                                           (subs arg 2)
                                           (str/replace arg #"^(:|-|)" ""))
                                ;; split on the first = only: --header=k=v binds "k=v"
                                [opt-name opt-val] (if long-opt?
                                                     (str/split opt-name #"=" 2)
                                                     [opt-name])
                                opt-kw (keyword opt-name)
                                opt-kw-for-alias (when-not long-opt? (get aliases opt-kw))
                                parsed-opt (or opt-kw-for-alias opt-kw)
                                ;; the literal option the user typed (sans any =value)
                                literal-opt (if long-opt? (str "--" opt-name) arg)]
                            (if opt-val
                              ;; continue loop: inject val for --foo=val into args
                              (recur (stamp (maybe-close-open-opt acc open-opt valued-opt opt-val-collector) parsed-opt literal-opt)
                                     :injected-bound-val
                                     parsed-opt nil
                                     mode (cons opt-val (rest args)) a->o
                                     (track-ivs implicit-values open-opt valued-opt)
                                     (track-kpo opt-parse-order parsed-opt))
                              (let [next-args (next args)
                                    next-arg (first next-args)
                                    next-arg-info (analyze-arg next-arg mode open-opt boolean-opt? valued-opt known-keys alias-keys)
                                    negated-opt? (when-not (contains? known-keys parsed-opt)
                                                   (str/starts-with? (str parsed-opt) #?(:squint "no-" :default ":no-")))]
                                (if (or (:hyphen-opt next-arg-info) ;; --open-opt --next
                                        (empty? next-args)          ;; --open-opt
                                        negated-opt?)               ;; --no-foo
                                  ;; implicit true or false
                                  (if (and (not opt-kw-for-alias) composite-opt)
                                    ;; continue loop: expand -abc to: -a true, -b true, -c true onto args
                                    (let [expanded (mapcat (fn [c] [(str "-" c) true]) (name parsed-opt))]
                                      (recur acc
                                             :injected-expanded-composite
                                             nil nil ;; start afresh for open-opt and valued-opt
                                             mode (concat expanded next-args) a->o
                                             implicit-values opt-parse-order))
                                    (let [parsed-opt (if negated-opt?
                                                       (keyword (str/replace (str parsed-opt) #?(:squint "no-" :default ":no-") ""))
                                                       parsed-opt)]
                                      ;; continue loop: adding true for --foo or false for --no-foo to args
                                      (recur (stamp (maybe-close-open-opt acc open-opt valued-opt opt-val-collector) parsed-opt literal-opt)
                                             :injected-implicit-bool
                                             parsed-opt valued-opt
                                             mode (cons (not negated-opt?) next-args) a->o
                                             (track-ivs implicit-values open-opt valued-opt)
                                             (track-kpo opt-parse-order parsed-opt))))
                                  ;; continue loop: not implicit, carry on to parse opt value
                                  (recur (stamp (maybe-close-open-opt acc open-opt valued-opt opt-val-collector) parsed-opt literal-opt)
                                         :found-opt-with-unparsed-val
                                         parsed-opt nil
                                         mode next-args a->o
                                         (track-ivs implicit-values open-opt valued-opt)
                                         (track-kpo opt-parse-order parsed-opt))))))))
                      ;; arg (is not option)
                      (let [done-parsing-options? (or
                                                   ;; boolean with next arg that is not true/false ends
                                                   ;; --some-bool foo --bar -baz
                                                   ;; gets us :some-bool true with args: foo,--bar,--baz
                                                   (and boolean-opt?
                                                        (not= "true" arg)
                                                        (not= "false" arg))
                                                   (and (= valued-opt open-opt)     ;; working on opt with vals
                                                        (or (not opt-val-collector) ;; not collecting vals
                                                            repeated-opts           ;; must specify --foo a --foo and not --foo a b
                                                            (contains? (::dispatch-tree-ignored-args parse-opts) (first args)))))]
                        (if done-parsing-options?
                          (let [{new-trailing-pos-args :args a->o :args->opts}
                                (if (and args a->o)
                                  (args->opts args a->o (::dispatch-tree-ignored-args parse-opts))
                                  {:args args})
                                new-args? (not= args new-trailing-pos-args)]
                            (if new-args?
                              ;; continue loop: with trailing args -> options
                              (recur acc
                                     :injected-trailing-args-to-opts
                                     open-opt valued-opt
                                     mode new-trailing-pos-args a->o
                                     implicit-values opt-parse-order)
                              ;; exit loop: args -> options resulted in no new args
                              [(vary-meta acc assoc-in [:org.babashka/cli :args] (vec args)) open-opt valued-opt implicit-values opt-parse-order]))
                          (let [opt (when-not (and (= :keywords mode) fst-colon) open-opt)]
                            ;; continue loop: add/update opt with value (arg), and setup to parse next arg
                            ;; notice the difference from all othe recurs,
                            ;; this is the only recur where we explicitly add/update a value/opt to acc
                            (recur (add-val-to-opt acc open-opt opt-val-collector arg)
                                   :added-opt-and-val
                                   opt opt ;; keep opt open
                                   mode (next args) a->o
                                   (cond-> implicit-values (boolean? raw-arg) (assoc open-opt raw-arg))
                                   opt-parse-order)))))))))))
        ;; Finalize: process last opt, prepend leading positional args to args metadata
        implicit-values (track-ivs implicit-values last-open-opt last-valued-opt)
        opt-val-collector (collect-fn collect coerce last-open-opt)
        parsed (-> (maybe-close-open-opt parsed last-open-opt last-valued-opt opt-val-collector)
                   (cond->
                    (and (seq leading-pos-args) (not (::dispatch-tree parse-opts)))
                     (vary-meta update-in [:org.babashka/cli :args]
                                (fn [args] (into (vec leading-pos-args) args)))))]
    (vary-meta parsed assoc ::implicit-values implicit-values ::keys-order key-parse-order)))

(defn parse-opts
  "Returns a map of options parsed from command line arguments `args`, a seq of strings.
  Instead of a leading `:` either `--` or `-` may be used as well.

  Metadata on returned map, under `:org.babashka/cli`:
  * `:args` remaining unparsed `args` (not corresponding to any options)

  Supported `opts`:
  * `:coerce` - a map of option (keyword) names to type keywords (optionally wrapped in a collection.)
  * `:alias` - a map of short names to long names.
  * `:spec` - a spec of options. See [spec](/README.md#spec).
  * `:restrict` - `true` or coll of keys. Throw on first parsed option not in set of keys or keys of `:spec` and `:coerce` combined.
  * `:require` - a coll of options that are required. See [require](/README.md#restrict).
  * `:validate` - a map of validator functions. See [validate](/README.md#validate).
  * `:exec-args` - a map of default args. Will be overridden by args specified in `args`. Values from `:exec-args` are NOT coerced or auto-coerced; provide them in their final form. Not subject to `:restrict`.
  * `:no-keyword-opts` - `true`. Support only `--foo`-style opts (i.e. `:foo` will not work).
  * `:repeated-opts` - `true`. Forces writing the option name for every value, e.g. `--foo a --foo b`, rather than `--foo a b`
  * `:args->opts` - consume unparsed commands and args as options
  * `:collect` - a map of collection fns. See [custom collection handling](/README.md#custom-collection-handling).

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
     (vary-meta validated dissoc ::implicit-values ::keys-order ::opt->flag))))

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
  #?(:cljd (or (when-let [c (get io/Platform.environment "COLUMNS")]
                 (dart:core/int.tryParse c))
               (try (when io/stdout.hasTerminal
                      (let [w io/stdout.terminalColumns]
                        (when (pos? w) w)))
                    (catch Object _ nil)))
     :cljs (when (and (exists? js/process) js/process.stdout
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

(defn format-table
  "Formats `rows` into a table (string).
  See [Printing options](/README.md#printing-options)."
  [{:keys [rows indent divider wrap max-width-fn]
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

(defn opts->table
  "Converts options to a table of rows.
  See [Printing options](/README.md#printing-options)."
  [{:keys [spec order columns]}]
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
              (map (fn [k] [k (get spec k)]) order))
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
                  (map (fn [k] [k (get spec k)]) (or order (keys spec)))
                  spec)
        ;; `:no-doc` options still parse but are hidden from help, like `:no-doc`
        ;; commands are hidden from the command list
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

(defn format-opts
  "Formats options into an options usage help string.

  See [Printing options](/README.md#printing-options)."
  [{:as cfg
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

(defn #?(:cljd ^:no-doc cmd-children :squint ^:no-doc cmd-children :default ^:private cmd-children)
  "Visible `[name child]` pairs of `node`'s commands, for display (help,
  completions, error suggestions): `:no-doc` children are dropped. An explicit
  node `:cmd-order` (vector of names) selects which children are shown and in
  what order, like `:order` does for options. Without it: the declaration
  order recorded by [[table->tree]]."
  [node]
  (let [m (:cmd node)
        order (or (:cmd-order node) (::cmd-order node))
        pairs (if order
                (keep (fn [k] (when-let [child (get m k)] [k child])) order)
                (map (juxt key val) m))]
    (remove (comp :no-doc second) pairs)))

(defn- help-usage-line [prog node any-options?]
  (str "Usage: " prog
       (when any-options? " [options]")
       ;; `<command>` reflects the runtime contract (the node accepts/requires
       ;; a command), so it keys on the dispatchable children, not the
       ;; visible ones: a node may hide all children and still demand one
       (cond (seq (:cmd node)) " <command>"
             ;; a runnable command: show labeled positionals from :args->opts, if
             ;; any. We don't show a generic `[<args>]` placeholder otherwise
             ;; (matches argparse/clap/click/picocli/cli-tools).
             (:fn node)        (when-let [labels (args->opts-labels (:args->opts node))]
                                 (str " " (str/join " " labels)))
             :else             "")))

(defn- help-commands-table [node]
  (mapv (fn [[cmd subnode]]
          [(str cmd) (or (help-first-line (:doc subnode)) "")])
        (cmd-children node)))

(defn- ->spec-map [spec]
  (cond (nil? spec) {}
        (map? spec) spec
        :else (into {} spec)))

(defn- visible-spec?
  "Does `spec` have any non-`:no-doc` option? Gates the help sections, so an
  all-hidden spec doesn't render an empty `Options:` header."
  [spec]
  (boolean (some (fn [[_ v]] (not (:no-doc v))) (->spec-map spec))))

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
        (cond-> [(help-usage-line prog node (or (visible-spec? spec) (visible-spec? inherited)))]
          desc
          (conj desc)

          (seq cmds)
          (conj (str "Commands:\n" (format-table {:rows cmds :indent 2})))

          (visible-spec? spec)
          (conj (str "Options:\n" (format-opts (cond-> {:spec spec :required (:require node)}
                                                 order (assoc :order order)))))

          (visible-spec? inherited)
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

(defn- add-table-entry
  "Merge table entry `cfg` into `node` at path `cmds`, recording each child in
  its parent's `::cmd-order` along the way (duplicates are fine,
  `normalize-node` dedupes). Empty `cmds` merges onto `node` itself (the
  catch-all entry); a literal `:cmd` it carries merges with (not clobbers)
  children from other entries, recorded like path-declared ones."
  [node cmds cfg]
  (if-let [[c & cs] (seq cmds)]
    (-> node
        (update ::cmd-order (fnil conj []) c)
        (update-in [:cmd c] add-table-entry cs cfg))
    (let [extra (:cmd cfg)]
      (cond-> (merge node cfg)
        extra (assoc :cmd (merge (:cmd node) extra))
        extra (update ::cmd-order (fnil into []) (keys extra))))))

(defn- normalize-node
  "Normalize tree `node`, recursively. Rejects table-entry `:cmds` on a node,
  dedupes an explicit `:cmd-order` and
  reconciles the recorded `::cmd-order` with the actual `:cmd` children -
  stale names dropped, unrecorded children appended in `:cmd` map order
  (whatever order the map iterates in is at least stable from then on).
  Idempotent; an already-normalized node comes back `identical?` (subtrees
  are shared, not copied)."
  [node]
  (when (:cmds node)
    (throw (ex-info "A tree node contains :cmds (table entry syntax): nest children under :cmd, or pass a table (vector of entries)"
                    {:node node})))
  (if-let [m (:cmd node)]
    (let [recorded (into [] (comp (distinct) (filter #(contains? m %))) (::cmd-order node))
          order (into recorded (remove (set recorded)) (keys m))
          m' (reduce-kv (fn [acc k v]
                          (let [v' (normalize-node v)]
                            (if (identical? v v') acc (assoc acc k v'))))
                        m m)
          deduped (when-let [co (:cmd-order node)] (vec (distinct co)))]
      (cond-> node
        (not (identical? m m')) (assoc :cmd m')
        (not= order (::cmd-order node)) (assoc ::cmd-order order)
        (and deduped (not= deduped (:cmd-order node))) (assoc :cmd-order deduped)))
    node))

(defn table->tree
  "Converts a `dispatch` table into a tree. Each `:cmds` becomes a path of
  nested `:cmd` maps; other entry keys are kept on the node. Empty `:cmds`
  merges onto the root. Table entry order is recorded on each node (internal
  key) and used as the display order for help and completions (see
  [[dispatch]]).

  ```clojure
  (table->tree [{:cmds [\"add\"] :fn add} {:cmds [] :fn help}])
  ;; => {:fn help, :cmd {\"add\" {:fn add}}, ...}
  ```

  A tree passed in is normalized and returned, so the function is idempotent."
  [table]
  (if (map? table)
    (if (:cmds table)
      (throw (ex-info "Expected a table (vector of entries) or a tree, got a single table entry - wrap it in a vector"
                      {:table table}))
      (normalize-node table))
    (normalize-node
     (reduce (fn [tree {:as cfg :keys [cmds]}]
               (add-table-entry tree cmds (dissoc cfg :cmds)))
             {} table))))

(comment
  (table->tree [{:cmds [] :fn identity}])
 )

(defn- deep-merge [a b]
  (reduce (fn [acc k] (update acc k (fn [v]
                                      (if (map? v)
                                        (deep-merge v (get b k))
                                        (get b k)))))
          a (keys b)))

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

;;;; Shell completion

(defn- format-long-opt [k] (str "--" (kw->str k)))
(defn- format-short-opt [k] (str "-" (kw->str k)))

(defn- true-prefix?
  "True when `s` starts with `prefix` and is strictly longer."
  [prefix s]
  (and (< (count prefix) (count s))
       (str/starts-with? s prefix)))

(defn- strip-prefix [prefix s]
  (if (str/starts-with? s prefix) (subs s (count prefix)) s))

(defn- gnu-option? [s]
  (and s (str/starts-with? s "-")))

(defn- option-key
  "Resolve an option token (`--foo` or `-f`) to its keyword."
  [token opts]
  (if (str/starts-with? token "--")
    (keyword (strip-prefix "--" token))
    (get-in opts [:alias (keyword (strip-prefix "-" token))])))

(defn- bool-opt?
  "True when option token `o` takes no value: a boolean `:coerce`, or the
  parser's `--no-foo` negation (an unknown `:no-` key parses as `{:foo false}`)."
  [o opts known]
  (let [k (option-key o opts)]
    (or (some? (#{:boolean :bool} (coerce-coerce-fn (get-in opts [:coerce k]))))
        (and k (not (contains? known k))
             (str/starts-with? (kw->str k) "no-")))))

(defn- normalize-value-candidate [c]
  (cond
    (map? c) (update c :value (fn [v] (if (keyword? v) (kw->str v) (str v))))
    (keyword? c) {:value (kw->str c)}
    :else {:value (str c)}))

(defn- candidates-for-entry
  "Value candidates for spec `entry` (key `k`): `:complete-fn` (called with
  `{:to-complete :opts :option}`), `:complete`, or a set-valued `:validate`.
  Normalized to `{:value :description}` maps and prefix-filtered against
  `to-complete` (powershell does not filter shell-side)."
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
  "Candidates for the value of option token `prev`. No completion configured
  defaults to the shell's file completion, `:complete false` opts out."
  [spec opts prev to-complete parsed]
  (let [k (option-key prev opts)
        entry (get spec k)]
    (if (or (:complete entry) (:complete-fn entry) (set? (:validate entry)))
      (candidates-for-entry entry k to-complete parsed)
      (when-not (false? (:complete entry))
        [{:file-completion true}]))))

(defn- resolve-completion-opts
  "Resolve completion opts to `[opts aliases known-keys]`, with the spec merged
  in and mapified. `known-keys` matches the parser's own derivation."
  [copts]
  (let [spec (->spec-map (:spec copts))
        opts (assoc (if (seq spec) (merge-opts copts (spec->opts spec copts)) copts)
                    :spec spec)
        aliases (or (:alias opts) (:aliases opts))
        known (set (concat (keys spec)
                           (keys (:coerce opts))
                           (vals aliases)))]
    [opts aliases known]))

(defn- node-completion-opts
  "Effective parse opts at tree `node`, `:spec` merged like dispatch-tree' does
  (dispatch-level < inherited < the node's own)."
  [global-opts inherited node]
  (let [parse-keys [:coerce :alias :aliases :collect :no-keyword-opts :repeated-opts]
        spec (deep-merge (deep-merge (->spec-map (:spec global-opts)) inherited)
                         (->spec-map (:spec node)))]
    (assoc (deep-merge (select-keys global-opts parse-keys)
                       (select-keys node parse-keys))
           :spec spec)))

(defn- repeatable-opt?
  "True when option `k` may appear more than once (collection-valued `:coerce`
  or `:collect`)."
  [opts k]
  (or (coll? (get-in opts [:coerce k]))
      (contains? (:collect opts) k)))

(defn- safe-parse
  "Parse `args` for completion: `:exec-args` dropped so a `:default` does not
  count as already typed, no-op `:error-fn` so partial input (e.g. a missing
  `:require`) still returns the parsed prefix."
  [args opts]
  (try (parse-args args (-> opts
                            (dissoc :exec-args)
                            (assoc :error-fn (fn [_]) ::resolved true)))
       (catch #?(:cljd cljd.core/ExceptionInfo :clj ExceptionInfo :cljs :default) _ {:args nil :opts nil})))

(defn- option-candidates
  "Option candidates completing `to-complete`, minus the single-value options
  already in `parsed`."
  [spec opts aliases known parsed to-complete]
  (let [used (set (remove #(repeatable-opt? opts %) (keys parsed)))]
    (keep (fn [[token k]]
            (when (and (not (:no-doc (get spec k)))
                       (not (used k))
                       (true-prefix? to-complete token))
              {:value token :description (help-first-line (:desc (get spec k)))}))
          (concat (map (fn [k] [(format-long-opt k) k]) known)
                  (map (fn [[a l]] [(format-short-opt a) l]) aliases)))))

(defn- command-candidates
  "Command candidates of `node` completing `to-complete`."
  [node to-complete]
  (keep (fn [[cmd subnode]]
          (when (true-prefix? to-complete cmd)
            {:value cmd :description (help-first-line (:doc subnode))}))
        (cmd-children node)))

(defn- positional-candidates
  "Candidates for the positional being completed at `node`, resolved to its spec
  key via `:args->opts` (the count of `pos-args` is the current index). No value
  completion yields the file-completion marker, `:complete false` opts out."
  [node spec pos-args parsed to-complete]
  (when-let [a->o (seq (:args->opts node))]
    ;; nth on the seq directly: `:args->opts` may be infinite (variadic
    ;; `(cons :foo (repeat :bar))`), so it must not be `vec`'d
    (let [k (nth a->o (count pos-args) nil)
          entry (when k (get spec k))]
      (when k
        (if (or (:complete entry) (:complete-fn entry) (set? (:validate entry)))
          (candidates-for-entry entry k to-complete parsed)
          (when-not (false? (:complete entry))
            [{:file-completion true}]))))))

(defn- descend
  "Walk the completed prefix `tokens` down dispatch tree `tree`, consuming
  commands and options with their values, accumulating `:inherit`-ed spec
  entries like dispatch-tree' does. Returns
  `[[opts aliases known] deepest-node tokens-at-deepest-level end-of-options?]`."
  [tree global-opts tokens]
  (let [inherit-opt (:inherit global-opts)
        resolve-node (fn [inherited node]
                       (resolve-completion-opts (node-completion-opts global-opts inherited node)))]
    (loop [node tree, ropts (resolve-node {} tree), inherited {},
           toks (seq tokens), level [], eoo? false]
      (let [head (first toks)
            [opts _ known] ropts]
        (cond
          (nil? head) [ropts node level eoo?]
          ;; literal `--`: everything after is positional
          (= "--" head) (recur node ropts inherited (next toks) (conj level head) true)
          (and (not eoo?) (get-in node [:cmd head]))
          (let [inherited (merge inherited (inherited-entries (:spec node) inherit-opt))
                child (get-in node [:cmd head])]
            (recur child (resolve-node inherited child) inherited (next toks) [] false))
          (and (not eoo?) (gnu-option? head))
          ;; flags consume one token, other options also their value
          (let [n (if (bool-opt? head opts known) 1 2)]
            (recur node ropts inherited (drop n toks) (into level (take n toks)) eoo?))
          ;; stray positional
          :else (recur node ropts inherited (next toks) (conj level head) eoo?))))))

(defn- split-eq
  "Split a long `--opt=val` token into `[\"--opt\" \"val\"]`, other tokens pass
  through unchanged."
  [token]
  (if (and (str/starts-with? token "--") (str/includes? token "="))
    (str/split token #"=" 2)
    [token]))

(defn #?(:cljd ^:no-doc complete-tree* :squint ^:no-doc complete-tree* :default ^:private complete-tree*)
  "Returns completion candidate maps (`{:value :description}`) for dispatch tree
  `cmd-tree` and `args` (a vector of tokens, last = the token being completed).
  `global-opts` are the dispatch-level opts, accepted at every level like
  dispatch accepts them."
  ([cmd-tree args] (complete-tree* cmd-tree args nil))
  ([cmd-tree args global-opts]
   (let [done (vec (mapcat split-eq (butlast args)))
         raw-last (or (last args) "")
         ;; `--opt=val`: complete the value, but emit full `--opt=val` tokens -
         ;; shells match and replace the whole word (bash strips the `--opt=`
         ;; wordbreak prefix back off in the stub)
         [eq-opt eq-val] (let [s (split-eq raw-last)] (when (second s) s))
         [[opts aliases known] node level eoo?] (descend cmd-tree global-opts done)
         spec (:spec opts)
         previous (peek done)]
     (cond
       eoo?
       (let [{parsed :opts pos-args :args} (safe-parse level opts)]
         (positional-candidates node spec pos-args parsed raw-last))
       eq-opt
       (let [{parsed :opts} (safe-parse level opts)]
         ;; no file fallback here: shells complete files against the whole
         ;; `--opt=...` token, which never matches a filename
         (->> (value-candidates spec opts eq-opt eq-val parsed)
              (remove :file-completion)
              (map #(update % :value (fn [v] (str eq-opt "=" v))))))
       ;; previous option awaits a value; parse the tokens before it (no value
       ;; yet) for dependent completion
       (and (gnu-option? previous) (not (bool-opt? previous opts known)))
       (let [{parsed :opts} (safe-parse (vec (butlast level)) opts)]
         (value-candidates spec opts previous raw-last parsed))
       :else
       (let [{parsed :opts pos-args :args} (safe-parse level opts)]
         (concat (when-not (gnu-option? raw-last)
                   (command-candidates node raw-last))
                 (option-candidates spec opts aliases known parsed raw-last)
                 (when-not (gnu-option? raw-last)
                   (positional-candidates node spec pos-args parsed raw-last))))))))

;; The stub a user installs. On each TAB it calls the program back with the
;; hidden `org.babashka.cli/completions complete` command, passing the
;; shell-tokenized words up to the cursor, and renders the
;; `value<TAB>description` lines that come back.
(defn- completion-shell-snippet [shell names]
  ;; `names` are the command names to register completion for (primary first,
  ;; e.g. the :prog plus the script's own file name for dev/path invocations).
  ;; function named after the primary name so multiple CLIs don't collide
  (let [program-name (first names)
        fn (str "_babashka_cli_complete_" (str/replace program-name #"[^a-zA-Z0-9_]+" "_"))
        names-sp (str/join " " names)
        names-csv (str/join "," names)
        names-nu (str "[" (str/join " " (map #(str "\"" % "\"") names)) "]")]
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
    # keep our candidate order. Only on the real compopt builtin (bash 4.4+): on
    # bash 3.2 with bash-completion, compopt is a shim that forwards to `complete`,
    # which rejects -o nosort. The probe is cached: its answer never changes
    if [[ -z ${_babashka_cli_compopt_t+x} ]]; then _babashka_cli_compopt_t=$(type -t compopt); fi
    [[ $_babashka_cli_compopt_t == builtin ]] && compopt -o nosort 2>/dev/null
    local out
    out=$(\"${words[0]}\" org.babashka.cli/completions complete --shell bash -- \"${words[@]:1:cword}\" 2>/dev/null)
    # candidates come back already prefix-filtered; insert them verbatim.
    # read -r keeps them out of word splitting and pathname expansion (an
    # unquoted loop would glob a '*.txt' candidate against the cwd), printf %q
    # escapes spaces/quotes for insertion
    local line v
    while IFS= read -r line; do
        [[ -n $line ]] || continue
        if [[ $line == org.babashka.cli/file-completion ]]; then
            compopt -o filenames 2>/dev/null
            while IFS= read -r v; do
                [[ -n $v ]] && COMPREPLY+=( \"$v\" )
            done < <(compgen -f -- \"$cur\")
        else
            v=${line%%$'\\t'*}
            printf -v v '%q' \"$v\"
            COMPREPLY+=( \"$v\" )
        fi
    done <<< \"$out\"
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
complete -F " fn " " names-sp "
")
    :zsh (str "#compdef " names-sp "
" fn "() {
    local -a lines described
    lines=(\"${(@f)$(\"${words[1]}\" org.babashka.cli/completions complete --shell zsh -- \"${(@)words[2,CURRENT]}\" 2>/dev/null)}\")
    local do_files= l v d
    for l in $lines; do
        if [[ $l == org.babashka.cli/file-completion ]]; then do_files=1; continue; fi
        v=\"${l%%$'\\t'*}\"; d=
        [[ $l == *$'\\t'* ]] && d=\"${l#*$'\\t'}\"
        # _describe eats backslashes and splits on ':', so escape both
        v=\"${v//\\\\/\\\\\\\\}\"; d=\"${d//\\\\/\\\\\\\\}\"
        v=\"${v//:/\\\\:}\"; d=\"${d//:/\\\\:}\"
        described+=(\"$v${d:+:$d}\")
    done
    local ret=1
    # claim success whenever we produced candidates: _describe's own exit status
    # is not reliably 0 under a user matcher-list / multi-completer setup, and a
    # non-zero return makes zsh retry other completers (_match, _approximate, ...)
    # and re-list everything with detached descriptions
    (( $#described )) && { _describe -t values completion described; ret=0; }
    [[ -n $do_files ]] && { _files; ret=0; }
    return $ret
}
# register the bare name(s); zsh's _normal completes ./name and /abs/name via the basename
compdef " fn " " names-sp "
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
            # printf, not echo: echo eats a bare -n/-e candidate as its own flag
            printf '%s\\n' $line
        end
    end
end
" (str/join "\n" (map #(str "complete --command " % " --no-files --arguments \"(" fn ")\"") names)) "
")
    :powershell (str "Register-ArgumentCompleter -Native -CommandName " names-csv " -ScriptBlock {
    param($wordToComplete, $commandAst, $cursorPosition)
    $exe = $commandAst.CommandElements[0].Value
    $toks = @()
    for ($i = 1; $i -lt $commandAst.CommandElements.Count; $i++) {
        $el = $commandAst.CommandElements[$i]
        if ($el.Extent.StartOffset -ge $cursorPosition) { break }
        $toks += $el.Extent.Text
    }
    # a fresh word is signaled via --fresh, not a trailing '' token: PowerShell 5.1
    # and pwsh with legacy native argument passing drop empty-string arguments
    $fresh = if ($wordToComplete) { 'false' } else { 'true' }
    $lines = @(& $exe org.babashka.cli/completions complete --shell powershell --fresh $fresh -- $toks 2>$null)
    $lines | Where-Object { $_ -ne 'org.babashka.cli/file-completion' } | ForEach-Object {
        $parts = $_ -split \"`t\", 2
        $tip = if ($parts.Length -gt 1) { $parts[1] } else { $parts[0] }
        $text = $parts[0]
        if ($text -match '\\s') { $text = \"'\" + ($text -replace \"'\", \"''\") + \"'\" }
        [System.Management.Automation.CompletionResult]::new($text, $parts[0], 'ParameterValue', $tip)
    }
    if ($lines -contains 'org.babashka.cli/file-completion') {
        [System.Management.Automation.CompletionCompleters]::CompleteFilename($wordToComplete)
    }
}
")
    :nushell (str "# " program-name " tab completion for nushell. Nushell completes external
# commands through one global external completer, so this chains any previously
# configured completer: several CLIs can install side by side
let " fn "_prev = $env.config.completions?.external?.completer?
$env.config.completions.external.enable = true
$env.config.completions.external.completer = {|spans|
    if ($spans | first | path basename) in " names-nu " {
        let res = (do { ^($spans | first) org.babashka.cli/completions complete --shell nushell -- ...($spans | skip 1) } | complete)
        let lines = (if $res.exit_code == 0 { $res.stdout | lines } else { [] })
        if \"org.babashka.cli/file-completion\" in $lines {
            null  # defer to nushell's own file completion
        } else {
            $lines | each {|l|
                let parts = ($l | split row -n 2 \"\\t\")
                if ($parts | length) == 2 {
                    {value: $parts.0, description: $parts.1}
                } else {
                    {value: $parts.0}
                }
            }
        }
    } else if $" fn "_prev != null {
        do $" fn "_prev $spans
    } else {
        null
    }
}
"))))

(defn- print-completions
  "Print one `value<TAB>description` line per candidate (tab and newline
  stripped, they would break the line/field wire format)."
  [candidates]
  (doseq [{:keys [value description]} candidates]
    (let [value (str/replace value #"[\t\n\r]" " ")
          desc (when-not (str/blank? description)
                 (str/replace (first (str/split-lines description)) "\t" " "))]
      (println (if desc (str value \tab desc) value)))))

(defn- eprintln [s]
  #?(:cljs (binding [*print-fn* *print-err-fn*] (println s))
     :default (binding [*out* *err*] (println s))))

(defn- has-parse-opts? [m]
  (some #{:spec :coerce :require :restrict :validate :args->opts :exec-args} (keys m)))

(defn- is-option? [s]
  (and s
       (or (str/starts-with? s "-")
           (str/starts-with? s ":"))))


(defn- command-help-context
  "Given a dispatch `tree`, command path `cmds`, `prog` name and dispatch-level
  `inherit` value, compute everything [[render-help]] needs: the target `:node`,
  its full `:prog` path, the `:inherited` options usable here (aggregated from
  ancestors) and the `:parents` pointers (ancestors with non-inherited options
  that must precede the command).

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
        ;; (those must be given before the command)
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
  in a `dispatch` table or tree.

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
  * `:table`   - a `dispatch` table, or a tree (hand-written or from
                 [[table->tree]]) - see [[dispatch]] (required)
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
  (let [tree (table->tree table)
        ctx (command-help-context tree (vec cmds) prog inherit)]
    (render-help (:node ctx) ctx)))

(defn ^:dynamic *exit-fn*
  "Terminates the process after `dispatch`'s `:help` option prints an *error*
  (unknown/missing command, option error). `--help`/`-h` is not an error - it
  prints help and returns, so it does not call this. Called with a map:

  * `:exit` - exit code (always non-zero, `1`)
  * `:cause` - the dispatch error cause: `:no-match` (unknown command),
    `:input-exhausted` (no command or incomplete multi-word command), or the option cause
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
  #?(:cljd (io/exit exit)
     :clj (System/exit exit)
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
  "Render a terse, helpful message (a string) for a dispatch error.
  It is given the data `dispatch` passes to its `:error-fn`:

  * `:no-match` (unknown command) -> message + commands + hint
  * `:input-exhausted` (no command or incomplete multi-word command) -> message + commands + hint
  *  option error (`:restrict` / `:require` / `:validate` / `:coerce`) -> message
    + usage + hint

  Reads the command tree, `:prog`, `:inherit`, `:dispatch` (the path), and for
  option errors `:msg` (and for `:no-match`, `:wrong-input`) from the data.
  Messages name the option as typed (`--foo`/`-x`), not `:foo`.

  This is the renderer the `:help` option's default `:error-fn` uses (it prints
  this, then calls [[*exit-fn*]]). Call it from a custom `:error-fn` to keep the
  standard message and add your own output. `--help`/`-h` is not an error - it
  goes to the `:help-fn`, rendered by [[format-command-help]]."
  [{:keys [cause dispatch wrong-input msg prog inherit tree]}]
  (let [tree   (table->tree tree)
        path   (or dispatch [])
        ctx-at (fn [p] (command-help-context tree (vec p) prog inherit))
        hint  (str "Run \"" (str/join " " (cons prog path))
                   " --help\" for more information.")
        usage (fn [p]
                (let [{:keys [node prog inherited]} (ctx-at p)]
                  (help-usage-line prog node (or (seq (:spec node))
                                                 (seq inherited)))))
        ;; command-level error (unknown / missing): terse message + the
        ;; available commands + a pointer to --help
        command-error
        (fn [message]
          (let [cmds (help-commands-table (:node (ctx-at path)))]
            (str/join "\n"
                      (concat [message ""]
                              (when (seq cmds)
                                [(str "Commands:\n" (format-table {:rows cmds :indent 2})) ""])
                              [hint]))))]
    (cond
      (= :no-match cause)
      (command-error (str "Unknown command: " wrong-input))

      (= :input-exhausted cause)
      (command-error "No command given.")

      ;; genuine option error (restrict / require / validate / coerce): terse.
      ;; The lib message already names the option the user typed.
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

;; command names to suggest in errors: skip `:no-doc`, same as help and
;; completion hide them
(defn- visible-command-names [cmd-info]
  (mapv first (cmd-children cmd-info)))

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
           ;; thread dispatch context into option-level errors
           ;; (restrict/require/validate/coerce) so an :error-fn can render help:
           ;; the current path, the program name, dispatch-level :inherit, and the
           ;; command tree
           user-error-fn (:error-fn parse-opts)
           ;; With the `:help` option on, an option error (require/validate/restrict/
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
   (let [;; with `:help` on, option errors are deferred (stashed) during the walk
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
       ;; Help wins over any stashed option error.
       (= :help error)
       ((::help-fn opts) (thread-dispatch-context
                          (assoc (select-keys res [:dispatch]) :tree tree)
                          opts))
       ;; an option error was stashed during the walk and help did not win: fire the
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

(defn- drop-lone-eq
  "bash without bash-completion splits `--opt=val` into `--opt` `=` `val`
  (COMP_WORDBREAKS); drop the lone `=` so the value still completes."
  [toks]
  (loop [acc [] s (seq toks)]
    (if-not s
      acc
      (let [t (first s)]
        (if (and (= "=" t) (gnu-option? (peek acc)))
          (recur (if (next s) acc (conj acc "")) (next s))
          (recur (conj acc t) (next s)))))))

(defn- script-basename
  "The file name (no directory) of the script babashka is running, or nil.
  Used to also register completions under the name a script is invoked by
  (e.g. `./my-cli.clj`), so dev/path invocations work without a `:prog`-named
  symlink on PATH."
  []
  (some-> #?(:cljd nil :clj (System/getProperty "babashka.file") :cljs nil)
          (str/split #"[/\\]")
          last))

(defn- completions-command
  "Handle the hidden `org.babashka.cli/completions` command: `snippet` prints
  the per-shell install snippet, `complete` completes the tokens after `--`
  against `tree`. Parsed here, isolated from the dispatch-level opts: a global
  `:require` or `:error-fn` must not be able to fail the completion callback
  (the stub discards stderr, so that failure would be silent)."
  [tree args opts]
  (let [[sub & more] args
        [pre toks] (split-with #(not= "--" %) more)
        {{:keys [shell prog fresh]} :opts}
        ;; --prog may repeat to register several names (aliases); collect to a vec
        (parse-args (vec pre) {:coerce {:shell :keyword :fresh :boolean :prog []}})
        toks (vec (rest toks))]
    (case sub
      "snippet"
      ;; explicit --prog (repeatable) registers only those names; otherwise
      ;; register the :prog and (for dev/path invocations) the script file name.
      ;; The name is used as-is for shell registration (like cobra/clap/argcomplete);
      ;; only the derived completion function name is sanitized, so non-ASCII
      ;; program names (e.g. a CLI named in a non-Latin script) are supported.
      (let [names (if (seq prog)
                    (vec prog)
                    (distinct (filter some? [(:prog opts) (script-basename)])))]
        (cond
          (empty? names)
          (eprintln (str "[babashka.cli] Set :prog in opts, or pass --prog,"
                         " to generate a completion snippet"))
          (not (#{:bash :zsh :fish :powershell :nushell} shell))
          (eprintln (str "[babashka.cli] Unknown --shell " (pr-str shell)
                         ", expected one of: bash zsh fish powershell nushell"))
          :else (print (completion-shell-snippet shell names))))
      "complete"
      (let [toks (cond-> toks
                   (= :bash shell) drop-lone-eq
                   ;; powershell can't pass an empty fresh-word token (PS 5.1
                   ;; drops empty args), so it sends --fresh instead
                   fresh (conj ""))
            cands (complete-tree* tree toks opts)]
        (print-completions (remove :file-completion cands))
        ;; marker line: the stub defers to the shell's own file completer
        (when (some :file-completion cands)
          (println "org.babashka.cli/file-completion")))
      (eprintln (str "[babashka.cli] Expected completions command snippet or complete, got "
                     (pr-str sub))))))

(defn dispatch
  "Command dispatcher.

  Dispatches on longest matching command entry in `table` by matching
  commands to the `:cmds` vector and invoking the corresponding `:fn`.

  Table is in the form:

  ```clojure
  [{:cmds [\"sub_1\" .. \"sub_n\"] :fn f :args->opts [:lib]}
   ...
   {:cmds [] :fn f}]
  ```

  Instead of a table, bb.cli also accepts a tree-shaped format: a map node with
  the root options and a `:cmd` map from command name to child node. Each node
  takes the same keys a table entry does (except `:cmds`):

  ```clojure
  {:spec {:format {:desc \"edn or table\"}}
   :cmd {\"outdated\" {:fn outdated}
         \"cache\"    {:doc \"Manage cache\"
                     :cmd {\"clean\" {:fn clean-cache}}}}}
  ```

  The commands render in help and completions in the order specified. Map literals with
  more than 8 entries lose insertion order, so put a `:cmd-order` (vector of
  child command names) on the map to control which children are shown and in
   what order (like `:order` does for options). A table keeps its entry order
  automatically.

  When a match is found, `:fn` called with the return value of
  [[parse-args]] applied to `args` enhanced with:

  * `:dispatch` - the matching commands
  * `:args` - concatenation of unparsed commands and args
  * `:rest-cmds`: DEPRECATED, this will be removed in a future version

  Use an empty `:cmds` vector to always match or to provide global options.

  For a single-command CLI (no commands), use a one-entry table whose `:cmds`
  is `[]`:

  ```clojure
  (dispatch [{:cmds [] :fn f :spec spec}] args {:prog \"tool\" :help true})
  ```

  Provide an `:error-fn` to deal with non-matches.

  Set `:prog` to the program name shown in help. Provide `:help true` to wire up
  help without `:restrict`:

  * `--help`/`-h` print help for the command they precede and return (no error,
    so the process ends with status 0). This goes through a `:help-fn`.
  * an unknown/missing command or an option error prints a terse message and
    exits non-zero (via [[*exit-fn*]]). This goes through the `:error-fn`.

  Both default handlers can be overridden: pass your own `:help-fn` (called with
  `{:tree :dispatch :prog :inherit}`) and/or `:error-fn`. `dispatch` threads
  `:prog`, `:inherit` and the command tree into the data either receives, so
  they can render without being handed them separately.

  Each entry in the table may have additional [[parse-args]] options.

  For more information and examples, see [README.md](README.md#commands)."
  ([table args]
   (dispatch table args {}))
  ([table args opts]
   (let [;; complete against the same tree the user dispatches with, so
         ;; `--help`/`-h` (injected by `:help`) also show up as completions
         tree (cond-> (table->tree table) (:help opts) inject-help)]
     (if (= "org.babashka.cli/completions" (first args))
       ;; the hidden completions command, handled before (and isolated from) the
       ;; normal dispatch parse. A caller that preprocesses argv before `dispatch`
       ;; must pass this command through.
       (completions-command tree (rest args) opts)
       (if (:help opts)
         (dispatch-tree tree args
                        (assoc opts ::help true
                               ;; default :error-fn = print the message, then exit;
                               ;; :cause is the dispatch cause as-is (:no-match /
                               ;; :input-exhausted / an option cause)
                               :error-fn (or (:error-fn opts)
                                             (fn [{:keys [cause dispatch] :as data}]
                                               (print-command-error data)
                                               (*exit-fn* {:exit 1 :cause cause
                                                           :dispatch dispatch :data data})))
                               ::help-fn (or (:help-fn opts) print-command-help)))
         (dispatch-tree tree args opts))))))
