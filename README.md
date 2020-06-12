# babashka.nrepl

The nREPL server from babashka as a library, so it can be used from
other sci-based CLIs.

Sci is available here: https://github.com/borkdude/sci

## Usage

To start a server, call `babashka.nrepl.server/start-server!`. To stop a
server call `babashka.nrepl.server/stop-server!`

### Starting a Server

Before you start a server you will need to create a sci context:

```clojure
(require '[sci.core :as sci])
(require '[sci.addons :as addons])

(def opts (-> {:namespaces {'foo.bar {'x 1}}} addons/future))
(def sci-ctx (sci/init opts))
```

A sci context is derived from options as documented in `sci.core/eval-string`
and contains the runtime state of a sci session. This context is re-used over
successive REPL invocations.

To start an nREPL server in your project, call
`babashka.nrepl.server/start-server!`. The call takes two arguments, your
initial sci context and some options including the IP address to bind to, the
port number and optional debug and quiet flags. E.g.:

```clojure
(babashka.nrepl.server/start-server! sci-ctx {:host "127.0.0.1" :port 23456})
;; Started nREPL server at 127.0.0.1:23456
```

If `:debug` is set to `true`, the nREPL server will print to stdout all the
messages it is receiving over the nREPL channel.

If `:debug-send` is set to `true`, the server will also print the
messages it is sending.

if `:quiet` is set to `true`, the nREPL server will not print out the message
"starting nREPL server at...". If not specified then `:quiet` defaults to
`false`, and the message will be printed.

If `:port` is not specified, it defaults to `1667`.

If `:host` is not specified, it defaults to `0.0.0.0` (bind to every interface).

Options can contain a `:describe` map which will be merged in with the response
for the `:describe` op.

If no options hashmap is specified at all, all the defaults will be used. Thus
the following is a valid way to launch an nREPL server.

```clojure
(babashka.nrepl.server/start-server! sci-ctx)
;; Started nREPL server at 0.0.0.0:1667
```

### Stopping a Server

Pass the result you received from `start-server!` to `stop-server!` to shut down
the server.

```clojure
(->
  (babashka.nrepl.server/start-server! sci-ctx)
  (babashka.nrepl.server/stop-server!))
Started nREPL server at 0.0.0.0:1667
nil
```

### Parsing an nREPL options string

Use `babashka.nrepl.server/parse-opt` to parse strings like:

```clojure
(babashka.nrepl.server/parse-opt "localhost:1667")
;;=> {:host "localhost", :port 1667}
(babashka.nrepl.server/parse-opt "1667")
;;=> {:host nil, :port 1667}
```

You can pass the return value of `parse-opt` to `start-server!`:

```clojure
(babashka.nrepl.server/start-server!
    sci-ctx
    (babashka.nrepl.server/parse-opt "localhost:23456"))
```

### Tips and Tricks

#### Blocking after launching

Often you will want to launch the server and then block execution until the
server is shutdown (at which point the code will continue executing), or ctrl-C
is pressed (at which point the process will exit). This can be achieved as
follows:

```clojure
(babashka.nrepl.server/start-server! sci-ctx {:host "127.0.0.1"
                                              :port 1667})
@(promise)
```

#### Complaints about clojure.main/repl-requires

Connecting to the nREPL from CIDER gives:

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
    sci.core/init
    babashka.nrepl.server/start-server!)
```

## Authors

The main body of work was done by Michiel Borkent
([@borkdude](https://github.com/borkdude)). Addition rework and some added
functionality was done by Crispin Wellington
([@retrogradeorbit](https://github.com/retrogradeorbit)).

## License

The project code is Copyright © 2019-2020 Michiel Borkent

It is distributed under the Eclipse Public License 1.0
(http://opensource.org/licenses/eclipse-1.0.php)
