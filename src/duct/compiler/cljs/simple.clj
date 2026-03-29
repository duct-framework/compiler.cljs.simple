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

(def ^:private top-level-forms
  '#{ns require use require-macros use-macros})

(defn- top-level? [form]
  (and (list? form) (contains? top-level-forms (first form))))

(defn- cljs->js [env form]
  (let [form (if (top-level? form) form `((fn [] ~form)))
        js   (build/compile env {} form)]
    (json/generate-string {:eval js})))

(defn- new-session [env]
  {:id (random-uuid), :env env, :in (a/chan 128), :out (a/chan 128)})

(defn- close-session! [{:keys [in out]}]
  (a/close! in) (a/close! out))

(defn- read-loop [{:keys [env in out]} ws-out]
  (a/go-loop []
    (when-some [form (<! in)]
      (let [js-or-error (try (cljs->js env form)
                             (catch Exception ex ex))]
        (if (string? js-or-error)
          (when (>! ws-out js-or-error) (recur))
          (when (>! out {:error js-or-error}) (recur)))))))

(defn- print-loop [{:keys [out]} ws-in]
  (a/go-loop []
    (when-some [result (<! ws-in)]
      (when (>! out (json/parse-string result true))
        (recur)))))

(defn- build-repl-handler [env sessions]
  (wrap-websocket-keepalive
   (fn [_request]
     (wsa/go-websocket [ws-in ws-out]
       (let [{:keys [id] :as sess} (new-session env)]
         (swap! sessions assoc id sess)
         (try
           (a/alts! [(read-loop sess ws-out) (print-loop sess ws-in)])
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
     (a/alt!! [out] ([{:keys [value error]} _] (println (or error value)))
              (a/timeout timeout-ms) (prn :timeout)))))
