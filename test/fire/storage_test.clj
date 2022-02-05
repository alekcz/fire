(ns fire.storage-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [fire.auth :as fire-auth]
            [fire.storage :as store]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.nippy :as nippy]))

(defn rand-str [len]
  (apply str (take len (repeatedly #(char (+ (rand 26) 65))))))

(defn delete-directory-recursive
  "Recursively delete a directory."
  [^java.io.File file]
  (when (.isDirectory file)
    (run! delete-directory-recursive (.listFiles file)))
  (io/delete-file file))
  
(defn make-env [f]
  (io/make-parents "temp/source/pew.txt")
  (f)
  (delete-directory-recursive (io/file "temp/")))

(def local-root "http://localhost:9199/storage/v1/b")

(use-fixtures :once make-env)

(deftest storage-test
  (testing "Upload, download, and delete a text file"
    (let [_ (println "Upload, download, and delete a text file")
          auth' (fire-auth/create-token :fire)
          two-hours (* 2 60 60 1000)
          auth (update auth' :expiry - two-hours)
          randy (rand-str 10)
          file (str "temp/source/" randy ".bin")
          test (str "temp/test/" randy ".test")
          deleted (str "temp/test/" randy ".deleted")
          contents (rand-str 100000)
          _ (spit file contents)
          _ (store/upload! file file "text/plain" auth)
          dl1 (store/download file auth {:async true})
          _ (store/download-to-file file test auth)
          thawed (slurp test)
          _ (store/delete! file auth)
          dl2 (store/download file auth)
          _ (store/download-to-file file deleted auth)]
      (is (= contents dl1 thawed))
      (is (= dl2 (slurp deleted)))
      (is (= java.net.URISyntaxException (type (store/download "doesn't exist" auth {:project-id "doesnt exist"}))))
      (is (str/includes? dl2 "o such object")))))

(deftest binary-storage-test
  (testing "Upload, download, and delete a binary file"
    (let [_ (println "Upload, download, and delete a binary file")
          auth (fire-auth/create-token :fire)
          randy (rand-str 10)
          file (str "temp/source/" randy ".bin")
          file2 (str "temp/source/" randy "2.bin")
          test (str "temp/test/" randy ".test")
          deleted (str "temp/test/" randy ".deleted")
          contents (rand-str 10)
          _ (nippy/freeze-to-file file contents)
          _ (store/upload! file file "application/octet-stream" auth {:project-id "alekcz-dev"})
          _ (store/upload! file2 (nippy/freeze contents) "application/octet-stream" auth {:project-id "alekcz-dev" :async true})
          dl1 (store/download file auth)
          _ (store/download-to-file file test auth {:async true})
          thawed1 (nippy/thaw (store/stream->bytes dl1))
          thawed2 (nippy/thaw-from-file test)
          _ (store/delete! file auth)
          dl2 (store/download file auth)
          _ (store/download-to-file file deleted auth)]
      (is (= contents thawed1 thawed2))
      (is (= dl2 (slurp deleted)))
      (is (str/includes? dl2 "o such object")))))

(deftest error-test
  (testing "Test that errors are handled"
    (let [_ (println "Test that errors are handled")
          auth (fire-auth/create-token :fire)
          a1 (store/upload! nil nil "application/octet-stream" auth {:project-id "is-not-authenticated-to"})]
      (println a1))))

;; (deftest local-test
;;   (testing "Upload, download, and delete a text file"
;;     (let [_ (println "Upload, download, and delete a text file locally")
;;           auth (fire-auth/create-token :fire)
;;           file "temp.txt"
;;           test "temp.test"
;;           deleted "temp.deleted"
;;           contents (rand-str 10)
;;           ;; _ (println "upload" (store/upload! file file "text/plain" auth {:host local-root :bucket "default-bucket"}))
;;           dl1 (store/download file auth {:host local-root :bucket "default-bucket"})
;;           _ (store/download-to-file file test auth {:host local-root :bucket "default-bucket"})
;;           thawed (slurp test)
;;           ;; _ (store/delete! file auth {:host local-root})
;;           dl2 (store/download file auth {:host local-root :bucket "default-bucket"})
;;           _ (store/download-to-file file deleted auth {:host local-root :bucket "default-bucket"})]
;;       (is (= contents dl1 thawed))
;;       (is (= dl2 (slurp deleted)))
;;       (is (str/includes? dl2 "o such object")))))