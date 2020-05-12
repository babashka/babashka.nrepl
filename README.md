# sci-nrepl

A Clojure library designed to facilitate adding nrepl support to your clojure projects that use the small clojure interpreter, sci.

## Usage

To start a server, call `sci-nrepl.server/start-server!`. To stop a server call `sci-nrepl.server/stop-server!`

### Starting a Server

To start an nrepl in your project, call `sci-nrepl.server/start-server!`. The call takes two arguments, your initial sci context, and some options including the IP address to bind to, the port number and optional debug and quiet flags. eg:

```clojure
(sci-nrepl.server/start-server! sci-ctx {:address "127.0.0.1" :port 23456})
;; => {:socket #object[java.net.ServerSocket 0x4ad88197 "ServerSocket[addr=/127.0.0.1,localport=23456]"],
       :process #object[clojure.core$future_call$reify__8459 0x68a8273f {:status :pending, :val nil}]}
```

If `:debug` is set to `true`, the nrepl server will print to stdout all the messages it is receiving over the nrepl channel.

If `:debug-send` is set to `true`, the server will also print the messages it is sending.

if `:quiet` is set to `true`, the nrepl server will not print out the message "starting nREPL server at...". If not specified then the default `quiet` is false, and the message will be printed.

If `:port` is not specified, the default babashka port of 1667 is used.

If `:address` is not specified, a default of `0.0.0.0` is used (bind to every interface).

If no options hashmap is specified at all, all the defaults will be used. Thus the following is a valid way to launch an nrepl server.

```clojure
(sci-nrepl.server/start-server! sci-ctx)
;; Started nREPL server at 0.0.0.0:1667
;; => {:socket #object[java.net.ServerSocket 0x68867145 "ServerSocket[addr=/0.0.0.0,localport=1667]"],
       :process #object[clojure.core$future_call$reify__8459 0x3061657 {:status :pending, :val nil}]}

```

The `start-server!` call returns a hashmap with two keys. `:socket` holds the java Socket object that is bound and listening. And `:process` holds the future which contains the running server.

### Stopping a Server

Pass the hashmap you received from `start-server!` to `stop-server!` to close the server port and shut down the server.

```clojure
(sci-nrepl.server/stop-server! (sci-nrepl.server/start-server! {}))
Started nREPL server at 0.0.0.0:1667
nil
```

### Tips and Tricks



## License

The project contains code by the following people under these terms

### bencode

The included bencode implementation is Copyright © Meikel Brandmeyer. All rights reserved.

It is distributed under the Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)

### nrepl code

The included nrepl implementation code is Copyright © 2019-2020 Michiel Borkent

It is distributed under the Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
