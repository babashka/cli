# Table of contents
-  [`babashka.cli`](#babashkacli) 
    -  [`coerce`](#coerce) - Coerce string <code>s</code> using <code>f</code>
    -  [`coerce-vals`](#coerce-vals) - Coerce vals of map <code>m</code> using <code>mapping</code>, a map of keys to functions, using `coerc
    -  [`parse-args`](#parse-args) - Parse the command line arguments <code>args</code>, a seq of strings.
-  [`babashka.cli.exec`](#babashkacliexec) 
    -  [`-main`](#-main) - Main entrypoint for command line usage.
# babashka.cli 





## `coerce`
``` clojure

(coerce s f)
```


Coerce string `s` using `f`. Does not coerce when `s` is not a string.
<br><sub>[source](https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L4-L9)</sub>
## `coerce-vals`
``` clojure

(coerce-vals m mapping)
```


Coerce vals of map `m` using `mapping`, a map of keys to functions, using [`coerce`](#coerce).
<br><sub>[source](https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L11-L17)</sub>
## `parse-args`
``` clojure

(parse-args args)
(parse-args args opts)
```


Parse the command line arguments `args`, a seq of strings.
  Expected format: `["cmd_1" ... "cmd_n" ":k_1" "v_1" .. ":k_n" "v_n"]`.

  Return value: a map of `:cmds` and `:opts`

  Supported options:
  - `:coerce`: a map of keys to coercion functions that will be applied to parsed `:opts`. See [`coerce-vals`](#coerce-vals).

  Examples:
  ``` clojure
  (parse-args ["foo" ":bar" "1])
  ;; => {:cmds ["foo"] :opts {:bar "1"}}
  (parse-args ["foo" ":bar" "1] {:coerce {:b parse-long}})
  ;; => {:cmds ["foo"] :opts {:bar 1}}
  ```

<br><sub>[source](https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L19-L46)</sub>
# babashka.cli.exec 





## `-main`
``` clojure

(-main & [f & args])
```


Main entrypoint for command line usage.
  Expects a fully qualified symbol and zero or more key value pairs.

  Example when used as a clojure CLI alias: ``` clojure -M:exec
  clojure.core/prn :a 1 :b 2 ```
<br><sub>[source](https://github.com/babashka/cli/blob/main/src/babashka/cli/exec.clj#L4-L18)</sub>
