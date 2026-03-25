(ns duct.client.repl.simple
  (:require [clojure.core.async :as a :refer [<! >!]]
            [haslett.client :as ws]
            [haslett.format :as fmt]))

(defn connect
  ([] (connect "ws://localhost:9000"))
  ([url]
   (a/go (let [{:keys [in out]} (<! (ws/connect url {:format fmt/json}))]
           (loop []
             (when-some [{:strs [form]} (<! in)]
               (let [result (js/eval form)]
                 (>! out {:value (pr-str result)})
                 (recur)))))
         (js/console.log "Websocket closed"))))

(js/console.log "REPL connecting...")
(connect)
