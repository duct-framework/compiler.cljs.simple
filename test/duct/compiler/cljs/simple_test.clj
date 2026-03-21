(ns duct.compiler.cljs.simple-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [duct.compiler.cljs.simple :as simple]
            [integrant.core :as ig]))

(deftest build-test
  (try
    (ig/init {::simple/compiler-env {}
              ::simple/build
              {:compiler-env (ig/ref ::simple/compiler-env)
               :output-dir "target/cljs/js"
               :output-to "target/cljs/js/main.js"
               :optimizations :none
               :main 'duct.compiler.cljs.client-test}})
   (is (.exists (io/file "target/cljs/js/main.js")))
   (finally
     (doseq [f (reverse (file-seq (io/file "target/cljs")))]
       (.delete f)))))
