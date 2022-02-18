(ns fire.ocr
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

(defn ocr  
  "Run OCR on file using Google Cloud Vision. API key required in env-var"
  [source env-var]
  (with-open [filestream (io/input-stream (io/as-file source))]
    (let [b64 (.encodeToString b64encoder ^"[B" (stream->bytes filestream))
          api-key (env (-> env-var utils/clean-env-var keyword))
          res (request {:requests [{:image {:content b64}
                                    :features [{:type "TEXT_DETECTION"}]}]} api-key)]
      (utils/decode res))))