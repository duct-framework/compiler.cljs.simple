(ns duct.client.repl.simple.preload
  (:require [duct.client.repl.simple :as repl]
            [clojure.browser.repl :as browser]))

(browser/bootstrap)
(repl/connect)
