(ns duct.compiler.cljs.simple
  (:require [cljs.build.api :as build]
            [integrant.core :as ig]))

(defmethod ig/init-key :duct.compiler.cljs/simple
  [_ {:keys [source] :as opts}]
  (build/build source (dissoc opts :source)))
