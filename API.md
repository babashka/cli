# Table of contents
-  [`babashka.cli`](#babashkacli) 
    -  [`coerce`](#coerce) - Coerce string <code>s</code> using <code>f</code>
    -  [`coerce-vals`](#coerce-vals) - Coerce vals of map <code>m</code> using <code>mapping</code>, a map of keys to functions.
    -  [`parse-args`](#parse-args) - Parse the command line arguments <code>args</code>, a seq of strings.
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
## `coerce-vals`
``` clojure

(coerce-vals m mapping)
```


Coerce vals of map `m` using `mapping`, a map of keys to functions.
  Uses [`coerce`](#coerce) to coerce values.
<br><sub>[source](https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L35-L42)</sub>
## `parse-args`
``` clojure

(parse-args args)
(parse-args args opts)
```


Parse the command line arguments `args`, a seq of strings.
  Expected format: `["cmd_1" ... "cmd_n" ":k_1" "v_1" .. ":k_n" "v_n"]`.
  Instead of a leading `:` either `--` or `-` may be used as well.

  Return value: a map of `:cmds` and `:opts`

  Supported options:
  - `:coerce`: a map of keys to coercion functions that will be applied to parsed `:opts`. See [`coerce-vals`](#coerce-vals).
  - `:aliases`: a map of short names to long names.

  Examples:
  ``` clojure
  (parse-args ["foo" ":bar" "1])
  ;; => {:cmds ["foo"] :opts {:bar "1"}}
  (parse-args [":b" "1] {:aliases {:b :bar} :coerce {:bar parse-long}})
  ;; => {:cmds [] :opts {:bar 1}}
  ```
  
<br><sub>[source](https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L74-L132)</sub>
# babashka.cli.exec 





## `-main`
``` clojure

(-main & args)
```


Main entrypoint for command line usage.
  Expects a namespace and var name followed by zero or more key value pair arguments.

  Example when used as a clojure CLI alias:
  ``` clojure
  clojure -M:exec clojure.core prn :a 1 :b 2
  ;;=> {:a "1" :b "2"}
  ```
<br><sub>[source](https://github.com/babashka/cli/blob/main/src/babashka/cli/exec.clj#L6-L36)</sub>
