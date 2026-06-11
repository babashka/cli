# Table of contents
-  [`babashka.cli`](#babashka.cli) 
    -  [`*exit-fn*`](#babashka.cli/*exit-fn*) - Terminates the process after <code>dispatch</code>'s <code>:help</code> option prints an *error* (unknown/missing subcommand, flag error).
    -  [`apply-defaults`](#babashka.cli/apply-defaults) - Fills missing keys in <code>m</code> from defaults.
    -  [`auto-coerce`](#babashka.cli/auto-coerce) - Auto-coerces <code>s</code> to data.
    -  [`coerce`](#babashka.cli/coerce) - Coerce string <code>s</code> using <code>f</code>.
    -  [`coerce-opts`](#babashka.cli/coerce-opts) - Coerces values in the map <code>m</code> using the provided configuration.
    -  [`default-width-fn`](#babashka.cli/default-width-fn) - The default <code>:max-width-fn</code> for [<code>format-table</code>](#babashka.cli/format-table)/[<code>format-opts</code>](#babashka.cli/format-opts).
    -  [`dispatch`](#babashka.cli/dispatch) - Subcommand dispatcher.
    -  [`format-command-error`](#babashka.cli/format-command-error) - Render a terse, helpful message (a string) for a dispatch error, given the data <code>dispatch</code> passes to its <code>:error-fn</code>: * <code>:no-match</code> (unknown subcommand) -> message + commands + hint * <code>:input-exhausted</code> (group, no subcommand) -> message + commands + hint * flag error (<code>:restrict</code> / <code>:require</code> / <code>:validate</code> / <code>:coerce</code>) -> message + usage + hint Reads the command tree, <code>:prog</code>, <code>:inherit</code>, <code>:dispatch</code> (the path), and for flag errors <code>:msg</code> (and for <code>:no-match</code>, <code>:wrong-input</code>) from the data.
    -  [`format-command-help`](#babashka.cli/format-command-help) - Render conventional <code>--help</code> text (a string) for the command at path <code>cmds</code> in a <code>dispatch</code> table or tree: <code></code>` Usage: <prog> [options] <command> <description> ; the entry's :doc (first line, then the rest) Commands: ; child commands with their one-line :doc ...
    -  [`format-opts`](#babashka.cli/format-opts)
    -  [`format-table`](#babashka.cli/format-table)
    -  [`merge-opts`](#babashka.cli/merge-opts) - Merges babashka CLI options.
    -  [`number-char?`](#babashka.cli/number-char?)
    -  [`opts->table`](#babashka.cli/opts->table)
    -  [`parse-args`](#babashka.cli/parse-args) - Same as [<code>parse-opts</code>](#babashka.cli/parse-opts) with return data reshaped.
    -  [`parse-cmds`](#babashka.cli/parse-cmds) - Parses sub-commands (arguments not starting with an option prefix).
    -  [`parse-keyword`](#babashka.cli/parse-keyword) - Parse keyword from <code>s</code>.
    -  [`parse-opts`](#babashka.cli/parse-opts) - Returns a map of options parsed from command line arguments <code>args</code>, a seq of strings.
    -  [`parse-opts*`](#babashka.cli/parse-opts*) - Parses CLI <code>args</code> into a raw opts map.
    -  [`spec->opts`](#babashka.cli/spec->opts) - Converts spec into opts format.
    -  [`table->tree`](#babashka.cli/table->tree) - Converts a <code>dispatch</code> table into a tree.
    -  [`validate-opts`](#babashka.cli/validate-opts) - Validates the map <code>m</code> using the provided configuration.
-  [`babashka.cli.exec`](#babashka.cli.exec) 
    -  [`-main`](#babashka.cli.exec/-main) - Main entrypoint for command line usage.
    -  [`main`](#babashka.cli.exec/main)

-----
# <a name="babashka.cli">babashka.cli</a>






## <a name="babashka.cli/*exit-fn*">`*exit-fn*`</a>
``` clojure
(*exit-fn* {:keys [exit]})
```
Function.

Terminates the process after `dispatch`'s `:help` option prints an *error*
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
  (binding [babashka.cli/*exit-fn* (fn [m] (throw (ex-info "exit" m)))]
    ...)
  ```

  Must exit or throw.

  Default: `System/exit` (JVM), `js/process.exit` (Node), `throw` (browser).
<p><sub><a href="https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L1544-L1571">Source</a></sub></p>

## <a name="babashka.cli/apply-defaults">`apply-defaults`</a>
``` clojure
(apply-defaults m)
(apply-defaults m opts)
```
Function.

Fills missing keys in `m` from defaults. Existing keys in `m` win.
  Preserves metadata of `m`.

  Supported options:
  * `:exec-args` - map of defaults. Not subject to `:restrict`.
  * `:spec` - spec; `:default` entries become defaults via `spec->opts`.
<p><sub><a href="https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L455-L471">Source</a></sub></p>

## <a name="babashka.cli/auto-coerce">`auto-coerce`</a>
``` clojure
(auto-coerce s)
```
Function.

Auto-coerces `s` to data. Does not coerce when `s` is not a string.
  If `s`:
  * is `true` or `false`, it is coerced as boolean
  * starts with number, it is coerced as a number (through Clojure's `edn/read-string`)
  * starts with `:`, it is coerced as a keyword (through [`parse-keyword`](#babashka.cli/parse-keyword))
<p><sub><a href="https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L77-L103">Source</a></sub></p>

## <a name="babashka.cli/coerce">`coerce`</a>
``` clojure
(coerce s f)
```
Function.

Coerce string `s` using `f`. Does not coerce when `s` is not a string.
  `f` may be a keyword (`:boolean`, `:int`, `:double`, `:symbol`,
  `:keyword`) or a function. When `f` return `nil`, this is
  interpreted as a parse failure and throws.
<p><sub><a href="https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L149-L155">Source</a></sub></p>

## <a name="babashka.cli/coerce-opts">`coerce-opts`</a>
``` clojure
(coerce-opts m)
(coerce-opts m opts)
```
Function.

Coerces values in the map `m` using the provided configuration.
  Does not coerce values that are not strings.
  Returns a new map with coerced values.

  Supported options:
  * `:coerce` - a map of option (keyword) names to type keywords (optionally wrapped in a collection).
  * `:spec` - a spec of options. See [spec](https://github.com/babashka/cli#spec).
  * `:error-fn` - error handler, called with a map containing `:cause` (`:coerce`), `:msg`, `:option`, `:value`, `:opts`, and `:flag` (when the option was typed).

  `:flag` is the literal option token as it appeared on the command line (e.g.
  `"--foo"`, `"-f"`, or `":foo"`), as opposed to `:option`, the normalized
  keyword (`:foo`). It lets a handler echo what the user actually typed rather
  than reconstruct it. It is omitted when no originating token is known.
<p><sub><a href="https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L285-L360">Source</a></sub></p>

## <a name="babashka.cli/default-width-fn">`default-width-fn`</a>
``` clojure
(default-width-fn _cfg)
```
Function.

The default `:max-width-fn` for [`format-table`](#babashka.cli/format-table)/[`format-opts`](#babashka.cli/format-opts). Receives the
  table cfg map (currently unused, reserved for extension) and returns the terminal
  width or nil: node `process.stdout.columns`, else `$COLUMNS`, else a JLine
  provider probe (clj, when JLine is on the classpath, e.g. babashka), else nil
  (the caller then falls back to 80).
<p><sub><a href="https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L727-L739">Source</a></sub></p>

## <a name="babashka.cli/dispatch">`dispatch`</a>
``` clojure
(dispatch table args)
(dispatch table args opts)
```
Function.

Subcommand dispatcher.

  Dispatches on longest matching command entry in `table` by matching
  subcommands to the `:cmds` vector and invoking the correspondig `:fn`.

  Table is in the form:

  ```clojure
  [{:cmds ["sub_1" .. "sub_n"] :fn f :args->opts [:lib]}
   ...
   {:cmds [] :fn f}]
  ```

  Instead of a table, a tree (the shape [`table->tree`](#babashka.cli/table->tree) produces) is also
  accepted: a map node with the root options and a `:cmd` map from command
  name to child node. Each node takes the same keys a table entry does
  (except `:cmds`):

  ```clojure
  {:spec {:format {:desc "edn or table"}}
   :cmd {"outdated" {:fn outdated}
         "cache"    {:doc "Manage cache"
                     :cmd {"clean" {:fn clean-cache}}}}}
  ```

  Commands render in help and complete in `:cmd` map order. Map literals with
  more than 8 entries lose insertion order, so put a `:cmd-order` (vector of
  child command names) on the node to control which children are shown and in
  what order, like `:order` does for options. A table keeps its entry order
  automatically ([`table->tree`](#babashka.cli/table->tree) records it as `:cmd-order`).

  When a match is found, `:fn` called with the return value of
  [`parse-args`](#babashka.cli/parse-args) applied to `args` enhanced with:

  * `:dispatch` - the matching commands
  * `:args` - concatenation of unparsed commands and args
  * `:rest-cmds`: DEPRECATED, this will be removed in a future version

  Use an empty `:cmds` vector to always match or to provide global options.

  For a single-command CLI (no subcommands), use a one-entry table whose `:cmds`
  is `[]`:

  ```clojure
  (dispatch [{:cmds [] :fn f :spec spec}] args {:prog "tool" :help true})
  ```

  Provide an `:error-fn` to deal with non-matches.

  Set `:prog` to the program name shown in help. Provide `:help true` to wire up
  help without `:restrict`:

  * `--help`/`-h` print help for the command they precede and return (no error,
    so the process ends with status 0). This goes through a `:help-fn`.
  * an unknown/missing subcommand or a flag error prints a terse message and
    exits non-zero (via [`*exit-fn*`](#babashka.cli/*exit-fn*)). This goes through the `:error-fn`.

  Both default handlers can be overridden: pass your own `:help-fn` (called with
  `{:tree :dispatch :prog :inherit}`) and/or `:error-fn`. `dispatch` threads
  `:prog`, `:inherit` and the command tree into the data either receives, so
  they can render without being handed them separately.

  Each entry in the table may have additional [`parse-args`](#babashka.cli/parse-args) options.

  For more information and examples, see [README.md](README.md#subcommands).
<p><sub><a href="https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L1856-L1946">Source</a></sub></p>

## <a name="babashka.cli/format-command-error">`format-command-error`</a>
``` clojure
(format-command-error {:keys [cause dispatch wrong-input msg prog inherit tree]})
```
Function.

Render a terse, helpful message (a string) for a dispatch error, given the
  data `dispatch` passes to its `:error-fn`:

  * `:no-match` (unknown subcommand) -> message + commands + hint
  * `:input-exhausted` (group, no subcommand) -> message + commands + hint
  * flag error (`:restrict` / `:require` / `:validate` / `:coerce`) -> message
    + usage + hint

  Reads the command tree, `:prog`, `:inherit`, `:dispatch` (the path), and for
  flag errors `:msg` (and for `:no-match`, `:wrong-input`) from the data.
  Messages name the flag as typed (`--foo`/`-x`), not `:foo`.

  This is the renderer the `:help` option's default `:error-fn` uses (it prints
  this, then calls [`*exit-fn*`](#babashka.cli/*exit-fn*)). Call it from a custom `:error-fn` to keep the
  standard message and add your own output. `--help`/`-h` is not an error - it
  goes to the `:help-fn`, rendered by [`format-command-help`](#babashka.cli/format-command-help).
<p><sub><a href="https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L1582-L1628">Source</a></sub></p>

## <a name="babashka.cli/format-command-help">`format-command-help`</a>
``` clojure
(format-command-help {:keys [table cmds prog inherit], :or {cmds []}})
```
Function.

Render conventional `--help` text (a string) for the command at path `cmds`
  in a `dispatch` table or tree:

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
                 [`table->tree`](#babashka.cli/table->tree)) - see [`dispatch`](#babashka.cli/dispatch) (required)
  * `:cmds`    - the command path, e.g. `["deps" "outdated"]` (default `[]`)
  * `:prog`    - program name shown in the usage line (required)
  * `:inherit` - only needed when you pass a dispatch-level `:inherit` to
                 `dispatch`; pass the same value so `Inherited options:` matches.
                 Per-option `:inherit true` is detected automatically.

  Options are listed in the entry's `:order` when it has one, else in spec order
  (a vec-of-pairs `:spec` keeps its order; a map follows key order, unreliable
  beyond a few keys - use a vec-of-pairs spec or `:order`).

  This is the renderer the `:help` option uses; call it from a custom `:help-fn`
  to render the standard help and then add your own output. An entry may carry
  `:no-doc true` to be omitted from `Commands:`.
<p><sub><a href="https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L1501-L1542">Source</a></sub></p>

## <a name="babashka.cli/format-opts">`format-opts`</a>
``` clojure
(format-opts {:as cfg, :keys [indent wrap max-width-fn], :or {indent 2, wrap true, max-width-fn default-width-fn}})
```
Function.
<p><sub><a href="https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L897-L904">Source</a></sub></p>

## <a name="babashka.cli/format-table">`format-table`</a>
``` clojure
(format-table
 {:keys [rows indent divider wrap max-width-fn],
  :or {indent 2, divider " ", wrap true, max-width-fn default-width-fn},
  :as cfg})
```
Function.
<p><sub><a href="https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L792-L811">Source</a></sub></p>

## <a name="babashka.cli/merge-opts">`merge-opts`</a>
``` clojure
(merge-opts m & ms)
```
Function.

Merges babashka CLI options.
<p><sub><a href="https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L12-L15">Source</a></sub></p>

## <a name="babashka.cli/number-char?">`number-char?`</a>
``` clojure
(number-char? c)
```
Function.
<p><sub><a href="https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L53-L55">Source</a></sub></p>

## <a name="babashka.cli/opts->table">`opts->table`</a>
``` clojure
(opts->table {:keys [spec order columns]})
```
Function.
<p><sub><a href="https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L841-L861">Source</a></sub></p>

## <a name="babashka.cli/parse-args">`parse-args`</a>
``` clojure
(parse-args args)
(parse-args args opts)
```
Function.

Same as [`parse-opts`](#babashka.cli/parse-opts) with return data reshaped.

  Returns a map with:
  * `:opts` parsed opts
  * `:args` remaining unparsed `args`
<p><sub><a href="https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L668-L678">Source</a></sub></p>

## <a name="babashka.cli/parse-cmds">`parse-cmds`</a>
``` clojure
(parse-cmds args)
(parse-cmds args {:keys [no-keyword-opts]})
```
Function.

Parses sub-commands (arguments not starting with an option prefix). Returns a map with:
  * `:cmds` - The parsed subcommands
  * `:args` - The remaining (unparsed) arguments
<p><sub><a href="https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L200-L210">Source</a></sub></p>

## <a name="babashka.cli/parse-keyword">`parse-keyword`</a>
``` clojure
(parse-keyword s)
```
Function.

Parse keyword from `s`. Ignores leading `:`.
<p><sub><a href="https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L65-L70">Source</a></sub></p>

## <a name="babashka.cli/parse-opts">`parse-opts`</a>
``` clojure
(parse-opts args)
(parse-opts args opts)
```
Function.

Returns a map of options parsed from command line arguments `args`, a seq of strings.
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
  (parse-opts ["foo" ":bar" "1"])
  ;; => ^{:org.babashka/cli {:args ["foo"]}} {:bar 1}
  (parse-opts [":b" "1"] {:aliases {:b :bar} :coerce {:bar parse-long}})
  ;; => {:bar 1}
  (parse-opts ["--baz" "--qux"] {:spec {:baz {:desc "Baz"}} :restrict true})
  ;; => throws 'Unknown option --qux' exception b/c there is no :qux key in the spec
  ```
  See also: [`parse-args`](#babashka.cli/parse-args)
<p><sub><a href="https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L620-L666">Source</a></sub></p>

## <a name="babashka.cli/parse-opts*">`parse-opts*`</a>
``` clojure
(parse-opts* args {:keys [coerce collect no-keyword-opts repeated-opts], :as opts})
```
Function.

Parses CLI `args` into a raw opts map. Returns string values unchanged
  (no coercion), does not apply `:exec-args` defaults, does not run
  `:restrict`/`:require`/`:validate`. Result map includes
  `:org.babashka/cli` metadata and internal `::implicit-true-keys` /
  `::keys-order` metadata used by `coerce-opts`.

  Use this when you want to merge other sources (e.g. config files)
  before coerce/validate. Pipeline: `parse-opts*` -> merge -> `apply-defaults`
  -> `coerce-opts` -> `validate-opts`.

  Supported options (subset of `parse-opts`): `:alias`/`:aliases`, `:coerce`,
  `:collect`, `:no-keyword-opts`, `:repeated-opts`, `:args->opts`, `:spec`.
<p><sub><a href="https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L477-L618">Source</a></sub></p>

## <a name="babashka.cli/spec->opts">`spec->opts`</a>
``` clojure
(spec->opts spec)
(spec->opts spec {:keys [exec-args]})
```
Function.

Converts spec into opts format. Pass existing opts as optional second argument.
<p><sub><a href="https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L177-L198">Source</a></sub></p>

## <a name="babashka.cli/table->tree">`table->tree`</a>
``` clojure
(table->tree table)
```
Function.

Converts a `dispatch` table into a tree. Each `:cmds` becomes a path of
  nested `:cmd` maps; other entry keys are kept on the node. Empty `:cmds`
  merges onto the root. Table order is kept: each node gets a `:cmd-order`
  with its children in first-appearance order (see [`dispatch`](#babashka.cli/dispatch)).

  ```clojure
  (table->tree [{:cmds ["add"] :fn add} {:cmds [] :fn help}])
  ;; => {:fn help, :cmd {"add" {:fn add}}, :cmd-order ["add"]}
  ```
<p><sub><a href="https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L1037-L1050">Source</a></sub></p>

## <a name="babashka.cli/validate-opts">`validate-opts`</a>
``` clojure
(validate-opts m)
(validate-opts m opts)
```
Function.

Validates the map `m` using the provided configuration. Returns `m`.

  Supported options:
  * `:restrict` - `true` or coll of keys. Error on keys in `m` not in the restrict set or not derivable from `:spec` and `:coerce`.
  * `:require` - a coll of options that are required.
  * `:validate` - a map of option keys to validator functions (or maps with `:pred` and `:ex-msg`).
  * `:spec` - a spec of options (restrict, require, validate extracted from it).
  * `:coerce` - used with `:restrict true` to derive the set of known keys.
  * `:error-fn` - error handler, called with a map containing `:cause`, `:msg`, `:option`, `:opts`, and `:flag`.

  `:flag` is the literal option token as it appeared on the command line (e.g.
  `"--foo"`, `"-f"`, or `":foo"`), as opposed to `:option`, the normalized
  keyword (`:foo`). It lets a handler echo what the user actually typed rather
  than reconstruct it. It is present for `:restrict` and `:validate`, and absent
  for `:require` (a missing required option was never typed, so it has no token).
<p><sub><a href="https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L362-L453">Source</a></sub></p>

-----
# <a name="babashka.cli.exec">babashka.cli.exec</a>






## <a name="babashka.cli.exec/-main">`-main`</a>
``` clojure
(-main & args)
```
Function.

Main entrypoint for command line usage.
  Expects a namespace and var name followed by zero or more key value
  pair arguments that will be parsed and passed to the var. If the
  first argument is map-shaped, it is read as an EDN map containing
  parse instructions.

  Example when used as a clojure CLI alias:
  ``` clojure
  clojure -M:exec clojure.core prn :a 1 :b 2
  ;;=> {:a "1" :b "2"}
  ```
<p><sub><a href="https://github.com/babashka/cli/blob/main/src/babashka/cli/exec.clj#L97-L110">Source</a></sub></p>

## <a name="babashka.cli.exec/main">`main`</a>
``` clojure
(main & args)
```
Function.
<p><sub><a href="https://github.com/babashka/cli/blob/main/src/babashka/cli/exec.clj#L92-L95">Source</a></sub></p>
