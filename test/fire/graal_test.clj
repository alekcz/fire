(ns fire.graal-test
  (:require [clojure.test :refer [deftest is testing]]
            [fire.core :as fire]
            [fire.socket :as socket]))

(deftest graal-test
  (testing "Run main function"
    (is (= {:name "graal"} (fire/-main)))))

(deftest graal-test-socket
  (testing "Run main function"
    (is (= {:name "graal-socket"} (socket/-main)))))    