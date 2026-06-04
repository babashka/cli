# babashka.cli

[![Clojars Project](https://img.shields.io/clojars/v/org.babashka/cli.svg)](https://clojars.org/org.babashka/cli)
[![bb built-in](https://raw.githubusercontent.com/babashka/babashka/master/logo/built-in-badge.svg)](https://book.babashka.org#badges)

Turn Clojure functions into CLIs! This library can be used from:
- [babashka](https://github.com/babashka/babashka) - included as a built-in library
- [Clojure on the JVM](https://www.clojure.org/guides/install_clojure) - we support Clojure 1.10.3 and above on Java 11 and above
- [ClojureScript](https://clojurescript.org) - we test against the current release

## [API](API.md)

## Status

This library is still in design phase and may still undergo breaking changes.
Check [breaking changes](CHANGELOG.md#breaking-changes) before upgrading!

## Installation

Add to your `deps.edn` or `bb.edn` `:deps` entry:

``` clojure
org.babashka/cli {:mvn/version "<latest-version>"}
```

## Intro

Command line arguments in clojure and babashka CLIs are often in the form:

``` clojure
$ cli command :opt1 v1 :opt2 v2
```

or the more Unixy:

``` clojure
$ cli command --long-opt1 v1 -o v2
```

The main ideas:

- Put as little effort as possible into turning a clojure function into a CLI,
  similar to `-X` style invocations. For lazy people like me! If you are not
  familiar with `clj -X`, read the docs
  [here](https://clojure.org/reference/clojure_cli#use_fn).
- But with a better UX by not having to use quotes on the command line as a
  result of having to pass EDN directly: `:dir foo` instead of `:dir '"foo"'` or
  who knows how to write the latter in `cmd.exe` or Powershell.
- By default, employ an open world assumption: passing extra arguments does not break and arguments
  can be re-used in multiple contexts.
- But also support incremental restrictions and validations as a form of polishing a CLI for production use.

Both `:` and `--` are supported as the initial characters of a named option, but
cannot be mixed. See [options](#options) for more details.

See [clojure CLI](#clojure-cli) for how to turn your exec functions into CLIs.

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
- [Subcommands](#subcommands)
- [Adding Production Polish](#adding-production-polish)
- [Babashka tasks](#babashka-tasks)
- [Clojure CLI](#clojure-cli)
- [Leiningen](#leiningen)

## Simple example

Babashka CLI works in Clojure, ClojureScript and [babashka](https://book.babashka.org/).
Here is an example babashka script to get you started!

```clojure
#!/usr/bin/env bb
(require '[babashka.cli :as cli]
         '[babashka.fs :as fs])

(defn dir-exists? [path]
  (fs/directory? path))

(def spec
  {:num  {:coerce :long
          :alias :n                  ; adds -n alias for --num
          :desc "Number of some items"
          :validate pos?             ; tests if supplied --num > 0
          :require true}             ; --num,-n is required
   :dir  {:alias :d
          :desc "Directory name to do stuff"
          :validate dir-exists?}     ; tests if --dir exists
   :flag {:coerce :boolean           ; defines a boolean flag
          :desc "I am just a flag"}})

(defn run [{:keys [opts]}]
  (println "Here are your cli args!:" opts))

(defn -main [& args]
  (cli/dispatch [{:cmds [] :fn run :spec spec}] args {:prog "try-me" :help true}))

(-main *command-line-args*)
```

In the above example, `:help true` wires up automatic `--help`/`-h` support and terse error messages for you. See [Subcommands > Help](#help) for
customizing it.

The CLI uses a table (the first argument to `dispatch`): a vector of command
entries. Each entry's `:cmds` is its subcommand path. This CLI has no
subcommands, so its single entry uses `:cmds []`: a special case of a
multi-subcommand CLI with 0 levels.

And this is how you run it:

```
$ bb try-me.clj --num 1 --dir my_dir --flag
Here are your cli args!: {:num 1, :dir my_dir, :flag true}

$ bb try-me.clj --help
Usage: try-me [options]

Options:
  -n, --num  Number of some items
  -d, --dir  Directory name to do stuff
      --flag I am just a flag
  -h, --help Show this help

$ bb try-me.clj
Error: Required option: --num

Usage: try-me [options]

Run "try-me --help" for more information.
```

## Options

For parsing options, use either [`parse-opts`](/API.md#parse-opts) or [`parse-args`](/API.md#parse-args).

Options are configured with a [spec](#spec) (short for "options spec", not
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

Transforming to a collection of a certain type:

``` clojure
(cli/parse-opts ["--foo" "bar" "--foo" "baz"] {:spec {:foo {:coerce [:keyword]}}})
;; => {:foo [:bar :baz]}
```

Besides the built-in coercion keywords, `:coerce` accepts any function (called
with the string value):

``` clojure
(cli/parse-opts ["--letter" "alpha"] {:spec {:letter {:coerce (fn [s] (subs s 0 1))}}})
;;=> {:letter "a"}
```

Booleans need no explicit `true` value and `:coerce` option:

``` clojure
(cli/parse-opts ["--verbose"])
;;=> {:verbose true}

(cli/parse-opts ["-v" "-v" "-v"] {:spec {:verbose {:alias :v :coerce []}}})
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

Arguments that start with `--no-` arg parsed as negative flags:

``` clojure
(cli/parse-opts ["--no-colors"])
;;=> {:colors false}
```

This works for any option. For a boolean option where the negation is meaningful,
set `:negatable true` in its spec to advertise it in help as `--[no-]colors`.

## Spec

A spec (short for "options spec", not `clojure.spec`) is a map keyed by option
name; each value configures one option.
Alongside the parsing keys (`:coerce`, `:alias`, `:validate`, ...) it carries
`:desc` and `:ref`, used when printing options (see [Printing options](#printing-options)). For
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

You can pass the spec to `parse-opts` under the `:spec` key: `(parse-opts args {:spec spec})`.
An explanation of each key:

- `:ref`: a name which can be used as a reference in the description (`:desc`)
- `:desc`: a description of the option.
- `:coerce`: coerce a string value to a type. Built-in keywords: `:boolean`
  (`:bool`), `:int` (`:long`), `:double`, `:number`, `:symbol`, `:keyword`,
  `:string`, `:edn`, `:auto`. A collection - `[]` (vector), `#{}` (set) or `()`
  (list) - collects repeated values; put a coercion keyword inside it to coerce
  each element (e.g. `[:keyword]`, `#{:int}`). A function is also accepted: it is
  called with the string and returns the value.
- `:alias`: mapping of short name to long name.
- `:default`: default value.
- `:default-desc`: a string representation of the default value.
- `:require`: `true` make this opt required.
- `:validate`: a function used to validate the value of this opt (as described
  in the [Validate](#validate) section).
- `:collect`: collect repeated values into a collection - `[]` (vector), `#{}`
  (set) or `()` (list) - or a function `(fn [coll arg-value] ...)` for custom
  collection
- `:negatable`: `true` shows a boolean option as `--[no-]name` in help (the `--no-name` form parses regardless)

### Custom collection handling

Usually the above will suffice, but for custom transformation to a collection, you can use `:collect`.
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
it automatically tries to convert booleans, numbers and keywords.

### Aliases

An `:alias` specifies a mapping from short to long name.

The library can distinguish aliases with characters in common, so a way to implement the common `-v`/`-vv` unix pattern is:
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
option. E.g. to parse arguments for the `git push` command:

``` clojure
(cli/parse-args ["--force" "ssh://foo"] {:spec {:force {:coerce :boolean}}})
;;=> {:args ["ssh://foo"], :opts {:force true}}

(cli/parse-args ["ssh://foo" "--force"] {:spec {:force {:coerce :boolean}}})
;;=> {:args ["ssh://foo"], :opts {:force true}}
```

Note that this library can only disambiguate correctly between values for
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

If you want to fold a variable amount of arguments, you can coerce into a vector
and specify the variable number of arguments with `repeat`:

``` clojure
(def cli-opts {:spec {:bar {:coerce []}} :args->opts (cons :foo (repeat :bar))})
(cli/parse-opts ["arg1" "arg2" "arg3" "arg4"] cli-opts)
;;=> {:foo "arg1", :bar ["arg2" "arg3" "arg4"]}
```

## Subcommands

To handle subcommands, use
[dispatch](/API.md#dispatch).

Say we want a CLI called as:

```
$ example copy <file> --dry-run
$ example delete <file> --recursive --depth 3
```

Building on the [simple example](#simple-example): there, the single entry used
`:cmds []`. Give each entry a non-empty `:cmds` path and you have subcommands:

``` clojure
(ns example
  (:require [babashka.cli :as cli]))

(defn copy [{:keys [opts]}]
  (prn :copy opts))

(defn delete [{:keys [opts]}]
  (prn :delete opts))

(def table
  [{:cmds ["copy"]   :fn copy   :doc "Copy a file" :args->opts [:file]
    :spec {:dry-run {:coerce :boolean :desc "Do a dry run"}}}
   {:cmds ["delete"] :fn delete :doc "Delete a file" :args->opts [:file]
    :spec {:recursive {:coerce :boolean :desc "Recurse"}
           :depth     {:coerce :long    :desc "Max depth"}}}])

(defn -main [& args]
  (cli/dispatch table args {:prog "example" :help true}))
```

Run it with `clojure -M -m example ...` or `bb -m example ...`. `dispatch`
matches the longest `:cmds` path in the args and calls that entry's `:fn` with
the parsed result. `:help true` wires up `--help`/`-h` and terse errors (see
[Help](#help)):

```
$ example --help
Usage: example [options] <command>

Commands:
  copy   Copy a file
  delete Delete a file

Options:
  -h, --help Show this help

Run "example <command> --help" for more information on a command.
```

`example copy the-file --dry-run` calls `copy`, which prints:

``` clojure
:copy {:file "the-file", :dry-run true}
```

The `:fn` is called with a map of the parsed result:

- `:opts` - the parsed options (`{:file "the-file" :dry-run true}`; `:file` comes
  from `:args->opts`)
- `:dispatch` - the matched command path (`["copy"]`)
- `:args` - any leftover positional args (`nil` here)

An unknown or missing subcommand prints a terse message and exits 1:

```
$ example bogus
Unknown command: bogus

Commands:
  copy
  delete

Run "example --help" for more information.
```

See [neil](https://github.com/babashka/neil) for a real-world CLI using subcommands.

Each table entry accepts any [parse-args](#options) option (`:spec`,
`:args->opts`, `:alias`, `:restrict`, ...). The order of entries in the table
doesn't matter (since 0.8.54).

### Shared options

Babashka CLI supports parsing shared options in between and before the subcommands.

E.g.:

``` clojure
(def global-spec {:foo {:coerce :keyword}})
(def sub1-spec {:bar {:coerce :keyword}})
(def sub2-spec {:baz {:coerce :keyword}})

(def table
  [{:cmds [] :spec global-spec}
   {:cmds ["sub1"] :fn identity :spec sub1-spec}
   {:cmds ["sub1" "sub2"] :fn identity :spec sub2-spec}])

(cli/dispatch table ["--foo" "a" "sub1" "--bar" "b" "sub2" "--baz" "c" "arg"])

;;=>

{:dispatch ["sub1" "sub2"],
 :opts {:foo :a, :bar :b, :baz :c},
 :args ["arg"]}
```

Specs are not merged across levels: an option is parsed with the spec of the
level it appears at. So:

``` clojure
(cli/dispatch table ["sub1" "--foo" "bar"])
```

returns `{:dispatch ["sub1"], :opts {:foo "bar"}}` - `--foo` is parsed at the
`sub1` level, whose spec has no `:foo`, so it is not coerced as a keyword. To
make an option's spec apply after its subcommand, mark it `:inherit` (see
[Inherited options](#inherited-options)).

Note that it is possible to use `:args->opts` but subcommands are always prioritized over arguments:

``` clojure
(def table
  [{:cmds ["sub1"] :fn identity :spec sub1-spec :args->opts [:some-opt]}
   {:cmds ["sub1" "sub2"] :fn identity :spec sub2-spec}])

(cli/dispatch table ["sub1" "dude"]) ;;=> {:dispatch ["sub1"], :opts {:some-opt "dude"}}
(cli/dispatch table ["sub1" "sub2"]) ;;=> {:dispatch ["sub1" "sub2"], :opts {}}
```

### Inherited options

By default an option is only parsed at the level whose spec defines it, so it
must be supplied before its subcommand:

``` clojure
(def table
  [{:cmds ["group"]       :spec {:registry {}}}
   {:cmds ["group" "sub"] :fn identity :spec {:format {}}}])

(cli/dispatch table ["group" "--registry" "X" "sub"] {:restrict true})
;;=> {:dispatch ["group" "sub"], :opts {:registry "X"}}

(cli/dispatch table ["group" "sub" "--registry" "X"] {:restrict true})
;; throws: Unknown option: :registry
```

Mark an option `:inherit true` to also accept it at any descendant level (after
the subcommand). It is coerced and restrict-checked at whichever level it
appears:

``` clojure
(def table
  [{:cmds ["group"]       :spec {:registry {:inherit true}}}
   {:cmds ["group" "sub"] :fn identity :spec {:format {}}}])

(cli/dispatch table ["group" "sub" "--registry" "X"] {:restrict true})
;;=> {:dispatch ["group" "sub"], :opts {:registry "X"}}
```

It's called `:inherit` because the option is inherited down the command tree by
the descendants of the level that declares it. A descendant may redefine it in
its own spec, in which case the descendant's definition wins.

Instead of marking individual options, you can pass `:inherit` to `dispatch`.
Use `true` to inherit all options, or a set of keys to inherit only those:

``` clojure
(cli/dispatch table ["group" "sub" "--registry" "X"] {:inherit true})
(cli/dispatch table ["group" "sub" "--registry" "X"] {:inherit #{:registry}})
```

### Help

Pass `:help true` to `dispatch` (and `:prog`, the program name) to add help to a
CLI - no `:restrict` needed:

``` clojure
(cli/dispatch table args {:prog "example" :help true})
```

- `--help`/`-h` print help for the command in front of them and return (the
  process ends with status 0). So `example deps outdated --help` shows help for
  `deps outdated`.
- A mistyped or missing subcommand prints a terse message and exits with 1.
- `-h, --help` is listed in each command's options, appended last. To control
  the order, give the command entry an `:order` (a vector of option keys) - it
  is used verbatim, so you decide the order, which options to list, and whether
  to list `--help` at all (omit `:help` to hide it; it still works):

  ``` clojure
  {:cmds [...] :spec {...} :order [:help :port :verbose]}   ; --help first
  ```

  Without `:order`, the order is taken from the spec (a vec-of-pairs spec keeps
  its order; a map follows its key order, which Clojure does not guarantee), and
  `--help` is appended.
- `--help`/`-h` are reserved while `:help` is on (a command may still define its
  own `:help`).

It works for a single-command CLI too: a one-entry table whose `:cmds` is `[]` -
`example --help` then shows Usage + Options:

``` clojure
(cli/dispatch [{:cmds [] :fn run :spec {:port {:coerce :long :desc "Port"}}}]
              args
              {:prog "example" :help true})
```

`--help`/`-h` are a success path: they print help and return (no exit call), so
your `-main` ends and the process exits 0 - like a normal command. Errors go
through the dynamic `*exit-fn*`, which exits non-zero:

| invocation | outcome |
|---|---|
| `--help` / `-h` | print help, return (status 0) - no `*exit-fn*` |
| group, no subcommand | terse message, `*exit-fn*` exit 1, `:cause :input-exhausted` |
| unknown subcommand | terse message, `*exit-fn*` exit 1, `:cause :no-match` |
| flag error | terse message, `*exit-fn*` exit 1, `:cause` = the babashka.cli cause |

A bare group is a usage error (exit 1), like `git bisect` with no subcommand;
its full help is one keystroke away via `--help`. `*exit-fn*` is called only on
errors, with `{:exit :cause :dispatch :data}` (`:cause` is the dispatch cause:
`:no-match`, `:input-exhausted`, or a flag cause; `:data` holds the raw
`dispatch` error data). The default exits the process (`System/exit` on JVM,
`js/process.exit` on Node); rebind it to not exit (tests, REPL) or to remap
codes by `:cause`:

``` clojure
;; treat a bare group as success (exit 0) instead of a usage error
(binding [cli/*exit-fn* (fn [{:keys [exit cause]}]
                          (System/exit (if (= :input-exhausted cause) 0 exit)))]
  (cli/dispatch table args {:prog "example" :help true}))
```

Both handlers are overridable: pass your own `:help-fn` (called with
`{:tree :dispatch :prog :inherit}`) and/or `:error-fn` to `dispatch`. To render
the standard help and add to it, call `format-command-help` - the same renderer
the default uses:

``` clojure
(cli/dispatch table args
  {:prog "example" :help true
   :help-fn (fn [{:keys [tree dispatch prog inherit]}]
              (println "my-tool v1.2.3")
              (println (cli/format-command-help
                        {:table tree :cmds dispatch :prog prog :inherit inherit})))})
```

`format-command-help` is also usable on its own (without `dispatch`): pass
`:table` (a `dispatch` table, or a tree from `table->tree`), `:cmds` (the command
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

## Adding Production Polish
Babashka cli lets you get up and running quickly.
As you move toward production quality, it’s helpful to let users know when their inputs are invalid.
Strict validation can be introduced with [:restrict](#restrict), [:require](#require), and [:validate](#validate).

As you add polish, you'll likely make use of a [:spec](#spec), a custom [:error_fn](#error-handling), and maybe [subcommand dispatching](#subcommands). 

## Restrict

Use the `:restrict` option to restrict options to only those explicitly mentioned in configuration:

``` clojure
(cli/parse-args ["--foo"] {:spec {:bar {}} :restrict true})
;;=>
Execution error (ExceptionInfo) at babashka.cli/parse-opts (cli.cljc:357).
Unknown option: :foo
```

## Require

Use the `:require` option to throw an error when an option is not present:

``` clojure
(cli/parse-args ["--foo"] {:spec {:bar {:require true}}})
;;=>
Execution error (ExceptionInfo) at babashka.cli/parse-opts (cli.cljc:363).
Required option: :bar
```

## Validate

``` clojure
(cli/parse-args ["--foo" "0"] {:spec {:foo {:validate pos?}}})
Execution error (ExceptionInfo) at babashka.cli/parse-opts (cli.cljc:378).
Invalid value for option :foo: 0
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

By default, babashka cli will throw exception on errors it detects.
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

You can also choose collect and then report all detected errors (see `babashka.cli-test/error-fn-test` for an example of this).

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
  -i, --from   <format> edn      The input format. <format> can be edn, json or transit.
  -o, --to     <format> json     The output format. <format> can be edn, json or transit.
      --paths           src test Paths of files to transform.
  -p, --pretty                   Pretty-print output.
```

As options can often be re-used in multiple subcommands, you can determine the
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
You can then use`format-table` to produce a table as returned by `format-opts`.
For example to add a header row with labels for each column, you could do something like:

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

## Babashka tasks

For documentation on babashka tasks, go
[here](https://book.babashka.org/#tasks).

Since babashka `0.9.160`, `babashka.cli` has become a built-in and has better
integration through `-x` and `exec`.  Read about that in the [babashka
book](https://book.babashka.org/#cli).

## Clojure CLI

You can control parsing behavior by adding `:org.babashka/cli` metadata to
Clojure functions. It does not introduce a dependency on `babashka.cli`
itself. Not adding any metadata will result in string values, which in many
cases may already be a reasonable default.

Adding support for this library will cause less friction with shell usage,
especially on Windows since you need less quoting. You can support the same
function for both `clojure -X` and `clojure -M` style invocations without
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
