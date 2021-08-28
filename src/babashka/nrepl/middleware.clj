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

(defn wrap-stdio->stdio
  "Middleware for writing stdout messages to stdout."
  [rf]
  (completing
   (fn [result input]
     (if (:start-stream input)
       (let [{:keys [start-stream session callback id]} input]

         (reset! callback
                 (fn write!
                   ([x]
                    (let [cbuf (to-char-array x)]
                      (write! cbuf (int 0) (count cbuf))))
                   ([x off len]
                    (let [cbuf (to-char-array x)
                          text (str (doto (StringWriter.)
                                      (.write cbuf ^int off ^int len)))]
                      (when (pos? (count text))
                        (prn {start-stream text
                              :id id
                              :session session}))))))

         (rf result input))
       (rf result input)))))

(defn wrap-stdio->stream
  "Middleware for capturing asynchronous stdout/stderr messages and
  emitting nrepl messages for out and err."
  [rf]
  (completing
   (fn [result input]
     (if (:start-stream input)
       (let [{:keys [start-stream session callback id]} input]
         (reset! callback
                 (fn write!
                   ([x]
                    (let [cbuf (to-char-array x)]
                      (write! cbuf (int 0) (count cbuf))))
                   ([x off len]
                    (let [cbuf (to-char-array x)
                          text (str (doto (StringWriter.)
                                      (.write cbuf ^int off ^int len)))]
                      (when (pos? (count text))
                        (rf result {:response {"session" (or session "none")
                                               "id" (or id "unknown")
                                               (name start-stream) text}}))))))
         result)
       (rf result input)))))

(def default-xform
  "Default middleware used by sci nrepl server."
  (comp wrap-read-msg
        wrap-process-message
        wrap-stdio->stream))
