(defproject org.duct-framework/compiler.cljs.simple "0.1.0-SNAPSHOT"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.12.4"]
                 [org.clojure/clojurescript "1.12.134"]
                 [org.clojure/core.async "1.9.865"]
                 [org.clojure/java.classpath "1.1.1"]
                 [org.clojure/tools.namespace "1.5.1"]
                 [cheshire "6.2.0"]
                 [haslett "0.2.0"]
                 [integrant "1.0.1"]
                 [org.duct-framework/logger "0.4.0"]
                 [org.duct-framework/server.http.jetty "0.3.4"]
                 [org.ring-clojure/ring-websocket-async "0.2.0"]
                 [org.ring-clojure/ring-websocket-middleware "0.2.1"]]
  :profiles
  {:dev {:dependencies [[java-http-clj "0.4.3"]]}})
