(ns duct.compiler.cljs.simple
  (:require [cljs.analyzer.api :as ana]
            [cljs.build.api :as build]
            [cljs.closure :as clos]
            [integrant.core :as ig]))

(defn- compiler-env [opts]
  (ana/empty-state (-> opts (dissoc :foreign-libs) (clos/add-externs-sources))))

(defmethod ig/init-key ::build [_ {:keys [source] :as opts}]
  (let [env (compiler-env {})]
    (build/build source (dissoc opts :source :compiler-env) env)
    {:compiler-env env}))

(defmethod ig/init-key ::repl-server [_ {{:keys [compiler-env]} :build}]
  (fn [form] (ana/with-state compiler-env (clos/compile form {}))))
