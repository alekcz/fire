(ns fire.graal-test
  (:require [clojure.test :refer [deftest is testing]]
            [fire.core :as fire]))

(deftest graal-test
  (testing "Run main function"
    (is (= {:name "graal"} (fire/-main)))))