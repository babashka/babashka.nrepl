(ns babashka.nrepl.impl.server
  {:author "Michiel Borkent"
   :no-doc true}
  (:require [babashka.nrepl.impl.utils :as utils]
            [bencode.core :refer [read-bencode]]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as str]
            [sci.core :as sci]
            [sci.impl.utils :as sci-utils]
            [sci.impl.vars :as vars])
  (:import [java.io InputStream PushbackInputStream PrintWriter EOFException BufferedOutputStream BufferedWriter Writer]
           [java.net ServerSocket]))

(set! *warn-on-reflection* true)

(def pretty-print-fns-map
  {"clojure.core/prn" prn
   "clojure.pprint/pprint" pprint
   "cider.nrepl.pprint/pprint" pprint})

(defn make-stream []
  (let [callback (atom
                      (fn
                        ([x])
                        ([x off len])))

        pw (-> (proxy [Writer] []
                     (write
                       ([x]
                        (@callback x))
                       ([x off len]
                        (@callback x off len)))
                     (flush [])
                     (close []))
                   (BufferedWriter. 1024)
                   (PrintWriter. true))]
    [callback pw]))

(defn eval-msg [rf result {:keys [ctx msg opts]}]
  (let [current-result (volatile! result)]
    (try
      (let [code-str (get msg :code)
            reader (sci/reader code-str)
            ns-str (get msg :ns)
            sci-ns (when ns-str (sci-utils/namespace-object (:env ctx) (symbol ns-str) true nil))
            file (:file msg)
            debug (:debug opts)
            nrepl-pprint (:nrepl.middleware.print/print msg)
            _ (when debug (println "current ns" (vars/current-ns-name)))

            [err-callback err-pw] (make-stream)
            [out-callback out-pw] (make-stream)]
        (vreset!
         current-result
         (rf @current-result
             {:start-stream :err
              :session (:session msg)
              :callback err-callback
              :id (:id msg)}))
        (vreset!
         current-result
         (rf @current-result
             {:start-stream :out
              :session (:session msg)
              :callback out-callback
              :id (:id msg)}))

        (sci/with-bindings (cond-> {sci/*1 *1
                                    sci/*2 *2
                                    sci/*3 *3
                                    sci/*e *e
                                    sci/out out-pw
                                    sci/err err-pw}
                             sci-ns (assoc sci/ns sci-ns)
                             file (assoc sci/file file))
          (loop []
            (let [form (sci/parse-next ctx reader)
                  eof? (identical? :sci.core/eof form)]
              (when-not eof?
                (let [value (when-not eof?
                              (let [eval-result (sci/eval-form ctx form)]
                                (.flush ^java.io.Flushable out-pw)
                                (.flush ^java.io.Flushable err-pw)
                                eval-result))]
                  (set! *3 *2)
                  (set! *2 *1)
                  (set! *1 value)
                  (vreset!
                   current-result
                   (rf @current-result
                       {:response (utils/response-for msg
                                                      {"ns" (vars/current-ns-name)
                                                       "value" (if nrepl-pprint
                                                                 (if-let [pprint-fn (get pretty-print-fns-map nrepl-pprint)]
                                                                   (with-out-str (pprint-fn value))
                                                                   (do
                                                                     (when debug
                                                                       (println "Pretty-Printing is only supported for clojure.core/prn and clojure.pprint/pprint."))
                                                                     (pr-str value)))
                                                                 (pr-str value))})
                        :opts opts}))
                  (recur))))))
        (vreset!
         current-result
         (rf @current-result {:response (utils/response-for msg {"status" #{"done"}})
                              :opts opts})))
      (catch Exception ex
        (set! *e ex)
        (rf @current-result
            {:msg msg
             :ex ex
             :opts opts})))))

(defn fully-qualified-syms [ctx ns-sym]
  (let [syms (sci/eval-string* ctx (format "(keys (ns-map '%s))" ns-sym))
        sym-strs (map #(str "`" %) syms)
        sym-expr (str "[" (str/join " " sym-strs) "]")
        syms (sci/eval-string* ctx sym-expr)]
    syms))

(defn match [_alias->ns ns->alias query [sym-ns sym-name qualifier]]
  (let [pat (re-pattern (java.util.regex.Pattern/quote query))]
    (or (when (and (identical? :unqualified qualifier) (re-find pat sym-name))
          [sym-ns sym-name])
        (when sym-ns
          (or (when (re-find pat (str (get ns->alias (symbol sym-ns)) "/" sym-name))
                [sym-ns (str (get ns->alias (symbol sym-ns)) "/" sym-name)])
              (when (re-find pat (str sym-ns "/" sym-name))
                [sym-ns (str sym-ns "/" sym-name)]))))))

(defn complete [rf result {:keys [ctx msg opts]}]
  (let [debug (:debug ctx)]
    (try
      (let [ns-str (get msg :ns)
            sci-ns (when ns-str
                     (sci-utils/namespace-object (:env ctx) (symbol ns-str) nil false))]
        (sci/binding [vars/current-ns (or sci-ns @vars/current-ns)]
          (if-let [query (or (:symbol msg)
                             (:prefix msg))]
            (let [has-namespace? (str/includes? query "/")
                  from-current-ns (fully-qualified-syms ctx (sci/eval-string* ctx "(ns-name *ns*)"))
                  from-current-ns (map (fn [sym]
                                         [(namespace sym) (name sym) :unqualified])
                                       from-current-ns)
                  alias->ns (sci/eval-string* ctx "(let [m (ns-aliases *ns*)] (zipmap (keys m) (map ns-name (vals m))))")
                  ns->alias (zipmap (vals alias->ns) (keys alias->ns))
                  from-aliased-nss (doall (mapcat
                                           (fn [alias]
                                             (let [ns (get alias->ns alias)
                                                   syms (sci/eval-string* ctx (format "(keys (ns-publics '%s))" ns))]
                                               (map (fn [sym]
                                                      [(str ns) (str sym) :qualified])
                                                    syms)))
                                           (keys alias->ns)))
                  all-namespaces (->> (sci/eval-string* ctx (format "(all-ns)"))
                                      (map (fn [sym]
                                             [(str (.-name ^sci.impl.vars.SciNamespace sym)) nil :qualified])))
                  fully-qualified-names (when has-namespace?
                                          (let [fqns (symbol (first (str/split query #"/")))
                                                ns (get alias->ns fqns fqns)
                                                syms (sci/eval-string* ctx (format "(keys (ns-publics '%s))" ns))]
                                            (map (fn [sym]
                                                   [(str ns) (str sym) :qualified])
                                                 syms)))
                  svs (concat from-current-ns from-aliased-nss all-namespaces fully-qualified-names)
                  completions (keep (fn [entry]
                                      (match alias->ns ns->alias query entry))
                                    svs)
                  completions (->> (map (fn [[namespace name]]
                                          {"candidate" (str name) "ns" (str namespace) #_"type" #_"function"})
                                        completions)
                                   set)]
              (when debug (prn "completions" completions))
              (rf result
                  {:response (utils/response-for msg {"completions" completions
                                                      "status" #{"done"}})
                   :opts opts}))
            (rf result
                {:response (utils/response-for msg {"status" #{"done"}})
                 :opts opts}))))
      (catch Throwable e
        (println e)
        (rf result
            {:response (utils/response-for msg {"completions" []
                                                "status" #{"done"}})
             :opts opts})))))

(defn close-session [rf result {:keys [ctx msg opts]}]
  (let [session (:session msg)]
    (swap! (:sessions ctx) disj session))
  (rf result
      {:response (utils/response-for msg {"status" #{"done" "session-closed"}})
       :opts opts}))

(defn ls-sessions [rf result {:keys [ctx msg opts]}]
  (let [sessions @(:sessions ctx)]
    (rf result
        {:response (utils/response-for msg {"sessions" sessions
                                            "status" #{"done"}})
         :opts opts})))

(defn forms-join [forms]
  (->> (map pr-str forms)
       (str/join \newline)))

(defn lookup [rf result {:keys [ctx msg opts]}]
  (let [ns-str (:ns msg)
        sym-str (or (:sym msg) (:symbol msg))
        mapping-type (-> msg :op)
        debug (:debug opts)
        sci-ns (when ns-str
                 (sci-utils/namespace-object (:env ctx) (symbol ns-str) nil false))]
    (try
      (sci/binding [vars/current-ns (or sci-ns @vars/current-ns)]
        (let [m (sci/eval-string* ctx (format "
(let [ns '%s
      full-sym '%s]
  (when-let [v (ns-resolve ns full-sym)]
    (let [m (meta v)]
      (assoc m :arglists (:arglists m)
       :doc (:doc m)
       :name (:name m)
       :ns (some-> m :ns ns-name)
       :val @v))))" ns-str sym-str))
              doc (:doc m)
              reply (case mapping-type
                      :eldoc (cond->
                                 {"ns" (:ns m)
                                  "name" (:name m)
                                  "eldoc" (mapv #(mapv str %) (:arglists m))
                                  "type" (cond
                                           (ifn? (:val m)) "function"
                                           :else "variable")
                                  "status" #{"done"}}
                               doc (assoc "docstring" doc))
                      (:info :lookup) (cond->
                                          {"ns" (:ns m)
                                           "name" (:name m)
                                           "arglists-str" (forms-join (:arglists m))
                                           "status" #{"done"}}
                                        doc (assoc "doc" doc)))]
          (rf result {:response (utils/response-for msg reply)
                      :opts opts})))
      (catch Throwable e
        (when debug (println e))
        (let [status (cond-> #{"done"}
                       (= mapping-type :eldoc)
                       (conj "no-eldoc"))]
          (rf result
              {:response (utils/response-for msg {"status" status})
               :opts opts}))))))

(defn read-msg [msg]
  (-> (zipmap (map keyword (keys msg))
              (map #(if (bytes? %)
                      (String. (bytes %))
                      %) (vals msg)))
      (update :op keyword)))

;; run (bb | clojure) script/update_version.clj to update this version
(def babashka-nrepl-version "0.0.5-SNAPSHOT")

(defmulti process-msg
  (fn [rf result m]
    (-> m :msg :op)))

(defmethod process-msg :clone [rf result {:keys [ctx msg opts] :as m}]
  (when (:debug opts) (println "Cloning!"))
  (let [id (str (java.util.UUID/randomUUID))]
    (swap! (:sessions ctx) (fnil conj #{}) id)
    (rf result {:response (utils/response-for msg {"new-session" id "status" #{"done"}})
                :opts opts})))

(defmethod process-msg :close [rf result {:keys [ctx msg opts] :as m}]
  (close-session rf result m))

(defmethod process-msg :eval [rf result m]
  (eval-msg rf result m))

(defmethod process-msg :load-file [rf result {:keys [ctx msg opts] :as m}]
  (let [file (:file msg)
        msg (assoc msg :code file)]
    (eval-msg rf result (assoc-in m [:msg :code] (:file msg)))))

(defmethod process-msg :complete [rf result {:keys [ctx msg opts] :as m}]
  (complete rf result m))

(defmethod process-msg :lookup [rf result m]
  (lookup rf result m))

(defmethod process-msg :info [rf result m]
  (lookup rf result m))

(defmethod process-msg :describe [rf result {:keys [msg opts] :as m}]
  (rf result {:response (utils/response-for
                         msg
                         (merge-with merge
                                     {"status" #{"done"}
                                      "ops" (zipmap #{"clone" "close" "eval" "load-file"
                                                      "complete" "describe" "ls-sessions"
                                                      "eldoc" "info" "lookup"}
                                                    (repeat {}))
                                      "versions" {"babashka.nrepl" babashka-nrepl-version}}
                                     (:describe opts)))
              :opts opts}))

(defmethod process-msg :ls-sessions [rf result m]
  (ls-sessions rf result m))

(defmethod process-msg :eldoc [rf result m]
  (lookup rf result m))

(defmethod process-msg :default [rf result {:keys [opts msg]}]
  (when (:debug opts)
    (println "Unhandled message" msg))
  (rf result
      {:response (utils/response-for msg {"status" #{"error" "unknown-op" "done"}})
       :opts opts}))

(defn session-loop [rf is os {:keys [ctx opts id] :as m} ]
  (when (:debug opts) (println "Reading!" id (.available ^InputStream is)))
  (when-let [msg (try (read-bencode is)
                      (catch EOFException _
                        (when-not (:quiet opts)
                          (println "Client closed connection."))))]
    (let [response (rf os {:msg msg
                           :opts opts
                           :ctx ctx})])
    (recur rf is os m)))

(defn send-reduce [os response]
  (if-let [ex (:ex response)]
    (utils/send-exception os (:msg response) ex (:opts response))
    (utils/send os (:response response) (:opts response)))
  os)

(defn listen [ctx ^ServerSocket listener {:keys [debug thread-bind xform] :as opts}]
  (when debug (println "Listening"))
  (let [client-socket (.accept listener)
        in (.getInputStream client-socket)
        in (PushbackInputStream. in)
        out (.getOutputStream client-socket)
        out (BufferedOutputStream. out)
        rf (xform send-reduce)]
    (when debug (println "Connected."))
    (sci/future
      (binding [*1 nil
                *2 nil
                *3 nil
                *e nil]
        (sci/with-bindings
          (merge {sci/ns (sci/create-ns 'user nil)
                  sci/print-length @sci/print-length
                  sci/*1 nil
                  sci/*2 nil
                  sci/*3 nil
                  sci/*e nil}
                 (zipmap thread-bind (map deref thread-bind)))
          (session-loop rf in out {:opts opts
                                   :id "pre-init"
                                   :ctx ctx}))))
    (recur ctx listener opts)))
