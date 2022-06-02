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
<subcommand> :opt1 v1 :opt2 :v2
```

This library eases that style of command line parsing.

This library does not support validation, or everything that you might expect
from an arg-parse library. I think a lot of these things can be done using spec
or otherwise, nowadays.

It does not convert options into EDN automatically which, arguably, is more
convenient for command line usage. This library does offer a light-weight way to
coerce strings.

Adding support for `babashka.cli` coercions to Clojure functions does not
introduce a dependency on `babashka.cli` itself.  It can be done via metadata
and core functions. Perhaps doing so will cause less friction with shell usage,
especially on Windows. You can support the same function for both `clojure -X`
and `clojure -M` style invocations without writing extra boilerplate.

## Quickstart

``` clojure
(require '[babashka.cli :as cli])
(cli/parse-args ["server" ":port" "1339"] {:coerce {:port parse-long}})
;;=> {:cmds ["server"] :opts {:port 1339}}
```

## Usage in babashka tasks

For documentation on babashka tasks, go [here](https://book.babashka.org/#tasks).

To parse options to your tasks, add `[babashka.cli :as cli]` to
`:requires`. Then you can parse the options in `:init`:

``` clojure
:init (def cmd-line-opts
        (:opts (cli/parse-args *command-line-args*
          {:coerce {:skip-bump parse-boolean}})))
```
and then use this in any task:

``` clojure
(when-not (:skip-bump cmd-line-opts)
  (run 'bump-release))
```

and your tasks can then be called with options:

``` clojure
$ bb publish :skip-bump true
```

## Usage with the clojure CLI

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
