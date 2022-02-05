(ns fire.graal 
  (:require [fire.core :as fire]
            [fire.auth :as auth]
            [fire.socket :as socket]
            [fire.storage :as storage]
            [clojure.java.io :as io])
  (:gen-class))


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

(defn -main [ & _]
  (core-main)
  (socket-main)
  (storage-main))
