(ns fire.ocr-test
  (:require [clojure.test :refer [deftest is testing]]
            [fire.ocr :as ocr]))

(deftest ocr-test
  (testing "Tests if OCR is applied correctly"
    (let [res (ocr/ocr "test/resources/test.png" :vision-api)
          answer "About\nA lightweight clojure client for Firebase\nbased using the REST API. Basically\nCharmander 2.0\n"]
      (is (= answer (-> res :responses first :fullTextAnnotation :text))))))

(deftest no-api-test
  (testing "Tests if error is returned for missing API key"
    (let [res (ocr/ocr "test/resources/test.png" :missing-var)]
      (is (= 403 (-> res :error :code))))))      

(deftest wrong-api-test
  (testing "Tests if a token is returned"
    (let [res (ocr/ocr "test/resources/test.png" :wrong-api)]
      (is (= 403 (-> res :error :code))))))

(deftest invalid-request-test
  (testing "Tests if a token is returned"
    (let [res (ocr/request 10 10 {:url "https://1.firebaseio.com"})
          answer "Firebase error. Please ensure that you spelled the name of your Firebase correctly"]
      (is (= answer res)))))      