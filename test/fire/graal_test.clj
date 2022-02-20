(ns fire.graal-test
  (:require [clojure.test :refer [deftest is testing]]
            [fire.graal :as graal]))

(deftest graal-test
  (testing "Run core main function"
    (is (= {:name "graal"} (graal/core-main)))))

(deftest graal-test-socket
  (testing "Run socket main function"
    (is (= {:name "graal-socket"} (graal/socket-main)))))    

(deftest graal-test-storage
  (testing "Run storage main function"
    (is (= "graal-storage" (graal/storage-main)))))        

(deftest graal-test-vision
  (testing "Run ocr main function"
    (is (= "GraalVM\n" (graal/vision-main)))))                

(deftest graal-test-overall
  (testing "Run graal main function"
    (is (= "graal" (graal/-main))))) 

