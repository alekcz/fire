(ns fire.auth-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [fire.auth :as fire-auth]))

(deftest default-token-test
  (testing "Tests if a token is returned"
    (let [auth (fire-auth/create-token)
          token (:token auth)]
      (is (not (str/blank? token))))))

(deftest nil-token-test
  (testing "Tests if a token is returned"
    (let [auth (fire-auth/create-token nil)
          token (:token auth)]
      (is (not (str/blank? token))))))

(deftest keyword-token-test
  (testing "Tests if a token is returned"
    (let [auth (fire-auth/create-token :fire)
          token (:token auth)]
      (is (not (str/blank? token))))))

(deftest string-token-test
  (testing "Tests if a token is returned"
    (let [auth (fire-auth/create-token "FIRE")
          token (:token auth)]
      (is (not (str/blank? token))))))
