(ns fire.storage
  (:require [fire.utils :as utils]
            [fire.auth :as auth]
            [org.httpkit.client :as client]
            [org.httpkit.sni-client :as sni-client]
            [clojure.java.io :as io])       
  (:import [java.net URLEncoder]) 
  (:gen-class))

(def sni-client (delay (client/make-client {:ssl-configurer sni-client/ssl-configurer})))


(defn stream->bytes [is]
  (let [baos (java.io.ByteArrayOutputStream.)]
    (io/copy is baos)
    (.toByteArray baos)))

(defn request 
  "Request method used by other functions."
  [method domain url' mime data & [auth options]]
    (let [token (when (:expiry auth) 
                  (if (< (utils/now) (:expiry auth))
                    (:token auth) 
                    (-> auth :env auth/create-token :token)))
          bucket (str (or (:project-id options) (:project-id auth)) ".appspot.com")
          url (str domain "/" bucket url')
          request-options (reduce utils/recursive-merge 
                              [{:method method}
                              {:url url}
                              {:headers {"Connection" "keep-alive"}}
                              {:keepalive 600000}
                              (when mime {:headers {"Content-Type" mime}})
                              (when auth {:headers {"Authorization" (str "Bearer " token)}})
                              (when-not (nil? data) {:body data})
                              (dissoc options :async)])
          c sni-client]
      (binding [org.httpkit.client/*default-client* c]
        (let [response @(client/request request-options)
              res (-> response :body)
              error (:error response)]
          (if error error res)))))

(defn clean [url]
 (URLEncoder/encode url "UTF-8"))

(defn to-file [file-path data]
  (when data
    (io/make-parents file-path)
    (with-open [out (io/output-stream (io/as-file file-path))]
      (clojure.java.io/copy data out))))

(defn upload! 
  "Uploads a file to Firebase storage"
  [name source mime & [auth options]]
  (let [res (if (string? source)
              (with-open [filestream (io/input-stream (io/as-file source))]
                (request :post utils/storage-upload-root (str "/o?uploadType=media&name=" name) mime (stream->bytes filestream) auth options))
              (request :post utils/storage-upload-root (str "/o?uploadType=media&name=" name) mime source auth options))]
      (utils/decode res)))

(defn download 
  "Uploads a file to Firebase storage"
  [source & [auth options]]
  (request :get utils/storage-download-root  (str "/o/" (clean source) "?alt=media") nil nil auth options))   

(defn download-to-file
  "Uploads a file to Firebase storage"
  [source destination & [auth options]]
  (let [res (request :get utils/storage-download-root  (str "/o/" (clean source) "?alt=media") nil nil auth options)]
    (to-file destination res)))   

(defn delete! 
  "Uploads a file to Firebase storage"
  [target & [auth options]]
  (let [res (request :delete utils/storage-download-root  (str "/o/" (clean target)) nil nil auth options)]
    (utils/decode res)))

(defn -main []
  (let [auth (auth/create-token :fire)
        file (str "temp.graal.bin")
        test (str "temp.graal.test")
        deleted (str "temp.graal.deleted")
        contents "graal-storage"
        _ (spit file contents)
        _ (upload! file file "text/plain" auth)
        dl1 (download file auth {:async true})
        _ (download-to-file file test auth)
        thawed (slurp test)
        _ (delete! file auth)
        dl2 (download file auth)
        _ (download-to-file file deleted auth)]
      (println (= contents dl1 thawed))
      (println (= dl2 (slurp deleted)))
      (io/delete-file file)
      (io/delete-file test)
      (io/delete-file deleted)
      dl1))