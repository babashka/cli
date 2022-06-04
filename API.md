# Table of contents
-  [`babashka.cli`](#babashkacli) 
    -  [`coerce`](#coerce) - Coerce string <code>s</code> using <code>f</code>
    -  [`parse-opts`](#parse-opts) - Parse the command line arguments <code>args</code>, a seq of strings.
-  [`babashka.cli.exec`](#babashkacliexec) 
    -  [`-main`](#-main) - Main entrypoint for command line usage.
# babashka.cli 





## `coerce`
``` clojure

(coerce s f)
```


Coerce string `s` using `f`. Does not coerce when `s` is not a string.
  `f` may be a keyword (`:boolean`, `:int`, `:double`, `:symbol`,
  `:keyword`) or a function. When `f` return `nil`, this is
  interpreted as a parse failure and throws.
<br><sub>[source](https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L6-L33)</sub>
## `parse-opts`
``` clojure

(parse-opts args)
(parse-opts args opts)
```


Parse the command line arguments `args`, a seq of strings.
  Expected format: `["cmd_1" ... "cmd_n" ":k_1" "v_1" .. ":k_n" "v_n"]`.
  Instead of a leading `:` either `--` or `-` may be used as well.

  Return value: a map with parsed opts. Additional data such as
  initial subcommands and remaining args after `--` are available
  under the `:org.babashka/cli` key in the metadata.

  Supported options:
  - `:coerce`: a map of keys to coercion functions that will be applied to parsed `:opts`. See `coerce-vals`.
  - `:aliases`: a map of short names to long names.

  Examples:

  ```clojure
  (parse-opts ["foo" ":bar" "1])
  ;; => {:bar "1", :org.babashka/cli {:cmds ["foo"]}}
  (parse-args [":b" "1] {:aliases {:b :bar} :coerce {:bar parse-long}})
  ;; => {:bar 1}
  ```
  
<br><sub>[source](https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L76-L148)</sub>
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
<br><sub>[source](https://github.com/babashka/cli/blob/main/src/babashka/cli/exec.clj#L22-L60)</sub>
