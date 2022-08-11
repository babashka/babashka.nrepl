(ns babashka.nrepl.impl.server
  {:author "Michiel Borkent"
   :no-doc true}
  (:require
   [babashka.nrepl.impl.utils :as utils]
   [bencode.core :refer [read-bencode]]
   [clojure.pprint :refer [pprint]]
   [clojure.reflect]
   [clojure.string :as str]
   [sci.core :as sci])
  (:import
   [java.io
    BufferedOutputStream
    BufferedWriter
    EOFException
    InputStream
    PrintWriter
    PushbackInputStream
    StringWriter
    Writer]
   [java.net ServerSocket]))

(set! *warn-on-reflection* true)

(def pretty-print-fns-map
  {"clojure.core/prn" prn
   "clojure.pprint/pprint" pprint
   "cider.nrepl.pprint/pprint" pprint})

(defn- to-char-array
  ^chars
  [x]
  (cond
    (string? x) (.toCharArray ^String x)
    (integer? x) (char-array [(char x)])
    :else x))

(defn make-writer [rf result msg stream-key]
  (let [pw (-> (proxy [Writer] []
                 (write
                   ([x]
                    (let [cbuf (to-char-array x)]
                      (.write ^Writer this cbuf (int 0) (count cbuf))))
                   ([x off len]
                    (let [cbuf (to-char-array x)
                          text (str (doto (StringWriter.)
                                      (.write cbuf ^int off ^int len)))]
                      (when (pos? (count text))
                        (rf result
                            {:response-for msg
                             :response {stream-key text}})))))
                 (flush [])
                 (close []))
               (BufferedWriter. 1024)
               (PrintWriter. true))]
    pw))

(defn the-sci-ns [ctx ns-sym]
  (sci/eval-form ctx (list 'clojure.core/the-ns (list 'quote ns-sym))))

;; :nrepl.middleware.print/options {"right-margin" 120, "length" 50, "level" 10}

(defn format-value [nrepl-pprint debug v msg]
  (if nrepl-pprint
    (if-let [pprint-fn (get pretty-print-fns-map nrepl-pprint)]
      (let [{:strs [right-margin length level]} (get msg :nrepl.middleware.print/options)]
        (binding [*print-length* length
                  *print-level* level
                  clojure.pprint/*print-right-margin* right-margin]
          (with-out-str (pprint-fn v))))
      (do
        (when debug
          (println "Pretty-Printing is only supported for clojure.core/prn and clojure.pprint/pprint."))
        (pr-str v)))
    (pr-str v)))

(defn eval-msg [rf result {:keys [ctx msg opts]}]
  (try
    (let [ctx (assoc ctx :main-thread-id (.getId (Thread/currentThread)))
          debug (:debug opts)
          code-str (get msg :code)
          load-file? (:load-file msg)
          file (if load-file?
                 (or (:file-path msg)
                     (:file-name msg))
                 (:file msg))
          reader (sci/reader code-str)
          ns-str (get msg :ns)
          sci-ns (when ns-str (the-sci-ns ctx (symbol ns-str)))
          nrepl-pprint (:nrepl.middleware.print/print msg)
          _ (when (:debug opts)
              (prn :msg msg))
          err-pw (make-writer rf result msg "err")
          out-pw (make-writer rf result msg "out")]
      (when debug (println "current ns" (str @sci/ns)))
      (sci/with-bindings (cond-> {sci/*1 *1
                                  sci/*2 *2
                                  sci/*3 *3
                                  sci/*e *e
                                  sci/out out-pw
                                  sci/err err-pw}
                           file (assoc sci/file file)
                           load-file? (assoc sci/ns @sci/ns)
                           sci-ns (assoc sci/ns sci-ns))
        (let [last-val
              (loop [last-val nil]
                (let [form (sci/parse-next ctx reader)
                      eof? (identical? :sci.core/eof form)]
                  (if-not eof?
                    (let [value (when-not eof?
                                  (let [result (sci/eval-form ctx form)]
                                    (.flush ^Writer out-pw)
                                    (.flush ^Writer err-pw)
                                    result))]
                      (when-not load-file?
                        (set! *3 *2)
                        (set! *2 *1)
                        (set! *1 value)
                        (rf result
                            {:response-for msg
                             :response {"ns" (str @sci/ns)
                                        "value" (format-value nrepl-pprint debug value msg)}
                             :opts opts}))
                      (recur value))
                    last-val)))]
          (when load-file?
            (rf result
                {:response-for msg
                 :response {"value" (format-value nrepl-pprint debug last-val msg)}
                 :opts opts}))))
      (rf result
          {:response-for msg
           :response {"status" #{"done"}}
           :opts opts}))
    (catch Throwable ex
      (set! *e ex)
      (rf result
          {:response-for msg
           :ex ex
           :opts opts}))))

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

(defn ns-imports->completions [ctx query-ns query]
  (let [[ns-part name-part] (str/split query #"/")
        resolved (sci/eval-string* ctx
                                   (pr-str `(let [resolved# (resolve '~query-ns)]
                                              (when-not (var? resolved#)
                                                resolved#))))
        pat (when name-part (re-pattern (java.util.regex.Pattern/quote name-part)))]
    (when
        resolved
        (->>
         (clojure.reflect/reflect resolved)
         :members
         (into
          []
          (comp
           (filter (comp :static :flags))
           (filter (comp :public :flags))
           (filter (fn [{member-sym :name}]
                     (or (not pat) (re-find pat (str member-sym)))))
           (map
            (fn [{:keys [name parameter-types]}]
              [ns-part
               (str ns-part "/" name)
               (if parameter-types "static-method" "static-field")]))))))))

(defn import-symbols->completions [imports query]
  (let [pat (re-pattern (java.util.regex.Pattern/quote query))]
    (doall
     (sequence
      (comp
       (map key)
       (filter
        (fn [sym-name]
          (re-find pat (str sym-name))))
       (map (fn [class-name]
              [nil (str class-name) "class"])))
      imports))))


(defn complete [rf result {:keys [ctx msg opts]}]
  (try
    (let [ns-str (get msg :ns)
          sci-ns (when ns-str
                   (the-sci-ns ctx (symbol ns-str)))]
      (sci/binding [sci/ns (or sci-ns @sci/ns)]
        (if-let [query (or (:symbol msg)
                           (:prefix msg))]
          (let [has-namespace? (str/includes? query "/")
                query-ns (when has-namespace? (symbol (first (str/split query #"/"))))
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
                                    (map (fn [ns]
                                           [(str ns) nil :qualified])))
                from-imports (when query-ns (ns-imports->completions ctx (symbol query-ns) query))
                ns-found? (sci/eval-string* ctx (format "(find-ns '%s)" query-ns))
                fully-qualified-names (when-not from-imports
                                        (when (and has-namespace? ns-found?)
                                          (let [ns (get alias->ns query-ns query-ns)
                                                syms (sci/eval-string* ctx (format "(keys (ns-publics '%s))" ns))]
                                            (map (fn [sym]
                                                   [(str ns) (str sym) :qualified])
                                                 syms))))
                svs (concat from-current-ns from-aliased-nss all-namespaces fully-qualified-names)
                completions (keep (fn [entry]
                                    (match alias->ns ns->alias query entry))
                                  svs)
                completions (concat completions from-imports)
                import-symbols (import-symbols->completions (:imports @(:env ctx)) query)
                completions (concat completions import-symbols)
                completions (->> (map (fn [[namespace name type]]
                                        (cond->
                                            {"candidate" (str name)}
                                            namespace (assoc "ns" (str namespace))
                                            type (assoc "type" (str type))))
                                      completions)
                                 set)]
            (when (:debug opts) (prn "completions" completions))
            (rf result
                {:response-for msg
                 :response {"completions" completions
                            "status" #{"done"}}
                 :opts opts}))
          (rf result
              {:response-for msg
               :response {"status" #{"done"}}
               :opts opts}))))
    (catch Throwable e
      (println e)
      (rf result
          {:response-for msg
           :response {"completions" []
                      "status" #{"done"}}
           :opts opts}))))


(defn close-session [rf result {:keys [ctx msg opts]}]
  (let [session (:session msg)]
    (swap! (:sessions ctx) disj session))
  (rf result
      {:response {"status" #{"done" "session-closed"}}
       :response-for msg
       :opts opts}))

(defn ls-sessions [rf result {:keys [ctx msg opts]}]
  (let [sessions @(:sessions ctx)]
    (rf result
        {:response {"sessions" sessions
                    "status" #{"done"}}
         :response-for msg
         :opts opts})))

(defn forms-join [forms]
  (->> (map pr-str forms)
       (str/join \newline)))

(defn lookup [rf result {:keys [ctx msg opts]}]
  (let [ns-str (:ns msg)
        sym-str (or (:sym msg) (:symbol msg))
        mapping-type (-> msg :op)
        debug (:debug opts)]
    (try
      (let [sci-ns (when ns-str
                     (the-sci-ns ctx (symbol ns-str)))]
        (sci/binding [sci/ns (or sci-ns @sci/ns)]
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
                file (:file m)
                line (:line m)
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
                                          doc (assoc "doc" doc)
                                          file (assoc "file" file)
                                          line (assoc "line" line)))]
            (rf result {:response reply
                        :response-for msg
                        :opts opts}))))
      (catch Throwable e
        (when debug (println e))
        (let [status (cond-> #{"done"}
                       (= mapping-type :eldoc)
                       (conj "no-eldoc"))]
          (rf result
              {:response {"status" status}
               :response-for msg
               :opts opts}))))))

(defn read-msg [msg]
  (-> (zipmap (map keyword (keys msg))
              (map #(if (bytes? %)
                      (String. (bytes %))
                      %) (vals msg)))
      (update :op keyword)))

;; run (bb | clojure) script/update_version.clj to update this version
(def babashka-nrepl-version "0.0.6-SNAPSHOT")

(defmulti process-msg
  (fn [rf result m]
    (-> m :msg :op)))

(defmethod process-msg :clone [rf result {:keys [ctx msg opts] :as m}]
  (when (:debug opts) (println "Cloning!"))
  (let [id (str (java.util.UUID/randomUUID))]
    (swap! (:sessions ctx) (fnil conj #{}) id)
    (rf result {:response {"new-session" id "status" #{"done"}}
                :response-for msg
                :opts opts})))

(defmethod process-msg :close [rf result {:keys [ctx msg opts] :as m}]
  (close-session rf result m))

(defmethod process-msg :eval [rf result m]
  (eval-msg rf result m))

(defmethod process-msg :load-file [rf result {:keys [ctx msg opts] :as m}]
  (let [file (:file msg)
        msg (assoc msg
                   :code file
                   :file-path (:file-path msg)
                   :file-name (:file-name msg)
                   :load-file true)]
    (eval-msg rf result (assoc m :msg msg))))

(defmethod process-msg :complete [rf result {:keys [ctx msg opts] :as m}]
  (complete rf result m))

(defmethod process-msg :lookup [rf result m]
  (lookup rf result m))

(defmethod process-msg :info [rf result m]
  (lookup rf result m))

(defmethod process-msg :describe [rf result {:keys [msg opts] :as m}]
  (rf result {:response (merge-with merge
                                    {"status" #{"done"}
                                     "ops" (zipmap #{"clone" "close" "eval" "load-file"
                                                     "complete" "describe" "ls-sessions"
                                                     "eldoc" "info" "lookup"}
                                                   (repeat {}))
                                     "versions" {"babashka.nrepl" babashka-nrepl-version}}
                                    (:describe opts))
              :response-for msg
              :opts opts}))

(defmethod process-msg :ls-sessions [rf result m]
  (ls-sessions rf result m))

(defmethod process-msg :eldoc [rf result m]
  (lookup rf result m))

(defmethod process-msg :default [rf result {:keys [opts msg]}]
  (when (:debug opts)
    (println "Unhandled message" msg))
  (rf result
      {:response {"status" #{"error" "unknown-op" "done"}}
       :response-for msg
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
    (utils/send-exception os (:response-for response) ex (:opts response))
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
