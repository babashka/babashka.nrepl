(ns babashka.nrepl.impl.server
  {:author "Michiel Borkent"
   :no-doc true}
  (:require [babashka.nrepl.impl.utils :as utils]
            [bencode.core :refer [read-bencode]]
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
                          (let [result (sci/with-bindings {sci/out pw}
                                         (eval-form ctx form))]
                            (.flush pw)
                            result))
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
                all-namespaces (->> (eval-string* ctx (format "(all-ns)"))
                                    (map (fn [sym]
                                           [(str (.-name ^sci.impl.vars.SciNamespace sym)) nil :qualified])))
                fully-qualified-names (when has-namespace?
                                        (let [fqns (symbol (first (str/split query #"/")))
                                              ns (get alias->ns fqns fqns)
                                              syms (eval-string* ctx (format "(keys (ns-publics '%s))" ns))]
                                          (map (fn [sym]
                                                 [(str ns) (str sym) :qualified])
                                               syms)))
                svs (concat from-current-ns from-aliased-nss all-namespaces fully-qualified-names)
                completions (keep (fn [entry]
                                    (match alias->ns ns->alias query entry))
                                  svs)
                completions (mapv (fn [[namespace name]]
                                    {"candidate" (str name) "ns" (str namespace) #_"type" #_"function"})
                                  completions)]
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

(defn eldoc [ctx msg os opts]
  (let [ns-str (:ns msg)
        sym-str (:sym msg)
        sci-ns (when ns-str
                 (sci-utils/namespace-object (:env ctx) (symbol ns-str) nil false))]
    (sci/binding [vars/current-ns (or sci-ns @vars/current-ns)]
      (let [m (eval-string* ctx (format "
(when-let [v (ns-resolve '%s '%s)]
  (let [m (meta v)]
    (assoc m :arglists (:arglists m)
     :doc (:doc m)
     :name (:name m)
     :ns (some-> m :ns ns-name)
     :val @v)))" ns-str sym-str))
            reply {"ns" (:ns m)
                   "name" (:name m)
                   "eldoc" (mapv #(mapv str %) (:arglists m))
                   "type" (cond
                            (:macro m) "macro"
                            (ifn? (:val m)) "function"
                            :else "unknown")
                   "docstring" (:doc m)
                   "status" #{"done"}}]
        (utils/send os
                    (utils/response-for msg reply) opts)))))

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
        :describe
        (do (utils/send os (utils/response-for
                            msg
                            (merge-with merge
                             {"status" #{"done"}
                              "ops" (zipmap #{"clone" "close" "eval" "load-file"
                                              "complete" "describe" "ls-sessions"
                                              "eldoc"}
                                            (repeat {}))
                              "versions" {"babashka.nrepl" babashka-nrepl-version}}
                             (:describe opts))) opts)
            (recur ctx is os id opts))
        :ls-sessions (do (ls-sessions ctx msg os opts)
                         (recur ctx is os id opts))
        :eldoc (do
                 (eldoc ctx msg os opts)
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
