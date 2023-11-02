(ns babashka.cli.test-macros
  (:require [clojure.walk :as walk]))

(defmacro deftest [_var-name & body]
  `(do ~@body))

(defmacro testing [_str & body]
  `(do ~@body))

(defn apply-template
  "For use in macros.  argv is an argument list, as in defn.  expr is
  a quoted expression using the symbols in argv.  values is a sequence
  of values to be used for the arguments.

  apply-template will recursively replace argument symbols in expr
  with their corresponding values, returning a modified expr.

  Example: (apply-template '[x] '(+ x x) '[2])
           ;=> (+ 2 2)"
  [argv expr values]
  (assert (vector? argv))
  (assert (every? symbol? argv))
  (walk/postwalk-replace (zipmap argv values) expr))

(defmacro do-template
  "Repeatedly copies expr (in a do block) for each group of arguments
  in values.  values are automatically partitioned by the number of
  arguments in argv, an argument vector as in defn.

  Example: (macroexpand '(do-template [x y] (+ y x) 2 4 3 5))
           ;=> (do (+ 4 2) (+ 5 3))"
  [argv expr & values]
  (let [c (count argv)]
    `(do ~@(map (fn [a] (apply-template argv expr a))
                (partition c values)))))

(defn ->assert [expr]
  (walk/postwalk (fn [expr]
                   (if (and (seq? expr)
                            (= '= (first expr)))
                     (list* 'assert.deepEqual (second expr) (first expr) (nnext expr))
                     expr)) expr))

(defmacro are
  "Checks multiple assertions with a template expression.
  See clojure.template/do-template for an explanation of
  templates.

  Example: (are [x y] (= x y)
                2 (+ 1 1)
                4 (* 2 2))
  Expands to:
           (do (is (= 2 (+ 1 1)))
               (is (= 4 (* 2 2))))

  Note: This breaks some reporting features, such as line numbers."
  {:added "1.1"}
  [argv expr & args]
  (if (or
       ;; (are [] true) is meaningless but ok
       (and (empty? argv) (empty? args))
       ;; Catch wrong number of args
       (and (pos? (count argv))
            (pos? (count args))
            (zero? (mod (count args) (count argv)))))
    `(do ~@(map (fn [a] (apply-template argv (->assert expr) a))
                (partition (count args) args)))
    #?(:clj (throw (IllegalArgumentException. "The number of args doesn't match are's argv."))
       :cljs (throw (js/Error "The number of args doesn't match are's argv.")))))

(defmacro is [& args]
  `(do ~@args))
