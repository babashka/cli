# babashka.cli

[![Clojars Project](https://img.shields.io/clojars/v/org.babashka/cli.svg)](https://clojars.org/org.babashka/cli)
[![bb built-in](https://raw.githubusercontent.com/babashka/babashka/master/logo/built-in-badge.svg)](https://babashka.org)

Turn Clojure functions into CLIs!

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
- Open world assumption: passing extra arguments does not break and arguments
  can be re-used in multiple contexts.

Both `:` and `--` are supported as the initial characters of a named option, but
cannot be mixed. See [options](https://github.com/babashka/cli#options) for more
details.

See [clojure CLI](https://github.com/babashka/cli#clojure-cli) for how to turn
your exec functions into CLIs.

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
- [Babashka tasks](#babashka-tasks)
- [Clojure CLI](#clojure-cli)
- [Leiningen](#leiningen)

## Simple example
Here is an example script to get you started!

```clojure
#!/usr/bin/env bb
(require '[babashka.cli :as cli]
         '[babashka.fs :as fs])

(defn dir-exists?
  [path]
  (fs/directory? path))

(defn show-help
  [spec]
  (cli/format-opts (merge spec {:order (vec (keys (:spec spec)))})))

(def cli-spec
  {:spec
   {:num {:coerce :long
          :desc "Number of some items"
          :alias :n                     ; adds -n alias for --num
          :validate pos?                ; tests if supplied --num >0
          :require true}                ; --num,-n is required
    :dir {:desc "Directory name to do stuff"
          :alias :d
          :validate dir-exists?}        ; tests if --dir exists
    :flag {:coerce :boolean             ; defines a boolean flag
           :desc "I am just a flag"}}
   :error-fn                           ; a function to handle errors
   (fn [{:keys [spec type cause msg option] :as data}]
     (when (= :org.babashka/cli type)
       (case cause
         :require
         (println
           (format "Missing required argument: %s\n" option))
         :validate
         (println
           (format "%s does not exist!\n" msg)))))})

(defn -main
  [args]
  (let [opts (cli/parse-opts args cli-spec)]
    (if (or (:help opts) (:h opts))
      (println (show-help cli-spec))
      (println "Here are your cli args!:" opts))))

(-main *command-line-args*)
```

And this is how you run it:
```
$ bb try-me.clj --num 1 --dir my_dir --flag
Here are your cli args!: {:num 1, :dir my_dir, :flag true}

$ bb try-me.clj --help
Missing required argument: :num

  -n, --num  Number of some items
  -d, --dir  Directory name to do stuff
      --flag I am just a flag
```

Using the [`spec`](https://github.com/babashka/cli#spec) format is optional and you can implement you own parsing logic just with [`parse-opts`/`parse-args`](https://github.com/babashka/cli#options).
However, many would find the above example familiar.

## Options

For parsing options, use either [`parse-opts`](https://github.com/babashka/cli/blob/main/API.md#parse-opts) or [`parse-args`](https://github.com/babashka/cli/blob/main/API.md#parse-args).

Examples:

Parse `{:port 1339}` from command line arguments:

``` clojure
(require '[babashka.cli :as cli])

(cli/parse-opts ["--port" "1339"] {:coerce {:port :long}})
;;=> {:port 1339}
```

Use an alias (short option):

``` clojure
(cli/parse-opts ["-p" "1339"] {:alias {:p :port} :coerce {:port :long}})
;; {:port 1339}
```

Coerce values into a collection:

``` clojure
(cli/parse-opts ["--paths" "src" "--paths" "test"] {:coerce {:paths []}})
;;=> {:paths ["src" "test"]}

(cli/parse-opts ["--paths" "src" "test"] {:coerce {:paths []}})
;;=> {:paths ["src" "test"]}
```

Transforming to a collection of a certain type:

``` clojure
(cli/parse-opts ["--foo" "bar" "--foo" "baz"] {:coerce {:foo [:keyword]}})
;; => {:foo [:bar :baz]}
```

Booleans need no explicit `true` value and `:coerce` option:

``` clojure
(cli/parse-opts ["--verbose"])
;;=> {:verbose true}

(cli/parse-opts ["-v" "-v" "-v"] {:alias {:v :verbose}
                                  :coerce {:verbose []}})
;;=> {:verbose [true true true]}
```

Long options also support the syntax `--foo=bar`:

``` clojure
(cli/parse-opts ["--foo=bar"])
;;=> {:foo "bar"}
```

Flags may be combined into a single short option (since 0.7.51):

``` clojure
(cli/parse-opts ["-abc"])
;;=> {:a true :b true :c true}
```

Arguments that start with `--no-` arg parsed as negative flags (since 0.7.51):

``` clojure
(cli/parse-opts ["--no-colors"])
;;=> {:colors false}
```

### Auto-coercion

Since `v0.3.35` babashka CLI auto-coerces values that have no explicit coercion
with
[`auto-coerce`](https://github.com/babashka/cli/blob/main/API.md#auto-coerce):
it automatically tries to convert booleans, numbers and keywords.

## Arguments

To parse positional arguments, you can use `parse-args` and/or the `:args->opts`
option. E.g. to parse arguments for the `git push` command:

``` clojure
(cli/parse-args ["--force" "ssh://foo"] {:coerce {:force :boolean}})
;;=> {:args ["ssh://foo"], :opts {:force true}}

(cli/parse-args ["ssh://foo" "--force"] {:coerce {:force :boolean}})
;;=> {:args ["ssh://foo"], :opts {:force true}}
```

Note that this library can only disambiguate correctly between values for
options and trailing arguments with enough `:coerce` information
available. Without the `:force :boolean` info, we get:

``` clojure
(cli/parse-args ["--force" "ssh://foo"])
{:opts {:force "ssh://foo"}}
```

In case of ambiguity `--` may also be used to communicate the boundary between
options and arguments:

``` clojure
(cli/parse-args ["--paths" "src" "test" "--" "ssh://foo"] {:coerce {:paths []}})
{:args ["ssh://foo"], :opts {:paths ["src" "test"]}}
```

### :args->opts

To fold positional arguments into the parsed options, you can use `:args->opts`:

``` clojure
(def cli-opts {:coerce {:force :boolean} :args->opts [:url]})

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
(def cli-opts {:coerce {:bar []} :args->opts (cons :foo (repeat :bar))})
(cli/parse-opts ["arg1" "arg2" "arg3" "arg4"] cli-opts)
;;=> {:foo "arg1", :bar ["arg2" "arg3" "arg4"]}
```

## Restrict

Use the `:restrict` option to restrict options to only those explicitly mentioned in configuration:

``` clojure
(cli/parse-args ["--foo"] {:restrict [:bar]})
;;=>
Execution error (ExceptionInfo) at babashka.cli/parse-opts (cli.cljc:357).
Unknown option: :foo
```

## Require

Use the `:require` option to throw an error when an option is not present:

``` clojure
(cli/parse-args ["--foo"] {:require [:bar]})
;;=>
Execution error (ExceptionInfo) at babashka.cli/parse-opts (cli.cljc:363).
Required option: :bar
```

## Validate

``` clojure
(cli/parse-args ["--foo" "0"] {:validate {:foo pos?}})
Execution error (ExceptionInfo) at babashka.cli/parse-opts (cli.cljc:378).
Invalid value for option :foo: 0
```

To gain more control over the error message, use `:pred` and `:ex-msg`:

``` clojure
(cli/parse-args ["--foo" "0"] {:validate {:foo {:pred pos? :ex-msg (fn [m] (str "Not a positive number: " (:value m)))}}})
;;=>
Execution error (ExceptionInfo) at babashka.cli/parse-opts (cli.cljc:378).
Not a positive number: 0
```

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

It is recommended to either throw an exception or otherwise exit in the error
handler function, unless you want to collect all of the errors and act on them
in the end (see `babashka.cli-test/error-fn-test` for an example of this).

For example:

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

## Spec

This library can work with partial information to parse options. As such, the
options to `parse-opts` and `parse-args` are optimized for terseness. However,
when writing a CLI that supports automated printing of options, it is recommended to use the spec format:

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
- `:coerce`: coerce string to given type.
- `:alias`: mapping of short name to long name.
- `:default`: default value.
- `:default-desc`: a string representation of the default value.
- `:require`: `true` make this opt required.
- `:validate`: a function used to validate the value of this opt (as described
  in the [Validate](#validate) section).

## Help

Given the above `spec` you can print options as follows:

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

## Subcommands

To handle subcommands, use
[dispatch](https://github.com/babashka/cli/blob/main/API.md#dispatch).

An example. Say we want to create a CLI that can be called as:

``` clojure
$ example copy <file> --dry-run
$ example delete <file> --recursive --depth 3
```

This can be accomplished by doing the following:

``` clojure
(ns example
  (:require [babashka.cli :as cli]))

(defn copy [m]
  (assoc m :fn :copy))

(defn delete [m]
  (assoc m :fn :delete))

(defn help [m]
  (assoc m :fn :help))

(def table
  [{:cmds ["copy"]   :fn copy   :args->opts [:file]}
   {:cmds ["delete"] :fn delete :args->opts [:file]}
   {:cmds []         :fn help}])

(defn -main [& args]
  (cli/dispatch table args {:coerce {:depth :long}}))
```

Calling the `example` namespace's `-main` function can be done using `clojure -M -m example` or `bb -m example`.
The last entry in the `dispatch-table` always matches and calls the help function.

When running `clj -M -m example --help`, `dispatch` calls `help` which returns:

``` clojure
{:opts {:help true}, :dispatch [], :fn :help}
```

When running `clj -M -m example copy the-file --dry-run`, `dispatch` calls `copy`,
which returns:

``` clojure
{:cmds ["copy" "the-file"], :opts {:file "the-file" :dry-run true},
 :dispatch ["copy"], :fn :copy}
```

When running `clj -M -m example delete the-file --depth 3`, `dispatch` calls `delete` which returns:

``` clojure
{:cmds ["delete" "the-file"], :opts {:depth 3, :file "the-file"},
 :dispatch ["delete"], :fn :delete}
```

See [neil](https://github.com/babashka/neil) for a real world example of a CLI
that uses subcommands.

Additional `parse-arg` options may be passed in each table entry:

``` clojure
(def table
  [{:cmds ["copy"]   :fn copy   :args->opts [:file] :alias {:f :file :restrict true}}
   {:cmds ["delete"] :fn delete :args->opts [:file]}
   {:cmds []         :fn help}])
```

Since cli 0.8.54 the order of `:cmds` in the table doesn't matter.

### Shared options

Since cli 0.8.54, babashka.cli supports parsing shared options in between and before the subcommands.

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

Note that specs are not merged, such that:

``` clojure
(cli/dispatch table ["sub1" "--foo" "bar"])
```

returns `{:dispatch ["sub1"], :opts {:foo "bar"}}` (`"bar"` is not coerced as a keyword).

Note that it is possible to use `:args->opts` but subcommands are always prioritized over arguments:

``` clojure
(def table
  [{:cmds ["sub1"] :fn identity :spec sub1-spec :args->opts [:some-opt]}
   {:cmds ["sub1" "sub2"] :fn identity :spec sub2-spec}])

(cli/dispatch table ["sub1" "dude"]) ;;=> {:dispatch ["sub1"], :opts {:some-opt "dude"}}
(cli/dispatch table ["sub1" "sub2"]) ;;=> {:dispatch ["sub1" "sub2"], :opts {}}
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

Copyright © 2022 Michiel Borkent

Distributed under the MIT License. See LICENSE.
