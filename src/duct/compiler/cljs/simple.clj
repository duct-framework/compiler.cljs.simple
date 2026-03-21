(ns duct.compiler.cljs.simple
  (:require [cljs.analyzer.api :as ana]
            [cljs.build.api :as build]
            [cljs.closure :as closure]
            [integrant.core :as ig]))

(defmethod ig/init-key ::compiler-env [_ opts]
  (ana/empty-state
   (-> opts (dissoc :foreign-libs) (closure/add-externs-sources))))

(defmethod ig/init-key ::build
  [_ {:keys [source compiler-env] :as opts}]
  (build/build source (dissoc opts :source :compiler-env) compiler-env))

(defmethod ig/init-key ::repl-server [_ {:keys [compiler-env]}]
  (fn [form]
    (ana/with-state compiler-env (closure/compile form {}))))
