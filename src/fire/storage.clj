(ns fire.storage
  (:require [fire.utils :as utils]
            [fire.auth :as auth]
            [org.httpkit.client :as client]
            [org.httpkit.sni-client :as sni-client]
            [clojure.java.io :as io])       
  (:import [java.net URLEncoder]) 
  (:gen-class))

(set! *warn-on-reflection* true)

(def sni-client (delay (client/make-client {:ssl-configurer sni-client/ssl-configurer})))

;; firebase changed the default storage bucket suffix in late 2024: projects
;; created after that answer on <project>.firebasestorage.app, while older ones
;; still answer on <project>.appspot.com. Rather than guess a project's vintage
;; — and get it wrong for half of them — probe once and remember the answer.
(defonce ^:private resolved-buckets (atom {}))

(defn- bucket-candidates [project-id]
  [(str project-id ".firebasestorage.app")
   (str project-id ".appspot.com")])

(defn- bucket-exists? [bucket token]
  (binding [org.httpkit.client/*default-client* sni-client]
    (let [res @(client/request (cond-> {:method :get
                                        :url (str utils/storage-download-root "/" bucket)}
                                 token (assoc :headers {"Authorization" (str "Bearer " token)})))]
      ;; 404 is the only answer that definitely means "no such bucket"; a 401 or
      ;; 403 means it's there and we simply can't read its metadata.
      (not= 404 (:status res)))))

(defn- default-bucket
  "The project's storage bucket, resolved once per project and cached."
  [project-id token]
  (or (get @resolved-buckets project-id)
      (let [candidates (bucket-candidates project-id)
            found (or (first (filter #(bucket-exists? % token) candidates))
                      (first candidates))]
        (swap! resolved-buckets assoc project-id found)
        found)))


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
          bucket (or (:bucket options)
                     (default-bucket (or (:project-id options) (:project-id auth)) token))
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
 (URLEncoder/encode (str url "") "UTF-8"))

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
