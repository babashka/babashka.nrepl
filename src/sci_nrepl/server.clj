(ns sci-nrepl.server
  {:author "Michiel Borkent"
   :no-doc true}
  (:require [bencode.core :refer [read-bencode]]
            [sci-nrepl.utils :as utils]
            [clojure.string :as str]
            [clojure.tools.reader.reader-types :as r]
            [sci.core :as sci]
            [sci.impl.interpreter :refer [eval-string* eval-form]]
            [sci.impl.parser :as p]
            [sci.impl.utils :as sci-utils]
            [sci.impl.vars :as vars])
  (:import [java.io InputStream PushbackInputStream EOFException BufferedOutputStream]
           [java.net ServerSocket]))

(set! *warn-on-reflection* true)

(defn eval-msg [ctx o msg {:keys [debug] :as opts}]
  (try
    (let [code-str (get msg :code)
          reader (r/indexing-push-back-reader (r/string-push-back-reader code-str))
          ns-str (get msg :ns)
          sci-ns (when ns-str (sci-utils/namespace-object (:env ctx) (symbol ns-str) true nil))]
      (when debug (println "current ns" (vars/current-ns-name)))
      (sci/with-bindings (cond-> {}
                           sci-ns (assoc vars/current-ns sci-ns))
        (loop []
          (let [pw (utils/replying-print-writer o msg opts)
                form (p/parse-next ctx reader)
                value (if (identical? :edamame.impl.parser/eof form) ::nil
                          (sci/with-bindings {sci/out pw}
                            (eval-form ctx form)))
                env (:env ctx)]
            (swap! env update-in [:namespaces 'clojure.core]
                   (fn [core]
                     (assoc core
                            '*1 value
                            '*2 (get core '*1)
                            '*3 (get core '*2))))
            (utils/send o (utils/response-for msg (cond-> {"ns" (vars/current-ns-name)}
                                        (not (identical? value ::nil)) (assoc "value" (pr-str value)))) opts)
            (when (not (identical? ::nil value))
              (recur)))))
      (utils/send o (utils/response-for msg {"status" #{"done"}}) opts))
    (catch Exception ex
      (swap! (:env ctx) update-in [:namespaces 'clojure.core]
             assoc '*e ex)
      (utils/send-exception o msg ex opts))))

(defn fully-qualified-syms [ctx ns-sym]
  (let [syms (eval-string* ctx (format "(keys (ns-map '%s))" ns-sym))
        sym-strs (map #(str "`" %) syms)
        sym-expr (str "[" (str/join " " sym-strs) "]")
        syms (eval-string* ctx sym-expr)]
    syms))

(defn match [_alias->ns ns->alias query [sym-ns sym-name qualifier]]
  (let [pat (re-pattern query)]
    (or (when (and (identical? :unqualified qualifier) (re-find pat sym-name))
          [sym-ns sym-name])
        (when sym-ns
          (or (when (re-find pat (str (get ns->alias (symbol sym-ns)) "/" sym-name))
                [sym-ns (str (get ns->alias (symbol sym-ns)) "/" sym-name)])
              (when (re-find pat (str sym-ns "/" sym-name))
                [sym-ns (str sym-ns "/" sym-name)]))))))

(defn complete [ctx o msg {:keys [debug] :as opts}]
  (try
    (let [ns-str (get msg :ns)
          sci-ns (when ns-str
                   (sci-utils/namespace-object (:env ctx) (symbol ns-str) nil false))]
      (sci/binding [vars/current-ns (or sci-ns @vars/current-ns)]
        (let [query (:symbol msg)
              from-current-ns (fully-qualified-syms ctx (eval-string* ctx "(ns-name *ns*)"))
              from-current-ns (map (fn [sym]
                                     [(namespace sym) (name sym) :unqualified])
                                   from-current-ns)
              alias->ns (eval-string* ctx "(let [m (ns-aliases *ns*)] (zipmap (keys m) (map ns-name (vals m))))")
              ns->alias (zipmap (vals alias->ns) (keys alias->ns))
              from-aliased-nss (doall (mapcat
                                       (fn [alias]
                                         (let [ns (get alias->ns alias)
                                               syms (eval-string* ctx (format "(keys (ns-publics '%s))" ns))]
                                           (map (fn [sym]
                                                  [(str ns) (str sym) :qualified])
                                                syms)))
                                       (keys alias->ns)))
              svs (concat from-current-ns from-aliased-nss)
              completions (keep (fn [entry]
                                  (match alias->ns ns->alias query entry))
                                svs)
              completions (mapv (fn [[namespace name]]
                                  {"candidate" (str name) "ns" (str namespace) #_"type" #_"function"})
                                completions)]
          (when debug (prn "completions" completions))
          (utils/send o (utils/response-for msg {"completions" completions
                                     "status" #{"done"}}) opts))))
       (catch Throwable e
         (println e)
         (utils/send o (utils/response-for msg {"completions" []
                                    "status" #{"done"}}) opts))))

(defn close-session [ctx msg _is os opts]
  (let [session (:session msg)]
    (swap! (:sessions ctx) disj session))
  (utils/send os (utils/response-for msg {"status" #{"done" "session-closed"}}) opts))

(defn ls-sessions [ctx msg os opts]
  (let [sessions @(:sessions ctx)]
    (utils/send os (utils/response-for msg {"sessions" sessions
                                "status" #{"done"}}) opts)))

(defn read-msg [msg]
  (-> (zipmap (map keyword (keys msg))
              (map #(if (bytes? %)
                      (String. (bytes %))
                      %) (vals msg)))
      (update :op keyword)))

(defn session-loop [ctx ^InputStream is os id {:keys [quiet debug] :as opts}]
  (when debug (println "Reading!" id (.available is)))
  (when-let [msg (try (read-bencode is)
                      (catch EOFException _
                        (when-not quiet
                          (println "Client closed connection."))))]
    (let [msg (read-msg msg)]
      (when debug (prn "Received" msg))
      (case (get msg :op)
        :clone (do
                 (when debug (println "Cloning!"))
                 (let [id (str (java.util.UUID/randomUUID))]
                   (swap! (:sessions ctx) (fnil conj #{}) id)
                   (utils/send os (utils/response-for msg {"new-session" id "status" #{"done"}}) opts)
                   (recur ctx is os id opts)))
        :close (do (close-session ctx msg is os opts)
                   (recur ctx is os id opts))
        :eval (do
                (eval-msg ctx os msg opts)
                (recur ctx is os id opts))
        :load-file (let [file (:file msg)
                         msg (assoc msg :code file)]
                     (eval-msg ctx os msg opts)
                     (recur ctx is os id opts))
        :complete (do
                    (complete ctx os msg opts)
                    (recur ctx is os id opts))
        :describe
        (do (utils/send os (utils/response-for msg {"status" #{"done"}
                                        "ops" (zipmap #{"clone" "close" "eval" "load-file"
                                                        "complete" "describe" "ls-sessions"}
                                                      (repeat {}))}) opts)
            (recur ctx is os id opts))
        :ls-sessions (do (ls-sessions ctx msg os opts)
                         (recur ctx is os id opts))
        ;; fallback
        (do (when debug
              (println "Unhandled message" msg))
            (utils/send os (utils/response-for msg {"status" #{"error" "unknown-op" "done"}}) opts)
            (recur ctx is os id opts))))))

(defn listen [ctx ^ServerSocket listener {:keys [debug] :as opts}]
  (when debug (println "Listening"))
  (let [client-socket (.accept listener)
        in (.getInputStream client-socket)
        in (PushbackInputStream. in)
        out (.getOutputStream client-socket)
        out (BufferedOutputStream. out)]
    (when debug (println "Connected."))
    (sci/future
      (sci/binding
          ;; allow *ns* to be set! inside future
          [vars/current-ns (vars/->SciNamespace 'user nil)
           sci/print-length @sci/print-length]
        (session-loop ctx in out "pre-init" opts)))
    (recur ctx listener opts)))

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
               (listen ctx socket-server opts))}))
