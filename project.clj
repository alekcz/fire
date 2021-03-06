(defproject alekcz/fire "0.4.1"
  :description "Firebase from Clojure. Basically Charmander 2.0"
  :url "https://github.com/alekcz/fire"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [ [org.clojure/clojure "1.10.3" :scope "provided"]
                  [org.clojure/core.async "1.3.618"]
                  [http-kit "2.5.3"]
                  [cheshire "5.10.0"]
                  [environ "1.2.0"]
                  [stylefruits/gniazdo "1.2.0"]
                  [danlentz/clj-uuid "0.1.9"]]
  :plugins [[lein-cloverage "1.2.2"]
            [lein-eftest "0.5.9"]]
  :aot :all
  :main fire.core
  :repl-options {:init-ns fire.core}
  :cloverage {:runner :eftest
              :runner-opts {:test-warn-time 500
                           :fail-fast? false
                           :multithread? :namespaces}}
  :profiles { :dev {:plugins [[lein-shell "0.5.0"]]
                    :dependencies [  [com.climate/claypoole "1.1.4"]
                                     [criterium "0.4.6"]
                                     [metosin/malli "0.0.1-20200404.091302-14"]
                                     [eftest/eftest "0.5.9"]]}}
  :aliases
  {"native"
   ["shell"
    "native-image" 
    "--report-unsupported-elements-at-runtime" 
    "--no-server"
    "--allow-incomplete-classpath"
    "--initialize-at-build-time"
    "--no-fallback"
    "--initialize-at-run-time=org.httpkit.client.ClientSslEngineFactory\\$SSLHolder"
    "--enable-url-protocols=http,https"
    "-jar" "./target/${:uberjar-name:-${:name}-${:version}-standalone.jar}"
    "-H:Name=./target/${:name}"]

   "run-native" ["shell" "./target/${:name}"]})

