(ns fire.socket-test
  (:require [clojure.test :refer [deftest is testing]]
            [malli.generator :as mg]
            [com.climate.claypoole :as cp]
            [fire.auth :as fire-auth]
            [fire.socket :as fire]
            [clojure.core.async :as async]))

(def home
  [:map
    [:name string?]
    [:description string?]
    [:rooms pos-int?]
    [:capacity float?]
    [:address
      [:map
        [:street string?]
        [:number int?]
        [:country [:enum "kenya" "lesotho" "south-africa" "italy" "mozambique" "spain" "india" "brazil" "usa" "germany"]]]]])

(defn non-zero [n] (inc (rand-int n)))

(defn random-homes [n] 
  (for [r (range n)]
    (-> (mg/generate home {:size (+ (mod r 10) 11) :seed r}) 
        (assoc :num (inc r)))))

(deftest prud-test
  (testing "Push read update and delete data test"
    (let [_ (println "Push read and delete data test")
          seed 1
          home (first (random-homes 1))
          updated-home (update-in home [:address :number] inc)
          auth (fire-auth/create-token :fire)
          db (fire/connect (:project-id auth) auth)
          path (str "/fire-test/t-" seed "/" (mg/generate string? {:size (non-zero 20) :seed seed}))
          resp (fire/push! db path home)
          npath (str path "/" (:name resp))
          read0  (fire/read db path)
          read1  (fire/read db npath)
          _ (fire/update! db npath updated-home)
          read2  (fire/read db npath)
          _ (fire/delete! db npath)
          read3  (fire/read db npath)]
      (is (not= home read0))
      (is (= home read1))
      (is (= updated-home read2))
      (is (nil? read3)))))

(deftest crud-test
  (testing "Create read update and delete data test"
    (let [_ (println "Create read and delete data test")
          seed 2
          home (first (random-homes 1))
          updated-home (update-in home [:address :number] inc)
          auth' (fire-auth/create-token :fire)
          two-hours (* 2 60 60 1000)
          auth (update auth' :expiry - two-hours)
          db' (str "wss://" (:project-id auth) ".firebaseio.com") 
          db (fire/connect db' auth)
          path (str "/fire-test/t-" seed "/" (mg/generate string? {:size (non-zero 20) :seed seed}))
          _ (fire/write! db path home)
          read1  (fire/read db path)
          _ (fire/update! db path updated-home)
          read2  (fire/read db path)
          _ (fire/delete! db path)
          read3  (fire/read db path)]
      (is (= home read1))
      (is (= updated-home read2))
      (is (nil? read3))))) 

(deftest bulk-test
  (testing "Bulk push read and delete data test"
    (let [_ (println "Bulk push read and delete data test")
          seed 2
          num 100
          homes (random-homes num)
          auth (fire-auth/create-token :fire)
          db (fire/connect (:project-id auth) auth)
          path (str "/fire-test/t-" seed "/" (mg/generate string? {:size (non-zero 20) :seed seed}))
          pool (cp/threadpool 100)
          _ (println (doall (cp/pmap pool #(fire/push! db path %) homes)))
          _ (Thread/sleep 5000)
          read (fire/read db path)
          _ (fire/delete! db path)
          read2  (fire/read db path)]
      (is (= num (count (keys read))))
      (is (every? some? (vals read)))
      (is (nil? read2))
      (cp/shutdown pool))))     

(deftest async-test
  (testing "Async test"
    (let [_ (println "Async test")
          seed 3
          num 10
          homes (random-homes num)
          auth (fire-auth/create-token :fire)
          db (fire/connect (:project-id auth) auth)
          path (str "/fire-test/t-" seed "/" (mg/generate string? {:size (non-zero 20) :seed seed}))
          pool (cp/threadpool 10)
          _ (doall (cp/pmap pool #(fire/push! db path % auth {:async true}) homes))
          _ (Thread/sleep 6000)
          read (async/<!! (fire/read db path auth {:query {:shallow true} :async true}))
          _ (async/<!! (fire/write! db path {:name "random"} auth {:async true}))
          _ (async/<!! (fire/update! db path {:name "random2"} auth {:async true}))
          _ (async/<!! (fire/delete! db path auth {:async true}))
          _ (Thread/sleep 6000)
          read2 (fire/read db path auth {:async true})]
    (is (= num (count (keys read))))
    (is (every? true? (vals read)))
    (is (nil? (async/<!! read2)))
    (cp/shutdown pool)))) 


(deftest limit-test
  (testing "Limit responses test"
    (let [_ (println "Limit responses test")
          seed 4
          num 10
          limit 2
          homes (random-homes num)
          auth (fire-auth/create-token :fire)
          db (fire/connect (:project-id auth) auth)
          path (str "/fire-test/t-" seed "/" (mg/generate string? {:size (non-zero 20) :seed seed}))
          pool (cp/threadpool 100)
          _ (doall (cp/pmap pool #(fire/push! db path % auth) homes))
          read (fire/read db path auth {:query {:orderBy "$key" :limitToFirst limit}})
          read2  (fire/read db path auth)
          _ (fire/delete! db path auth)]
      (is (= limit (count (keys read))))
      (is (= num (count (keys read2))))
      (cp/shutdown pool))))


(deftest query-test
  (testing "Query test"
    (let [_ (println "Query test")
          seed 5
          num 100
          anchor (+ 30 (rand-int 69))
          start (rand-int 20)
          end  (+ 50 (rand-int 40))
          homes (random-homes num)
          auth (fire-auth/create-token :fire)
          db (fire/connect (:project-id auth) auth)
          path (str "/fire-test/t-" seed "/" (mg/generate string? {:size (non-zero 20) :seed seed}))
          pool (cp/threadpool 100)
          _ (doall (cp/pmap pool #(fire/push! db path % auth ) homes))
          _ (Thread/sleep 2000)
          read (fire/read db path auth)
          ord (sort (map name (keys read)))
          read2  (fire/read db path auth {:query {:orderBy "$key" :startAt (nth ord start) :endAt (nth ord end)}})
          read3  (fire/read db path auth {:query {:orderBy "$key" :startAt (nth ord anchor) :endAt (nth ord anchor)}})
          read4  (fire/read db path auth {:query {:orderBy "$key" :equalTo (nth ord anchor)}})
          seventh ((keyword (nth ord anchor)) read)
          _ (fire/delete! db path auth)]
    (is (= num (count ord)))
    (is (= (- (inc end) start) (count (keys read2))))
    (is (= (-> read3 vals first) 
            (-> read4 vals first) 
          seventh))
    (cp/shutdown pool))))        

(deftest ex-test
  (testing "Exception test"
    (let [_ (println "Exception test")
          seed 6
          auth (fire-auth/create-token :fire)
          db (fire/connect (:project-id auth) auth)
          fake-auth {:token (fn [] "asdasdasd")}
          corrupted-auth {:expiry "12345"}
          path (str "/fire-test/t-ex" (mg/generate string? {:size 10 :seed seed}))]
      (is (contains? (fire/read db path fake-auth) :error))
      (is (contains? (fire/read "dont-have-auth" path auth) :error))
      (is (contains? (try (fire/read "fake.fake fake.fake" path auth) (catch Exception _ {:error true})) :error))
      (is (contains? (try (fire/read db path corrupted-auth)(catch Exception _ {:error true})) :error))
      nil)))

(deftest no-auth-test
  (testing "No auth push and read"
    (let [_ (println "No auth push and read")
          seed 99
          home (first (random-homes 1))
          updated-home (update-in home [:address :number] inc)
          auth nil
          db "http://localhost:9000"
          path (str "/fire-test/t-" seed "/" (mg/generate string? {:size (non-zero 20) :seed seed}))
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

(deftest denied-test
  (testing "Error in response"
    (let [_ (println "Error in reponse")
          auth nil
          data {:test "test"}
          db "http://localhost:1001"
          path "irrelevant"]
      (is (= "failed" (try (fire/push! db path data auth) (catch Exception _ "failed")))))))      
      