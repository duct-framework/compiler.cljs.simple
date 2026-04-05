(ns test-project.client
  (:require [test-project.support :as sup]))

(let [content (js/document.getElementById "content")]
  (set! (.-textContent content) (sup/greet "World")))
