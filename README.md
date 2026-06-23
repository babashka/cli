# babashka.cli

[![Clojars Project](https://img.shields.io/clojars/v/org.babashka/cli.svg)](https://clojars.org/org.babashka/cli)
[![bb built-in](https://raw.githubusercontent.com/babashka/babashka/master/logo/built-in-badge.svg)](https://book.babashka.org#badges)

Turn Clojure functions into Command Line Interfaces! This library can be used from:
- [babashka](https://github.com/babashka/babashka) - included as a built-in library
- [Clojure on the JVM](https://www.clojure.org/guides/install_clojure) - we support Clojure 1.10.3 and above on Java 11 and above
- [ClojureScript](https://clojurescript.org) - we test against the current release
- [ClojureDart](https://github.com/Tensegritics/ClojureDart) - we test against the current release

## [API](API.md)

## Installation

For Clojure, ClojureScript, and ClojureDart include a `:deps` entry in your  `deps.edn` file:

``` clojure
org.babashka/cli {:mvn/version "<latest-version>"}
```

For babashka, no changes are needed; `org.babashka/cli` is a babashka built-in library.

## Intro

Turn a Clojure function into a CLI that takes Unix-style command line arguments. E.g.:

```shell
$ program command --verbose --long-opt1 v1 -o v2 arg
```

Where:
- `program` is your executable program, which will be launched by Clojure, ClojureScript, babashka, or the native target created by ClojureDart. (other libraries might call this "command")
- `command` is a single or multi-word command for your program (other libraries might call this "subcommand")
- `--verbose` is a boolean option
- `--long-opt1 v1` is an option
- `-o v2` is a short option
- `arg` is a positional argument

See [Terminology](#terminology) for precise definitions.

The main ideas:

- Put as little effort as possible into turning a Clojure function into a CLI,
  similar to `-X` exec style invocations. For lazy people like me! If you are not
  familiar with `clj -X`, read the docs
  [here](https://clojure.org/reference/clojure_cli#use_fn).
- But with a better user experience by not having to use quotes on the command line as a
  result of having to pass EDN directly: `--dir foo` instead of `:dir '"foo"'` (or
  who knows how to write the latter in Windows' `cmd.exe` or Powershell?).
- By default, employ an open world assumption: passing extra arguments does not break, and arguments
  can be reused in multiple contexts.
- But also support incremental restrictions and validations as a way to polish a CLI for production use.

See [clojure CLI](#clojure-cli) for how to turn your `-X` exec functions into CLIs.

## Terminology

```shell
$ program command --verbose --long-opt1 v1 -o v2 arg
```

| Term | Meaning |
| --- | --- |
| `program` | The executable program, typically launched in a terminal |
| `command` | A single or multi-word command for the program. Other libraries often call this a subcommand |
| `option` | A named option, written `--opt val` or short alias `-o val` |
| `flag` | The literal `--opt` part of `--opt val`, `-o` in `-o v2`, or `:opt` _as the user passed it_. We use the word flag both for boolean and non-boolean options. |
| `alias` | A single character alias for the option name |
| `argument` | A positional argument |

> [!NOTE]
> To explain terminology choices, a concrete example:
> ```shell
> git remote show
> ```
> Some CLI libraries call `git` the `command`, and `remote show` the `subcommand`.
> These same libraries typically use the term `command` when describing `remote show`  in usage help.
> To keep terminology consistent for CLI users and CLI developers, we avoid `subcommand` entirely.
> We use `program` for `git` and `command` for `remote show`.

## Projects using babashka CLI

- [jet](https://github.com/borkdude/jet)
- [http-server](https://github.com/babashka/http-server)
- [neil](https://github.com/babashka/neil)
- [quickdoc](https://github.com/borkdude/quickdoc#clojure-cli)
- [clj-new](https://github.com/seancorfield/clj-new#babashka-cli)
- [deps-new](https://github.com/seancorfield/deps-new#babashka-cli)

## TOC

- [Simple example](#simple-example)
- [Options](#options)
- [Arguments](#arguments)
- [Commands](#commands)
- [Completions](#completions)
- [Adding Production Polish](#adding-production-polish)
- [Babashka tasks](#babashka-tasks)
- [Clojure CLI](#clojure-cli)
- [Leiningen](#leiningen)

## Simple example

Babashka CLI works in Clojure, ClojureScript, [Babashka](https://book.babashka.org/) and [ClojureDart](https://github.com/tensegritics/ClojureDart).
Here is an example babashka script to get you started. We'll write a small
stand-in for `git`. Save it to `mygit.clj`.

```clojure
#!/usr/bin/env bb
(require '[babashka.cli :as cli]
         '[babashka.fs :as fs])

(defn dir-exists? [path]
  (fs/directory? path))

(def spec
  {:depth {:coerce :long
           :alias :d                  ; adds -d alias for --depth
           :desc "Number of commits to fetch"
           :validate pos?             ; tests if supplied --depth > 0
           :require true}             ; --depth,-d is required
   :dir   {:alias :C                  ; like git's own -C
           :desc "Run as if git was started in <dir>"
           :validate                  ; tests if --dir exists,
           {:pred dir-exists?         ; with a custom error message
            :ex-msg (fn [{:keys [value]}]
                      (str "Directory does not exist: " value))}}
   :bare  {:coerce :boolean           ; defines a boolean flag
           :desc "Create a bare repository"}})

(defn run [{:keys [opts]}]
  (println "Here are your cli args!:" opts))

(defn -main [& args]
  (cli/dispatch {:fn run :spec spec} args {:prog "mygit" :help true}))

(apply -main *command-line-args*)
```

The `:help true` option supplied to `dispatch` wires up automatic `--help`/`-h` support and terse error messages (as opposed to thrown exceptions) for you.

Let's request usage help:
```
$ bb mygit.clj --help
Usage: mygit [options]

Options:
  -d, --depth  Number of commits to fetch (required)
  -C, --dir    Run as if git was started in <dir>
      --bare   Create a bare repository
  -h, --help   Show this help
```
See [Commands > Help](#help) to customize help.

Let's try running with some options:
```
$ bb mygit.clj --depth 1 --dir my_dir --bare
Error: Directory does not exist: my_dir

Usage: mygit [options]

Run "mygit --help" for more information.
```
The directory was validated with `dir-exists?`. Because the directory does not exist, you see the custom error message produced by the `:ex-msg` function.

Let's create `my_dir`, then try again:
```
$ mkdir my_dir
$ bb mygit.clj --depth 1 --dir my_dir --bare
Here are your cli args!: {:depth 1, :dir my_dir, :bare true}
```
All validations passed, and the `run` function was invoked.

The `:depth` option includes `:require true`, let's see what happens when we don't include it on the command line:
```
$ bb mygit.clj
Error: Required option: --depth

Usage: mygit [options]

Run "mygit --help" for more information.
```
We, appropriately, get a terse error message and an exit status of 1.

### Adding commands

To add commands to this CLI, we need to specify a command structure. We'll just give an example here. See [Commands](#commands) for more info.

Alter `mygit.clj`:
``` clojure
;; renamed from `run`
(defn clone [{:keys [opts]}]
  (println "Here are your cli args!:" opts))

;; new
(defn version [_]
  (println "mygit 1.0"))

;; new
(def tree
  {:cmd {"clone"   {:fn clone   :doc "Clone a repository" :spec spec}
         "version" {:fn version :doc "Print version"}}})

;; updated to use `tree` command structure
(defn -main [& args]
  (cli/dispatch tree args {:prog "mygit" :help true}))
```

`--help` now lists the available commands:
```
$ bb mygit.clj --help
Usage: mygit [options] <command>

Commands:
  clone   Clone a repository
  version Print version

Options:
  -h, --help  Show this help

Run "mygit <command> --help" for more information on a command.
```

The `clone` command calls the `clone` function:
```
$ bb mygit.clj clone --depth 1 --bare
Here are your cli args!: {:depth 1, :bare true}
```

The `version` command calls the `version` function:
```
$ bb mygit.clj version
mygit 1.0
```

See [Commands](#commands) for shared options, inheritance, and help customization.

## Options

If you'd like to parse options yourself (instead of using [`dispatch`](/API.md#dispatch)),
use either lower-level [`parse-opts`](/API.md#parse-opts) or [`parse-args`](/API.md#parse-args). We will
use these parse functions in this section to demonstrate how options parsing works.

On the command line, a named option is written as `--opt val` or short-form [alias](#aliases) `-o val`.

Options are configured with a [spec](#spec) (short for "options specification", not
`clojure.spec`): a map keyed by option name, each value a map of `:coerce`,
`:alias`, `:validate`, `:require`, `:desc`, etc., passed under `:spec`:

``` clojure
{:spec {:port {:coerce :long :alias :p}}}
```

A terser shape is also supported, where each key is lifted to the top level and
keyed by option name: `{:coerce {:port :long} :alias {:p :port}}`. It is handy
for quick scripts and partial parsing, but only a spec can carry `:desc`/`:ref`,
so generated help and option printing need a spec. The two are otherwise
equivalent. The examples below use the spec shape.

Examples:

Parse `{:port 1339}` from command line arguments:

``` clojure
(require '[babashka.cli :as cli])

(cli/parse-opts ["--port" "1339"] {:spec {:port {:coerce :long}}})
;;=> {:port 1339}
```

Use an alias (short option):

``` clojure
(cli/parse-opts ["-p" "1339"] {:spec {:port {:coerce :long :alias :p}}})
;; {:port 1339}
```

Coerce values into a collection:

``` clojure
(cli/parse-opts ["--paths" "src" "--paths" "test"] {:spec {:paths {:coerce []}}})
;;=> {:paths ["src" "test"]}

(cli/parse-opts ["--paths" "src" "test"] {:spec {:paths {:coerce []}}})
;;=> {:paths ["src" "test"]}
```

Transforming into a collection of a certain type:

``` clojure
(cli/parse-opts ["--foo" "bar" "--foo" "baz"] {:spec {:foo {:coerce [:keyword]}}})
;; => {:foo [:bar :baz]}
```

In addition to the built-in coercion keywords, `:coerce` accepts any function (called
with the option's value as a string):

``` clojure
(cli/parse-opts ["--letter" "alpha"] {:spec {:letter {:coerce (fn [s] (subs s 0 1))}}})
;;=> {:letter "a"}
```

Boolean flags are assumed by default, like so:

``` clojure
(cli/parse-opts ["--verbose"])
;;=> {:verbose true}

(cli/parse-opts ["-v" "-v" "-v"] {:spec {:verbose {:alias :v :coerce []}}})
;;=> {:verbose [true true true]}
```

But you can explicitly specify `:boolean` coercion (and will sometimes need to, see [Arguments](#arguments)):

``` clojure
(cli/parse-opts ["--verbose"] {:spec {:verbose {:coerce :boolean}}})
;;=> {:verbose true}

(cli/parse-opts ["-v" "-v" "-v"] {:spec {:verbose {:alias :v :coerce [:boolean]}}})
;;=> {:verbose [true true true]}
```

Long options also support the syntax `--foo=bar`:

``` clojure
(cli/parse-opts ["--foo=bar"])
;;=> {:foo "bar"}
```

Flags may be combined into a single short option:

``` clojure
(cli/parse-opts ["-abc"])
;;=> {:a true :b true :c true}
```

Long options that start with `--no-` are parsed as negative flags:

``` clojure
(cli/parse-opts ["--no-colors"])
;;=> {:colors false}
```
This works for any option. For a boolean option where the negation is meaningful,
set `:negatable true` in its spec to advertise it in help as `--[no-]colors`.


Babashka CLI also accepts a `:`-prefixed form, `:opt val`, to
match the Clojure CLI `-X` invocation style. The two forms cannot be mixed in a single
invocation. Use `--`/`-` or `:`, not both. If you prefer to only allow only `--`/`-` style options, specify `:no-keyword-opts true`:

```clojure
(cli/parse-args [":foo" "bar"])
;; => {:opts {:foo "bar"}}

(cli/parse-args [":foo" "bar"] {:no-keyword-opts true})
;; => {:args [":foo" "bar"], :opts {}}

(cli/parse-args ["--foo" "bar" ":no" "mixing"])
;; => {:args [":no" "mixing"], :opts {:foo "bar"}}
```
Notice how unrecognized options are considered [Arguments](#arguments).

### Spec

A spec (short for options specification, not `clojure.spec`) is a map keyed by option
name; each value configures one option.
Alongside the parsing keys (`:coerce`, `:alias`, `:validate`, ...), it carries
`:desc`, `:ref`, and `:default-desc` used when printing options (see [Printing options](#printing-options)). For
example:

``` clojure
(def spec {:from   {:ref          "<format>"
                    :desc         "The input format. <format> can be edn, json or transit."
                    :coerce       :keyword
                    :alias        :i
                    :default-desc "edn"
                    :default      :edn}
           :to     {:ref          "<format>"
                    :desc         "The output format. <format> can be edn, json or transit."
                    :coerce       :keyword
                    :alias        :o
                    :default-desc "json"
                    :default      :json}
           :pretty {:desc         "Pretty-print output."
                    :alias        :p}
           :paths  {:desc         "Paths of files to transform."
                    :coerce       []
                    :default      ["src" "test"]
                    :default-desc "src test"}})
```

You can pass the spec to `parse-opts` under the `:spec` key: `(parse-opts args {:spec spec})`, or when using
`dispatch`, in the entry for each command.
An explanation of each key:

- `:ref`: a name that describes the option value, which is typically used as a reference in the description (`:desc`)
- `:desc`: a description of the option.
- `:coerce`: coerce a string value to a type. Built-in keywords: `:boolean`
  (`:bool`), `:int` (`:long`), `:double`, `:number`, `:symbol`, `:keyword`,
  `:string`, `:edn`, `:auto`. A collection collects repeated values: `[]`
  (vector), `#{}` (set) or `()` (list); put a coercion keyword inside the collection to
  coerce each element (e.g., `[:keyword]`, `#{:int}`). A function is also
  accepted: it is called with the option value as a string and returns the coerced value.
- `:alias`: an alternative short name; a synonym for the option name.
- `:default`: default value.
- `:default-desc`: a string representation of the default value.
- `:require`: `true` make this opt required.
- `:validate`: a function used to validate the value of this option (as described
  in the [Validate](#validate) section).
- `:collect`: collect repeated values into a collection (`[]` vector, `#{}` set
  or `()` list), or a function `(fn [coll arg-value] ...)` for custom collection
- `:negatable`: `true` shows a boolean option as `--[no-]name` in help (the `--no-name` form parses regardless)

### Custom collection handling

For those rare cases when you need it, you can use a `:collect` function for custom collection.
Here's an example of parsing out `,` separated multi-arg-values:

``` clojure
(cli/parse-opts ["--foo" "a,b" "--foo=c,d,e" "--foo" "f"]
                {:spec {:foo {:collect (fn [coll arg-value]
                                         (into (or coll [])
                                               (str/split arg-value #",")))}}})
;; => {:foo ["a" "b" "c" "d" "e" "f"]}
```

### Auto-coercion

Babashka CLI auto-coerces values that have no explicit coercion
with [`auto-coerce`](/API.md#auto-coerce):
It automatically tries to convert booleans, numbers, and keywords.

``` clojure
(cli/parse-opts ["--num" "1339" "--kw" ":foo" "--bool" "false" "--str" "bar"])
;; => {:num 1339, :kw :foo, :bool false, :str "bar"}

;; the actual types...:
(->> (cli/parse-opts ["--num" "1339" "--kw" ":foo" "--bool" "false" "--str" "bar"])
     (reduce-kv (fn [m k v]
               (assoc m k [v (type v)]))
             {}))
;; => {:num [1339 java.lang.Long],
;;     :kw [:foo clojure.lang.Keyword],
;;     :bool [false java.lang.Boolean],
;;     :str ["bar" java.lang.String]}
```

### Aliases

An `:alias` specifies a synonym short option name for the option name.

Babashka CLI distinguishes aliases with characters in common, so a way to implement the common `-v`/`-vv` Unix pattern is:
``` clojure
(def spec {:verbose      {:alias :v
                          :desc  "Enable verbose output."}
           :very-verbose {:alias :vv
                          :desc  "Enable very verbose output."}})
```

You get:

```clojure
(cli/parse-opts ["-v"] {:spec spec})
;;=> {:verbose true}

(cli/parse-opts ["-vv"] {:spec spec})
;;=> {:very-verbose true}
```

Another way would be to collect the flags in a vector with `:coerce` (and base verbosity on the size of that vector):

``` clojure
(def spec {:verbose {:alias :v
                     :desc  "Enable verbose output."
                     :coerce []}})

user=> (cli/parse-opts ["-vvv"] {:spec spec})
{:verbose [true true true]}
```

## Arguments

To parse positional arguments, you can use `parse-args` and/or the `:args->opts`
option. E.g., to parse arguments for the `git push` command:

``` clojure
(cli/parse-args ["--force" "ssh://foo"] {:spec {:force {:coerce :boolean}}})
;;=> {:args ["ssh://foo"], :opts {:force true}}

(cli/parse-args ["ssh://foo" "--force"] {:spec {:force {:coerce :boolean}}})
;;=> {:args ["ssh://foo"], :opts {:force true}}
```

Note that babashka CLI can only disambiguate correctly between values for
options and trailing arguments with enough `:coerce` information
available. Without the `:coerce :boolean` info, we get:

``` clojure
(cli/parse-args ["--force" "ssh://foo"])
{:opts {:force "ssh://foo"}}
```

In case of ambiguity `--` may also be used to communicate the boundary between
options and arguments:

``` clojure
(cli/parse-args ["--paths" "src" "test" "--" "ssh://foo"] {:spec {:paths {:coerce []}}})
{:args ["ssh://foo"], :opts {:paths ["src" "test"]}}
```

### :args->opts

To fold positional arguments into the parsed options, you can use `:args->opts`:

``` clojure
(def cli-opts {:spec {:force {:coerce :boolean}} :args->opts [:url]})

(cli/parse-opts ["--force" "ssh://foo"] cli-opts)
;;=> {:force true, :url "ssh://foo"}
```

``` clojure
(cli/parse-opts ["ssh://foo" "--force"] cli-opts)
;;=> {:url "ssh://foo", :force true}
```

If you want to fold a variable number of arguments, you can coerce them into a vector
and specify the variable number of arguments with `repeat`:

``` clojure
(def cli-opts {:spec {:bar {:coerce []}} :args->opts (cons :foo (repeat :bar))})
(cli/parse-opts ["arg1" "arg2" "arg3" "arg4"] cli-opts)
;;=> {:foo "arg1", :bar ["arg2" "arg3" "arg4"]}
```

Options may be interspersed with the positional arguments:

``` clojure
(def cli-opts {:spec {:foo {:coerce :keyword}
                      :bar {:coerce []}
                      :force {:coerce :boolean}}
               :args->opts (cons :foo (repeat :bar))})

(cli/parse-opts ["arg1" "arg2" "--force" "arg3"] cli-opts)
;; => {:foo :arg1, :bar ["arg2" "arg3"], :force true}
```

This also holds for a command leaf in [dispatch](#commands): a command with
variadic `:args->opts` parses options before, among, or after its positional
arguments. Without `:args->opts`, `dispatch` stops at the first positional argument
(to route commands), so trailing options would not be parsed.

## Commands

Babashka CLI handles commands with [dispatch](/API.md#dispatch).

### Single-word Commands

Say we want a CLI with a `copy` command, a `delete` command, and an undocumented `debug` command:

```
$ example copy <file> --dry-run
$ example delete <file> --recursive --depth 3
$ example debug
```

Commands can be specified in two ways: as a tree or a table.
The difference is more apparent for [Multi-word Commands](#multi-word-commands).
We'll use the tree structure for this example, save it to `try_cmds.clj`.

```clojure
(ns try-cmds
  (:require [babashka.cli :as cli]))

(defn copy [{:keys [opts]}]
  (prn :copy opts))

(defn delete [{:keys [opts]}]
  (prn :delete opts))

(def tree
  {:cmd {"copy"   {:fn copy :doc "Copy a file\nMore details here" :args->opts [:file]
                   :spec {:dry-run {:coerce :boolean :desc "Do a dry run"}}}
         "delete" {:fn delete :doc "Delete a file" :args->opts [:file]
                   :spec {:recursive {:coerce :boolean :desc "Recurse"}
                          :depth     {:coerce :long    :desc "Max depth"}}}
         "debug"  {:fn prn :doc "Dump internal state"}}
   ;; specify which commands to show and in what order (we exclude hidden debug command)
   :cmd-order ["copy" "delete"]})

(defn -main [& args]
  (cli/dispatch tree args {:prog "try-cmds" :help true}))
```

The same command structure expressed as a table is:

```clojure
(def table
  [{:cmds ["copy"]   :fn copy   :doc "Copy a file\nMore details here" :args->opts [:file]
    :spec {:dry-run {:coerce :boolean :desc "Do a dry run"}}}
   {:cmds ["delete"] :fn delete :doc "Delete a file" :args->opts [:file]
    :spec {:recursive {:coerce :boolean :desc "Recurse"}
           :depth     {:coerce :long    :desc "Max depth"}}}
   ;; hide debug command from usage help and completions with :no-doc
   {:cmds ["debug"]  :fn prn    :doc "Dump internal state" :no-doc true}])

(defn -main [& args]
  (cli/dispatch table args {:prog "try-cmds" :help true}))
```
The order of the entries in the table does not matter when matching commands, but it is used for `--help`.

Regardless of tree or table format, each command entry accepts any [parse-args](#options) option (`:spec`,
`:args->opts`, `:alias`, `:restrict`, ...).

> [!NOTE]
> If you want to try `try_cmds.clj`  from your terminal:
>
> - For babashka, create `bb.edn` in the same dir:
>     ```clojure
>     {:paths ["."]}
>     ```
>   Then run with `bb -m try-cmds ...` (Our examples below use babashka).
>
> - For Clojure, create a `deps.edn` in the same dir:
>     ```clojure
>     {:paths ["."]  :deps {org.babashka/cli {:mvn/version "<latest-version>"}}}
>     ```
>   Then run with `clojure -M -m try-cmds ...`

`dispatch` matches the given command line args against specified commands and calls the matching entry's `:fn` with the parsed result.
`:help true` wires up `--help`/`-h` and prints terse errors instead of throwing exceptions (see [Help](#help)):

```
$ bb -m try-cmds --help
Usage: try-cmds [options] <command>

Commands:
  copy   Copy a file
  delete Delete a file

Options:
  -h, --help Show this help

Run "try-cmds <command> --help" for more information on a command.
```

The `Commands:` descriptions come from each command entry's `:doc` key.
The first line of `:doc` is used as a summary for the command.

The `debug` command is absent in the help because it is absent from `:cmd-order` (it was suppressed in table format via `:no-doc true`). This also hides `debug` from [completions](#completions).

> [!TIP]
> Like `:cmd-order`, options can be hidden with `:order`.
> And `:no-doc true` can also be used on an option to hide it.
> Hiding works well for deprecated or internal commands and options.

The full text of `:doc` is shown as the description on the command's `--help` output, between the usage line and `Options:`:

```
$ bb -m try-cmds copy --help
Usage: try-cmds copy [options] <file>

Copy a file
More details here

Options:
      --dry-run  Do a dry run
  -h, --help     Show this help
```

Running `bb -m try-cmds copy the-file --dry-run` calls `copy`, which prints:

``` clojure
:copy {:file "the-file", :dry-run true}
```

The `copy` command entry `:fn` is called with a map of the parsed result:

- `:opts`: the parsed options (`{:file "the-file" :dry-run true}`; `:file` comes
  from `:args->opts`)
- `:dispatch`: the matched command path `["copy"]` from the given `dispatch` command structure.
- `:args`: any leftover positional args (`nil` here)

An unknown or missing command prints a terse message and exits the process with a status of 1:

```
$ bb -m try-cmds bogus
Unknown command: bogus

Commands:
  copy   Copy a file
  delete Delete a file

Run "try-cmds --help" for more information.
```

### Multi-word Commands

Sometimes a command can be made up of multiple words.
Think of `git`, for example. We have `git remote`, `git remote add ...`, `git remote delete ...`, etc.

The command hierarchy is:
- `remote` (level 1) is a parent of both:
  - `remote add` (level 2)
  - `remote delete` (level 2)

A `dispatch` tree might be expressed as:
```clojure
{:cmd {"remote"
       {:fn remotes-list :doc "show list of remotes"
        :cmd {"add"   {:fn remote-add    :doc "add a new remote"}
              "delete" {:fn remote-delete :doc "delete a remote"}}
        :cmd-order ["add" "delete"]}}}
```

The same commands, expressed as a `dispatch` table:
```clojure
[{:cmds ["remote"]          :fn remotes-list  :doc "show list of remotes"}
 {:cmds ["remote" "add"]    :fn remote-add    :doc "add a new remote"}
 {:cmds ["remote" "delete"] :fn remote-delete :doc "delete a remote"}]
```

Multi-word commands are matched from the command line in the specified order, and the longest matching entry's `:fn` is called.
So `git remote add` would result in a call to the `remote-add` `:fn` and not the `remotes-list` `:fn`.

A command line can have options before and between commands and command hierarchy levels.

Root-level options are specified in the root spec.
A contrived example to illustrate:

``` clojure
(def root-spec {:foo {:coerce #(str "global-" %)}})
(def sub1-spec {:bar {:coerce #(str "sub1-" %)}})
(def sub2-spec {:baz {:coerce #(str "sub2-" %)}})

(def tree
  {:spec root-spec
   :cmd {"sub1" {:fn identity :spec sub1-spec
                 :cmd {"sub2" {:fn identity :spec sub2-spec}}}}})

(cli/dispatch tree ["--foo" "a" "sub1" "--bar" "b" "sub2" "--baz" "c" "arg"])
;; => {:dispatch ["sub1" "sub2"],
;;     :opts {:foo "global-a", :bar "sub1-b", :baz "sub2-c"},
;;     :args ["arg"]}
```

<details>
<summary>For reference, the equivalent command table structure:</summary>

```clojure
(def table
  [{:cmds [] :spec root-spec} ;; root spec specified with `:cmds []`
   {:cmds ["sub1"] :fn identity :spec sub1-spec}
   {:cmds ["sub1" "sub2"] :fn identity :spec sub2-spec}])
```
</details>

Specs are not merged across command hierarchy levels.
An option is parsed with the spec of the current matching command (while parsing the command line from left to right):
- `--foo a` appears before any matching command, so is coerced with `root-spec`
- `--bar b` appears after a matching `sub1` but before `sub2`, so is coerced with `sub1`'s `sub1-spec`
- `--baz c` appears after matching `sub1` and `sub2`, so is coerced with `sub2`s `sub2-spec`

Let's compare with a different ordering on the command line:
```clojure
(cli/dispatch tree ["--foo" "a" "sub1" "sub2" "--bar" "b" "--baz" "c" "arg"])
;; => {:dispatch ["sub1" "sub2"],
;;     :opts {:foo "global-a", :bar "b", :baz "sub2-c"},
;;     :args ["arg"]}
```
Notice that `--bar` is now after `sub1` and `sub2` and gets default string coercion treatment.
This is because it was processed with `sub2-spec`, which has no specific coercion for `:bar`.

Let's explore how `:restrict` works with command hierarchies.
``` clojure
(def tree
  {:cmd {"group"
         {:spec {:registry {}}
          :cmd {"sub"
                {:fn identity :spec {:format {}}}}}}})
```
<details>
<summary>Equivalent table syntax</summary>

```clojure
(def table
  [{:cmds ["group"]       :spec {:registry {}}}
   {:cmds ["group" "sub"] :fn identity :spec {:format {}}}])
```
</details>

Because `:registry` belongs to the `group` command, it is expected only to be used with the `group` command:
```clojure
(cli/dispatch tree ["group" "--registry" "X" "sub"] {:restrict true})
;; => {:dispatch ["group" "sub"], :opts {:registry "X"}, :args nil}
```
and not the `group sub` command:
```clojure
(cli/dispatch tree ["group" "sub" "--registry" "X"] {:restrict true})
;; throws: Unknown option: --registry
```

Mark an option with `:inherit true` to also accept it at any command hierarchy descendant level.
The option is coerced and restrict-checked wherever it appears:
``` clojure
(def tree
  {:cmd {"group"
         {:spec {:registry {:inherit true}} ;; <--
          :cmd {"sub"
                {:fn identity :spec {:format {}}}}}}})

(cli/dispatch tree ["group" "sub" "--registry" "X"] {:restrict true})
;; => {:dispatch ["group" "sub"], :opts {:registry "X"}, :args nil}
```

<details>
<summary>Equivalent table syntax</summary>

```clojure
(def table
  [{:cmds ["group"]       :spec {:registry {:inherit true}}} ;; <--
   {:cmds ["group" "sub"] :fn identity :spec {:format {}}}])
```
</details>

A descendant command may redefine an option in its own spec, in which case the descendant's spec wins.

Instead of marking individual options, you can pass an `:inherit` option to `dispatch`.
Specify `true` to inherit all options, or a set of keys to inherit only those options:

``` clojure
(def tree
  {:cmd {"group"
         {:spec {:registry {}}
          :cmd {"sub"
                {:fn identity :spec {:format {}}}}}}})

(cli/dispatch tree ["group" "sub" "--registry" "X"] {:inherit true})
;; => {:dispatch ["group" "sub"], :opts {:registry "X"}, :args nil}
(cli/dispatch tree ["group" "sub" "--registry" "X"] {:inherit #{:registry}})
;; => {:dispatch ["group" "sub"], :opts {:registry "X"}, :args nil}
```

You can use `:args->opts`, but command matching is always prioritized first:
``` clojure
(def tree
  {:cmd {"sub1"
         {:fn identity :spec sub1-spec :args->opts [:some-opt]
          :cmd {"sub2"
                {:fn identity :spec sub2-spec}}}}})

(cli/dispatch tree ["sub1" "dude"])
;; => {:dispatch ["sub1"], :opts {:some-opt "dude"}, :args nil}
(cli/dispatch tree ["sub1" "sub2"])
;; => {:dispatch ["sub1" "sub2"], :opts {}, :args nil}
```

<details>
<summary>Equivalent table syntax</summary>

```clojure
(def table
  [{:cmds ["sub1"] :fn identity :spec sub1-spec :args->opts [:some-opt]}
   {:cmds ["sub1" "sub2"] :fn identity :spec sub2-spec}])
```
</details>

See [neil](https://github.com/babashka/neil) for a real-world CLI using multi-word commands.

### Command formats
Commands can be specified in a tree or table format.
Both formats are supported; use the format that best suits you.

The difference between formats becomes apparent when multi-word commands are used.
For example, let's say we have a CLI with commands:
- `copy` (hierarchy level 1)
- `cache` (hierarchy level 1)
- `cache clean` (hierarchy level 2)

The table format represents this structure flatly:

```clojure
(def table
  [{:cmds [] :spec {:verbose {:coerce :boolean :inherit true :desc "Verbose output"}}} ;; top-level options
   {:cmds ["copy"] :fn copy :doc "Copy a file" :args->opts [:file]
                   :spec {:dry-run {:coerce :boolean :desc "Do a dry run"}}}
   {:cmds ["cache"] :doc "Manage the cache"}
   {:cmds ["cache" "clean"] :fn clean :doc "Clean the cache"}])

(cli/dispatch table args {:prog "example" :help true})
```

The tree format, as you would guess, uses nesting.
The root accepts a `:spec` for top-level options.
The first level of commands is specified under `:cmd`
in a map of strings to command options, which are the same as in the table
above, minus the `:cmds` entry. You can nest arbitrarily deep.

``` clojure
(def tree
  {:spec {:verbose {:coerce :boolean :inherit true :desc "Verbose output"}}
   :cmd {"copy" {:fn copy :doc "Copy a file" :args->opts [:file]
                 :spec {:dry-run {:coerce :boolean :desc "Do a dry run"}}}
         "cache" {:doc "Manage the cache"
                  ;; clean is nested under cache
                  :cmd {"clean" {:fn clean :doc "Clean the cache"}}}}})

(cli/dispatch tree args {:prog "example" :help true})
```

The table or tree format can be used interchangeably in `dispatch`,
`format-command-help` and the like.

You'll want consistent ordering for help output.
The tree format uses a map; the nature of Clojure maps is that they become unordered hash-maps after 8 entries.
You probably don't want to rely on this implementation detail and can explicitly control order with `:cmd-order`.
Commands not mentioned in `:cmd-order` are left out of printed output, but are still callable on the
command line.

``` clojure
{:cmd-order ["copy" "cache"]
 :cmd {"copy" {...}
       "cache" {...}}}
```

### Help

> For a guided walkthrough of automatic help and shell completions, see this
> [blog post](https://blog.michielborkent.nl/babashka-cli-help-and-completions.html).

Pass `:help true` to `dispatch` (and `:prog`, the program name) to add help to a
CLI:

``` clojure
(cli/dispatch tree args {:prog "some-prog" :help true})
```

- `--help`/`-h` alone prints help for all commands (or usage help for a command-less CLI) and exits with status 0.
- `some-command --help`/`some-command -h` prints help for `some-command` and exits with status 0.
This also works for multi-word commands, e.g., `some-prog deps outdated --help` would show help for the `deps outdated` command.
- A mistyped or missing command prints a terse message and exits with a status of 1.
- `-h, --help` is listed in each command's available options, appended last. To control
  the order, give the command entry an `:order` (a vector of option keys); it
  is used verbatim, so you decide the order, which options to list, and whether
  to list `--help` at all (omit `:help` from `:order` to hide it; but note, it will still work).
  An example `dispatch` command entry that lists `--help` first:

  - tree format
    ```clojure
    {:cmd {"foo" {:spec {...} :order [:help :port :verbose]}}}
    ```
  - table format
    ``` clojure
    {:cmds ["foo"] :spec {...} :order [:help :port :verbose]}
    ```

  Without `:order`, the order is taken from the spec (a vec-of-pairs spec keeps
  its order; a map follows its key order, which Clojure does not guarantee), and
  `--help` is appended.
- `--help`/`-h` are reserved when `:help true` is specified (a command may still define its
  own `:help`).
- A command entry's `:epilog` (a string) is rendered verbatim after that command's
  options, for examples, notes or links. Specify it at the root of the commands tree format (or `:cmds []` entry for commands table format) for the top-level help.

The `:help true` option works for a command-less CLI too.
`some-prog --help` then shows Usage + Options:

- tree format
  ```clojure
  (cli/dispatch {:fn run :spec {:port {:coerce :long :desc "Port"}}}
                args
                {:prog "some-prog" :help true})
  ```
- table format
  ``` clojure
  (cli/dispatch [{:cmds [] :fn run :spec {:port {:coerce :long :desc "Port"}}}]
                args
                {:prog "some-prog" :help true})
  ```

`--help`/`-h` are success paths: they print help and return naturally (no exit call), so
your `-main` ends and the process exits with a status of 0, like a normal command. Errors go
through the dynamic `*exit-fn*`, which exits non-zero:

| invocation | outcome |
|---|---|
| `--help` / `-h` | print help, return (status 0), no `*exit-fn*` |
| no command or incomplete multi-word command | terse message, `*exit-fn*` exit 1, `:cause :input-exhausted` |
| unknown command | terse message, `*exit-fn*` exit 1, `:cause :no-match` |
| option error | terse message, `*exit-fn*` exit 1, `:cause` = the babashka.cli cause |

You can include a `:doc` without a `:fn` to describe a grouping of multi-word commands.
For example, `git bisect` is not something we can invoke, but we can get `--help` for it:
```clojure
(cli/dispatch
  {:cmd {"bisect"
         {:doc "general bisect help"
          :cmd {"start" {:fn identity :doc "start the bisect"}
                "good"  {:fn identity :doc "commit is good"}
                "bad"   {:fn identity :doc "commit is bad"}}}}}
  ["bisect" "--help"]
  {:prog "git" :help true})
```
Outputs:
```
Usage: git bisect [options] <command>

general bisect help

Commands:
  start start the bisect
  good  commit is good
  bad   commit is bad

Options:
  -h, --help  Show this help

Run "git bisect <command> --help" for more information on a command.
```

`*exit-fn*` is called on errors, with a map with keys:
- `:exit` exit code
- `:cause` can be `:no-match`, `:input-exhausted`, or an option cause.
- `:dispatch` the matched command
- `:data` raw dispatch error data

The default `*exit-fn*` implementation exits the process (`System/exit` on JVM, `js/process.exit` on Node).
Rebind it to not exit (for tests, REPL use) or to remap codes by `:cause`, for example:

  ``` clojure
  ;; treat a missing command or incomplete multi-word command as success (exit 0) instead of a usage error
  (binding [cli/*exit-fn* (fn [{:keys [exit cause]}]
                            (System/exit (if (= :input-exhausted cause) 0 exit)))]
    (cli/dispatch table args {:prog "example" :help true}))
  ```

You can optionally override help and error handlers via `dispatch` `:help-fn` and `:error-fn` options.

To render the standard help and add to it, call `format-command-help`, the same renderer
the default uses:

``` clojure
(cli/dispatch table args
  {:prog "example" :help true
   :help-fn (fn [{:keys [tree dispatch prog inherit]}]
              (println "my-tool v1.2.3")
              (println (cli/format-command-help
                        {:table tree :cmds dispatch :prog prog :inherit inherit})))})
```

The function `format-command-help` is also usable on its own (without `dispatch`): pass
`:table` (a `dispatch` [table or tree](#tree-format)), `:cmds` (the command
path, default `[]`), `:prog`, and optional `:inherit`. It returns the help
string.

A custom `:error-fn` receives the dispatch error data
(`{:cause :dispatch :prog :inherit :tree :msg ...}`) and is responsible for
exiting (call `*exit-fn*` or exit yourself). To keep the standard terse message
and add to it, call `format-command-error` (the same renderer the default uses)
and exit afterwards:

``` clojure
(cli/dispatch table args
  {:prog "example" :help true
   :error-fn (fn [data]
               (println (cli/format-command-error data))
               (println "See https://example.com/docs")
               (cli/*exit-fn* {:exit 1 :cause (:cause data)}))})
```

## Completions

The `dispatch` function can generate dynamic shell completions for `bash`,
`zsh`, `fish`, `powershell` and `nushell`. Shells call back into your program on
each TAB to generate completions. The `:prog` (program name) value is essential
in the `dispatch` call. The generated snippet registers completion for that
name, so it must match the command you type.

``` clojure
(cli/dispatch table args {:prog "mycli" :help true})
```

Under babashka the snippet also registers the running script's own file name (from
the `babashka.file` system property), so a script invoked directly by path
(`./mycli.clj`, `/abs/mycli.clj`) completes without a `:prog`-named symlink. See
[Developing completions](#developing-completions).

If the installed command has a different name, e.g., when a distro renames it, pass
`--prog <name>` when generating the snippet to register that name instead. `--prog`
may be repeated to register several names (aliases):

``` bash
mycli org.babashka.cli/completions snippet --shell zsh --prog sq
mycli org.babashka.cli/completions snippet --shell zsh --prog squint --prog sq
```

The completions call goes through a hidden `org.babashka.cli/completions`
command group that `dispatch` adds for you. Running `mycli
org.babashka.cli/completions snippet --shell <shell>` prints the install snippet
for that specific shell to stdout. It does not write files or edit your shell
config for you.

Commands and options come with completion support out of the box. Descriptions come from the
same `:desc` (options) and `:doc` (commands) you already write for `--help`. A
`:no-doc` command or option is hidden. Options that already appeared are filtered
out of later suggestions, except repeatable options (e.g., `:coerce [:string]`).

Instructions follow to enable auto-completions in your shell.

### Bash

Add this code to your bash init file:

``` bash
source <(mycli org.babashka.cli/completions snippet --shell bash)
```

Bash completes values only and does not show descriptions. For correct handling of
`=` and `:` inside values, install the bash-completion package, which needs bash 4.1
or newer. The macOS system bash 3.2 still works for the common cases.

### Zsh

Add this to your zsh init file, after `compinit`:

``` bash
source <(mycli org.babashka.cli/completions snippet --shell zsh)
```

or save the output as `_mycli` on your `$fpath`. Option and command
descriptions show inline. Completions also fire when the program is invoked by
path, such as `./mycli`.

### Fish

``` fish
mycli org.babashka.cli/completions snippet --shell fish | source
```

Option and command descriptions show inline. Completion also fires on a path
invocation.

### Powershell

Add this to your `$PROFILE`:

``` powershell
mycli org.babashka.cli/completions snippet --shell powershell | Out-String | Invoke-Expression
```

Descriptions show in menu-completion mode, which you can enable with
`Set-PSReadLineKeyHandler -Key Tab -Function MenuComplete`.

### Nushell

Nushell cannot `source` from a pipe, so save the snippet to a file in the
autoload directory and restart `nu`:

``` nu
mkdir ($nu.user-autoload-dirs | first)
mycli org.babashka.cli/completions snippet --shell nushell | save -f (($nu.user-autoload-dirs | first) | path join "mycli.nu")
```

On nushell versions without autoload dirs, save it anywhere and add
`source <literal path>` to your `config.nu`. Descriptions show in the completion
menu.

Unlike the other shells, nushell has no per-command completion registration. Instead, one
global hook (`$env.config.completions.external.completer`) handles TAB for all
external commands. The snippet does not overwrite a completer you already have
there: it saves the previous one and falls back to it for every command other
than `mycli`, so several tools can install side by side.

### Developing completions

Under babashka, the snippet registers completion for the running script's file
name (read from the `babashka.file` system property) in addition to `:prog`. A
dev build invoked directly completes as-is, by bare name or by path, with no
symlink to the `:prog` name required:

``` bash
./run.clj <TAB>
/abs/path/to/run.clj <TAB>
run.clj <TAB>            # when its directory is on PATH
```

Source the snippet for the shell you are testing, using the install command from
its section above, and re-source it after each change to your CLI so new commands
and options show up.

When `babashka.file` is not set (e.g. running via `clojure` or an uberjar) the
script file name is unknown, so completion is registered for `:prog` only. Make
the build callable under that name on `PATH`, for example with a symlink:

``` bash
ln -sf "$PWD/run.clj" /tmp/mycli                   # name the link :prog
export PATH="/tmp:$PATH"                           # bash and zsh
```

In fish use `set -gx PATH /tmp $PATH`, in nushell
`$env.PATH = ($env.PATH | prepend /tmp)`. On Windows, put a `mycli` wrapper
script on your `PATH` instead of a symlink.

You can also register one or more explicit names by passing `--prog` (repeatable)
when generating the snippet, e.g. `--prog mycli --prog mc`.

Now `mycli <TAB>` completes commands and `mycli sub --<TAB>` its options. To see the
completer's raw output directly, without a shell, call the hidden command
yourself. The tokens after `--` are what the shell would pass on TAB, here the
command `sub` and a `--` to complete its options. It prints one candidate per
line, as the value, a tab, then the description:

``` bash
mycli org.babashka.cli/completions complete --shell zsh -- sub --
```

### Completing option values

To complete an option's value, give it one of:

- `:complete` - a static collection of values (or `{:value .. :description ..}`
  maps)
- A set-valued `:validate`, whose members double as completions
- `:complete-fn` - a function for dynamic completion

``` clojure
{:env   {:coerce :string
         :complete ["dev" "staging" "prod"]}         ; static list
 :level {:coerce :keyword
         :validate #{:local :global :system}}        ; reused as completions
 :branch {:coerce :string
          :complete-fn (fn [{:keys [to-complete opts]}] ; dynamic
                         (git-branches to-complete))}}
```

The `:complete-fn` is called with `{:to-complete <partial> :opts <opts parsed so
far> :option <key>}` and returns values (strings) (or `{:value .. :description
..}` maps). All three sources are prefix-filtered against the partial value for
you.

An option value with none of these defaults to the shell's own file completion.
For a value where file suggestions are not appropriate, you can opt out with
`:complete false`.

Positional arguments mapped with [`:args->opts`](#args-opts) complete in the same
way. A positional resolves to its spec key by position, so the same `:complete`,
`:complete-fn` or set `:validate` on that key completes the positional too. With
`:args->opts [:env]` and `:env {:complete ["dev" "prod"]}`, `mycli deploy <TAB>`
completes `dev`/`prod`.

A positional declared in `:args->opts` with no value completion defaults to the
shell's own file completion the same way. So `:args->opts [:file]` with a bare
`:file` makes `mycli cat <TAB>` complete filenames and `:complete false` opts
out here too.

## Adding Production Polish
Babashka CLI lets you get up and running quickly.
As you move toward production quality, it's helpful to let users know when their inputs are invalid.
Strict validation can be introduced with [:restrict](#restrict), [:require](#require), and [:validate](#validate).

As you add polish, you'll likely make use of a [:spec](#spec) and maybe a custom [:error_fn](#error-handling). Even if your program does not use [commands](#commands), consider using `dispatch` with the `:help true` option (as shown in [Simple Example](#simple-example)) for printed terse error messages (instead of exceptions), and automatic `--help` generation.

## Restrict

Use the `:restrict` option to restrict options to only those explicitly mentioned in configuration:

``` clojure
(cli/parse-args ["--foo"] {:spec {:bar {}} :restrict true})
;;=>
Execution error (ExceptionInfo) at babashka.cli/parse-opts (cli.cljc:357).
Unknown option: --foo
```

## Require

Mark an option required in its [spec](#spec) with `:require true`; parsing throws
when it is not present:

``` clojure
(cli/parse-args ["--foo"] {:spec {:bar {:require true}}})
;;=>
Execution error (ExceptionInfo) at babashka.cli/parse-opts (cli.cljc:363).
Required option: --bar
```

Required options are shown as `(required)` in `--help`, in the slot a default
would otherwise occupy.

## Validate

``` clojure
(cli/parse-args ["--foo" "0"] {:spec {:foo {:validate pos?}}})
Execution error (ExceptionInfo) at babashka.cli/parse-opts (cli.cljc:378).
Invalid value for option --foo: 0
```

To gain more control over the error message, use `:pred` and `:ex-msg`:

``` clojure
(cli/parse-args ["--foo" "0"] {:spec {:foo {:validate {:pred pos? :ex-msg (fn [m] (str "Not a positive number: " (:value m)))}}}})
;;=>
Execution error (ExceptionInfo) at babashka.cli/parse-opts (cli.cljc:378).
Not a positive number: 0
```

## Error handling

By default, an exception will be thrown in the following situations:
- A restricted option is encountered
- A required option is missing
- Validation fails for an option
- Coercion fails for an option

You may supply a custom error handler function with `:error-fn`. The function
will be called with a map containing the following keys:
- `:type` - `:org.babashka/cli` (for filtering out other types of errors).
- `:cause` - one of:
  - `:restrict` - a restricted option was encountered.
  - `:require` - a required option was missing.
  - `:validate` - validation failed for an option.
  - `:coerce` - coercion failed for an option.
- `:msg` - default error message.
- `:option` - the option being parsed when the error occurred.
- `:spec` - the spec passed into `parse-opts` (see the [Spec](#spec) section).

The following keys are present depending on `:cause`:
- `:cause :restrict`
  - `:restrict` - the value of the `:restrict` opt to `parse-args` (see the
    [Restrict](#restrict) section).
- `:cause :require`
  - `:require` - the value of the `:require` opt to `parse-args` (see the
    [Require](#require) section).
- `:cause :validate`
  - `:value` - the value of the option that failed validation.
  - `:validate` - the value of the `:validate` opt to `parse-args` (see the
    [Validate](#validate) section).
- `:cause :coerce`
  - `:value` - the value of the option that failed coercion.

By default, babashka CLI will throw exceptions on errors it detects.
You can do the same from your custom error handler.

For a more polished user experience, you might choose to have your custom error handler print the error and exit. For example:
``` clojure
(cli/parse-opts
 []
 {:spec {:foo {:desc "You know what this is."
         :ref "<val>"
         :require true}}
  :error-fn
  (fn [{:keys [spec type cause msg option] :as data}]
    (if (= :org.babashka/cli type)
      (case cause
        :require
        (println
         (format "Missing required argument:\n%s"
                 (cli/format-opts {:spec (select-keys spec [option])})))
        (println msg))
      (throw (ex-info msg data)))
    (System/exit 1))})
```
would print:

```
Missing required argument:
  --foo <val> You know what this is.
```

You can also choose to collect and then report all detected errors (see `babashka.cli-test/error-fn-test` for an example of this).

## Adding default args

You can supply default args with `:exec-args`:

``` clojure
(cli/parse-args ["--foo" "0"] {:exec-args {:bar 1}})
;;=> {:foo 0, :bar 1}
```

Note that args specified in `args` will override defaults in `:exec-args`:

``` clojure
(cli/parse-args ["--foo" "0" "--bar" "42"] {:exec-args {:bar 1}})
;;=> {:foo 0, :bar 42}
```

## Printing options

Given a [spec](#spec) (like the `from`/`to`/`paths`/`pretty` one above), print
its options with `format-opts`:

``` clojure
(println (cli/format-opts {:spec spec :order [:from :to :paths :pretty]}))
```

This will print:

```
  -i, --from <format>  The input format. <format> can be edn, json or transit. (default: edn)
  -o, --to <format>    The output format. <format> can be edn, json or transit. (default: json)
      --paths          Paths of files to transform. (default: src test)
  -p, --pretty         Pretty-print output.
```

As options can often be reused in multiple commands, you can determine the
order _and_ selection of printed options with `:order`. If you don't want to use
`:order` and simply want to present the options as written, you can also use a
vector of vectors for the spec:

``` clojure
[[:pretty {:desc "Pretty-print output."
           :alias :p}]
 [:paths {:desc "Paths of files to transform."
          :coerce []
          :default ["src" "test"]
          :default-desc "src test"}]]
```

If you need more flexibility, you can also use `opts->table`, which turns a spec into a vector of vectors, representing rows of a table.
You can then use `format-table` to produce a table as returned by `format-opts`.
For example, to add a header row with labels for each column, you could do something like:

``` clojure
(cli/format-table
 {:rows (concat [["alias" "option" "ref" "default" "description"]]
                (cli/opts->table
                 {:spec {:foo {:alias :f, :default "yupyupyupyup", :ref "<foo>"
                               :desc "Thingy"}
                         :bar {:alias :b, :default "sure", :ref "<bar>"
                               :desc "Barbarbar" :default-desc "Mos def"}}}))
  :indent 2})
```

### Terminal width

`format-opts` and `format-table` wrap long descriptions to the terminal width,
aligning continuation lines under the description column:

```
  --copy-resources <resource>  Copy non cljs/cljc files from --paths as
                               resources; a keyword matches by extension,
                               otherwise by regex
```

On by default; `:wrap false` disables it.

The width comes from `:max-width-fn`, a `(fn [cfg] -> width)` defaulting to
`cli/default-width-fn`: on node it reads `process.stdout.columns`; on the JVM it
reads `$COLUMNS` then probes JLine (when on the classpath). Falls back to 80.
Override per call:

``` clojure
(cli/format-opts {:spec spec :max-width-fn (constantly 80)})
```

On the JVM, `default-width-fn` reads the real width via JLine when it is on the
classpath. babashka bundles it, so bb scripts get it for free; without JLine the
width falls back to `$COLUMNS`/80. If you want real-width detection on another
JVM, you can add a JLine provider (FFM is the lightest):

``` clojure
;; deps.edn
org.jline/jline-terminal     {:mvn/version "3.30.4"}
org.jline/jline-terminal-ffm {:mvn/version "3.30.4"}
```

## Babashka tasks

For documentation on babashka tasks, go
[here](https://book.babashka.org/#tasks).

Since babashka `0.9.160`, `babashka.cli` has become a built-in and has better
integration through `-x` and `exec`.  Read about that in the [babashka
book](https://book.babashka.org/#cli).

## Clojure CLI

The Clojure CLI supports [invoking a function with a single map arg](https://clojure.org/reference/clojure_cli#use_fn) via the `-X` command line option.
Because `-X` does no automatic coercion of values, getting the command-line correct can be, at best, awkward on macOS and Linux, and often an exercise of real frustration on Windows.

You can control parsing behavior by adding `:org.babashka/cli` metadata to
Clojure functions. It does not introduce a dependency on `babashka.cli`
itself. Not adding any metadata will result in string values, which in many
cases may already be a reasonable default.

Adding support for babashka CLI will cause less friction with shell usage.
You can support the same function for both `clojure -X` and `clojure -M` style invocations without
writing extra boilerplate.

In your `deps.edn` `:aliases` entry, add:

``` clojure
:exec {:extra-deps {org.babashka/cli {:mvn/version "<latest-version>"}}
       :main-opts ["-m" "babashka.cli.exec"]}
```

Now you can call any function that accepts a map argument. E.g.:

``` clojure
$ clojure -M:exec clojure.core prn :a 1 :b 2
{:a "1", :b "2"}
```

Use `:org.babashka/cli` metadata for coercions:

``` clojure
(ns my-ns)

(defn foo
  {:org.babashka/cli {:coerce {:a :symbol
                               :b :long}}}
  ;; map argument:
  [m]
  ;; print map argument:
  (prn m))
```

``` clojure
$ clojure -M:exec my-ns foo :a foo/bar :b 2 :c vanilla
{:a foo/bar, :b 2, :c "vanilla"}
```

Note that any library can add support for babashka CLI without depending on
babashka CLI.

An example that specializes `babashka.cli` usage to a function:

``` clojure
:prn {:extra-deps {org.babashka/cli {:mvn/version "<latest-version>"}}
      :main-opts ["-m" "babashka.cli.exec" "clojure.core" "prn"]}
```

``` clojure
$ clojure -M:prn --foo=bar --baz
{:foo "bar" :baz true}
```

You can also pre-define the exec function in `:exec-fn`:

``` clojure
:prn {:extra-deps {org.babashka/cli {:mvn/version "<latest-version>"}}
      :exec-fn clojure.core/prn
      :main-opts ["-m" "babashka.cli.exec"]}
```

To alter the parsing behavior of functions you don't control, you can add
`:org.babashka/cli` data in the `deps.edn` alias:

``` clojure
:prn {:deps {org.babashka/cli {:mvn/version "<latest-version>"}}
      :exec-fn clojure.core/prn
      :main-opts ["-m" "babashka.cli.exec"]
      :org.babashka/cli {:coerce {:foo :long}}}
```

``` clojure
$ clojure -M:prn --foo=1
{:foo 1}
```

### [antq](https://github.com/liquidz/antq)

`.clojure/deps.edn` alias:

``` clojure
:antq {:deps {org.babashka/cli {:mvn/version "<latest-version>"}
              com.github.liquidz/antq {:mvn/version "1.7.798"}}
       :paths []
       :main-opts ["-m" "babashka.cli.exec" "antq.tool" "outdated"]
       :org.babashka/cli {:coerce {:skip []}}}
```

On the command line you can now run it with:

``` clojure
$ clj -M:antq --upgrade
```

Note that we are calling the same `outdated` function that you normally call
with `-T`:

``` clojure
$ clj -Tantq outdated :upgrade true
```
even though antq has its own `-main` function.

Note that we added the `:org.babashka/cli {:coerce {:skip []}}` data in the
alias to make sure that `--skip` options get collected into a vector:

``` clojure
clj -M:antq --upgrade --skip github-action
```

vs.

``` clojure
clj -Tantq outdated :upgrade true :skip '["github-action"]'
```

The following projects have added support for babashka CLI. Feel free to add a PR to
list your project as well!

### [clj-new](https://github.com/seancorfield/clj-new#babashka-cli)

### [codox](https://github.com/weavejester/codox)

In `deps.edn` create an alias:

``` clojure
:codox {:extra-deps {org.babashka/cli {:mvn/version "<latest-version>"}
                     codox/codox {:mvn/version "0.10.8"}}
        :exec-fn codox.main/generate-docs
        ;; default arguments:
        :exec-args {:source-paths ["src"]}
        :org.babashka/cli {:coerce {:source-paths []
                                    :doc-paths []
                                    :themes [:keyword]}}
        :main-opts ["-m" "babashka.cli.exec"]}
```

CLI invocation:

``` clojure
$ clojure -M:codox --output-path /tmp/out
```

### [deps-new](https://github.com/seancorfield/deps-new#babashka-cli)

### [kaocha](https://github.com/lambdaisland/kaocha)

In `deps.edn` create an alias:

``` clojure
:kaocha {:extra-deps {org.babashka/cli {:mvn/version "<latest-version>"}
                      lambdaisland/kaocha {:mvn/version "1.66.1034"}}
         :exec-fn kaocha.runner/exec-fn
         :exec-args {} ;; insert default arguments here
         :org.babashka/cli {:alias {:watch :watch?
                                      :fail-fast :fail-fast?}
                            :coerce {:skip-meta :keyword
                                     :kaocha/reporter [:symbol]}}
         :main-opts ["-m" "babashka.cli.exec"]}
```

Now you are able to use kaocha's exec-fn to be used as a CLI:

``` clojure
$ clj -M:kaocha --watch --fail-fast --kaocha/reporter kaocha.report/documentation
```

### [quickdoc](https://github.com/borkdude/quickdoc#clojure-cli)

### [tools.build](https://github.com/clojure/tools.build)

In `deps.edn` create an alias:

``` clojure
:build {:deps {org.babashka/cli {:mvn/version "<latest-version>"}
               io.github.clojure/tools.build {:git/tag "v0.8.2" :git/sha "ba1a2bf"}}
        :paths ["."]
        :ns-default build
        :main-opts ["-m" "babashka.cli.exec"]}
```

Now you can call your build functions as CLIs:

``` clojure
clj -M:build jar --verbose
```

### [tools.deps.graph](https://github.com/clojure/tools.deps.graph)

In `deps.edn` create an alias:

``` clojure
:graph {:deps {org.babashka/cli {:mvn/version "<latest-version>"}
               org.clojure/tools.deps.graph {:mvn/version "1.1.68"}}
        :exec-fn clojure.tools.deps.graph/graph
        :exec-args {} ;; insert default arguments here
        :org.babashka/cli {:coerce {:trace-omit [:symbol]}}
        :main-opts ["-m" "babashka.cli.exec"]}
```

Then invoke on the command line:

``` clojure
clj -M:graph --size --output graph.png
```

## Leiningen

This tool can be used to run clojure exec functions with [lein](https://leiningen.org/).

An example with `clj-new`:

In `~/.lein/profiles.clj` put:

``` clojure
{:clj-1.11 {:dependencies [[org.clojure/clojure "1.11.1"]]}
 :clj-new {:dependencies [[org.babashka/cli "<latest-version>"]
                          [com.github.seancorfield/clj-new "1.2.381"]]}
 :user {:aliases {"clj-new" ["with-profiles" "+clj-1.11,+clj-new"
                             "run" "-m" "babashka.cli.exec"
                             {:exec-args {:env {:description "My project"}}
                              :coerce {:verbose :long
                                       :args []}
                              :alias {:f :force}}
                             "clj-new"]}}}
```

After that you can use `lein clj-new app` to create a new app:

``` clojure
$ lein clj-new app --name foobar/baz --verbose 3 -f
```

<!-- ## Future ideas -->

<!-- ### Command line syntax for `:coerce` and `:collect` -->

<!-- Perhaps this library can consider a command line syntax for `:coerce` and -->
<!-- `:collect`, e.g.: -->

<!-- ``` clojure -->
<!-- $ clj -M:example --skip.0=github-actions --skip.1=clojure-cli -->
<!-- ``` -->

<!-- ``` clojure -->
<!-- $ clj -M:example --lib%sym=org.babashka/cli -->
<!-- ``` -->

<!-- Things to look out for here is if the delimiter works well with bash / zsh / -->
<!-- cmd.exe and Powershell. -->

<!-- ### Merge args from a file -->

<!-- Merge default arguments from a file so you don't have to write them on the command line: -->

<!-- ``` clojure -->
<!-- --org.babashka/cli-defaults=foo.edn -->
<!-- ``` -->

## License

Copyright © 2022-2026 Michiel Borkent

Distributed under the MIT License. See LICENSE.
