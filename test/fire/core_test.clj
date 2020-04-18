(ns fire.core-test
  (:require [clojure.test :refer :all]
            [malli.generator :as mg]
            [com.climate.claypoole :as cp]
            [fire.auth :as fire-auth]
            [fire.core :as fire]))


(def home
  [:map
    [:name string?]
    [:description string?]
    [:rooms int?]
    [:capcity int?]
    [:address
      [:map
        [:street string?]
        [:number int?]
        [:country [:enum "kenya" "lesotho" "south-africa" "italy" "mozambique" "spain" "india" "brazil" "usa" "germany"]]]]])

(defn non-zero [n] (inc (rand-int n)))

(defn random-homes [n] (repeat n (mg/generate home {:size (non-zero 100) :seed (non-zero 1000)})))

(deftest prud-test
  (testing "Push read update and delete data"
    (let [seed 1
          home (first (random-homes 1))
          updated-home (update-in home [:address :number] inc)
          auth (fire-auth/create-token :fire)
          db (:project-id auth)
          path (str "/fire-test-" seed "/" (mg/generate string? {:size (non-zero 100) :seed seed}))
          resp (fire/push! db path home auth)
          npath (str path "/" (:name resp))
          read0  (fire/read db path auth)
          read1  (fire/read db npath auth)
          _ (fire/update! db npath updated-home auth)
          read2  (fire/read db npath auth)
          _ (fire/delete! db npath auth)
          read3  (fire/read db npath auth)]
      (is (not= home read0))
      (is (= home read1))
      (is (= updated-home read2))
      (is (nil? read3)))))

(deftest crud-test
  (testing "Create read update and delete data"
    (let [seed 2
          home (first (random-homes 1))
          updated-home (update-in home [:address :number] inc)
          auth (fire-auth/create-token :fire)
          db (:project-id auth)
          path (str "/fire-test-" seed "/" (mg/generate string? {:size (non-zero 100) :seed seed}))
          _ (fire/write! db path home auth)
          read1  (fire/read db path auth)
          _ (fire/update! db path updated-home auth)
          read2  (fire/read db path auth)
          _ (fire/delete! db path auth)
          read3  (fire/read db path auth)]
      (is (= home read1))
      (is (= updated-home read2))
      (is (nil? read3))))) 

(deftest parallel-bulk-prd-test
  (testing "Bulk push read and delete data"
      (let [seed 3
            num 1000
            homes (random-homes num)
            auth (fire-auth/create-token :fire)
            db (:project-id auth)
            path (str "/fire-test-" seed "/" (mg/generate string? {:size (non-zero 100) :seed seed}))
            pool (cp/threadpool 100)
            _ (time (doall (cp/pmap pool #(fire/push! db path % auth) homes)))
            read (fire/read db path auth)
            _ (fire/delete! db path auth)
            read2  (fire/read db path auth)]
        (is (= num (count (keys read))))
        (is (= (set homes) (set (vals read))))
        (is (nil? read2)))))      
