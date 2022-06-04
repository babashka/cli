(defproject org.babashka/cli "0.2.10"
  :description "Turn Clojure functions into CLIs!"
  :url "https://github.com/babashka/cli"
  :scm {:name "git"
        :url "https://github.com/babashka/cli"}
  :license {:name "MIT"
            :url "https://opensource.org/licenses/MIT"}
  :source-paths ["src"]
  :dependencies [[org.clojure/clojure "1.11.1"]]
  :deploy-repositories [["clojars" {:url "https://clojars.org/repo"
                                    :username :env/clojars_user
                                    :password :env/clojars_pass
                                    :sign-releases false}]])
