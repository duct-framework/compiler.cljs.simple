(ns duct.client.repl.simple
  (:require [clojure.core.async :as a :refer [<!]]
            [haslett.client :as ws]
            [haslett.format :as fmt]))

(defn connect
  ([] (connect "ws://localhost:9000"))
  ([url]
   (a/go (let [ws (<! (ws/connect url {:format fmt/json}))]
           (loop []
             (when-some [msg (<! (:in ws))]
               (prn msg)
               (recur)))))))

(js/console.log "REPL connecting")
(connect)
