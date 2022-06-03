(ns utils
  (:require [clojure.edn :as edn]))

(defn version [] (edn/read-string (slurp "version.edn")))

(defn format-version []
  (apply format "%s.%s.%s" ((juxt :major :minor :release) (version))))

(defn bump-version []
  (spit "version.edn" (update (version) :release inc)))
