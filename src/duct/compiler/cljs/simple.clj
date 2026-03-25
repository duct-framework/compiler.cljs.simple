(ns duct.compiler.cljs.simple
  (:require [cheshire.core :as json]
            [cljs.analyzer.api :as ana]
            [cljs.build.api :as build]
            [cljs.closure :as clos]
            [clojure.core.async :as a :refer [<! >!]]
            [duct.server.http.jetty]
            [integrant.core :as ig]
            [ring.websocket.async :as wsa]
            [ring.websocket.keepalive :refer [wrap-websocket-keepalive]]))

(defn- compiler-env [opts]
  (ana/empty-state (-> opts (dissoc :foreign-libs) (clos/add-externs-sources))))

(defmethod ig/init-key ::build [_ {:keys [source] :as opts}]
  (let [env (compiler-env {})]
    (build/build source (dissoc opts :source) env)
    {:compiler-env env}))

(defn- build-repl-handler [env in out]
  (wrap-websocket-keepalive
   (fn [_request]
     (wsa/go-websocket [ws-in ws-out]
       (loop []
         (when-some [form (<! in)]
           (let [js (build/compile env {} `((fn [] ~form)))]
             (>! ws-out (json/generate-string {:op :eval :form #p js}))
             (when-some [result (<! ws-in)]
               (>! out (json/parse-string #p result))
               (recur)))))))))

(defmethod ig/init-key ::repl-server
  [_ {{:keys [compiler-env]} :build, :keys [port] :or {port 9000}}]
  (let [in      (a/chan 128)
        out     (a/chan 128)
        handler (build-repl-handler compiler-env in out)
        options {:port port, :handler handler}]
    {:in     in
     :out    out
     :server (ig/init-key :duct.server.http/jetty options)}))

(defmethod ig/halt-key! ::repl-server [_ {:keys [server]}]
  (ig/halt-key! :duct.server.http/jetty server))
