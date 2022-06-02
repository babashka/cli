# babashka.cli

Easy command line parsing for Clojure.

## API

See [API.md](API.md).

## Rationale

Parsing command line arguments in clojure and babashka CLIs often are in the form of

``` clojure
<subcommand> :opt1 v1 :opt2 :v2
```

See e.g. [neil](https://github.com/babashka/neil):

``` clojure
Usage: neil <subcommand> <options>
```

This library eases that way of command line parsing. It does not convert options
into EDN automatically which, arguably, is more convenient for command line
usage. This library does offer a light-weight way to coerce strings using core
functions.

Adding support for `babashka.cli` coercions to Clojure functions does not
introduce a dependency on `babashka.cli` itself.  It can be done via metadata
and core functions.

## Quickstart

``` clojure
(require '[babashka.cli :as cli])
(cli/parse-args ["server" ":port" "1339"] {:coerce {:port parse-long}})
;;=> {:cmds ["server"] :opts {:port 1339}}
```

## Usage with the clojure CLI

In your `deps.edn` `:aliases` entry, add:

``` clojure
:exec {:deps {org.babashka/cli {:git/url "https://github.com/babashka/cli"
                                :git/sha "<latest-sha>"}}
       :main-opts ["-m" "babashka.cli.exec"]}
```

There-after you can call any function that accepts a map argument. E.g.:

``` clojure
$ clojure -M:exec clojure.core/prn :a 1 :b 2
{:a "1", :b "2"}
```

Functions that are annotated with `:babashka/cli` metadata can add coerce options:

``` clojure
(ns my-ns)

(defn foo
  {:babashka/cli {:coerce {:a symbol :b parse-long}}}
  ;; map argument:
  [m]
  ;; print map argument:
  (prn m))
```

Note that any library can add support for babashka CLI without depending on
babashka CLI.

``` clojure
$ clojure -M:exec my-ns/foo :a foo/bar :b 2 :c vanilla
{:a foo/bar, :b 2, :c "vanilla"}
```

## License

Copyright © 2022 Michiel Borkent

Distributed under the MIT License. See LICENSE.
