(ns duct.compiler.cljs.simple
  (:require [cheshire.core :as json]
            [cljs.analyzer.api :as ana]
            [cljs.build.api :as build]
            [cljs.closure :as clos]
            [clojure.core.async :as a :refer [<! >! >!!]]
            [clojure.java.classpath :as cp]
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

(defn- pipe [from to]
  (a/go-loop []
    (when-some [v (<! from)]
      (when (>! to v) (recur)))))

(defn- new-session [env]
  {:id      (random-uuid)
   :env     env
   :mesg-id (atom 0)
   :in      (a/chan 128 (map #(json/generate-string %)))
   :out     (a/chan 128 (map #(json/parse-string % true)))})

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

(defn- init-tracker [dirs]
  (-> (track/tracker)
      (dir/scan-dirs dirs {:platform find/cljs :add-all? true})
      (assoc ::dirs dirs)
      (dissoc ::track/load ::track/unload)))

(defn- update-tracker [tracker]
  (dir/scan-dirs tracker (::dirs tracker) {:platform find/cljs}))

(defmethod ig/init-key ::server
  [_ {{:keys [compiler-env]} :build
      :keys [port dirs] :or {port 9000, dirs (cp/classpath-directories)}}]
  (compile-main compiler-env) 
  (let [sessions    (atom {})
        handler     (build-repl-handler compiler-env sessions)
        server-opts {:port port, :handler handler}]
    {:sessions sessions
     :handler  handler
     :server   (ig/init-key :duct.server.http/jetty server-opts)
     :tracker  (init-tracker dirs)}))

(defmethod ig/halt-key! ::server [_ {:keys [server]}]
  (ig/halt-key! :duct.server.http/jetty server))

(defn- timeout-exception [{:keys [id]} mesg]
  (ex-info (str "Timeout waiting for a response from session " id
                " for message: " (pr-str mesg))
           {:session-id id, :message mesg}))

(def ^:private top-level-forms
  '#{ns require use require-macros use-macros})

(defn- top-level? [form]
  (and (list? form) (contains? top-level-forms (first form))))

(defn- cljs->js [env form]
  (let [form (if (top-level? form) form `((fn [] ~form)))]
    (build/compile env {} form)))

(defn- remote-call [{:keys [mesg-id in out] :as session} mesg timeout-ms]
  (let [next-id (swap! mesg-id inc)]
    (>!! in (assoc mesg :id next-id))
    (a/alt!! [out] ([{:keys [id] :as result} _]
                    (assert (= id next-id) "response ID out of sequence")
                    result)
             (a/timeout timeout-ms)
             (throw (timeout-exception session mesg)))))

(defn eval-cljs
  ([session form]
   (eval-cljs session form 10000))
  ([{:keys [env] :as session} form timeout-ms]
   (let [ mesg  {:op :eval :js (cljs->js env form)}
         result (remote-call session mesg timeout-ms)]
     (println (or (:error result) (:value result))))))

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
        tracker     (update-tracker tracker)
        reload-mesg {:op :load :namespaces (::track/load tracker)}]
    (doseq [session (vals @sessions)]
      (remote-call session reload-mesg 10000))
    {:sessions sessions
     :server   (ig/resume-key :duct.server.http/jetty new-opts old-opts server)
     :tracker  (dissoc tracker ::track/load ::track/unload)}))
