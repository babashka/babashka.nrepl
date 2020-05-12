# sci-nrepl

A Clojure library designed to facilitate adding nrepl support to your
clojure projects that use the small clojure interpreter, sci.

Sci is available here: https://github.com/borkdude/sci

## Usage

To start a server, call `sci-nrepl.server/start-server!`. To stop a
server call `sci-nrepl.server/stop-server!`

### Starting a Server

To start an nrepl in your project, call
`sci-nrepl.server/start-server!`. The call takes two arguments, your
initial sci context, and some options including the IP address to bind
to, the port number and optional debug and quiet flags. eg:

```clojure
(sci-nrepl.server/start-server! sci-ctx {:address "127.0.0.1" :port 23456})
;; => {:socket #object[java.net.ServerSocket 0x4ad88197 "ServerSocket[addr=/127.0.0.1,localport=23456]"],
;;     :future #object[clojure.core$future_call$reify__8459 0x68a8273f {:status :pending, :val nil}]}
```

If `:debug` is set to `true`, the nrepl server will print to stdout
all the messages it is receiving over the nrepl channel.

If `:debug-send` is set to `true`, the server will also print the
messages it is sending.

if `:quiet` is set to `true`, the nrepl server will not print out the
message "starting nREPL server at...". If not specified then the
default `quiet` is false, and the message will be printed.

If `:port` is not specified, the default babashka port of 1667 is
used.

If `:address` is not specified, a default of `0.0.0.0` is used (bind
to every interface).

If no options hashmap is specified at all, all the defaults will be
used. Thus the following is a valid way to launch an nrepl server.

```clojure
(sci-nrepl.server/start-server! sci-ctx)
;; Started nREPL server at 0.0.0.0:1667
;; => {:socket #object[java.net.ServerSocket 0x68867145 "ServerSocket[addr=/0.0.0.0,localport=1667]"],
;;     :future #object[clojure.core$future_call$reify__8459 0x3061657 {:status :pending, :val nil}]}

```

The `start-server!` call returns a hashmap with two keys. `:socket`
holds the java Socket object that is bound and listening. And
`:future` holds the future which contains the running server.

### Stopping a Server

Pass the hashmap you received from `start-server!` to `stop-server!`
to close the server port and shut down the server.

```clojure
(sci-nrepl.server/stop-server! (sci-nrepl.server/start-server! {}))
Started nREPL server at 0.0.0.0:1667
nil
```

### Tips and Tricks

#### Blocking after launching

Often you will want to launch the server and then block execution
until the server is shutdown (at which point the code will continue
executing), or ctrl-C is pressed (at which point the proess will
exit). This can be easily achieved by derefing the returned `:future`
value:

```clojure
(-> (sci-nrepl.server/start-server! sci-ctx {:address "127.0.0.1"
                                             :port 1667})
    :future
    deref)
```

#### Complaints about resolving symbols in the nREPL

When connecting to the nREPL you may recieve errors like:

```
;; nREPL:
clojure.lang.ExceptionInfo: Could not resolve symbol: clojure.core/apply [at line 1, column 2]
```

You may also find a missing default namespace or core clojure
functionality missing.

```clojure
;; nREPL:
nil> (inc 1)
clojure.lang.ExceptionInfo: Could not resolve symbol: inc [at line 1, column 2]
```

This is caused by an incomplete sci context var. Unlike
`sci.core/eval-string`, the nREPL server does not do any further
initialisation of the sci context, like bolting in default clojure
bindings. It serves exactly the sci context you give it. And so, often
a little extra initialisation is helpful by using `sci.impl.opts/init`
to flesh out the sci context var.

```clojure
(sci-nrepl.server/start-server!
    (sci.impl.opts/init sci-ctx)
    {:address "127.0.0.1"
     :port 1667})
```

You may also wish to add on futures support. For example:

```clojure
(-> sci-ctx
    sci.addons/future
    sci.impl.opts/init
    sci-nrepl.server/start-server!)
```

#### Complaints about clojure.main/repl-requires

Connecting to the nREPL from cider gives:

```
;; nREPL:
clojure.lang.ExceptionInfo: Could not resolve symbol: clojure.main/repl-requires [at line 1, column 42]
```

This is because some nREPL clients use `clojure.main/repl-requires` to
find a list of automatic requires to run at the beginning of the
nREPL. Simply supply a value in you sci bound namespace for this
value:

```clojure
(-> sci-ctx
    (assoc-in [:namespaces 'clojure.main 'repl-requires]
        '[[clojure.repl :refer [dir doc]]])
    sci.addons/future
    sci.impl.opts/init
    sci-nrepl.server/start-server!)
```

## License

The project code is Copyright © 2019-2020 Michiel Borkent

It is distributed under the Eclipse Public License 1.0
(http://opensource.org/licenses/eclipse-1.0.php)
