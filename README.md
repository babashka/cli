# babashka.cli

Easy command line parsing for Clojure.

## API

See [API.md](API.md).

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
  {:babashka/cli {:coerce {:b parse-long}}}
  ;; map argument:
  [m]
  ;; print map argument:
  (prn m))
```

``` clojure
$ clojure -M:exec my-ns/foo :a 1 :b 2
{:a "1", :b 2}
```
