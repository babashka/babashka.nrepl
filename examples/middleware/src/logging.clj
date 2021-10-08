(ns logging
  (:require [sci.core :as sci]
            [babashka.nrepl.server.middleware :as middleware]
            babashka.nrepl.server))

(def sci-ctx (sci/init {}))

(defonce responses (atom []))
(def
  ^{::middleware/requires #{#'middleware/wrap-response-for}}
  log-responses 
  (map (fn [response]
         (swap! responses conj (:response response))
         response)))

(defonce requests (atom []))
(def
  ^{::middleware/requires #{#'middleware/wrap-read-msg}
    ::middleware/expects #{#'middleware/wrap-process-message}}
  log-requests
  (map (fn [request]
         (swap! requests conj (:msg request))
         request)))

;; Add cross cutting middleware
(def xform
  (middleware/middleware->xform
   (conj middleware/default-middleware
         #'log-requests
         #'log-responses)))

(defn -main [& args]
  (babashka.nrepl.server/start-server! sci-ctx {:host "127.0.0.1" :port 23456
                                                :xform xform}))
