## Middleware

Middleware describes how the nrepl server should respond to requests. It is designed to be modular so that servers can provide extra features without having to reimplement an nrepl server from scratch. Babashka's middleware implementation differs from the [main nrepl implementation](https://nrepl.org/nrepl/design/middleware.html) mostly to reduce dependencies.


### Design

Middleware for babashka's nrepl server is implemented as a transducer. The goal of the transducer is to produce zero, one, or more responses for each input (ie. each incoming nrepl message).


Input format:
```clojure
{:msg nrepl-request
 :ctx current-sci-ctx
 :opts options}
```

Output format:
```clojure
{:response nrepl-response
 :response-for nrepl-request}
```

For a description of the `nrepl-response` and `nrepl-request` formats, check the [nrepl documentation](https://nrepl.org/nrepl/design/overview.html).

### Usage

The most common types of middleware are implementing extra nrepl ops and cross-cutting middleware like logging. Below are examples of both of these common types of middleware.


### Cross cutting middleware like logging

```clojure

(require '[sci.core :as sci])
(require '[babashka.nrepl.middleware :as middleware])
(require 'babashka.nrepl.server)

(def sci-ctx (sci/init opts))

(defonce responses (atom []))
(def
  ^{::middleware/requires #{#'middleware/wrap-response-for}}
  log-responses 
  (map (fn [response]
         (swap! responses conj (:response response))
         response)))

(defonce requests (atom []))
(def
  ^{::middleware/requires #{#'middleware/wrap-read-msg}
    ::middleware/expects #{#'middleware/wrap-process-message}}
  log-requests
  (map (fn [request]
         (swap! requests conj (:msg request))
         request)))

;; Add cross cutting middleware
(def xform
  (middleware/middleware->xform
   (conj middleware/default-middleware
         #'log-requests
         #'log-responses)))

(babashka.nrepl.server/start-server! sci-ctx {:host "127.0.0.1" :port 23456
                                              :xform xform})
```

### Adding or overriding nrepl ops with middleware

```clojure

(require '[sci.core :as sci])
(require '[babashka.nrepl.middleware :as middleware])
(require 'babashka.nrepl.server)

(def sci-ctx (sci/init opts))

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

(babashka.nrepl.server/start-server! sci-ctx {:host "127.0.0.1" :port 23456
                                              :xform xform})

```

## Advanced usage

Since middleware for babashka's nrepl is just a transducer, any transducer that produces nrepl responses for nrepl requests can be used. As a convenience, the default middleware is provided so that the transducer doesn't have to be built from scratch. The set of transducers can be found in the middleware namespace under `babashka.nrepl.middleware/default-middleware`. The `default-middleware` can be turned into a transducer using `babashka.nrepl.middleware/middleware->xform`. The benefit of using `middleware->xform` is that any transducers that have the meta data of `:babashka.nrepl.middleware/requires` or `:babashka.nrepl.middleware/expects` will be respected and the order of the transducers will be sorted accordingly.

