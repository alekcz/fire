(ns fire.vision
  (:require [fire.utils :as utils]
            [org.httpkit.client :as client]
            [org.httpkit.sni-client :as sni-client]
            [clojure.java.io :as io]
            [environ.core :refer [env]])       
  (:import  [java.util Base64 Base64$Decoder Base64$Encoder]) 
  (:gen-class))

(set! *warn-on-reflection* true)

(def sni-client (delay (client/make-client {:ssl-configurer sni-client/ssl-configurer})))
(def ^Base64$Encoder b64encoder (. Base64 getEncoder))
(def ^Base64$Decoder b64decoder (. Base64 getDecoder))
(def api-list { :text "TEXT_DETECTION"
                :ocr  "TEXT_DETECTION"
                :faces  "FACE_DETECTION"
                :landmarks "LANDMARK_DETECTION"
                :logos "LOGO_DETECTION"
                :labels "LABEL_DETECTION"
                :properties "IMAGE_PROPERTIES"
                :objects "OBJECT_LOCALIZATION"
                :safe "SAFE_SEARCH_DETECTION"
                :web "WEB_DETECTION"})


(defn type-match [api]
  (if (keyword? api)
    (get api-list api)
    api))

(defn stream->bytes [is]
  (let [baos (java.io.ByteArrayOutputStream.)]
    (io/copy is baos)
    (.toByteArray baos)))

(defn request 
  "Request method used by other functions."
  [data key & [options]]
    (let [request-options (reduce utils/recursive-merge 
                              [{:method :post}
                              {:url (str utils/vision-root "?key=" key)}
                              {:headers {"Connection" "keep-alive"}}
                              {:keepalive 600000}
                              {:headers {"Content-Type" "application/json"}}
                              {:body (utils/encode data)}
                              options])
          c sni-client]
      (binding [org.httpkit.client/*default-client* c]
        (let [response @(client/request request-options)]
          (:body response :body)))))

(defn detect  
  "Run OCR on Base64 string using Google Cloud Vision. API key required in env-var"
  [b64 type env-var]
  (let [api-key (env (-> env-var utils/clean-env-var keyword))
        res (request {:requests [{:image {:content b64}
                                  :features [{:type (type-match type)}]}]} api-key)]
    (utils/decode res)))

(defn detect-bytes  
  "Run OCR on bytes using Google Cloud Vision. API key required in env-var"
  [bytes type env-var]
  (let [b64 (.encodeToString b64encoder ^"[B" bytes)
        api-key (env (-> env-var utils/clean-env-var keyword))
        res (request {:requests [{:image {:content b64}
                                  :features [{:type (type-match type)}]}]} api-key)]
    (utils/decode res)))

(defn detect-file  
  "Run OCR on file using Google Cloud Vision. API key required in env-var"
  [source type env-var]
  (with-open [filestream (io/input-stream (io/as-file source))]
    (let [b64 (.encodeToString b64encoder ^"[B" (stream->bytes filestream))
          api-key (env (-> env-var utils/clean-env-var keyword))
          res (request {:requests [{:image {:content b64}
                                    :features [{:type (type-match type)}]}]} api-key)]
      (utils/decode res))))
