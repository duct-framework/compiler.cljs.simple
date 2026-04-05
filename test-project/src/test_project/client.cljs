(ns test-project.client
  (:require [replicant.dom :as r]
            [test-project.support :as sup]))

(r/render
 (js/document.getElementById "content")
 [:div
  [:p "This is a test."]
  [:p (sup/greet "World") "."]])          
