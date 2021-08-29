(ns babashka.nrepl.middleware
  (:require [babashka.nrepl.impl.server :as server])
  (:import  [java.io
             StringWriter]))


(def wrap-read-msg
  "Middleware for normalizing an nrepl message read from bencode."
  (map (fn [m]
         (update m :msg server/read-msg))))

(defn wrap-process-message
  "Middleware for producing responses based on op code."
  [rf]
  (completing
   (fn [result input]
     (server/process-msg rf result input))))

(defn- to-char-array
  ^chars
  [x]
  (cond
    (string? x) (.toCharArray ^String x)
    (integer? x) (char-array [(char x)])
    :else x))


(def default-xform
  "Default middleware used by sci nrepl server."
  (comp wrap-read-msg
        wrap-process-message))
