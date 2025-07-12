# Table of contents
-  [`babashka.cli`](#babashkacli) 
    -  [`auto-coerce`](#auto-coerce) - Auto-coerces <code>s</code> to data
    -  [`coerce`](#coerce) - Coerce string <code>s</code> using <code>f</code>
    -  [`dispatch`](#dispatch) - Subcommand dispatcher.
    -  [`format-opts`](#format-opts)
    -  [`format-table`](#format-table)
    -  [`merge-opts`](#merge-opts) - Merges babashka CLI options.
    -  [`number-char?`](#number-char?)
    -  [`opts->table`](#opts->table)
    -  [`pad`](#pad)
    -  [`pad-cells`](#pad-cells)
    -  [`parse-args`](#parse-args) - Same as <code>parse-opts</code> but separates parsed opts into <code>:opts</code> and adds
    -  [`parse-cmds`](#parse-cmds) - Parses sub-commands (arguments not starting with an option prefix) and returns a
    -  [`parse-keyword`](#parse-keyword) - Parse keyword from <code>s</code>
    -  [`parse-opts`](#parse-opts) - Parse the command line arguments <code>args</code>, a seq of strings.
    -  [`rows`](#rows)
    -  [`spec->opts`](#spec->opts) - Converts spec into opts format
-  [`babashka.cli.exec`](#babashkacliexec) 
    -  [`-main`](#-main) - Main entrypoint for command line usage.
    -  [`main`](#main)
-  [`scratch`](#scratch) 
    -  [`-main`](#-main-1)
    -  [`dns-get-spec`](#dns-get-spec)
    -  [`dns-spec`](#dns-spec)
    -  [`global-spec`](#global-spec)
    -  [`table`](#table)
# babashka.cli 





## `auto-coerce`
``` clojure

(auto-coerce s)
```


Auto-coerces `s` to data. Does not coerce when `s` is not a string.
  If `s`:
  * is `true` or `false`, it is coerced as boolean
  * starts with number, it is coerced as a number (through `edn/read-string`)
  * starts with `:`, it is coerced as a keyword (through `parse-keyword`)
<br><sub>[source](https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L77-L103)</sub>
## `coerce`
``` clojure

(coerce s f)
```


Coerce string `s` using `f`. Does not coerce when `s` is not a string.
  `f` may be a keyword (`:boolean`, `:int`, `:double`, `:symbol`,
  `:keyword`) or a function. When `f` return `nil`, this is
  interpreted as a parse failure and throws.
<br><sub>[source](https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L143-L149)</sub>
## `dispatch`
``` clojure

(dispatch table args)
(dispatch table args opts)
```


Subcommand dispatcher.

  Dispatches on longest matching command entry in [`table`](#table) by matching
  subcommands to the `:cmds` vector and invoking the correspondig `:fn`.

  Table is in the form:

  ```clojure
  [{:cmds ["sub_1" .. "sub_n"] :fn f :args->opts [:lib]}
   ...
   {:cmds [] :fn f}]
  ```

  When a match is found, `:fn` called with the return value of
  [`parse-args`](#parse-args) applied to `args` enhanced with:

  * `:dispatch` - the matching commands
  * `:args` - concatenation of unparsed commands and args
  * `:rest-cmds`: DEPRECATED, this will be removed in a future version

  Use an empty `:cmds` vector to always match or to provide global options.

  Provide an `:error-fn` to deal with non-matches.

  Each entry in the table may have additional [`parse-args`](#parse-args) options.

  For more information and examples, see [README.md](README.md#subcommands).
<br><sub>[source](https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L728-L760)</sub>
## `format-opts`
``` clojure

(format-opts {:as cfg, :keys [indent], :or {indent 2}})
```

<sub>[source](https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L627-L631)</sub>
## `format-table`
``` clojure

(format-table {:keys [rows indent], :or {indent 2}})
```

<sub>[source](https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L565-L578)</sub>
## `merge-opts`
``` clojure

(merge-opts m & ms)
```


Merges babashka CLI options.
<br><sub>[source](https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L12-L15)</sub>
## `number-char?`
``` clojure

(number-char? c)
```

<sub>[source](https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L53-L55)</sub>
## `opts->table`
``` clojure

(opts->table {:keys [spec order]})
```

<sub>[source](https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L608-L625)</sub>
## `pad`
``` clojure

(pad len s)
```

<sub>[source](https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L543-L543)</sub>
## `pad-cells`
``` clojure

(pad-cells rows)
```

<sub>[source](https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L545-L551)</sub>
## `parse-args`
``` clojure

(parse-args args)
(parse-args args opts)
```


Same as [`parse-opts`](#parse-opts) but separates parsed opts into `:opts` and adds
  `:cmds` and `:rest-args` on the top level instead of metadata.
<br><sub>[source](https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L514-L521)</sub>
## `parse-cmds`
``` clojure

(parse-cmds args)
(parse-cmds args {:keys [no-keyword-opts]})
```


Parses sub-commands (arguments not starting with an option prefix) and returns a map with:
  * `:cmds` - The parsed subcommands
  * `:args` - The remaining (unparsed) arguments
<br><sub>[source](https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L207-L217)</sub>
## `parse-keyword`
``` clojure

(parse-keyword s)
```


Parse keyword from `s`. Ignores leading `:`.
<br><sub>[source](https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L65-L70)</sub>
## `parse-opts`
``` clojure

(parse-opts args)
(parse-opts args opts)
```


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
  (parse-args [":b" "1"] {:aliases {:b :bar} :coerce {:bar parse-long}})
  ;; => {:bar 1}
  (parse-args ["--baz" "--qux"] {:spec {:baz {:desc "Baz"} :restrict true})
  ;; => throws 'Unknown option --qux' exception b/c there is no :qux key in the spec
  ```
  
<br><sub>[source](https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L266-L512)</sub>
## `rows`
<sub>[source](https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L581-L583)</sub>
## `spec->opts`
``` clojure

(spec->opts spec)
(spec->opts spec {:keys [exec-args]})
```


Converts spec into opts format. Pass existing opts as optional second argument.
<br><sub>[source](https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L184-L205)</sub>
# babashka.cli.exec 





## `-main`
``` clojure

(-main & args)
```


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
<br><sub>[source](https://github.com/babashka/cli/blob/main/src/babashka/cli/exec.clj#L89-L102)</sub>
## `main`
``` clojure

(main & args)
```

<sub>[source](https://github.com/babashka/cli/blob/main/src/babashka/cli/exec.clj#L84-L87)</sub>
# scratch 





## `-main`
``` clojure

(-main & args)
```

<sub>[source](https://github.com/babashka/cli/blob/main/src/scratch.clj#L15-L17)</sub>
## `dns-get-spec`
<sub>[source](https://github.com/babashka/cli/blob/main/src/scratch.clj#L8-L8)</sub>
## `dns-spec`
<sub>[source](https://github.com/babashka/cli/blob/main/src/scratch.clj#L7-L7)</sub>
## `global-spec`
<sub>[source](https://github.com/babashka/cli/blob/main/src/scratch.clj#L4-L6)</sub>
## `table`
<sub>[source](https://github.com/babashka/cli/blob/main/src/scratch.clj#L10-L13)</sub>
