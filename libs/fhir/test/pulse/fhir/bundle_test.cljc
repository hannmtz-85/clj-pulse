(ns pulse.fhir.bundle-test
  (:require [clojure.test :refer [deftest is testing]]
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

(def ^:private alert-system "https://pulse.example/identifier/alert")

(def ^:private alert
  (r/flag {:id "flag-1" :patient "pt-ana" :text "BP stage 2" :start "2026-10-13T08:00:00Z"
           :identifier {:system alert-system :value "R1-pt-ana-2026-10-13T08:00:00Z"}}))

(deftest a-conditional-create-is-a-post-that-says-when-not-to-create
  (let [entry (bundle/conditional-create-entry alert (str "identifier=" alert-system "|R1-pt-ana-2026-10-13T08:00:00Z"))]
    (is (= {:method "POST"
            :url "Flag"
            :ifNoneExist (str "identifier=" alert-system "|R1-pt-ana-2026-10-13T08:00:00Z")}
           (:request entry)))
    (is (not (contains? (:resource entry) :id)) "a create may not name the id")
    (is (= (dissoc alert :id) (:resource entry)) "and nothing else changes")
    (is (schema/valid? (bundle/transaction-of [entry])))))

(deftest a-transaction-of-entries-keeps-their-order
  (let [entries [(bundle/put-entry (first orgs))
                 (bundle/conditional-create-entry alert "identifier=x")]
        tx (bundle/transaction-of (list (first entries) (second entries)))]
    (is (= "transaction" (:type tx)))
    (is (= entries (:entry tx)))
    (is (vector? (:entry tx)))))

(deftest the-id-of-a-location
  (is (= ["Flag" "123"] (bundle/location-id "Flag/123/_history/1")))
  (is (= ["Flag" "123"] (bundle/location-id "Flag/123")))
  (is (= ["Flag" "0a8f-1"] (bundle/location-id "Flag/0a8f-1/_history/12")))
  (is (= ["Flag" "123"] (bundle/location-id "http://localhost:8090/fhir/Flag/123/_history/1"))
      "also when the server gives an absolute URL")
  (is (nil? (bundle/location-id nil)))
  (is (nil? (bundle/location-id "")))
  (is (nil? (bundle/location-id "Flag")))
  (is (nil? (bundle/location-id 42)))
  (testing "what is not the location of a resource"
    (is (nil? (bundle/location-id "flag/123")) "a resource type starts with a capital")
    (is (nil? (bundle/location-id "xFlag/123")) "and at the start of a segment")
    (is (nil? (bundle/location-id "Flag/12/3")) "an id is one segment")
    (is (nil? (bundle/location-id "Flag/123/_history/1/more")) "nothing follows the version")
    (is (nil? (bundle/location-id "Flag/123 and more")))
    (is (= ["Flag" (apply str (repeat 64 "a"))] (bundle/location-id (str "Flag/" (apply str (repeat 64 "a")))))
        "an id has at most 64 characters, as FHIR says")
    (is (nil? (bundle/location-id (str "Flag/" (apply str (repeat 65 "a"))))) "and not more")))

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
