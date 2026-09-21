(ns graal-check
  "Find what native-image would refuse, without native-image.

   The image builder runs class initializers at BUILD time, so anything a
   `def` constructs is baked into the image heap. For a Random that is fatal
   and it says so: the seed is frozen into the binary, and every copy of it
   would draw the same values. It refuses rather than ship that.

   Reachability runs through the namespace graph — scanning one function
   reaches its Var, then the Var's namespace, then that namespace's whole
   mapping table, and on outward. So a Random in ANY library fire loads
   counts, not only one in fire: clj-uuid 0.2.5 failed the build this way,
   from a `defonce` nobody here wrote.

   Loading fire.graal pulls in every namespace the native image entry point
   touches, which is the same graph. Run from `bb graal-check`."
  (:require [fire.graal]))

(defn -main [& _]
  (let [offenders (for [n (all-ns)
                        [sym v] (ns-interns n)
                        :let [root (try (.getRawRoot ^clojure.lang.Var v)
                                        (catch Throwable _ nil))]
                        :when (or (instance? java.util.Random root)
                                  (instance? java.util.SplittableRandom root))]
                    (str n "/" sym " holds a " (.getName (class root))))]
    (if (seq offenders)
      (binding [*out* *err*]
        (println "These would be baked into the native image heap:")
        (doseq [o offenders] (println "  " o))
        (println)
        (println "Hold it behind a `delay`, so the initializer constructs a function")
        (println "and not an instance. If it belongs to a dependency, pin a version")
        (println "that already does, or add --initialize-at-run-time for its class.")
        (System/exit 1))
      (println (format "no Random in any var root - scanned %d namespaces"
                       (count (all-ns)))))))
