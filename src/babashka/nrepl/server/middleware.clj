(ns babashka.nrepl.server.middleware
  (:require [babashka.nrepl.impl.server :as server]
            clojure.set))

(def wrap-read-msg
  "Middleware for normalizing an nrepl message read from bencode."
  (map (fn [m]
         (update m :msg server/read-msg))))

;; make default message processing public
;; but not the underlying multimethod
(defn default-process-msg [rf result msg]
  (server/process-msg rf result msg))

(def wrap-response-for
  "Middleware responsible for updating message with session and id."
  (map (fn [response]
         (let [old-msg (:response-for response)
               session (get old-msg :session "none")
               id (get old-msg :id "unknown")]
           (-> response
               (assoc-in [:response "session"] session)
               (assoc-in [:response "id"] id))))))

(defn wrap-process-message
  "Middleware for producing responses based on op code."
  {::requires #{#'wrap-read-msg}
   ::expects #{#'wrap-response-for}}
  [rf]
  (completing
   (fn [result input]
     (default-process-msg rf result input))))

(def default-middleware
  #{#'wrap-read-msg
    #'wrap-process-message
    #'wrap-response-for})

(defn ^:private merge-graph [g1 g2]
  (merge-with clojure.set/union g1 g2))

(defn ^:private middleware->graph
  "Given a set of middleware, return a graph represented as a map.

  Each (key, value) pair is: (node, set of nodes pointed to).
  "
  [middleware]
  (transduce
   (map
    (fn [v]
      (reduce merge-graph
              {}
              (let [vmeta (meta v)
                    requires (::requires vmeta)
                    expects (::expects vmeta)]
                (assert (seqable? requires) ":babashka.nrepl.server.middleware/requires must be seqable")
                (assert (seqable? expects) ":babashka.nrepl.server.middleware/expects must be seqable")
                (assert (every? #(contains? middleware %)
                                (concat requires
                                        expects))
                        (str "Middleware required or expected, but not provided"))
                (cons {v (into #{} requires)}
                      (for [expected expects]
                        {expected #{v}}))))))
   (completing merge-graph)
   {}
   middleware))

;; Based off of Kahn's algorithm
;; https://en.wikipedia.org/wiki/Topological_sorting
(defn ^:private topo-sort [g]
  (loop [deps []
         g g]
    (if (seq g)
      (let [next-deps (into #{} (comp
                                 (filter (fn [[f deps]]
                                           (empty? deps)))
                                 (map (fn [[f deps]]
                                        f)))
                            g)]
        (when (empty? next-deps)
          (throw (IllegalArgumentException. "Middleware has cycles or missing dependencies!")))
        (recur (conj deps next-deps)
               (reduce-kv (fn [g f deps]
                            (conj g [f (clojure.set/difference deps next-deps)]))
                          {}
                          (apply dissoc g next-deps))))
      ;; else
      deps)))

(defn middleware->xform
  "Converts a set of middleware functions into a transducer
  that can be used with the sci nrepl server.

  Middleware functions will topologically sorted based off the
  meta data keys :babashka.nrepl.server.middleware/requires and :babashka.nrepl.middleware/expects.
  "
  [middleware]
  (let [g (middleware->graph middleware)
        sorted (topo-sort g)
        xform (transduce cat
                         comp
                         sorted)]
    xform))

(def default-xform
  "Default middleware used by sci nrepl server."
  (middleware->xform default-middleware))

(defn default-middleware-with-extra-ops
  "Use the default handler, but use the map of `op-handlers` first.

  `op-handlers` should be a map of `op` => `handler`.

  A handler is function that receives three arguments, [rf result nrepl-request].
  The `rf` argument should be called with result and the `nrepl-response`. For multiple synchronous
  responses, the result of calling rf should be chained.

  Example:

  ```
    (middleware/default-middleware-with-extra-ops
     {:foo (fn [rf result request]
             (-> result
                 (rf {:response {:foo-echo (-> request :msg :foo)}
                      :response-for request})
                 (rf {:response {:bar-echo (-> request :msg :bar)}
                      :response-for request})))})
  ```
"
  [op-handlers]
  (let [op-handler
        (with-meta
          (fn [rf]
            (completing
             (fn [result request]
               (if-let [handler (op-handlers (-> request :msg :op))]
                 (handler rf result request)
                 (default-process-msg rf result request)))))
          {::requires #{#'wrap-read-msg}
           ::expects #{#'wrap-response-for}})]
    (middleware->xform
     (-> default-middleware
         (disj #'wrap-process-message)
         (conj op-handler)))))

(defn middleware->transducer
  "Return a transducer from a `middleware`."
  ([middleware]
   (fn [rf]
     (fn
       ([] (rf))
       ([result] (rf result))
       ([result input]
        ((middleware #(rf result %)) input))))))
