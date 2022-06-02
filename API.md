# Table of contents
-  [`babashka.cli`](#babashkacli) 
    -  [`coerce`](#coerce) - Coerce string <code>s</code> using <code>f</code>
    -  [`coerce-vals`](#coerce-vals) - Coerce vals of map <code>m</code> using <code>mapping</code>, a map of keys to functions, using `coerc
    -  [`parse-args`](#parse-args) - Parse the command line arguments <code>args</code>, a seq of strings in the format `["cmd_1
-  [`babashka.cli.exec`](#babashkacliexec) 
    -  [`-main`](#-main) - <code></code>
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


Parse the command line arguments `args`, a seq of strings in the format `["cmd_1" ... "cmd_n" ":k_1" "v_1" .. ":k_n" "v_n"]`.

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

<br><sub>[source](https://github.com/babashka/cli/blob/main/src/babashka/cli.cljc#L19-L45)</sub>
# babashka.cli.exec 





## `-main`
``` clojure

(-main & [f & args])
```


``
  (ns my-ns (:require [babashka.cli.exec :refer [-main]]))

  (defn foo
    {:babashka/cli {:coerce {:b parse-long}}}
    [{:keys [b]}] {:b b})

  (-main "my-ns/foo" ":b" "1") ;;=> {:b 1}
  ```
<br><sub>[source](https://github.com/babashka/cli/blob/main/src/babashka/cli/exec.clj#L4-L22)</sub>
