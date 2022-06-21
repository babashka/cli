(ns examples.malli
  (:require
   [babashka.cli :as cli]
   [malli.core :as malli]
   [malli.experimental.lite :as l]))

(def schema (l/schema {:boolean?
                       :repo }))

(malli/validate schema (cli/parse-opts ["--force" "--repo" "https://foobar.com"]))

x
