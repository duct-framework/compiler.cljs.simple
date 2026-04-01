(ns duct.client.repl.simple
  (:require [cljs.repl :as repl]
            [clojure.core.async :as a :refer [<! >!]]
            [haslett.client :as ws]
            [haslett.format :as fmt]))

(defn- eval-js [js]
  (try {:value (pr-str (js/eval js))}
       (catch :default e
         {:error (pr-str (repl/ex-triage (repl/Error->map e)))})))

(defn- handle-messages [{:keys [in out]}]
  (a/go-loop []
    (when-some [{js "eval"} (<! in)]
      (>! out (eval-js js))
      (recur))))

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
