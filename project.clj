(defproject alekcz/fire "0.2.6"
  :description "Firebase from Clojure. Basically Charmander 2.0"
  :url "https://github.com/alekcz/fire"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [ [org.clojure/clojure "1.10.0"]
                  [alekcz/googlecredentials "3.0.1"]
                  [http-kit "2.4.0-alpha6"]
                  [cheshire "5.10.0"]
                  [org.clojure/core.async "1.1.587"]
                  [overtone/at-at "1.2.0"]]
  :plugins [[lein-cloverage "1.1.2"]]
  :aot :all
  :main fire.core
  :repl-options {:init-ns fire.core}
  :profiles { :dev {:dependencies [  [com.climate/claypoole "1.1.4"]
                                     [metosin/malli "0.0.1-20200404.091302-14"]
                                     [alekcz/charmander "1.0.1"]
                                     [environ "1.2.0"]]}})

