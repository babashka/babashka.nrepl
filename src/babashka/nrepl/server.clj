(ns babashka.nrepl.server
  {:author "Michiel Borkent"}
  (:require [babashka.nrepl.impl.server :as server])
  (:import [java.net ServerSocket]))

(set! *warn-on-reflection* true)

(defn stop-server! [{:keys [socket]}]
  (.close ^ServerSocket socket))

(defn start-server! [ctx & [{:keys [address port quiet]
                             :or {address "0.0.0.0"
                                  port 1667}
                             :as opts}]]
  (let [ctx (assoc ctx :sessions (atom #{}))
        inet-address (java.net.InetAddress/getByName address)
        socket-server (new ServerSocket port 0 inet-address)]
    (when-not quiet
      (println (format "Started nREPL server at %s:%d" (.getHostAddress inet-address) port)))
    {:socket socket-server
     :future (future
               (server/listen ctx socket-server opts))}))
