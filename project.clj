(defproject alekcz/fire "0.7.0"
  :description "Firebase from Clojure. Basically Charmander 2.0"
  :url "https://github.com/alekcz/fire"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [ [org.clojure/clojure "1.11.4"]
                  [org.clojure/core.async "1.8.741"]
                  [http-kit "2.8.1"]
                  [cheshire "6.2.0"]
                  [environ "1.2.0"]
                  [danlentz/clj-uuid "0.2.5"]
                  ]
  :plugins [[lein-cloverage "1.2.4"]
            [lein-eftest "0.6.0"]
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
              ;; the clojure a consumer runs fire on. CI runs the offline tier
              ;; under each, because a library that ships source runs on theirs,
              ;; not on the one pinned above.
              :clj-1.11 {:dependencies [[org.clojure/clojure "1.11.4"]]}
              :clj-1.12 {:dependencies [[org.clojure/clojure "1.12.0"]]}
              :dev {:plugins [[lein-shell "0.5.0"]]
                    :env {:wrong-api "GARBAGE"}
                    :dependencies [  [com.climate/claypoole "1.1.4"]
                                     [criterium "0.4.6"]
                                     [com.taoensso/nippy "3.9.0"]
                                     [metosin/malli "0.8.0"]
                                     [eftest/eftest "0.6.0"]]}}
  :aliases
  {;; bb.edn is where the workflows live, so that `bb release` and `lein
   ;; publish` cannot drift into meaning two different things. bb release
   ;; cleans first (a stale target/classes from a previous uberjar or native
   ;; build gets swept into `lein jar`, which is how compiled clojure and
   ;; dependency classes ended up in a published artifact once), then reads
   ;; the built jar back and refuses to deploy one carrying .class files.
   ;; It calls `lein deploy clojars` directly, never this alias — pointing it
   ;; here instead would loop.
   "publish" ["shell" "bb" "release"]
   "verify"  ["shell" "bb" "verify"]
   "sign-check" ["shell" "bb" "sign-check"]

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

