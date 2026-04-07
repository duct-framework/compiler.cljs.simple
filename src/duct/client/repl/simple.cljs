(ns duct.client.repl.simple
  (:require [cljs.repl :as repl]
            [clojure.core.async :as a :refer [<! >!]]
            [haslett.client :as ws]
            [haslett.format :as fmt]))

(defn- parse-error [e]
  (repl/ex-triage (repl/Error->map e)))

(defn- eval-js [js]
  (try {:value (pr-str (js* "(0,eval)(~{})" js))}
       (catch :default e
         {:error (pr-str (parse-error e))})))

(def ^:private logo-data-url
  (str "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox="
       "'0 0 160 160'%3E%3Cpath fill='%235881d8' d='M0,0v120h120V40l-20,20v40"
       "H20V20h100l10,10,15-15L130,0Z'/%3E%3Cpath fill='%2375d23b' d='M145,15"
       "l-15,15c-.179-1.199,10,10,10,10V140H60V120H40v40h120V30Z'/%3E%3Cpath "
       "fill='%2375d23b' d='M60,100V60h40l20-20H40v60Z'/%3E%3C/svg%3E"))

(def ^:private notify-css-style
  (str "position:fixed;bottom:16px;right:16px;"
       "color:#fff;border-radius:8px;padding:7px 14px 7px 34px;"
       "font-family:sans-serif;font-size:14px;"
       "opacity:0;transition:opacity 0.5s ease-in-out;z-index:999999;"
       "background:#333 12px 8px/16px 16px no-repeat;"
       "background-image:url(\"" logo-data-url "\");"))

(defn- notify-element [message]
  (doto (js/document.createElement "div")
    (as-> e (set! (.. e -style -cssText) notify-css-style))
    (as-> e (set! (.-textContent e) message))))

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
           (js/goog.require (munge ns-str) "reload")
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
