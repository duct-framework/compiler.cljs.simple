(ns duct.client.repl.simple
  (:require [cljs.repl :as repl]
            [clojure.core.async :as a :refer [<! >!]]
            [clojure.string :as str]
            [haslett.client :as ws]
            [haslett.format :as fmt]))

(defn- parse-error [e]
  (repl/ex-triage (repl/Error->map e)))

(defn- eval-js [js]
  (try {:value (pr-str (js/eval js))}
       (catch :default e
         {:error (pr-str (parse-error e))})))

(defn- demunge-ns [ns-str]
  (str/replace ns-str "-" "_"))

(defn- load-namespaces [namespaces]
  (try (doseq [ns-str namespaces]
         (js/goog.require (demunge-ns ns-str) "reload"))
       {:value :reloaded}
       (catch :default e
         {:error (pr-str (parse-error e))})))

(defn- handle-messages [{:keys [in out]}]
  (a/go-loop []
    (when-some [mesg (<! in)]
      (>! out (case (mesg "op")
                "eval" (eval-js (mesg "js"))
                "load" (load-namespaces (mesg "namespaces"))
                {:error {:message "No matching clause."}}))
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
