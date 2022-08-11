(defproject electric-socket "1.0.0"
  :description "A Clojure library designed to bring order to chaotic world of socket communication."
  :url "https://github.com/spinneyio/electric-socket"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.3"]
                 [org.clojure/data.json "1.0.0"]
                 [org.clojure/test.check "1.1.1"]
                 [failjure "2.2.0"]]
  :repl-options {:init-ns electric-socket.core})
