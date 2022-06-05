(ns babashka.cli.internal
  {:no-doc true})

(defn- into-able? [x]
  (and (coll? x)
       (or (sequential? x)
           (set? x))))

(defn- merge* [x y]
  (cond (and (map? x) (map? y)) (merge x y)
        (and (into-able? x)
             (into-able? y))
        (into x y)
        :else y))

(defn merge-opts [m & ms]
  (reduce #(merge-with merge* %1 %2) m ms))
