(defproject babashka/babashka.nrepl "0.0.2-SNAPSHOT"
  :description "babashka nREPL module"
  :url "https://github.com/babashka/babashka.nrepl"
  :scm {:name "git"
        :url "https://github.com/babashka/babashka.nrepl"}
  :license {:name "EPL-1.0"
            :url "https://www.eclipse.org/legal/epl-1.0/"}
  :dependencies [[org.clojure/clojure "1.10.2-alpha1"]
                 [nrepl/bencode "1.1.0"]
                 [borkdude/edamame "0.0.11-alpha.9"]
                 [borkdude/sci "0.0.13-alpha.20"]]
  :deploy-repositories [["clojars" {:url "https://clojars.org/repo"
                                    :username :env/babashka_nrepl_clojars_user
                                    :password :env/babashka_nrepl_clojars_pass
                                    :sign-releases false}]])
