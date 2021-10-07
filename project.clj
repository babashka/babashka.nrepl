(defproject babashka/babashka.nrepl "0.0.5"
  ;; run (bb | clojure) script/update_version.clj to update this version
  :description "babashka nREPL module"
  :url "https://github.com/babashka/babashka.nrepl"
  :scm {:name "git"
        :url "https://github.com/babashka/babashka.nrepl"}
  :license {:name "EPL-1.0"
            :url "https://www.eclipse.org/legal/epl-1.0/"}
  :dependencies [[org.clojure/clojure "1.10.2-alpha1"]
                 [nrepl/bencode "1.1.0"]
                 [borkdude/sci "0.2.1-alpha.1"]]
  :deploy-repositories [["clojars" {:url "https://clojars.org/repo"
                                    :username :env/babashka_nrepl_clojars_user
                                    :password :env/babashka_nrepl_clojars_pass
                                    :sign-releases false}]])
