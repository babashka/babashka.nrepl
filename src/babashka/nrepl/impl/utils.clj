(ns babashka.nrepl.impl.utils
  {:author "Michiel Borkent"
   :no-doc true}
  (:refer-clojure :exclude [send])
  (:require [bencode.core :refer [write-bencode]]
            [sci.core :as sci])
  (:import [java.io Writer PrintWriter StringWriter OutputStream BufferedWriter]))

(set! *warn-on-reflection* true)

(defn response-for [old-msg msg]
  (let [session (get old-msg :session "none")
        id (get old-msg :id "unknown")]
    (assoc msg "session" session "id" id)))

(defn send [^OutputStream os msg {:keys [debug-send]}]
  (when debug-send (prn "Sending" msg))
  (write-bencode os msg)
  (.flush os))

(defn send-exception [os msg ^Throwable ex {:keys [debug] :as opts}]
  (let [d (ex-data ex)
        sci-error? (isa? (:type d) :sci/error)
        ex-name (when sci-error?
                  (some-> ^Throwable (ex-cause ex)
                          .getClass .getName))
        ex-map (Throwable->map ex)
        cause (:cause ex-map)
        {:keys [:file :line :column]} d
        ns @sci/ns
        loc-str (str ns " "
                     (when line
                       (str (str (or file "REPL") ":")
                            line ":" column"")))
        _strace (sci/stacktrace ex)]
    (when debug (prn "sending exception" ex-map))
    (send os (response-for msg {"err" (str ex-name
                                           (when cause (str ": " cause))
                                           " " loc-str "\n")}) opts)
    (send os (response-for msg {"ex" (str "class " ex-name)
                                "root-ex" (str "class " ex-name)
                                "status" #{"eval-error"}}) opts)
    (send os (response-for msg {"status" #{"done"}}) opts)))

