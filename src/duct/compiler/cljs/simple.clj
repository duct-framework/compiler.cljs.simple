(ns duct.compiler.cljs.simple
  (:require [cljs.analyzer.api :as ana]
            [cljs.build.api :as build]
            [cljs.closure :as clos]
            [clojure.core.async :as a :refer [<!]]
            [duct.server.http.jetty]
            [integrant.core :as ig]
            [ring.middleware.json :refer [wrap-json-response]]
            [ring.util.response :as res]))

(defn- compiler-env [opts]
  (ana/empty-state (-> opts (dissoc :foreign-libs) (clos/add-externs-sources))))

(defmethod ig/init-key ::build [_ {:keys [source] :as opts}]
  (let [env (compiler-env {})]
    (build/build source (dissoc opts :source) env)
    {:compiler-env env}))

(defn- build-repl-handler [id env in]
  (wrap-json-response
   (fn [_request respond _raise]
     (a/go (let [js (build/compile env {} (<! in))]
             (respond (res/response {:repl id, :form js})))))))

(defmethod ig/init-key ::repl-server
  [key {{:keys [compiler-env]} :build, :keys [port] :or {port 9000}}]
  (let [in      (a/chan 128)
        handler (build-repl-handler key compiler-env in)
        options {:port port, :handler handler, :async? true}]
    {:in     in
     :server (ig/init-key :duct.server.http/jetty options)}))

(defmethod ig/halt-key! ::repl-server [_ {:keys [server]}]
  (ig/halt-key! :duct.server.http/jetty server))
