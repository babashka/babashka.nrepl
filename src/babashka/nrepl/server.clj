(ns babashka.nrepl.server
  {:author "Michiel Borkent"}
  (:require [babashka.nrepl.impl.server :as server]
            [babashka.nrepl.middleware :as middleware]
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

(defn start-server! [ctx & [{:keys [host port quiet]
                             :or {host "0.0.0.0"
                                  port 1667}
                             :as opts}]]
  (let [ctx (assoc ctx :sessions (atom #{}))
        opts (assoc opts :xform
                    (get opts :xform
                         middleware/default-xform))
        inet-address (java.net.InetAddress/getByName host)
        socket-server (new ServerSocket port 0 inet-address)]
    (when-not quiet
      (println (format "Started nREPL server at %s:%d" (.getHostAddress inet-address) (.getLocalPort socket-server))))
    {:socket socket-server
     :future (sci/future
               (server/listen ctx socket-server opts))}))
