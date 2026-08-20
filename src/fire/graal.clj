(ns fire.graal 
  (:require [fire.core :as fire]
            [fire.admin :as admin]
            [fire.auth :as auth]
            [fire.socket :as socket]
            [fire.storage :as storage]
            [fire.utils :as utils]
            [fire.vision :as vision]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.util Base64])
  (:gen-class))

(set! *warn-on-reflection* true)

(defn core-main[]
  (let [auth (auth/create-token :fire)
        db (:project-id auth)
        root "/fire-graalvm-test"]
    (fire/push! db root {:originalname "graalvm"} auth)
    (fire/write! db root {:name "graal"} auth)
    (let [res (fire/read db root auth)]
      (fire/delete! db root auth)
      (println res)
      res)))


(defn socket-main []
  (let [auth (auth/create-token :fire)
        db (socket/connect (:project-id auth) auth)
        root "/fire-graalvm-test-socket"]
    (socket/push! db root {:originalname "graalvm"})
    (socket/write! db root {:name "graal-socket"})
    (let [res (socket/read db root)]
      (socket/delete! db root)
      (println res)
      (socket/disconnect db) 
      res)))      

(defn storage-main []
  (let [auth (auth/create-token :fire)
        file (str "temp.graal.bin")
        test (str "temp.graal.test")
        deleted (str "temp.graal.deleted")
        contents "graal-storage"
        _ (spit file contents)
        _ (storage/upload! file file "text/plain" auth)
        dl1 (storage/download file auth {:async true})
        _ (storage/download-to-file file test auth)
        thawed (slurp test)
        _ (storage/delete! file auth)
        dl2 (storage/download file auth)
        _ (storage/download-to-file file deleted auth)]
      (println (= contents dl1 thawed))
      (println (= dl2 (slurp deleted)))
      (io/delete-file file)
      (io/delete-file test)
      (io/delete-file deleted)
      dl1))

(defn vision-main []
  (let [res (-> (vision/detect-file "test/resources/graalvm.png" :ocr :vision-api) :responses first :fullTextAnnotation :text)
        answer "GraalVM\n"]
      (println (= res answer))
      (println answer)
      res))

(defn admin-main []
  ;; deliberately the one admin path with no api call and no side effect behind
  ;; it: minting a custom token is local RSA signing. That still exercises the
  ;; namespace under native-image — and native-image plus crypto is exactly
  ;; where things tend to break — without creating users on every build.
  (let [auth (auth/create-token :fire)
        token (admin/create-custom-token "graal-admin" auth {:claims {:graal true}})
        payload (-> (str token) (str/split #"\." 3) second)
        claims (utils/decode (String. (.decode (Base64/getUrlDecoder) ^String payload) "UTF-8"))]
    (println (= {:graal true} (:claims claims)))
    (println (:uid claims))
    (:uid claims)))

(defn -main [ & _]
  (core-main)
  (socket-main)
  (storage-main)
  (vision-main)
  (admin-main)
  "graal")
