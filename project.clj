(defproject alekcz/fire "0.3.0-SNAPSHOT"
  :description "Firebase from Clojure. Basically Charmander 2.0"
  :url "https://github.com/alekcz/fire"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [ [org.clojure/clojure "1.10.2-alpha1"]
                  [http-kit "2.5.0"]
                  [cheshire "5.10.0"]
                  [environ "1.2.0"]
                  [org.clojure/core.async "1.1.587"]]
  :plugins [[lein-cloverage "1.2.0"]]
  :aot :all
  :main fire.core
  :repl-options {:init-ns fire.core}
  :profiles { :dev {:plugins [[lein-shell "0.5.0"]]
                    :dependencies [  [com.climate/claypoole "1.1.4"]
                                     [metosin/malli "0.0.1-20200404.091302-14"]]}}
  :aliases
  {"native"
   ["shell"
    "native-image" 
    "--report-unsupported-elements-at-runtime" 
    "--no-server"
    "--allow-incomplete-classpath"
    "--initialize-at-build-time"
    "--no-fallback"
    "--enable-url-protocols=http,https"
    "-jar" "./target/${:uberjar-name:-${:name}-${:version}-standalone.jar}"
    "-H:Name=./target/${:name}"]

   "run-native" ["shell" "./target/${:name}"]})

