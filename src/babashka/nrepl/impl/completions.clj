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

(defn- class-sym?
  "Returns true if sym is a class import (e.g. clojure.lang.RT - has dots but no namespace)."
  [sym]
  (and (nil? (namespace sym))
       (str/includes? (name sym) ".")))

(defn- class-sym->completion
  "Converts a class symbol like clojure.lang.RT to [full-class-name simple-name type]."
  [sym]
  (let [n (name sym)
        idx (str/last-index-of n ".")]
    [n
     (subs n (inc idx))
     "class"]))

(defn match [_alias->ns ns->alias query [sym-ns sym-name qualifier]]
  (let [pat (re-pattern (java.util.regex.Pattern/quote query))
        has-slash? (str/includes? query "/")]
    (or (when (and (= "class" qualifier) (re-find pat sym-name))
          [sym-ns sym-name "class"])
        (when (and (identical? :unqualified qualifier) (re-find pat sym-name))
          [sym-ns sym-name])
        ;; Namespace-only entries (sym-name is nil) - match on namespace name
        (when (and (nil? sym-name) sym-ns (re-find pat sym-ns))
          [nil sym-ns "namespace"])
        ;; Qualified symbol matching
        (when (and sym-ns sym-name)
          (let [alias (get ns->alias (symbol sym-ns))
                alias-qualified (when alias (str alias "/" sym-name))
                ns-qualified (str sym-ns "/" sym-name)]
            (or ;; Always try alias-qualified matching (for `quux<TAB>` -> `quux/foo`)
             (when (and alias-qualified (re-find pat alias-qualified))
               [sym-ns alias-qualified])
                ;; Full namespace-qualified matching only when query has a slash
             (when (and has-slash? (re-find pat ns-qualified))
               [sym-ns ns-qualified])))))))

(defn- member-simple-name
  "Extracts simple name from a possibly qualified name (e.g., java.lang.String -> String)"
  [s]
  (let [idx (str/last-index-of s ".")]
    (if idx (subs s (inc idx)) s)))

(defn ns-imports->completions [ctx query-ns query]
  (let [[ns-part name-part] (str/split query #"/")
        resolved (sci/eval-string* ctx
                                   (pr-str `(let [resolved# (resolve '~query-ns)]
                                              (when-not (var? resolved#)
                                                resolved#))))
        pat (when name-part (re-pattern (java.util.regex.Pattern/quote name-part)))
        class-simple-name (when resolved (member-simple-name (str resolved)))]
    (when
     resolved
      (->>
       (clojure.reflect/reflect resolved)
       :members
       (into
        []
        (comp
         (filter (comp :public :flags))
         (filter (fn [{member-sym :name :keys [flags]}]
                   (let [static? (:static flags)
                         simple-name (member-simple-name (str member-sym))
                         constructor? (= simple-name class-simple-name)
                         member-str (cond
                                      constructor? "new"
                                      static? (str member-sym)
                                      :else (str "." member-sym))]
                     (or (not pat) (re-find pat member-str)))))
         (map
          (fn [{:keys [name parameter-types flags]}]
            (let [static? (:static flags)
                  simple-name (member-simple-name (str name))
                  constructor? (= simple-name class-simple-name)]
              [ns-part
               (cond
                 constructor? (str ns-part "/new")
                 static? (str ns-part "/" name)
                 :else (str ns-part "/." name))
               (cond
                 constructor? "constructor"
                 (and static? parameter-types) "static-method"
                 static? "static-field"
                 parameter-types "method"
                 :else "field")])))))))))

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

(defn fq-class->completions
  "Completions for fully qualified class names like java.lang.String"
  [classes query]
  (let [pat (re-pattern (java.util.regex.Pattern/quote query))]
    (doall
     (sequence
      (comp
       (map key)
       (map str)
       (filter (fn [fq-class] (re-find pat fq-class)))
       (map (fn [fq-class] [fq-class fq-class "class"])))
      classes))))

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
                                   (if (class-sym? sym)
                                     (class-sym->completion sym)
                                     [(namespace sym) (name sym) :unqualified]))
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
            completions (concat completions import-symbols)
            fq-classes (fq-class->completions (:raw-classes @(:env ctx)) query)
            completions (concat completions fq-classes)]
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

(comment
  (require '[sci.core :as sci])
  (def ctx (sci/init {:classes {'clojure.lang.RT clojure.lang.RT}}))
  (sci/eval-string* ctx "(import 'clojure.lang.RT)")
  (completions ctx "RT"))
