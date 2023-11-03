(ns babashka.one-off
  (:require [babashka.cli :as cli]))

(prn (cli/parse-opts ["2021a4" ":no-git-tag-version" ":deps-file" "foo.edn"] {:args->opts [:version] :spec {:no-git-tag-version {:coerce :boolean}}}))
(prn (cli/parse-opts ["foo"] {:args->opts [:bar]}))
