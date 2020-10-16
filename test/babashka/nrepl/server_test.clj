(ns babashka.nrepl.server-test
  {:author "Michiel Borkent"}
  (:require [babashka.nrepl.impl.server :refer [babashka-nrepl-version]]
            [babashka.nrepl.server :as server]
            [babashka.nrepl.test-utils :as test-utils]
            [bencode.core :as bencode]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [sci.core :as sci])
  (:import [java.net Socket]))

(def debug? false)

(set! *warn-on-reflection* true)

;; Test on a non standard port to minimize REPL interference
(def nrepl-test-port 54345)

(def dynvar (sci/new-dynamic-var '*x* 10))
(def reflection-var (sci/new-dynamic-var '*warn-on-reflection* false))

(def namespaces
  ;; fake namespaces for symbol completion tests
  {'cheshire.core {'generate-string 'foo
                   'somethingelse 'bar}
   'clojure.test {'deftest 'foo
                  'somethingelse 'bar
                  '*x* dynvar}
   'clojure.core {'*warn-on-reflection* reflection-var}})

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

(defn read-eldoc [eldoc]
  (map #(map (comp edn/read-string bytes->str) %) eldoc))

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
      (testing "describe"
        (bencode/write-bencode os {"op" "describe" "session" session "id" (new-id!)})
        (let [msg (read-reply in session @id)
              id (:id msg)
              versions (:versions msg)
              babashka-version (bytes->str (get versions "babashka"))
              bb-nrepl-ver (bytes->str (get versions "babashka.nrepl"))]
          (is (= 1 id))
          (is (= "0.0.1" babashka-version))
          (is (= babashka-nrepl-version bb-nrepl-ver))))
      (testing "eval"
        (bencode/write-bencode os {"op" "eval" "code" "(+ 1 2 3)" "session" session "id" (new-id!)})
        (let [msg (read-reply in session @id)
              id (:id msg)
              value (:value msg)]
          (is (= 2 id))
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
            (is (= "6" (:value reply-1) (:value reply-2)))))
        (testing "*file* is bound to file"
          (bencode/write-bencode os {"op" "eval"
                                     "file" "/tmp/foo.clj"
                                     "code" "*file*"
                                     "session" session
                                     "id" (new-id!)})
          (let [reply (read-reply in session @id)]
            (is (= "\"/tmp/foo.clj\"" (:value reply))))))
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
            (is (contains? completions ["clojure.test" "test/deftest"]))))
        (testing "completions for vars containing regex chars"
          (bencode/write-bencode os {"op" "complete" "symbol" "+" "session" session "id" (new-id!)})
          (let [reply (read-reply in session @id)
                completions (:completions reply)
                completions (mapv read-msg completions)
                completions (into #{} (map (juxt :ns :candidate)) completions)]
            (is (contains? completions ["clojure.core" "+"]))
            (is (contains? completions ["clojure.core" "+'"])))))
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
        (bencode/write-bencode os {"op" "eval" "code" "(print \"short no newline\")"
                                   "session" session "id" (new-id!)})
        (is (= "short no newline" (:out (read-reply in session @id)))))
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
                       (recur (str output out)))))))))

      (testing "error"
        (bencode/write-bencode os {"op" "eval" "code" "(binding [*out* *err*] (dotimes [i 3] (println \"Hello\")))"
                                   "session" session "id" (new-id!)})
        (dotimes [_ 3]
          (let [reply (read-reply in session @id)]
            (is (= "Hello\n" (:err reply))))))
      (testing "error flushing"
        (bencode/write-bencode os {"op" "eval" "code" "(binding [*out* *err*] (print \"short no newline\"))"
                                   "session" session "id" (new-id!)})
        (is (= "short no newline" (:err (read-reply in session @id)))))
      (testing "error not truncating"
        (let [large-block
              (apply str
                     (repeatedly 5000  ;; bigger than the 1024 buffer size
                                 #(rand-nth
                                   (seq "abcdefghijklmnopqrstuvwxyz ☂☀\t "))))]
          (bencode/write-bencode os {"op" "eval" "code" (format "(binding [*out* *err*] (print \"%s\"))" large-block)
                                     "session" session "id" (new-id!)})
          (is (= large-block
                 (loop [error ""]
                   (let [{:keys [status err]} (read-reply in session @id)]
                     (if (= status ["done"])
                       error
                       (recur (str error err)))))))))
      (testing "eldoc"
        (testing "eldoc of inc"
          (bencode/write-bencode os {"op" "eldoc" "ns" "user"
                                     "sym" "inc"
                                     "session" session "id" (new-id!)})
          (let [{:keys [docstring eldoc type]} (read-reply in session @id)]
            (is (str/includes? docstring "Returns a number one greater than num"))
            (let [eldoc (read-eldoc eldoc)]
              (is (= '((x)) eldoc))
              (is (= "function" type)))))
        (testing "user-defined macro"
          (bencode/write-bencode os {"op" "eval"
                                     "ns" "user"
                                     "code" "(defmacro foo \"foo\" [x y & zs])"
                                     "session" session "id" (new-id!)})
          (read-reply in session @id)
          (bencode/write-bencode os {"op" "eldoc" "ns" "user"
                                     "sym" "foo"
                                     "session" session "id" (new-id!)})
          (let [{:keys [docstring eldoc type]} (read-reply in session @id)]
            (is (str/includes? docstring "foo"))
            (let [eldoc (read-eldoc eldoc)]
              (is (= '((x y & zs)) eldoc)))
            (is (= "function" type))))
        (testing "non-function var"
          (bencode/write-bencode os {"op" "eval"
                                     "ns" "user"
                                     "code" "(def x \"foo\" 1)"
                                     "session" session "id" (new-id!)})
          (read-reply in session @id)
          (bencode/write-bencode os {"op" "eldoc" "ns" "user"
                                     "sym" "x"
                                     "session" session "id" (new-id!)})
          (let [{:keys [docstring type]} (read-reply in session @id)]
            (is (str/includes? docstring "foo"))
            (is (= "variable" type))))
        (testing "eldoc of invalid characters"
          (bencode/write-bencode os {"op" "eldoc" "ns" "user"
                                     "sym" ""
                                     "session" session "id" (new-id!)})
          (let [{:keys [status]} (read-reply in session @id)]
            (is (contains? (set status) "no-eldoc")))))
      (testing "dynamic var can be set! if provided in :dynamic-vars option"
        (bencode/write-bencode os {"op" "eval" "code" "(set! *warn-on-reflection* true)"
                                   "session" session "id" (new-id!)})
        (is (= "true" (:value (read-reply in session @id))))))))

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
                  :debug-send false
                  :describe {"versions" {"babashka" "0.0.1"}}
                  :thread-bind [reflection-var]}))
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
