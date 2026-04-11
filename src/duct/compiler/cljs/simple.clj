(ns duct.compiler.cljs.simple
  (:require [cheshire.core :as json]
            [cljs.analyzer.api :as ana]
            [cljs.build.api :as build]
            [cljs.closure :as clos]
            [cljs.util :as util]
            [clojure.core.async :as a :refer [<! >! >!!]]
            [clojure.java.classpath :as cp]
            [clojure.tools.namespace.dependency :as dep]
            [clojure.tools.namespace.dir :as dir]
            [clojure.tools.namespace.find :as find]
            [clojure.tools.namespace.track :as track]
            [duct.logger :as log]
            [duct.server.http.jetty]
            [integrant.core :as ig]
            [ring.websocket.async :as wsa]
            [ring.websocket.keepalive :refer [wrap-websocket-keepalive]]))

(defn- compiler-env [opts]
  (ana/empty-state (-> opts (dissoc :foreign-libs) (clos/add-externs-sources))))

(defn- depend-compiled [graph {:keys [ns requires]}]
  (reduce #(dep/depend %1 (str ns) %2) graph requires))

(defn ns-dep-graph [env]
  (reduce depend-compiled (dep/graph) (vals (::clos/compiled-cljs env)))) 

(defn ordered-ns-deps [graph ns-str]
  (->> (dep/topo-sort graph)
       (filter #(or (= ns-str %) (dep/depends? graph ns-str %)))))

(defn init-namespaces [graph namespaces]
  (reduce (fn [nss ns-sym]
            (->> (ordered-ns-deps graph (str ns-sym))
                 (remove (set nss))
                 (into nss)))
          [] namespaces))

(defn ns-compile-map [env]
  (group-by (comp str :ns) (vals (::clos/compiled-cljs env))))

(defn ns-eval-source [asset-path {:keys [ns out-file]}]
  (let [uri (str asset-path "/" (util/ns->relpath ns :js))]
    (prn :ns ns :out-file out-file)
    (-> out-file slurp (str "\n//# sourceURL=" uri))))

(defn- eval-js-form [js]
  (list 'js* "(0,eval)(~{})" js))

(defn- init-forms [env {:keys [asset-path main preloads]}]
  (let [namespaces  (conj (vec preloads) main)
        compile-map (ns-compile-map env)]
    (prn (compile-map "goog.string.StringBuffer"))
    (into ['(set! js/goog.provide js/goog.constructNamespace_)
           '(set! js/goog.require js/goog.module.get)]
          (map #(eval-js-form (ns-eval-source asset-path #p (compile-map #p %))))
          (init-namespaces (ns-dep-graph env) namespaces)))) 
 
(defn- create-init-script [env {:keys [output-to] :as opts}]
  (spit output-to (init-forms env opts)))
  
(defmethod ig/init-key ::build
  [_ {:keys [src logger optimizations output-to] :as opts}]
  (let [env (compiler-env {})]
    (if (= optimizations :none)
      (do (build/build src (dissoc opts :src :output-to) env)
          (create-init-script @env opts))
      (build/build src (dissoc opts :src) env))
    (log/info logger ::build-complete {:output-to output-to})
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
      :keys [logger port dirs] :or {port 9000, dirs (cp/classpath-directories)}}]
  (compile-main compiler-env) 
  (let [sessions    (atom {})
        handler     (build-repl-handler compiler-env sessions)
        server-opts {:port port, :handler handler}]
    (log/info logger ::starting-server {:port port})
    {:sessions sessions
     :logger   logger
     :handler  handler
     :server   (ig/init-key :duct.server.http/jetty server-opts)
     :tracker  (init-tracker dirs)}))

(defmethod ig/halt-key! ::server [_ {:keys [logger server]}]
  (log/info logger ::stopping-server)
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
   (let [mesg   {:op :eval :js (cljs->js env form)}
         result (remote-call session mesg timeout-ms)]
     (println (or (:error result) (:value result))))))

(defmethod ig/suspend-key! ::server [_ {:keys [server]}]
  (ig/suspend-key! :duct.server.http/jetty server))

(defn- add-ns-src [ns-sym {:keys [asset-path output-dir]}]
  (let [uri (str asset-path "/" (util/ns->relpath ns-sym :js))
        f   (build/target-file-for-cljs-ns ns-sym output-dir)]
    {:ns ns-sym :src (-> f slurp (str "\n//# sourceURL=" uri))}))

(defn- send-reload [logger build sessions namespaces]
  (when (seq namespaces)
    (let [mesg {:op :load :reload (map #(add-ns-src % build) namespaces)}]
      (doseq [session (vals @sessions)]
        (remote-call session mesg 10000)))
    (log/report logger :cljs/reloaded namespaces))) 

(defmethod ig/resume-key ::server
  [_ {{:keys [compiler-env] :as build} :build new-port :port, logger :logger}
   {:keys [port]}
   {:keys [sessions server tracker handler]}]
  (compile-main compiler-env)
  (let [new-handler (build-repl-handler compiler-env sessions)
        new-opts    {:port new-port :handler new-handler}
        old-opts    {:port port :handler handler}
        tracker     (update-tracker tracker)]
    (send-reload logger build sessions (::track/load tracker))
    {:sessions sessions
     :server   (ig/resume-key :duct.server.http/jetty new-opts old-opts server)
     :tracker  (dissoc tracker ::track/load ::track/unload)}))
