(ns babashka.nrepl.server-test
  {:author "Michiel Borkent"}
  (:require [babashka.nrepl.server :as server]
            [babashka.nrepl.test-utils :as test-utils]
            [bencode.core :as bencode]
            [clojure.test :as t :refer [deftest is testing]]
            [sci.core :as sci])
  (:import [java.net Socket]))

(def debug? false)

(set! *warn-on-reflection* true)

;; Test on a non standard port to minimize REPL interference
(def nrepl-test-port 54345)

(def dynvar (sci/new-dynamic-var '*x* 10))

(def namespaces
  ;; fake namespaces for symbol completion tests
  {'cheshire.core {'generate-string 'foo
                   'somethingelse 'bar}
   'clojure.test {'deftest 'foo
                  'somethingelse 'bar
                  '*x* dynvar}})

(defn bytes->str [x]
  (if (bytes? x) (String. (bytes x))
      (str x)))

(defn read-msg [msg]
  (let [res (zipmap (map keyword (keys msg))
                    (map #(if (bytes? %)
                            (String. (bytes %))
                            %)
                         (vals msg)))
        res (if-let [status (:status res)]
              (assoc res :status (mapv bytes->str status))
              res)
        res (if-let [status (:sessions res)]
              (assoc res :sessions (mapv bytes->str status))
              res)]
    res))

(defn read-reply [in session id]
  (loop []
    (let [msg (read-msg (bencode/read-bencode in))]
      (if (and (= (:session msg) session)
               (= (:id msg) id))
        (do
          (when debug? (prn "received" msg))
          msg)
        (do
          (when debug? (prn "skipping over msg" msg))
          (recur))))))

(defn nrepl-test [^Integer port]
  (with-open [socket (Socket. "127.0.0.1" port)
              in (.getInputStream socket)
              in (java.io.PushbackInputStream. in)
              os (.getOutputStream socket)]
    (bencode/write-bencode os {"op" "clone"})
    (let [session (:new-session (read-msg (bencode/read-bencode in)))
          id (atom 0)
          new-id! #(swap! id inc)]
      (testing "session"
        (is session))
      (testing "eval"
        (bencode/write-bencode os {"op" "eval" "code" "(+ 1 2 3)" "session" session "id" (new-id!)})
        (let [msg (read-reply in session @id)
              id (:id msg)
              value (:value msg)]
          (is (= 1 id))
          (is (= value "6")))
        (bencode/write-bencode os {"op" "eval"
                                   "code" "(do (require '[clojure.test :refer [*x*]]) *x*)"
                                   "session" session "id" (new-id!)})
        (let [msg (read-reply in session @id)
              value (:value msg)]
          (is (= value "11")))
        (testing "creating a namespace and evaluating something in it"
          (bencode/write-bencode os {"op" "eval"
                                     "code" "(ns ns0) (defn foo [] :foo0) (ns ns1) (defn foo [] :foo1)"
                                     "session" session
                                     "id" (new-id!)})
          (read-reply in session @id)
          (testing "not providing the ns key evaluates in the last defined namespace"
            (bencode/write-bencode os {"op" "eval" "code" "(foo)" "session" session "id" (new-id!)})
            (is (= ":foo1" (:value (read-reply in session @id)))))
          (testing "explicitly providing the ns key evaluates in that namespace"
            (bencode/write-bencode os {"op" "eval"
                                       "code" "(foo)"
                                       "session" session
                                       "id" (new-id!)
                                       "ns" "ns0"})
            (is (= ":foo0" (:value (read-reply in session @id)))))
          (testing "providing an ns value of a non-existing namespace creates the namespace"
            (bencode/write-bencode os {"op" "eval"
                                       "code" "(ns-name *ns*)"
                                       "session" session
                                       "id" (new-id!)
                                       "ns" "unicorn"})
            (let [reply (read-reply in session @id)]
              (is (= "unicorn" (:value reply))))))
        (testing "multiple top level expressions results in two value replies"
          (bencode/write-bencode os {"op" "eval"
                                     "code" "(+ 1 2 3) (+ 1 2 3)"
                                     "session" session
                                     "id" (new-id!)})
          (let [reply-1 (read-reply in session @id)
                reply-2 (read-reply in session @id)]
            (is (= "6" (:value reply-1) (:value reply-2))))))
      (testing "load-file"
        (bencode/write-bencode os {"op" "load-file" "file" "(ns foo) (defn foo [] :foo)" "session" session "id" (new-id!)})
        (read-reply in session @id)
        (bencode/write-bencode os {"op" "eval" "code" "(foo)" "ns" "foo" "session" session "id" (new-id!)})
        (is (= ":foo" (:value (read-reply in session @id)))))
      (testing "complete"
        (testing "completions for fo"
          (bencode/write-bencode os {"op" "complete"
                                     "symbol" "fo"
                                     "session" session
                                     "id" (new-id!)
                                     "ns" "foo"})
          (let [reply (read-reply in session @id)
                completions (:completions reply)
                completions (mapv read-msg completions)
                completions (into #{} (map (juxt :ns :candidate)) completions)]
            (is (contains? completions ["foo" "foo"]))
            (is (contains? completions ["clojure.core" "format"]))))
        (testing "completions for quux should be empty"
          (bencode/write-bencode os {"op" "complete"
                                     "symbol" "quux"
                                     "session" session "id" (new-id!)
                                     "ns" "foo"})
          (let [reply (read-reply in session @id)
                completions (:completions reply)]
            (is (empty? completions)))
          (testing "unless quux is an alias"
            (bencode/write-bencode os {"op" "eval" "code" "(require '[cheshire.core :as quux])" "session" session "id" (new-id!)})
            (read-reply in session @id)
            (bencode/write-bencode os {"op" "complete" "symbol" "quux" "session" session "id" (new-id!)})
            (let [reply (read-reply in session @id)
                  completions (:completions reply)
                  completions (mapv read-msg completions)
                  completions (into #{} (map (juxt :ns :candidate)) completions)]
              (is (contains? completions ["cheshire.core" "quux/generate-string"])))))
        (testing "completions for clojure.test"
          (bencode/write-bencode os {"op" "eval" "code" "(require '[clojure.test :as test])" "session" session "id" (new-id!)})
          (read-reply in session @id)
          (bencode/write-bencode os {"op" "complete" "symbol" "test" "session" session "id" (new-id!)})
          (let [reply (read-reply in session @id)
                completions (:completions reply)
                completions (mapv read-msg completions)
                completions (into #{} (map (juxt :ns :candidate)) completions)]
            (is (contains? completions ["clojure.test" "test/deftest"])))))
      (testing "close + ls-sessions"
        (bencode/write-bencode os {"op" "ls-sessions" "session" session "id" (new-id!)})
        (let [reply (read-reply in session @id)
              sessions (set (:sessions reply))]
          (is (contains? sessions session))
          (let [new-sessions (loop [i 0
                                    sessions #{}]
                               (bencode/write-bencode os {"op" "clone" "session" session "id" (new-id!)})
                               (let [new-session (:new-session (read-reply in session @id))
                                     sessions (conj sessions new-session)]
                                 (if (= i 4)
                                   sessions
                                   (recur (inc i) sessions))))]
            (bencode/write-bencode os {"op" "ls-sessions" "session" session "id" (new-id!)})
            (let [reply (read-reply in session @id)
                  sessions (set (:sessions reply))]
              (is (= 6 (count sessions)))
              (is (contains? sessions session))
              (is (= new-sessions (disj sessions session)))
              (testing "close"
                (doseq [close-session (disj sessions session)]
                  (bencode/write-bencode os {"op" "close" "session" close-session "id" (new-id!)})
                  (let [reply (read-reply in close-session @id)]
                    (is (contains? (set (:status reply)) "session-closed")))))
              (testing "session not listen in ls-sessions after close"
                (bencode/write-bencode os {"op" "ls-sessions" "session" session "id" (new-id!)})
                (let [reply (read-reply in session @id)
                      sessions (set (:sessions reply))]
                  (is (contains? sessions session))
                  (is (not (some #(contains? sessions %) new-sessions)))))))))
      (testing "output"
        (bencode/write-bencode os {"op" "eval" "code" "(dotimes [i 3] (println \"Hello\"))"
                                   "session" session "id" (new-id!)})
        (dotimes [_ 3]
          (let [reply (read-reply in session @id)]
            (is (= "Hello\n" (:out reply))))))
      (testing "output flushing"
        (bencode/write-bencode os {"op" "eval" "code" "(print \"short\")"
                                   "session" session "id" (new-id!)})
        (is (= "short" (:out (read-reply in session @id)))))
      (testing "output not truncating"
        (let [large-block
              (apply str
                     (repeatedly 5000  ;; bigger than the 1024 buffer size
                                 #(rand-nth
                                   (seq "abcdefghijklmnopqrstuvwxyz ☂☀\t "))))]
          (bencode/write-bencode os {"op" "eval" "code" (format "(print \"%s\")" large-block)
                                     "session" session "id" (new-id!)})
          (is (= large-block
                 (loop [output ""]
                   (let [{:keys [status out]} (read-reply in session @id)]
                     (if (= status ["done"])
                       output
                       (recur (str output out))))))))))))

(deftest nrepl-server-test
  (let [service (atom nil)]
    (sci/binding [dynvar 11]
      (try
        (reset! service
                (server/start-server!
                 (sci/init {:namespaces namespaces
                            :features #{:bb}})
                 {:host "0.0.0.0"
                  :port nrepl-test-port
                  :debug false
                  :debug-send false}))
        (test-utils/wait-for-port "localhost" nrepl-test-port)
        (nrepl-test nrepl-test-port)
        (finally
          (server/stop-server! @service))))))

(deftest parse-opt-test
  (is (= 1668 (:port (server/parse-opt "1668"))))
  (is (= 1668 (:port (server/parse-opt "localhost:1668"))))
  (is (= "localhost" (:host (server/parse-opt "localhost:1668")))))

;;;; Scratch

(comment
  )
