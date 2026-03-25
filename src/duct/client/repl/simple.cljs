(ns duct.client.repl.simple
  (:require [clojure.core.async :as a :refer [<! >!]]
            [haslett.client :as ws]
            [haslett.format :as fmt]))

(defn- handle-messages [{:keys [in out]}]
  (a/go-loop []
    (when-some [{form "eval"} (<! in)]
      (let [result (js/eval form)]
        (>! out {:value (pr-str result)})
        (recur)))))

(defn connect
  ([] (connect "ws://localhost:9000"))
  ([url]
   (a/go-loop []
     (let [ws (<! (ws/connect url {:format fmt/json}))]
       (js/console.log "REPL connected.")
       (<! (handle-messages ws))
       (js/console.log "REPL closed. Retrying...")
       (<! (a/timeout 1000))
       (recur)))))
