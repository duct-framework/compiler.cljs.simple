(ns duct.compiler.cljs.simple
  (:require [cheshire.core :as json]
            [cljs.analyzer.api :as ana]
            [cljs.build.api :as build]
            [cljs.closure :as clos]
            [clojure.core.async :as a :refer [<! >! >!!]]
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

(defn- pipe [from to]
  (a/go-loop []
    (when-some [v (<! from)]
      (when (>! to v) (recur)))))

(defn- cljs->js [env form]
  (let [js (build/compile env {} `((fn [] ~form)))]
    (json/generate-string {:eval js})))

(defn- new-session [env]
  {:id  (random-uuid)
   :in  (a/chan 128 (map #(cljs->js env %)))
   :out (a/chan 128 (map #(json/parse-string % true)))})

(defn- close-session! [{:keys [in out]}]
  (a/close! in) (a/close! out))

(defn- build-repl-handler [env sessions]
  (wrap-websocket-keepalive
   (fn [_request]
     (wsa/go-websocket [ws-in ws-out]
       (let [{:keys [id in out] :as sess} (new-session env)]
         (swap! sessions assoc id sess)
         (try
           (a/alts! [(pipe in ws-out) (pipe ws-in out)])
           (finally
             (close-session! sess)
             (swap! sessions dissoc id))))))))

(defn- compile-main [env]
  (let [main (-> @env :options :main)]
    (build/compile env {} (list 'require (list 'quote main)))))

(defmethod ig/init-key ::repl-server
  [_ {{:keys [compiler-env]} :build, :keys [port] :or {port 9000}}]
  (compile-main compiler-env) 
  (let [sessions (atom {})
        handler  (build-repl-handler compiler-env sessions)
        options  {:port port, :handler handler}]
    {:sessions sessions
     :server   (ig/init-key :duct.server.http/jetty options)}))

(defmethod ig/halt-key! ::repl-server [_ {:keys [server]}]
  (ig/halt-key! :duct.server.http/jetty server))

(defn server-sessions [server]
  (-> server :sessions deref keys))

(defn eval-in-client
  ([server form]
   (let [session-id (first (server-sessions server))]
     (eval-in-client server session-id form)))
  ([server session-id form]
   (eval-in-client server session-id form 10000))
  ([server session-id form timeout-ms]
   (let [{:keys [in out]} (-> server :sessions deref (get session-id))]
     (>!! in form)
     (a/alt!! [out] ([{:keys [value]} _] (println value))
              (a/timeout timeout-ms) (prn :timeout)))))
