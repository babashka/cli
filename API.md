# Table of contents
-  [`babashka.cli`](#babashkacli) 
    -  [`auto-coerce`](#auto-coerce) - Auto-coerces <code>s</code> to data
    -  [`coerce`](#coerce) - Coerce string <code>s</code> using <code>f</code>
    -  [`dispatch`](#dispatch) - Subcommand dispatcher.
    -  [`format-opts`](#format-opts)
    -  [`merge-opts`](#merge-opts) - Merges babashka CLI options.
    -  [`parse-args`](#parse-args) - Same as <code>parse-opts</code> but separates parsed opts into <code>:opts</code> and adds
    -  [`parse-cmds`](#parse-cmds) - Parses sub-commands (arguments not starting with an option prefix) and returns a
    -  [`parse-keyword`](#parse-keyword) - Parse keyword from <code>s</code>
    -  [`parse-opts`](#parse-opts) - Parse the command line arguments <code>args</code>, a seq of strings.
    -  [`spec->opts`](#spec->opts) - Converts spec into opts format.
-  [`babashka.cli.exec`](#babashkacliexec) 
    -  [`-main`](#-main) - Main entrypoint for command line usage.
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
<br><sub>[source](https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L69-L91)</sub>
## `coerce`
``` clojure

(coerce s f)
```


Coerce string `s` using `f`. Does not coerce when `s` is not a string.
  `f` may be a keyword (`:boolean`, `:int`, `:double`, `:symbol`,
  `:keyword`) or a function. When `f` return `nil`, this is
  interpreted as a parse failure and throws.
<br><sub>[source](https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L93-L125)</sub>
## `dispatch`
``` clojure

(dispatch table args)
(dispatch table args opts)
```


Subcommand dispatcher.

  Dispatches on first matching command entry in `table`. A match is
  determines by whether `:cmds`, a vector of strings, is a subsequence
  (matching from the start) of the invoked commands.

  Table is in the form:

  ```clojure
  [{:cmds ["sub_1" .. "sub_n"] :fn f :cmds-opts [:lib]}
   ...
   {:cmds [] :fn f}]
  ```

  When a match is found, `:fn` called with the return value of
  [`parse-args`](#parse-args) applied to `args` enhanced with:

  * `:dispatch` - the matching commands
  * `:args` - concatenation of unparsed commands and args
  * `:rest-cmds`: DEPRECATED, this will be removed in a future version

  This function does not throw. Use an empty `:cmds` vector to always match.

  Each entry in the table may have additional [`parse-args`](#parse-args) options.

  Examples: see [README.md](README.md#subcommands).
<br><sub>[source](https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L477-L521)</sub>
## `format-opts`
``` clojure

(format-opts {:keys [spec indent order], :or {indent 2}})
```

<sub>[source](https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L414-L470)</sub>
## `merge-opts`
``` clojure

(merge-opts m & ms)
```


Merges babashka CLI options.
<br><sub>[source](https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L11-L14)</sub>
## `parse-args`
``` clojure

(parse-args args)
(parse-args args opts)
```


Same as [`parse-opts`](#parse-opts) but separates parsed opts into `:opts` and adds
  `:cmds` and `:rest-args` on the top level instead of metadata.
<br><sub>[source](https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L392-L399)</sub>
## `parse-cmds`
``` clojure

(parse-cmds args)
(parse-cmds args {:keys [no-keyword-opts]})
```


Parses sub-commands (arguments not starting with an option prefix) and returns a map with:
  * `:cmds` - The parsed subcommands
  * `:args` - The remaining (unparsed) arguments
<br><sub>[source](https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L178-L188)</sub>
## `parse-keyword`
``` clojure

(parse-keyword s)
```


Parse keyword from `s`. Ignores leading `:`.
<br><sub>[source](https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L57-L62)</sub>
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
  * `:require` - a coll of options that are required
  * `:exec-args` - a map of default args. Will be overridden by args specified in `args`.
  * `:no-keyword-opts` - `true`. Support only `--foo`-style opts (i.e. `:foo` will not work).
  * `:args->opts` - consume unparsed commands and args as options

  Examples:

  ```clojure
  (parse-opts ["foo" ":bar" "1"])
  ;; => {:bar "1", :org.babashka/cli {:cmds ["foo"]}}
  (parse-args [":b" "1"] {:aliases {:b :bar} :coerce {:bar parse-long}})
  ;; => {:bar 1}
  (parse-args ["--baz" "--qux"] {:spec {:baz {:desc "Baz"} :restrict true})
  ;; => throws 'Unknown option --qux' exception b/c there is no :qux key in the spec
  ```
  
<br><sub>[source](https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L206-L390)</sub>
## `spec->opts`
``` clojure

(spec->opts spec)
```


Converts spec into opts format.
<br><sub>[source](https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L160-L176)</sub>
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
<br><sub>[source](https://github.com/babashka/cli/blob/main/src/babashka/cli/exec.clj#L17-L70)</sub>
