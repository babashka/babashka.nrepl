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
  (:import [java.io InputStream PushbackInputStream EOFException BufferedOutputStream]
           [java.net ServerSocket]))

(set! *warn-on-reflection* true)

(def pretty-print-fns-map
  {"clojure.core/prn" prn
   "clojure.pprint/pprint" pprint
   "cider.nrepl.pprint/pprint" pprint})

(defn eval-msg [ctx o msg {:keys [debug] :as opts}]
  (try
    (let [code-str (get msg :code)
          reader (sci/reader code-str)
          ns-str (get msg :ns)
          sci-ns (when ns-str (sci-utils/namespace-object (:env ctx) (symbol ns-str) true nil))
          file (:file msg)
          nrepl-pprint (:nrepl.middleware.print/print msg)
          out-pw (utils/replying-print-writer "out" o msg opts)
          err-pw (utils/replying-print-writer "err" o msg opts)]
      (when debug (println "current ns" (vars/current-ns-name)))
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
                            (let [result (sci/eval-form ctx form)]
                              (.flush out-pw)
                              (.flush err-pw)
                              result))]
                (set! *3 *2)
                (set! *2 *1)
                (set! *1 value)
                (utils/send o (utils/response-for msg
                                                  {"ns" (vars/current-ns-name)
                                                   "value" (if nrepl-pprint
                                                             (if-let [pprint-fn (get pretty-print-fns-map nrepl-pprint)]
                                                               (with-out-str (pprint-fn value))
                                                               (do
                                                                 (when debug
                                                                   (println "Pretty-Printing is only supported for clojure.core/prn and clojure.pprint/pprint."))
                                                                 (pr-str value)))
                                                             (pr-str value))}) opts)
                (recur))))))
      (utils/send o (utils/response-for msg {"status" #{"done"}}) opts))
    (catch Exception ex
      (set! *e ex)
      (utils/send-exception o msg ex opts))))

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

(defn complete [ctx o msg {:keys [debug] :as opts}]
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
            (utils/send o (utils/response-for msg {"completions" completions
                                                   "status" #{"done"}}) opts))
          (utils/send o (utils/response-for msg {"status" #{"done"}}) opts))))
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

(defn lookup [ctx msg os mapping-type {:keys [debug] :as opts}]
  (let [ns-str (:ns msg)
        sym-str (or (:sym msg) (:symbol msg))
        sym (symbol sym-str)
        sym-name (name sym)
        sci-ns (when ns-str
                 (sci-utils/namespace-object (:env ctx) (symbol ns-str) nil false))]
    (try
      (sci/binding [vars/current-ns (or sci-ns @vars/current-ns)]
        (let [m (sci/eval-string* ctx (format "
(let [ns '%s
      full-sym '%s
      sym-name '%s]
  (when-let [v (ns-resolve ns full-sym)]
    (let [m (meta v)]
      (assoc m :arglists (:arglists m)
       :doc (:doc m)
       :name (:name m)
       :ns (some-> m :ns ns-name)
       :val @v))))" ns-str sym-str sym-name))
              arglists-vec (mapv #(mapv str %) (:arglists m))
              doc (:doc m)
              reply (case mapping-type
                      :eldoc (cond->
                                 {"ns" (:ns m)
                                  "name" (:name m)
                                  "eldoc" arglists-vec
                                  "type" (cond
                                           (ifn? (:val m)) "function"
                                           :else "variable")
                                  "status" #{"done"}}
                               doc (assoc "docstring" doc))
                      :lookup (cond->
                                  {"ns" (:ns m)
                                   "name" (:name m)
                                   "arglists-str" (str arglists-vec)
                                   "status" #{"done"}}
                                doc (assoc "doc" doc)))]
          (utils/send os
                      (utils/response-for msg reply) opts)))
      (catch Throwable e
        (when debug (println e))
        (let [status (cond-> #{"done"}
                       (= mapping-type :eldoc)
                       (conj "no-eldoc"))]
          (utils/send os (utils/response-for msg {"status" status}) opts))))))

(defn read-msg [msg]
  (-> (zipmap (map keyword (keys msg))
              (map #(if (bytes? %)
                      (String. (bytes %))
                      %) (vals msg)))
      (update :op keyword)))

;; run (bb | clojure) script/update_version.clj to update this version
(def babashka-nrepl-version "0.0.4-SNAPSHOT")

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
        (:lookup :info) (do
                          (lookup ctx msg os :lookup opts)
                          (recur ctx is os id opts))
        :describe
        (do (utils/send os (utils/response-for
                            msg
                            (merge-with merge
                             {"status" #{"done"}
                              "ops" (zipmap #{"clone" "close" "eval" "load-file"
                                              "complete" "describe" "ls-sessions"
                                              "eldoc" "info" "lookup"}
                                            (repeat {}))
                              "versions" {"babashka.nrepl" babashka-nrepl-version}}
                             (:describe opts))) opts)
            (recur ctx is os id opts))
        :ls-sessions (do (ls-sessions ctx msg os opts)
                         (recur ctx is os id opts))
        :eldoc (do
                 (lookup ctx msg os :eldoc opts)
                 (recur ctx is os id opts))
        ;; fallback
        (do (when debug
              (println "Unhandled message" msg))
            (utils/send os (utils/response-for msg {"status" #{"error" "unknown-op" "done"}}) opts)
            (recur ctx is os id opts))))))

(defn listen [ctx ^ServerSocket listener {:keys [debug thread-bind] :as opts}]
  (when debug (println "Listening"))
  (let [client-socket (.accept listener)
        in (.getInputStream client-socket)
        in (PushbackInputStream. in)
        out (.getOutputStream client-socket)
        out (BufferedOutputStream. out)]
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
          (session-loop ctx in out "pre-init" opts))))
    (recur ctx listener opts)))
