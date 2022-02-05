(ns fire.graal-test
  (:require [clojure.test :refer [deftest is testing]]
            [fire.core :as fire]
            [fire.socket :as socket]
            [fire.storage :as storage]))

(deftest graal-test
  (testing "Run core main function"
    (is (= {:name "graal"} (fire/-main)))))

(deftest graal-test-socket
  (testing "Run socket main function"
    (is (= {:name "graal-socket"} (socket/-main)))))    

(deftest graal-test-storage
  (testing "Run storage main function"
    (is (= "graal-storage" (storage/-main)))))        