# babashka.cli

[![Clojars Project](https://img.shields.io/clojars/v/org.babashka/cli.svg)](https://clojars.org/org.babashka/cli)

Easy command line parsing for Clojure.

## [API](API.md)

## Installation

Add to your `deps.edn` or `bb.edn` `:deps` entry:

``` clojure
org.babashka/cli {:mvn/version "0.1.1"}
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
convenient for command line usage. This library does offer a light-weight way to
coerce strings.

## Quickstart

Parse `:port 1339` and coerce port into long:

``` clojure
(require '[babashka.cli :as cli])

(cli/parse-args ["server" ":port" "1339"] {:coerce {:port parse-long}})
;;=> {:cmds ["server"] :opts {:port 1339}}
```

or use the convience keyword notation:

``` clojure
(cli/parse-args ["--port" "1339"] {:coerce {:port :long}})
;;=> {:cmds [] :opts {:port 1339}}
```

Use an alias (short option):

``` clojure
(:opts (cli/parse-args ["-p" "1339"] {:aliases {:p :port} :coerce {:port :long}}))
;; {:port 1339}
```

Collect values into a collection:

``` clojure
(:opts (cli/parse-args ["--paths" "src" "--paths" "test"] {:collect {:paths []}}))
;;=> {:paths ["src" "test"]}

(:opts (cli/parse-args ["--paths" "src" "test"] {:collect {:paths []}}))
;;=> {:paths ["src" "test"]}
```

Booleans need no explicit `true` value and `:coerce` option:

``` clojure
(:opts (cli/parse-args ["--verbose"]))
;;=> {:verbose true}
```

## Usage in babashka tasks

For documentation on babashka tasks, go [here](https://book.babashka.org/#tasks).

To parse options to your tasks, add `[babashka.cli :as cli]` to
`:requires`. Then you can parse the options in `:init`:

``` clojure
:init (def cmd-line-opts (:opts (cli/parse-args *command-line-args*)))
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

By adding `:babashka.cli` metadata to Clojure functions it will make them
callable with the clojure CLI. It does not introduce a dependency on
`babashka.cli` itself. Doing so will cause less friction with shell usage,
especially on Windows since you need less quoting. You can support the same
function for both `clojure -X` and `clojure -M` style invocations without
writing extra boilerplate.

In your `deps.edn` `:aliases` entry, add:

``` clojure
:exec {:deps {org.babashka/cli {:mvn/version "0.1.1"}
       :main-opts ["-m" "babashka.cli.exec"]}
```

Now you can call any function that accepts a map argument. E.g.:

``` clojure
$ clojure -M:exec clojure.core/prn :a 1 :b 2
{:a "1", :b "2"}
```

Use `:babashka/cli` metadata for coercions:

``` clojure
(ns my-ns)

(defn foo
  {:babashka/cli {:coerce {:a symbol :b parse-long}}}
  ;; map argument:
  [m]
  ;; print map argument:
  (prn m))
```

``` clojure
$ clojure -M:exec my-ns/foo :a foo/bar :b 2 :c vanilla
{:a foo/bar, :b 2, :c "vanilla"}
```

Note that any library can add support for babashka CLI without depending on
babashka CLI.

An example that specializes `babashka.cli` usage to a function:

``` clojure
:exec {:deps {org.babashka/cli {:git/url "https://github.com/babashka/cli"
                                :git/sha "<latest-sha>"}}
       :main-opts ["-m" "babashka.cli.exec"]}
:prn {:main-opts ["-m" "babashka.cli.exec" "clojure.core/prn"]}
```

``` clojure
$ clojure -M:exec:prn :foo 1
{:foo "1"}
```

To alter the parsing behavior of functions you don't control, you can alter the
metadata of a var using `alter-meta!`. For demo purposes we alter the metadata
on `prn`:

``` clojure
:prn {:main-opts ["-e" "(do (alter-meta! (requiring-resolve 'clojure.core/prn) assoc :babashka/cli {:coerce {:foo parse-long}}) nil)"
                  "-m" "babashka.cli.exec" "clojure.core/prn"]}
```

``` clojure
$ clojure -M:exec:prn :foo 1
{:foo 1}
```

Although we didn't have to use `requiring-resolve` of `prn`, when using
namespaces outside of clojure, you will.

## License

Copyright © 2022 Michiel Borkent

Distributed under the MIT License. See LICENSE.
