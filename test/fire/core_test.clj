(ns fire.core-test
  (:require [clojure.test :refer :all]
            [malli.generator :as mg]
            [clojure.string :as str]
            [com.climate.claypoole :as cp]
            [fire.auth :as fire-auth]
            [fire.core :as fire]
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
  (testing "Push read update and delete data"
    (let [_ (println "Push read and delete data")
          seed 1
          home (first (random-homes 1))
          updated-home (update-in home [:address :number] inc)
          auth (fire-auth/create-token :fire)
          db (:project-id auth)
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

(deftest crud-test
  (testing "Create read update and delete data"
    (let [_ (println "Create read and delete data")
          seed 2
          home (first (random-homes 1))
          updated-home (update-in home [:address :number] inc)
          auth (fire-auth/create-token :fire)
          db (:project-id auth)
          path (str "/fire-test/t-" seed "/" (mg/generate string? {:size (non-zero 20) :seed seed}))
          _ (fire/write! db path home auth)
          read1  (fire/read db path auth)
          _ (fire/update! db path updated-home auth)
          read2  (fire/read db path auth)
          _ (fire/delete! db path auth)
          read3  (fire/read db path auth)]
      (is (= home read1))
      (is (= updated-home read2))
      (is (nil? read3))))) 

(deftest bulk-test
  (testing "Bulk push read and delete data"
    (let [_ (println "Bulk push read and delete data")
          seed 2
          num 100
          homes (random-homes num)
          conns (fire/connection-pool num)
          auth (fire-auth/create-token :fire)
          db (:project-id auth)
          path (str "/fire-test/t-" seed "/" (mg/generate string? {:size (non-zero 20) :seed seed}))
          pool (cp/threadpool 100)
          _ (doall (cp/pmap pool #(fire/push! db path % auth {:pool conns}) homes))
          read (fire/read db path auth {:query {:shallow true} :pool conns})
          _ (fire/delete! db path auth {:pool conns})
          read2  (fire/read db path auth {:pool conns})]
      (is (= num (count (keys read))))
      (is (every? true? (vals read)))
      (is (nil? read2))
      (cp/shutdown pool)
      (fire/shutdown! conns))))     

(deftest async-test
  (testing "Async test"
    (let [_ (println "Async test")
          seed 3
          num 10
          conns (fire/connection-pool num)
          homes (random-homes num)
          auth (fire-auth/create-token :fire)
          db (:project-id auth)
          path (str "/fire-test/t-" seed "/" (mg/generate string? {:size (non-zero 20) :seed seed}))
          pool (cp/threadpool 10)
          _ (doall (cp/pmap pool #(fire/push! db path % auth {:async true :pool conns}) homes))
          _ (Thread/sleep 3000)
          read (async/<!! (fire/read db path auth {:query {:shallow true} :async true :pool conns}))
          _ (async/<!! (fire/write! db path (first homes) auth {:async true :pool conns}))
          _ (fire/update! db path (second homes) auth {:async true :pool conns})
          read2 (fire/read db path auth {:async true :pool conns})
          _ (fire/delete! db path auth {:async true :pool conns})
          read3 (fire/read db path auth {:async true :pool conns})]
    (is (= num (count (keys read))))
    (is (every? true? (vals read)))
    (is (= (second homes) (async/<!! read2)))
    (is (nil? (async/<!! read3)))
    (cp/shutdown pool)
    (fire/shutdown! conns)))) 


(deftest limit-test
  (testing "Limit responses"
    (let [_ (println "Limit responses")
          seed 4
          num 10
          limit 2
          homes (random-homes num)
          auth (fire-auth/create-token :fire)
          conns (fire/connection-pool num)
          db (:project-id auth)
          path (str "/fire-test/t-" seed "/" (mg/generate string? {:size (non-zero 20) :seed seed}))
          pool (cp/threadpool 100)
          _ (doall (cp/pmap pool #(fire/push! db path % auth {:pool conns}) homes))
          read (fire/read db path auth {:query {:orderBy "$key" :limitToFirst limit}})
          read2  (fire/read db path auth)
          _ (fire/delete! db path auth)]
      (is (= limit (count (keys read))))
      (is (= num (count (keys read2))))
      (cp/shutdown pool)
      (fire/shutdown! conns))))


(deftest query-test
  (testing "Query test"
    (let [_ (println "Query test")
          seed 5
          num 100
          conns (fire/connection-pool num)
          anchor (+ 30 (rand-int 69))
          start (rand-int 20)
          end  (+ 50 (rand-int 40))
          homes (random-homes num)
          auth (fire-auth/create-token :fire)
          db (:project-id auth)
          path (str "/fire-test/t-" seed "/" (mg/generate string? {:size (non-zero 20) :seed seed}))
          pool (cp/threadpool 100)
          _ (doall (cp/pmap pool #(fire/push! db path % auth  {:pool conns}) homes))
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
    (cp/shutdown pool)
    (fire/shutdown! conns))))        

(deftest run-test
  (testing "Run test"
    (do (println "Run test")
      (is (str/includes? (with-out-str (fire/-main)) "{:name graal}")))))

(deftest merge-test
  (testing "Push read update and delete data"
    (let [m1 {:one 2}
          m2 {:three 4}
          both {:one 2 :three 4}]
      (is (= both (fire/recursive-merge m1 m2)))
      (is (= m1 (fire/recursive-merge m1 2)))
      (is (= m2 (fire/recursive-merge 1 m2))))))

(deftest ex-test
  (testing "Exception test"
    (let [_ (println "Exception test")
          seed 6
          home (first (random-homes 1))
          auth (fire-auth/create-token :fire)
          db (:project-id auth)
          fake-auth {:token (fn [] "asdasdasd")}
          path (str "/fire-test/t-ex" (mg/generate string? {:size 10 :seed seed}))]
      (is (= clojure.lang.ExceptionInfo (type (fire/read db path home auth))))
      (is (= clojure.lang.ExceptionInfo (type (fire/read "fake.fake fake.fake" path home auth))))
      (is (= clojure.lang.ExceptionInfo (type (fire/read db path home fake-auth))))
      (is (= clojure.lang.ExceptionInfo (type (fire/read "dont-have-auth" path home auth)))))))