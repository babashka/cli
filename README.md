# babashka.cli

[![Clojars Project](https://img.shields.io/clojars/v/org.babashka/cli.svg)](https://clojars.org/org.babashka/cli)

Easy command line parsing for Clojure.

## [API](API.md)

## Status

This library is still in design phase and may still undergo breaking changes.
Check [breaking changes](CHANGELOG.md#breaking-changes) before upgrading!

## Installation

Add to your `deps.edn` or `bb.edn` `:deps` entry:

``` clojure
org.babashka/cli {:mvn/version "0.2.9"}
```

## Rationale

Command line arguments in clojure and babashka CLIs are often in the form:

``` clojure
$ tool subcommand :opt1 :v1 :opt2 :v2
```

or the more Unixy:

``` clojure
$ tool subcommand --long-opt1 v1 -o v2
```

This library eases that style of command line parsing.

It does not convert options into EDN automatically which, arguably, is more
convenient for command line usage, especially on Windows. This library does
offer a light-weight way to coerce strings.

Both `:` and `--` are supported as the initial characters of a named option.

## Quickstart

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

Collect values into a collection:

``` clojure
(cli/parse-opts ["--paths" "src" "--paths" "test"] {:collect {:paths []}})
;;=> {:paths ["src" "test"]}

(cli/parse-opts ["--paths" "src" "test"] {:collect {:paths []}})
;;=> {:paths ["src" "test"]}
```

Booleans need no explicit `true` value and `:coerce` option:

``` clojure
(cli/parse-opts ["--verbose"])
;;=> {:verbose true}

(cli/parse-opts ["-v" "-v" "-v"] {:aliases {:v :verbose}
                                         :collect {:verbose []}})
;;=> {:verbose [true true true]}
```

Long options also support the syntax `--foo=bar`:

``` clojure
(cli/parse-opts ["--foo=bar"])
;;=> {:foo "bar"}
```

## Projects using babashka CLI

- [neil](https://github.com/babashka/neil)

## Usage in babashka tasks

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

## Usage with the clojure CLI

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
:exec {:deps {org.babashka/cli {:mvn/version "0.2.9"}
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
  {:org.babashka/cli {:coerce {:a :symbol :b :long}}}
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
:prn {:deps {org.babashka/cli {:mvn/version "0.2.9"}}
      :main-opts ["-m" "babashka.cli.exec" "clojure.core" "prn"]}
```

``` clojure
$ clojure -M:prn --foo=bar --baz
{:foo "bar" :baz true}
```

To alter the parsing behavior of functions you don't control, you can add
`:org.babashka/cli` data in the `deps.edn` alias:

``` clojure
:prn {:main-opts ["-m" "babashka.cli.exec" "clojure.core" "prn"]
      :org.babashka/cli {:coerce {:foo :long}}}
```

``` clojure
$ clojure -M:prn --foo=1
{:foo 1}
```

### antq

`.clojure/deps.edn` alias:

``` clojure
:antq {:deps {org.babashka/cli {:mvn/version "0.2.9"}
              com.github.liquidz/antq {:mvn/version "1.7.798"}}
       :main-opts ["-m" "babashka.cli.exec" "antq.tool" "outdated"]
       :org.babashka/cli {:collect {:skip []}}}
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

Note that we added the `:org.babashka/cli {:collect {:skip []}}` data in the
alias to make sure that `--skip` options get collected into a vector:

``` clojure
clj -M:antq --upgrade --skip github-action
```

vs.

``` clojure
clj -Tantq outdated :upgrade true :skip '["github-action"]'
```

### clj-new

`.clojure/deps.edn` alias:

``` clojure
:new {:deps {org.babashka/cli {:mvn/version "0.2.9"}
             com.github.seancorfield/clj-new {:mvn/version "1.2.381"}}
      :main-opts ["-m" "babashka.cli.exec" "clj-new"]}
```

Usage:

``` clojure
$ clj -M:new app --name foo/bar --force --version 1.2.3
Generating a project called bar based on the 'app' template.
```

## Leiningen

This tool can be used to run clojure exec functions with [lein](https://leiningen.org/).

In `~/.lein/profiles.clj` put:

``` clojure
{:clj-1.11 {:dependencies [^:displace [org.clojure/clojure "1.11.1"]]}
 :clj-new {:dependencies [[org.babashka/cli "0.2.9"]
                          [com.github.seancorfield/clj-new "1.2.381"]]}
 :user {:aliases {"clj-new" ["with-profiles" "+clj-1.11,+clj-new"
                             "run" "-m" "babashka.cli.exec"
                             {:org.babashka/cli {:exec-args {:env {:description "My project"}}
                                                 :coerce {:verbose :long}
                                                 :collect {:args []}
                                                 :aliases {:f :force}}}
                             "clj-new"]}}}
```

After that you can use `lein clj-new app` to call an exec function:

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
