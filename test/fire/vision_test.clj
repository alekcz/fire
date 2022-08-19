(ns fire.vision-test
  (:require [clojure.test :refer [deftest is testing]]
            [fire.vision :as vision]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import  [java.util Base64 Base64$Decoder Base64$Encoder]))

(def ^Base64$Encoder b64encoder (. Base64 getEncoder))
(def ^Base64$Decoder b64decoder (. Base64 getDecoder))

(deftest vision-test
  (testing "Tests if OCR is applied correctly"
    (with-open [filestream (io/input-stream (io/as-file "test/resources/test.png"))]
      (let [b64 (.encodeToString b64encoder ^"[B" (vision/stream->bytes filestream))
            res (vision/detect b64 :ocr :vision-api)
            answer "About\nA lightweight clojure client for Firebase\nbased using the REST API. Basically\nCharmander 2.0"]
        (is (= answer (-> res :responses first :fullTextAnnotation :text str/trim)))))))

(deftest vision-bytes-test
  (testing "Tests if OCR is applied correctly"
    (with-open [filestream (io/input-stream (io/as-file "test/resources/test.png"))]
      (let [bytes (vision/stream->bytes filestream)
            res (vision/detect-bytes bytes :ocr :vision-api)
            answer "About\nA lightweight clojure client for Firebase\nbased using the REST API. Basically\nCharmander 2.0"]
        (is (= answer (-> res :responses first :fullTextAnnotation :text str/trim)))))))

(deftest vision-file-test
  (testing "Tests if OCR is applied correctly"
    (let [res (vision/detect-file "test/resources/test.png" :ocr :vision-api)
          answer "About\nA lightweight clojure client for Firebase\nbased using the REST API. Basically\nCharmander 2.0"]
      (is (= answer (-> res :responses first :fullTextAnnotation :text str/trim))))))

(deftest vision-various-test
  (testing "Tests the rest of vision API"
    (with-open [filestream (io/input-stream (io/as-file "test/resources/various.jpg"))]
      (let [b64 (.encodeToString b64encoder ^"[B" (vision/stream->bytes filestream))
            res1 (vision/detect b64 :faces  :vision-api)
            res2 (vision/detect b64 :landmarks :vision-api)
            res3 (vision/detect b64 :labels :vision-api)
            res4 (vision/detect b64 :logos :vision-api)
            res5 (vision/detect b64 :properties :vision-api)
            res6 (vision/detect b64 :objects :vision-api)
            res7 (vision/detect b64 :safe :vision-api)
            res8 (vision/detect b64 :web :vision-api)
            res9 (vision/detect b64 "FACE_DETECTION" :vision-api)]
        (is (-> res1 :responses count (> 0)))
        (is (-> res2 :responses count (> 0)))
        (is (-> res3 :responses count (> 0)))
        (is (-> res4 :responses count (> 0)))
        (is (-> res5 :responses count (> 0)))
        (is (-> res6 :responses count (> 0)))
        (is (-> res7 :responses count (> 0)))
        (is (-> res8 :responses count (> 0)))
        (is (-> res9 :responses count (> 0)))))))

(deftest no-api-test
  (testing "Tests if error is returned for missing API key"
    (let [res (vision/detect-file "test/resources/test.png" :ocr :missing-var)]
      (is (= 403 (-> res :error :code))))))      

(deftest wrong-api-test
  (testing "Tests if a token is returned"
    (let [res (vision/detect-file "test/resources/test.png" :ocr :wrong-api)]
      (is (= 403 (-> res :error :code))))))

(deftest invalid-request-test
  (testing "Tests if a token is returned"
    (let [res (vision/request 10 10 {:url "https://1.firebaseio.com"})
          answer "Firebase error. Please ensure"]
      (is (str/includes? res answer)))))      
