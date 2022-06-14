# babashka.cli

[![Clojars Project](https://img.shields.io/clojars/v/org.babashka/cli.svg)](https://clojars.org/org.babashka/cli)

Turn Clojure functions into CLIs!

## [API](API.md)

## Status

This library is still in design phase and may still undergo breaking changes.
Check [breaking changes](CHANGELOG.md#breaking-changes) before upgrading!

## Installation

Add to your `deps.edn` or `bb.edn` `:deps` entry:

``` clojure
org.babashka/cli {:mvn/version "0.2.17"}
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

This library eases that style of command line parsing.

It does not convert options into EDN automatically which, arguably, is more
convenient for command line usage, especially on Windows. This library does
offer a light-weight way to coerce strings.

Both `:` and `--` are supported as the initial characters of a named option. See
[options](https://github.com/babashka/cli#options) for more details.

This library also supports calling exec-style functions, such that:

``` clojure
(defn foo [{:keys [foo bar] :as m}] (prn m))
```

``` clojure
clojure -M:foo --foo --bar=yes
{:foo true, :bar "yes"}
```

See [clojure CLI](https://github.com/babashka/cli#clojure-cli) for how to turn
your exec functions into CLIs.

## Projects using babashka CLI

- [neil](https://github.com/babashka/neil)
- [quickdoc](https://github.com/borkdude/quickdoc#clojure-cli)
- [clj-new](https://github.com/seancorfield/clj-new#babashka-cli)
- [deps-new](https://github.com/seancorfield/deps-new#babashka-cli)

## TOC

- [Options](#options)
- [Subcommands](#subcommands)
- [Babashka tasks](#babashka-tasks)
- [Clojure CLI](#clojure-cli)
- [Leiningen](#leiningen)

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
(cli/parse-opts ["-p" "1339"] {:aliases {:p :port} :coerce {:port :long}})
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

(cli/parse-opts ["-v" "-v" "-v"] {:aliases {:v :verbose}
                                  :coerce {:verbose []}})
;;=> {:verbose [true true true]}
```

Long options also support the syntax `--foo=bar`:

``` clojure
(cli/parse-opts ["--foo=bar"])
;;=> {:foo "bar"}
```

## Subcommands

To handle subcommands, use
[dispatch](https://github.com/babashka/cli/blob/main/API.md#dispatch).

An example. Say we want to create a CLI that can be called as:

``` clojure
$ cli copy <file> --dry-run
$ cli delete <file> --recursive --depth 3
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

(def dispatch-table
  [{:cmds ["copy"] :cmds-opts [:file] :fn copy}
   {:cmds ["delete"] :cmds-opts [:file] :fn delete}
   {:cmds [] :fn help}])

(defn -main [& args]
  (cli/dispatch dispatch-table args {:coerce {:depth :long}}))
```

Calling the `example` namespace's `-main` function can be done using `clojure -M -m example` or `bb -m example`.
The last entry in the `dispatch-table` always matches and calls the help function.

When running `bb -m example --help`, `dispatch` calls `help` which returns:

``` clojure
{:opts {:help true}, :dispatch [], :fn :help}
```

When running `bb -m example copy the-file --dry-run`, `dispatch` calls `copy`,
which returns:

``` clojure
{:cmds ["copy" "the-file"], :opts {:file "the-file" :dry-run true},
 :dispatch ["copy"], :fn :copy}
```

When running `bb -m example delete the-file --depth 3`, `dispatch` calls `delete` which returns:

``` clojure
{:cmds ["delete" "the-file"], :opts {:depth 3, :file "the-file"},
 :dispatch ["delete"], :fn :delete}
```

See [neil](https://github.com/babashka/neil) for a real world example of a CLI
that uses subcommands.

## Babashka tasks

For documentation on babashka tasks, go [here](https://book.babashka.org/#tasks).

To parse options to your tasks, add `[babashka.cli :as cli]` to
`:requires`. Then you can parse the options in `:init`:

``` clojure
:init (def cmd-line-opts (cli/parse-opts *command-line-args*)))
```
and then use this in any task:

``` clojure
(when-not (:skip-bump cmd-line-opts)
  (run 'bump-release))
```

and your tasks can then be called with options:

``` clojure
$ bb publish --skip-bump
```

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
:exec {:deps {org.babashka/cli {:mvn/version "0.2.17"}
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
:prn {:deps {org.babashka/cli {:mvn/version "0.2.17"}}
      :main-opts ["-m" "babashka.cli.exec" "clojure.core" "prn"]}
```

``` clojure
$ clojure -M:prn --foo=bar --baz
{:foo "bar" :baz true}
```

You can also pre-define the exec function in `:exec-fn`:

``` clojure
:prn {:deps {org.babashka/cli {:mvn/version "0.2.17"}}
      :exec-fn clojure.core/prn
      :main-opts ["-m" "babashka.cli.exec"]}
```

To alter the parsing behavior of functions you don't control, you can add
`:org.babashka/cli` data in the `deps.edn` alias:

``` clojure
:prn {:deps {org.babashka/cli {:mvn/version "0.2.17"}}
      :exec-fn clojure.core/prn
      :main-opts ["-m" "babashka.cli.exec"]
      :org.babashka/cli {:coerce {:foo :long}}}
```

``` clojure
$ clojure -M:prn --foo=1
{:foo 1}
```

### antq

`.clojure/deps.edn` alias:

``` clojure
:antq {:deps {org.babashka/cli {:mvn/version "0.2.17"}
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

### [deps-new](https://github.com/seancorfield/deps-new#babashka-cli)

### [kaocha](https://github.com/lambdaisland/kaocha)

In `deps.edn` create an alias:

``` clojure
:kaocha {:extra-deps {org.babashka/cli {:mvn/version "0.2.17"}
                      lambdaisland/kaocha {:mvn/version "1.66.1034"}}
         :exec-fn kaocha.runner/exec-fn
         :exec-args {} ;; insert default arguments here
         :org.babashka/cli {:aliases {:watch :watch?
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

## Leiningen

This tool can be used to run clojure exec functions with [lein](https://leiningen.org/).

An example with `clj-new`:

In `~/.lein/profiles.clj` put:

``` clojure
{:clj-1.11 {:dependencies [[org.clojure/clojure "1.11.1"]]}
 :clj-new {:dependencies [[org.babashka/cli "0.2.17"]
                          [com.github.seancorfield/clj-new "1.2.381"]]}
 :user {:aliases {"clj-new" ["with-profiles" "+clj-1.11,+clj-new"
                             "run" "-m" "babashka.cli.exec"
                             {:exec-args {:env {:description "My project"}}
                              :coerce {:verbose :long
                                       :args []}
                              :aliases {:f :force}}
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
