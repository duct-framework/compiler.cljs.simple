(ns duct.compiler.cljs.simple-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [duct.compiler.cljs.simple :as simple]
            [integrant.core :as ig]))

(deftest build-test
  (try
    (ig/init {::simple/build
              {:output-dir "target/cljs/js"
               :output-to "target/cljs/js/main.js"
               :optimizations :none
               :main 'duct.compiler.cljs.client-test}})
   (is (.exists (io/file "target/cljs/js/main.js")))
   (finally
     (doseq [f (reverse (file-seq (io/file "target/cljs")))]
       (.delete f)))))

(deftest repl-test
  (try
    (let [sys (ig/init {::simple/build
                        {:output-dir "target/cljs/js"
                         :output-to "target/cljs/js/main.js"
                         :optimizations :none
                         :main 'duct.compiler.cljs.client-test}
                        ::simple/repl-server
                        {:build (ig/ref ::simple/build)}})
          ->js (::simple/repl-server sys)]
      (is (= "((1) + (1));\n" (->js '(+ 1 1))))
      (is (= "goog.require('clojure.string');\n"
             (->js '(require '[clojure.string :as s]))))
      (is (= "clojure.string.trim.call(null,\" foo \");\n"
             (->js '(s/trim " foo ")))))
   (finally
     (doseq [f (reverse (file-seq (io/file "target/cljs")))]
       (.delete f)))))
