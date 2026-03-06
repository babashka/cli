# Table of contents
-  [`babashka.cli`](#babashka.cli) 
    -  [`auto-coerce`](#babashka.cli/auto-coerce) - Auto-coerces <code>s</code> to data.
    -  [`coerce`](#babashka.cli/coerce) - Coerce string <code>s</code> using <code>f</code>.
    -  [`dispatch`](#babashka.cli/dispatch) - Subcommand dispatcher.
    -  [`format-opts`](#babashka.cli/format-opts)
    -  [`format-table`](#babashka.cli/format-table)
    -  [`merge-opts`](#babashka.cli/merge-opts) - Merges babashka CLI options.
    -  [`number-char?`](#babashka.cli/number-char?)
    -  [`opts->table`](#babashka.cli/opts->table)
    -  [`pad`](#babashka.cli/pad)
    -  [`pad-cells`](#babashka.cli/pad-cells)
    -  [`parse-args`](#babashka.cli/parse-args) - Same as [<code>parse-opts</code>](#babashka.cli/parse-opts) but separates parsed opts into <code>:opts</code> and adds <code>:cmds</code> and <code>:rest-args</code> on the top level instead of metadata.
    -  [`parse-cmds`](#babashka.cli/parse-cmds) - Parses sub-commands (arguments not starting with an option prefix) and returns a map with: * <code>:cmds</code> - The parsed subcommands * <code>:args</code> - The remaining (unparsed) arguments.
    -  [`parse-keyword`](#babashka.cli/parse-keyword) - Parse keyword from <code>s</code>.
    -  [`parse-opts`](#babashka.cli/parse-opts) - Parse the command line arguments <code>args</code>, a seq of strings.
    -  [`spec->opts`](#babashka.cli/spec->opts) - Converts spec into opts format.
-  [`babashka.cli.exec`](#babashka.cli.exec) 
    -  [`-main`](#babashka.cli.exec/-main) - Main entrypoint for command line usage.
    -  [`main`](#babashka.cli.exec/main)

-----
# <a name="babashka.cli">babashka.cli</a>






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
<p><sub><a href="https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L143-L149">Source</a></sub></p>

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

  When a match is found, `:fn` called with the return value of
  [`parse-args`](#babashka.cli/parse-args) applied to `args` enhanced with:

  * `:dispatch` - the matching commands
  * `:args` - concatenation of unparsed commands and args
  * `:rest-cmds`: DEPRECATED, this will be removed in a future version

  Use an empty `:cmds` vector to always match or to provide global options.

  Provide an `:error-fn` to deal with non-matches.

  Each entry in the table may have additional [`parse-args`](#babashka.cli/parse-args) options.

  For more information and examples, see [README.md](README.md#subcommands).
<p><sub><a href="https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L729-L761">Source</a></sub></p>

## <a name="babashka.cli/format-opts">`format-opts`</a>
``` clojure
(format-opts {:as cfg, :keys [indent], :or {indent 2}})
```
Function.
<p><sub><a href="https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L628-L632">Source</a></sub></p>

## <a name="babashka.cli/format-table">`format-table`</a>
``` clojure
(format-table {:keys [rows indent], :or {indent 2}})
```
Function.
<p><sub><a href="https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L566-L579">Source</a></sub></p>

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
(opts->table {:keys [spec order]})
```
Function.
<p><sub><a href="https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L609-L626">Source</a></sub></p>

## <a name="babashka.cli/pad">`pad`</a>
``` clojure
(pad len s)
```
Function.
<p><sub><a href="https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L544-L544">Source</a></sub></p>

## <a name="babashka.cli/pad-cells">`pad-cells`</a>
``` clojure
(pad-cells rows)
```
Function.
<p><sub><a href="https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L546-L552">Source</a></sub></p>

## <a name="babashka.cli/parse-args">`parse-args`</a>
``` clojure
(parse-args args)
(parse-args args opts)
```
Function.

Same as [`parse-opts`](#babashka.cli/parse-opts) but separates parsed opts into `:opts` and adds
  `:cmds` and `:rest-args` on the top level instead of metadata.
<p><sub><a href="https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L515-L522">Source</a></sub></p>

## <a name="babashka.cli/parse-cmds">`parse-cmds`</a>
``` clojure
(parse-cmds args)
(parse-cmds args {:keys [no-keyword-opts]})
```
Function.

Parses sub-commands (arguments not starting with an option prefix) and returns a map with:
  * `:cmds` - The parsed subcommands
  * `:args` - The remaining (unparsed) arguments
<p><sub><a href="https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L207-L217">Source</a></sub></p>

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

Parse the command line arguments `args`, a seq of strings.
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
  (parse-opts ["foo" ":bar" "1"])
  ;; => {:bar "1", :org.babashka/cli {:cmds ["foo"]}}
  (parse-opts [":b" "1"] {:aliases {:b :bar} :coerce {:bar parse-long}})
  ;; => {:bar 1}
  (parse-opts ["--baz" "--qux"] {:spec {:baz {:desc "Baz"}} :restrict true})
  ;; => throws 'Unknown option --qux' exception b/c there is no :qux key in the spec
  ```
  
<p><sub><a href="https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L267-L513">Source</a></sub></p>

## <a name="babashka.cli/spec->opts">`spec->opts`</a>
``` clojure
(spec->opts spec)
(spec->opts spec {:keys [exec-args]})
```
Function.

Converts spec into opts format. Pass existing opts as optional second argument.
<p><sub><a href="https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L184-L205">Source</a></sub></p>

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
