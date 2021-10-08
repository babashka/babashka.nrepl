(ns extra-ops
  (:require [sci.core :as sci]
            [babashka.nrepl.server.middleware :as middleware]
            babashka.nrepl.server))

(def sci-ctx (sci/init {}))

;; Add :foo and :baz ops
(def xform
  (middleware/default-middleware-with-extra-ops
   {:foo (fn [rf result request]
           (-> result
               (rf {:response {:foo-echo (-> request :msg :foo)}
                    :response-for request})
               (rf {:response {:bar-echo (-> request :msg :bar)}
                    :response-for request})))
    :baz (fn [rf result request]
           (-> result
               (rf {:response {:baz-echo (-> request :msg :baz inc)}
                    :response-for request})))}))

(defn responses [ctx xform msg]
  (transduce (comp
              (map (fn [msg]
                     {:msg msg
                      :ctx ctx}))
              xform)
             conj
             []
             [msg]))

;; Look at responses to a request with a :foo op
(responses nil xform
           {:op :foo
            :foo "hello"
            :bar "world"})
;; [{:response {:foo-echo "hello", "session" "none", "id" "unknown"},
;;   :response-for
;;   {:msg {:op :foo, :foo "hello", :bar "world"}, :ctx nil}}
;;  {:response {:bar-echo "world", "session" "none", "id" "unknown"},
;;   :response-for
;;   {:msg {:op :foo, :foo "hello", :bar "world"}, :ctx nil}}]

;; Look at responses to a request with a :baz op
(responses nil xform
           {:op :baz
            :baz 42})
;; [{:response {:baz-echo 43, "session" "none", "id" "unknown"},
;;   :response-for {:msg {:op :baz, :baz 42}, :ctx nil}}]




