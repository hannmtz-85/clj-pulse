(ns pulse.fhir.client.hapi-test
  "Integration test: the HTTP client against a real HAPI FHIR server, checked against the
  in-memory client. Both must answer the same queries the same way, which is what makes the
  in-memory fake trustworthy as a test double.

  Needs the stack (`bb up`) and runs with `bb test:it`. It writes only resources whose ids
  start with `it-` and deletes them afterwards, so the demo data is untouched."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [pulse.fhir.bundle :as bundle]
            [pulse.fhir.client :as c]
            [pulse.fhir.client.http :as http]
            [pulse.fhir.client.memory :as mem]
            [pulse.fhir.observation :as obs]
            [pulse.fhir.resource :as r]
            [pulse.fhir.synthetic :as synthetic]))

(def ^:private base-url (or (System/getenv "FHIR_BASE_URL") "http://localhost:8090/fhir"))

(def ^:private patient-id "it-pt-1")

(def ^:private fixtures
  (into [(r/patient {:id patient-id :given "Integration" :family "Test" :gender "other"
                     :birth-date "1990-01-01"})]
        (map (fn [i]
               (obs/quantity-observation
                {:id (str "it-obs-" i) :patient patient-id :concept :heart-rate :value (+ 60 i)
                 :unit "/min" :at (str "2026-01-0" (inc i) "T08:00:00Z")}))
             (range 7))))

(defn- cleanup! [client]
  (doseq [{:keys [resourceType id]} (reverse fixtures)]
    (c/delete! client resourceType id)))

(defn- with-fixtures [f]
  (let [client (http/client {:base-url base-url})]
    (cleanup! client)
    (c/transact! client (bundle/transaction fixtures))
    (try (f)
         (finally (cleanup! client)))))

(use-fixtures :once with-fixtures)

(defn- ids [client params]
  (mapv :id (c/search client "Observation" params)))

(deftest ^:integration http-and-memory-clients-agree
  (let [hapi (http/client {:base-url base-url})
        memory (mem/memory-client fixtures)]
    (doseq [[label params]
            {"by patient" {:patient (str "Patient/" patient-id) :_sort "date"}
             "by bare patient id" {:patient patient-id :_sort "date"}
             "by code" {:patient patient-id :code "http://loinc.org|8867-4" :_sort "date"}
             "date lower bound" {:patient patient-id :date "ge2026-01-04T00:00:00Z" :_sort "date"}
             "date range" {:patient patient-id :date ["ge2026-01-02T00:00:00Z" "lt2026-01-06T00:00:00Z"] :_sort "date"}
             "newest first" {:patient patient-id :_sort "-date"}
             "small pages" {:patient patient-id :_sort "date" :_count 2}}]
      (testing label
        (is (= (ids memory params) (ids hapi params)))
        (is (seq (ids hapi params)))))))

(deftest ^:integration patient-searches-of-the-api-agree-on-the-demo-cohort
  ;; The searches the api makes for a practitioner's panel and for the family-name filter, on
  ;; the demo cohort (needs `bb seed`). The in-memory client is the "lite" profile's server.
  (let [hapi (http/client {:base-url base-url})
        memory (mem/memory-client (synthetic/generate (synthetic/cohort)))
        patient-ids (fn [client params] (set (map :id (c/search client "Patient" params))))]
    (doseq [[label params]
            {"a practitioner's panel" {:general-practitioner "Practitioner/pr-rivera"}
             "the other panel" {:general-practitioner "Practitioner/pr-okafor"}
             "a family name prefix" {:family "Ch"}
             "in any case" {:family "rOSS"}
             "without the accent" {:family "Garcia"}
             "with the accent" {:family "García"}
             "a panel and a family name" {:general-practitioner "Practitioner/pr-okafor" :family "P"}
             "no match" {:family "Zzz"}}]
      (testing label
        (is (= (patient-ids memory params) (patient-ids hapi params)))))
    (testing "the answers are not all empty by accident"
      (is (= #{"pt-ana" "pt-liam" "pt-omar" "pt-sofia"}
             (patient-ids hapi {:general-practitioner "Practitioner/pr-rivera"})))
      (is (= #{"pt-ana"} (patient-ids hapi {:family "Garcia"}))))))

(deftest ^:integration paging-follows-hapi-next-links
  (let [hapi (http/client {:base-url base-url})
        all (ids hapi {:patient patient-id :_sort "date"})]
    (is (= (mapv #(str "it-obs-" %) (range 7)) all))
    (is (= all (ids hapi {:patient patient-id :_sort "date" :_count 2}))
        "four pages of two give the same result as one page")))

(deftest ^:integration read-write-roundtrip
  (let [hapi (http/client {:base-url base-url})
        org (r/organization {:id "it-org-1" :name "Integration clinic"})]
    (try
      (is (= "it-org-1" (:id (c/put! hapi org))))
      (is (= "Integration clinic" (:name (c/read-resource hapi "Organization" "it-org-1"))))
      (c/delete! hapi "Organization" "it-org-1")
      (is (nil? (c/read-resource hapi "Organization" "it-org-1")) "a deleted resource reads as nil")
      (finally (c/delete! hapi "Organization" "it-org-1")))))

(deftest ^:integration server-reports-errors-as-exceptions
  (let [hapi (http/client {:base-url base-url})
        e (try (c/put! hapi {:resourceType "Patient" :id "it-bad" :gender "not-a-gender"})
               (catch clojure.lang.ExceptionInfo e e))]
    (is (= :pulse.fhir.client/fhir-error (:type (ex-data e))))
    (is (<= 400 (:status (ex-data e)) 499))
    (is (= "OperationOutcome" (-> e ex-data :outcome :resourceType)))))
