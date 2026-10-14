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
            [pulse.fhir.measurement :as measurement]
            [pulse.fhir.observation :as obs]
            [pulse.fhir.outcome :as outcome]
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
             "small pages" {:patient patient-id :_sort "date" :_count 2}
             "a series: one code, a period, newest first, in small pages"
             {:patient patient-id :code "http://loinc.org|8867-4"
              :date ["ge2026-01-02T00:00:00Z" "lt2026-01-06T00:00:00Z"] :_sort "-date" :_count 2}}]
      (testing label
        (is (= (ids memory params) (ids hapi params)))
        (is (seq (ids hapi params)))))))

(deftest ^:integration the-edges-of-a-period-are-where-the-api-says-they-are
  ;; The api asks for `from` as `ge` and for `to` as `lt`, both as UTC instants (ADR-0014): a
  ;; reading exactly at `from` is in and one exactly at `to` is out. HAPI and the fake must agree
  ;; on that to the second. The fixtures are at 08:00 UTC on 1-7 January 2026, 60 + day of beats.
  (let [hapi (http/client {:base-url base-url})
        memory (mem/memory-client fixtures)
        series {:patient patient-id :code "http://loinc.org|8867-4" :_sort "-date"}]
    (doseq [[label date expected]
            [["from and to both on a reading" ["ge2026-01-02T08:00:00Z" "lt2026-01-05T08:00:00Z"]
              ["it-obs-3" "it-obs-2" "it-obs-1"]]
             ["a second after those readings" ["ge2026-01-02T08:00:01Z" "lt2026-01-05T08:00:01Z"]
              ["it-obs-4" "it-obs-3" "it-obs-2"]]
             ["only from" ["ge2026-01-06T08:00:00Z"] ["it-obs-6" "it-obs-5"]]
             ["only to" ["lt2026-01-02T08:00:00Z"] ["it-obs-0"]]
             ["nothing in the period" ["ge2026-01-04T00:00:00Z" "lt2026-01-04T08:00:00Z"] []]]]
      (testing label
        (is (= expected (ids hapi (assoc series :date date))))
        (is (= expected (ids memory (assoc series :date date))))))))

(deftest ^:integration every-reading-the-api-can-record-is-a-valid-observation-on-hapi
  ;; What `pulse.fhir.measurement/observation` builds for each measurement, as it is recorded by
  ;; the patient, by a clinician and by nobody. One at a time: HAPI's validator runs out of
  ;; memory when it is called concurrently.
  (let [hapi (http/client {:base-url base-url})
        submissions [{:measurement :blood-pressure :systolic 150 :diastolic 95}
                     {:measurement :heart-rate :value 72}
                     {:measurement :spo2 :value 97}
                     {:measurement :glucose :value 6.1 :unit "mmol/L"}
                     {:measurement :weight :value 160 :unit "[lb_av]"}
                     {:measurement :temperature :value 98.6 :unit "[degF]"}]]
    (doseq [submission submissions
            [label options] {"by the patient" {} "by a clinician" {:performer "Practitioner/pr-rivera"} "by nobody" {:performer nil}}
            :let [observation (measurement/observation (:ok (measurement/normalize submission))
                                                       (merge {:id "it-obs-valid" :patient patient-id
                                                               :at "2026-10-14T08:00:00Z"}
                                                              options))
                  result (c/validate hapi observation)]]
      (testing (str (name (:measurement submission)) " " label)
        (is (not (outcome/error? result)) (outcome/summary result))))))

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

(deftest ^:integration the-queries-of-the-patient-summary-agree-on-the-demo-cohort
  ;; What the api's summary asks for: what is current of one patient, and the latest reading of
  ;; each vital sign (needs `bb seed`). The readings themselves differ in time between the two
  ;; servers, so for those only whether there is one, and of what, is compared.
  (let [hapi (http/client {:base-url base-url})
        memory (mem/memory-client (synthetic/generate (synthetic/cohort)))
        ids (fn [client resource-type params] (set (map :id (c/search client resource-type params))))
        patients ["Patient/pt-ana" "Patient/pt-sofia" "Patient/pt-liam" "Patient/pt-maya" "Patient/pt-noah"]]
    (doseq [patient patients
            [resource-type extra] [["Condition" {:clinical-status "active"}]
                                   ["MedicationRequest" {:status "active"}]
                                   ["AllergyIntolerance" {}]
                                   ["Appointment" {}]
                                   ["Flag" {:_sort "-date"}]]]
      (testing (str resource-type " of " patient)
        (let [params (assoc extra :patient patient)]
          (is (= (ids memory resource-type params) (ids hapi resource-type params))))))
    (testing "the latest reading of each vital sign"
      (doseq [patient patients
              [concept code] {:bp-panel "http://loinc.org|85354-9"
                              :heart-rate "http://loinc.org|8867-4"
                              :glucose "http://loinc.org|2339-0"
                              :spo2 "http://loinc.org|59408-5"
                              :body-weight "http://loinc.org|29463-7"}]
        (let [params {:patient patient :code code :_sort "-date"}
              from-hapi (c/find-one hapi "Observation" params)
              from-memory (c/find-one memory "Observation" params)]
          (is (= (some? from-memory) (some? from-hapi)) (str concept " of " patient))
          (when from-hapi
            (is (obs/concept? from-hapi concept))))))
    (testing "the answers are not all empty by accident"
      (is (= #{"cond-ana-1"} (ids hapi "Condition" {:patient "Patient/pt-ana" :clinical-status "active"})))
      (is (= #{"alg-sofia-1"} (ids hapi "AllergyIntolerance" {:patient "Patient/pt-sofia"}))))))

(deftest ^:integration every-search-parameter-of-the-fake-exists-on-hapi
  ;; The fake must not accept what the real server rejects: R4 gave Flag no `status` parameter,
  ;; the fake had one, and the api asked for it. Each parameter is tried with a value of its kind.
  (let [hapi (http/client {:base-url base-url})
        dummy {:id "x" :string "x" :reference "Patient/x" :token "x" :code "x" :date "ge2026-01-01" :identifier "x"}]
    (doseq [[resource-type params] mem/search-params
            [param [kind _]] params]
      (testing (str resource-type "?" param)
        (is (some? (doall (take 1 (c/search hapi resource-type {(keyword param) (dummy kind)})))))))))

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
