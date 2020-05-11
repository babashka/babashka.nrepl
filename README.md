# sci-nrepl

A Clojure library designed to facilitate adding nrepl support to your clojure projects that use the small clojure interpreter, sci.

## Usage

To start an nrepl in your project, call `sci-nrepl.server/start-server!`. The call takes two arguments, your initial sci context, and some options including the IP address to bind to, the port number and a debug flag. eg:

```clojure
(sci-nrepl/start-server! sci-ctx {:address "127.0.0.1" :port 2345 :debug true})
```



## License

The project contains code by the following people under these terms

### bencode

The included bencode implementation is Copyright © Meikel Brandmeyer. All rights reserved.

It is distributed under the Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)

### nrepl code

The included nrepl implementation code is Copyright © 2019-2020 Michiel Borkent

It is distributed under the Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
