(ns pulse.fhir.bundle-test
  (:require [clojure.test :refer [deftest is]]
            [pulse.fhir.bundle :as bundle]
            [pulse.fhir.resource :as r]
            [pulse.fhir.schema :as schema]))

(def ^:private orgs
  (mapv (fn [i] (r/organization {:id (str "org-" i) :name (str "Clinic " i)})) (range 5)))

(deftest transaction-puts-every-resource-at-its-own-id
  (let [tx (bundle/transaction orgs)]
    (is (= "transaction" (:type tx)))
    (is (= {:method "PUT" :url "Organization/org-0"} (-> tx :entry first :request)))
    (is (= orgs (bundle/resources tx)))
    (is (schema/valid? tx))))

(deftest transactions-are-chunked-in-order
  (let [txs (bundle/transactions orgs 2)]
    (is (= [2 2 1] (map (comp count :entry) txs)))
    (is (= orgs (mapcat bundle/resources txs)) "chunking keeps the dependency order")))

(deftest searchset-paging
  (let [page (bundle/searchset (take 2 orgs) {:total 5 :next-url "http://x/fhir?page=2"})
        last-page (bundle/searchset (drop 4 orgs) {:total 5})]
    (is (= "http://x/fhir?page=2" (bundle/next-url page)))
    (is (nil? (bundle/next-url last-page)))
    (is (= 5 (:total page)))
    (is (schema/valid? page))))

(deftest transaction-responses
  (is (bundle/all-succeeded? {:entry [{:response {:status "201 Created"}} {:response {:status "200 OK"}}]}))
  (is (not (bundle/all-succeeded? {:entry [{:response {:status "201 Created"}} {:response {:status "400 Bad Request"}}]})))
  (is (not (bundle/all-succeeded? {:entry []})) "an empty response is not a success"))
