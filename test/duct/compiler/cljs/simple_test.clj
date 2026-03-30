(ns duct.compiler.cljs.simple-test
  (:require [cheshire.core :as json]
            [clojure.core.async :refer [>!! <!!] :as a]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [duct.compiler.cljs.simple :as simple]
            [integrant.core :as ig]
            [java-http-clj.websocket :as ws]))

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
                        {:asset-path "/js"
                         :output-dir "target/cljs/js"
                         :output-to "target/cljs/js/main.js"
                         :optimizations :none
                         :main 'duct.compiler.cljs.client-test
                         :preloads ['duct.client.repl.simple.preload]}
                        ::simple/repl-server
                        {:port 9001
                         :build (ig/ref ::simple/build)}}) 
          wsout (a/chan 128)
          ws    (ws/build-websocket
                 "ws://localhost:9001/"
                 {:on-text (fn [_ text _] (>!! wsout text))})
          in    (-> sys ::simple/repl-server :sessions deref first val :in)]
      (try
        (>!! in '(+ 1 1))
        (is (= {"eval" "(function (){\nreturn ((1) + (1));\n}).call(null);\n"}
               (json/parse-string (<!! wsout))))
        (>!! in '(require '[clojure.string :as s]))
        (is (= {"eval" "goog.require('clojure.string');\n"}
               (json/parse-string (<!! wsout))))
        (>!! in '(s/trim " foo "))
        (is (= {"eval" (str "(function (){\nreturn "
                            "clojure.string.trim.call(null,\" foo \");\n"
                            "}).call(null);\n")}
               (json/parse-string (<!! wsout))))
        (finally
          (ws/close ws)
          (ig/halt! sys))))
   (finally
     (doseq [f (reverse (file-seq (io/file "target/cljs")))]
       (.delete f)))))
