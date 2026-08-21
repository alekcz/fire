(defproject alekcz/fire "0.7.0-RC2"
  :description "Firebase from Clojure. Basically Charmander 2.0"
  :url "https://github.com/alekcz/fire"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [ [org.clojure/clojure "1.11.3"]
                  [org.clojure/core.async "1.6.681"]
                  [http-kit "2.7.0"]
                  [cheshire "5.13.0"]
                  [environ "1.2.0"]
                  [stylefruits/gniazdo "1.2.1"]
                  [danlentz/clj-uuid "0.1.9"]
                  ]
  :plugins [[lein-cloverage "1.2.2"]
            [lein-eftest "0.5.9"]
            ]
  :jvm-opts ["-Dclojure.compiler.direct-linking=true"]
  :javac-options ["--release" "8" "-g"]
  :global-vars {*warn-on-reflection* true}
  ;; ^:skip-aot keeps `lein jar` from compiling anything: a library artifact
  ;; should ship .clj source and let the consumer's own clojure compile it.
  ;; AOT here would pin the clojure version fire was built against, bake
  ;; direct-linking into every consumer (so they couldn't redef or mock a fire
  ;; fn), and drag compiled copies of our dependencies into their classpath.
  ;; The native image is the one thing that genuinely needs AOT, so it gets it
  ;; in the :uberjar profile below and nowhere else.
  :main ^:skip-aot fire.graal
  :repl-options {:init-ns fire.core}
  ;; the tests split in two: those that need real firebase credentials, and
  ;; those that don't. `lein test :offline` runs only the second kind, which is
  ;; what CI can check on a fork or before secrets are in play.
  :test-selectors {:default (constantly true)
                   :offline :offline}
  :cloverage {:runner :eftest
              :runner-opts {:test-warn-time 500
                            :fail-fast? false
                            :multithread? :namespaces}}
  :profiles { :uberjar {:aot :all}
              :dev {:plugins [[lein-shell "0.5.0"]]
                    :env {:wrong-api "GARBAGE"}
                    :dependencies [  [com.climate/claypoole "1.1.4"]
                                     [criterium "0.4.6"]
                                     [com.taoensso/nippy "3.1.1"]
                                     [metosin/malli "0.8.0"]
                                     [eftest/eftest "0.5.9"]]}}
  :aliases
  {;; always clean first. a stale target/classes from a previous uberjar or
   ;; native build gets swept into `lein jar`, which is how compiled clojure
   ;; and dependency classes ended up in a published artifact once.
   "publish" ["do" "clean," "deploy" "clojars"]

   "native"
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

