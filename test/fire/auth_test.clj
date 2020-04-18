(ns fire.auth-test
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [fire.auth :as fire-auth]))

(deftest returns-token-test
  (testing "Tests if a token is returned"
    (let [auth (fire-auth/create-token :fire)
          token (:token auth)]
      (is (not (str/blank? token))))))
