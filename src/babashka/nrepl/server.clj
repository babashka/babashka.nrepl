(ns babashka.nrepl.server
  {:author "Michiel Borkent"}
  (:require [babashka.nrepl.impl.server :as server]
            [babashka.nrepl.server.middleware :as middleware]
            [clojure.string :as string]
            [sci.core :as sci])
  (:import [java.net ServerSocket]))

(set! *warn-on-reflection* true)

(defn stop-server! [{:keys [socket]}]
  (.close ^ServerSocket socket))

(defn parse-opt [host+port]
  (let [parts (string/split host+port #":")
        [host port] (if (= 1 (count parts))
                         [nil (Integer. ^String (first parts))]
                         [(first parts)
                          (Integer. ^String (second parts))])]
    {:host host
     :port port}))

(defn ->sci-var [sci-ctx sym]
  (or (sci/eval-form sci-ctx `(requiring-resolve '~sym))
      (throw (Exception. (str "Failed to resolve " sym)))))

(defn ->user-middleware [sci-ctx middlware]
  (sequence
   (comp
    (map #(->sci-var sci-ctx %))
    (map #(middleware/middleware->transducer sci-ctx %)))
   middlware))

(defn start-server! [ctx & [{:keys [host port quiet middleware]
                             :or {host "0.0.0.0"
                                  port 1667}
                             :as opts}]]
  (let [ctx (assoc ctx :sessions (atom #{}))
        opts (assoc opts :xform
                    (get opts :xform
                         (middleware/middleware->xform
                          (into middleware/default-middleware
                                (->user-middleware ctx middleware)))))
        inet-address (java.net.InetAddress/getByName host)
        socket-server (new ServerSocket port 0 inet-address)]
    (when-not quiet
      (println (format "Started nREPL server at %s:%d" (.getHostAddress inet-address) (.getLocalPort socket-server))))
    {:socket socket-server
     :future (sci/future
               (server/listen ctx socket-server opts))}))
