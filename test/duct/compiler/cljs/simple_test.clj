(ns duct.compiler.cljs.simple-test
  (:require [clj-http.client :as http]
            [clojure.core.async :refer [>!!]]
            [clojure.java.io :as io]
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
          in  (-> sys ::simple/repl-server :in)]
      (try
        (>!! in '(+ 1 1))
        (is (= {:repl "duct.compiler.cljs.simple/repl-server"
                :form "((1) + (1));\n"}
               (:body (http/post "http://localhost:9000" {:as :json}))))
        (>!! in '(require '[clojure.string :as s]))
        (is (= {:repl "duct.compiler.cljs.simple/repl-server"
                :form "goog.require('clojure.string');\n"}
               (:body (http/post "http://localhost:9000" {:as :json}))))
        (>!! in '(s/trim " foo "))
        (is (= {:repl "duct.compiler.cljs.simple/repl-server"
                :form "clojure.string.trim.call(null,\" foo \");\n"}
               (:body (http/post "http://localhost:9000" {:as :json}))))
        (finally
          (ig/halt! sys))))
   (finally
     (doseq [f (reverse (file-seq (io/file "target/cljs")))]
       (.delete f)))))
