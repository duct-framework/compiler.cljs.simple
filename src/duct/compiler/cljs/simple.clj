(ns duct.compiler.cljs.simple
  (:require [cheshire.core :as json]
            [cljs.analyzer.api :as ana]
            [cljs.build.api :as build]
            [cljs.closure :as clos]
            [clojure.core.async :as a :refer [<! >! >!!]]
            [clojure.tools.namespace.dir :as dir]
            [clojure.tools.namespace.find :as find]
            [clojure.tools.namespace.track :as track]
            [duct.server.http.jetty]
            [integrant.core :as ig]
            [ring.websocket.async :as wsa]
            [ring.websocket.keepalive :refer [wrap-websocket-keepalive]]))

(defn- compiler-env [opts]
  (ana/empty-state (-> opts (dissoc :foreign-libs) (clos/add-externs-sources))))

(defmethod ig/init-key ::build [_ {:keys [src] :as opts}]
  (let [env (compiler-env {})]
    (build/build src (dissoc opts :src) env)
    (assoc opts :compiler-env env)))

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

(defn- init-tracker [sources]
  (-> (track/tracker)
      (dir/scan-dirs sources {:platform find/cljs :add-all? true})
      (dissoc ::track/load ::track/unload)))

(defn- update-tracker [tracker]
  (dir/scan-files tracker (::dir/files tracker) {:platform find/cljs}))

(defmethod ig/init-key ::server
  [_ {{:keys [compiler-env src] :or {src "src"}} :build
      :keys [port] :or {port 9000}}]
  (compile-main compiler-env) 
  (let [sessions    (atom {})
        handler     (build-repl-handler compiler-env sessions)
        server-opts {:port port, :handler handler}]
    {:sessions sessions
     :handler  handler
     :server   (ig/init-key :duct.server.http/jetty server-opts)
     :tracker  (init-tracker [src])}))

(defmethod ig/halt-key! ::server [_ {:keys [server]}]
  (ig/halt-key! :duct.server.http/jetty server))

(defn- timeout-exception [session-id form]
  (ex-info (str "Timeout evaluating " form " on session " session-id)
           {:session-id session-id, :form form}))

(defn eval-cljs
  ([session form]
   (eval-cljs session form 10000))
  ([{:keys [id in out]} form timeout-ms]
   (>!! in form)
   (a/alt!! [out]
            ([{:keys [value error]} _] (println (or error value)))
            (a/timeout timeout-ms)
            (throw (timeout-exception id form)))))

(defmethod ig/suspend-key! ::server [_ {:keys [server]}]
  (ig/suspend-key! :duct.server.http/jetty server))

(defmethod ig/resume-key ::server
  [_ {{:keys [compiler-env]} :build new-port :port}
   {:keys [port]}
   {:keys [sessions server tracker handler]}]
  (compile-main compiler-env)
  (let [new-handler (build-repl-handler compiler-env sessions)
        new-opts    {:port new-port :handler new-handler}
        old-opts    {:port port :handler handler}
        tracker     (update-tracker tracker)]
    (doseq [session (vals @sessions)]
      (doseq [ns-sym (::track/load tracker)]
        (eval-cljs session (list 'require (list 'quote ns-sym) :reload))))
    {:sessions sessions
     :server   (ig/resume-key :duct.server.http/jetty new-opts old-opts server)
     :tracker  (dissoc tracker ::track/load ::track/unload)}))
