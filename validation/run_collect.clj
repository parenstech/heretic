(ns run-collect
  (:require [heretic.core :as heretic]))

(defn -main [& args]
  (println "Running heretic coverage collection...")
  (let [config (heretic/load-config)
        result (heretic/collect! config)]
    (println "\nResult:" result)))

(-main)
