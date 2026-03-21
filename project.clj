(defproject org.duct-framework/compiler.cljs.simple "0.1.0-SNAPSHOT"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.12.4"]
                 [org.clojure/clojurescript "1.12.134"]
                 [org.clojure/core.async "1.9.865"]
                 [cheshire "5.13.0"]
                 [integrant "1.0.1"]
                 [org.duct-framework/server.http.jetty "0.3.4"]
                 [ring/ring-core "1.15.3"]
                 [ring/ring-json "0.5.1"]]
  :profiles
  {:dev {:dependencies [[clj-http "3.13.1"]]}})
