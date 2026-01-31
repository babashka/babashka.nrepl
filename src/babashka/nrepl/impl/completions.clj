(ns babashka.nrepl.impl.completions
  "Completion logic for SCI-based REPLs.
   Extracted from nREPL server to be reusable by console REPL."
  {:author "Michiel Borkent"
   :no-doc true}
  (:require
   [clojure.reflect]
   [clojure.string :as str]
   [sci.core :as sci]))

(set! *warn-on-reflection* true)

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

(defn completions
  "Returns completions for the given query string using the SCI context.
   Returns a set of maps with :candidate (required), :ns (optional), :type (optional)."
  [ctx query]
  (when (and query (pos? (count query)))
    (try
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
            all-namespaces (->> (sci/eval-string* ctx "(all-ns)")
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
            completions (concat completions import-symbols)]
        {:completions
         (->> (map (fn [[namespace name type]]
                     (cond->
                      {:candidate (str name)}
                       namespace (assoc :ns (str namespace))
                       type (assoc :type (str type))))
                   completions)
              distinct
              (sort-by (fn [{:keys [candidate]}]
                         [(not (str/starts-with? candidate query)) ; prefix matches first
                          (count candidate)                        ; shorter first
                          candidate])))})
      (catch Throwable e
        {:error e :completions []}))))
