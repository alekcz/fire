(ns fire.utils
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [org.httpkit.client :as client])
  (:gen-class))

(set! *warn-on-reflection* true)

(def firebase-root "firebaseio.com")
(def storage-upload-root "https://storage.googleapis.com/upload/storage/v1/b")
(def storage-download-root "https://storage.googleapis.com/storage/v1/b")
(def vision-root "https://vision.googleapis.com/v1/images:annotate")

(defn now []
  (quot (inst-ms (java.util.Date.)) 1000))

(defn encode [m]
  (json/encode m))

(defn decode [json-string]
  (json/decode json-string true))

(defn escape 
  "Surround all strings in query with quotes"
  [query]
  (apply merge (for [[k v] query]  {k (if (string? v) (str "\"" v "\"") v)})))

(defn recursive-merge
  "Recursively merge hash maps."
  [a b]
  (if (and (map? a) (map? b))
    (merge-with recursive-merge a b)
    (if (map? a) a b)))

(defn clean-env-var [env-var]
  (-> env-var (name) (str) (str/lower-case) (str/replace "_" "-") (str/replace "." "-") (keyword)))

;; ---------------------------------------------------------------------------
;; The one place fire touches the network.
;;
;; Every outbound call — the OAuth exchange, Google's public certs, the
;; realtime database, storage, vision and the identity toolkit — goes through
;; http! below. That is what makes *http-fn* a complete seam: bind it and no
;; request leaves the process. It is a dynamic var rather than something to
;; with-redefs because this library compiles with direct linking, under which
;; a redefined defn is never seen by its callers; binding on a dynamic var is
;; also thread-local, so a test can install its own responder without it
;; reaching any other thread's requests.
;;
;; A responder takes http-kit's request options map and returns a response
;; map — at least :status and :body, :error for a failed connection — exactly
;; what derefing http-kit's own promise would have given.
;; ---------------------------------------------------------------------------

(def ^:dynamic *http-fn*
  "When bound, a (fn [request-options] response-map) that answers every
   request fire would otherwise send. nil — the default — means real http."
  nil)

(defn http!
  "Perform one http request through `client` (an http-kit client, or a delay
   of one) and return the response map. With a `callback`, hand the response
   to it instead and return whatever http-kit returns.

   Honours *http-fn* when bound, in which case nothing touches the network and
   the callback, if any, runs on the calling thread."
  ([client request-options]
   (if *http-fn*
     (*http-fn* request-options)
     (binding [org.httpkit.client/*default-client* client]
       @(client/request request-options))))
  ([client request-options callback]
   (if *http-fn*
     (callback (*http-fn* request-options))
     (binding [org.httpkit.client/*default-client* client]
       (client/request request-options callback)))))
