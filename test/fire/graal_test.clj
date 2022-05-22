(ns fire.graal-test
  (:require [clojure.test :refer [deftest is testing]]
            [fire.graal :as graal]
            [clojure.string :as str]))

(deftest graal-test
  (testing "Run core main function"
    (is (= {:name "graal"} (graal/core-main)))))

(deftest graal-test-socket
  (testing "Run socket main function"
    (is (= {:name "graal-socket"} (graal/socket-main)))))

(deftest graal-test-storage
  (testing "Run storage main function"
    (is (= "graal-storage" (str/trim (graal/storage-main))))))        

(deftest graal-test-vision
  (testing "Run ocr main function"
    (is (= "GraalVM" (str/trim (graal/vision-main))))))                

(deftest graal-test-overall
  (testing "Run graal main function"
    (is (= "graal" (str/trim (graal/-main)))))) 

