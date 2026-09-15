(ns tasks
  (:require [babashka.tasks :refer [run shell]]))

(defn publish
  "Publish to clojars and npm, then push."
  {:org.babashka/cli {:spec {:bump {:coerce :boolean
                                    :desc "Run bump-release first"}}}}
  [{:keys [bump]}]
  (when bump
    (run 'bump-release))
  ;; loaded after bump-release, so build reads the new version
  ((requiring-resolve 'build/deploy) {})
  (run 'npm-publish)
  ;; bump-release pushes the tag, the commit it points at needs this
  (shell "git push"))
