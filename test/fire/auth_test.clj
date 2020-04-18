(ns fire.auth-test
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [fire.auth :as fire-auth]))

(deftest returns-token-test
  (testing "Tests if a token is returned"
    (let [auth-token (fire-auth/create-token :fire)]
      (is (not (str/blank? (:token auth-token)))))))