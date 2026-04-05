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

(defn- notify-element [message]
 (let [element (js/document.createElement "div")]
    (set! (.. element -style -cssText)
          (str "position:fixed;bottom:16px;right:16px;padding:7px 14px;"
               "background:#333;color:#fff;border-radius:8px;"
               "font-family:sans-serif;font-size:14px;"
               "opacity:0;transition:opacity 0.5s ease-in-out;z-index:999999;"))
    (set! (.-textContent element) message)
    element))

(defn- show-notify [element]
  (js/document.body.appendChild element)
  (js/setTimeout #(set! (.. element -style -opacity) "1") 0))

(defn- hide-notify [element]
  (set! (.. element -style -opacity) "0")
  (js/setTimeout #(some-> (.-parentNode element) (.removeChild element)) 500))

(defn- load-namespaces [namespaces]
  (let [notify (notify-element "Reloading...")]
    (try (show-notify notify)
         (doseq [ns-str namespaces]
           (js/goog.require (demunge-ns ns-str) "reload")
           (js/console.debug (str "Reloaded " ns-str)))
         {:value :reloaded}
         (catch :default e
           {:error (pr-str (parse-error e))})
         (finally
           (js/setTimeout #(hide-notify notify) 500)))))

(defn- handle-messages [{:keys [in out]}]
  (a/go-loop []
    (when-some [{:strs [id] :as mesg} (<! in)]
      (let [response (case (mesg "op")
                       "eval" (eval-js (mesg "js"))
                       "load" (load-namespaces (mesg "namespaces"))
                       {:error {:message "No matching clause."}})]
        (>! out (assoc response :id id))
        (recur)))))

(defn connect
  ([] (connect "ws://localhost:9000"))
  ([url]
   (a/go-loop []
     (let [ws (<! (ws/connect url {:format fmt/json}))]
       (js/console.info "REPL connected.")
       (<! (handle-messages ws))
       (js/console.warn "REPL closed. Retrying...")
       (<! (a/timeout 1000))
       (recur)))))
