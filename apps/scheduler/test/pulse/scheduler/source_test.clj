(ns pulse.scheduler.source-test
  "Where the rules get their data: how an item of the api's series becomes a reading, and the
  source that asks the api for patients and readings and FHIR for conditions, against a stand-in
  for the api."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [pulse.fhir.client.memory :as mem]
            [pulse.fhir.resource :as r]
            [pulse.fhir.terminology :as terminology]
            [pulse.fhir.units :as units]
            [pulse.platform.http.client :as http]
            [pulse.platform.test.clock :as clock]
            [pulse.platform.test.stub :as stub]
            [pulse.platform.token :as token]
            [pulse.scheduler.rules :as rules]
            [pulse.scheduler.source :as source])
  (:import (java.time Instant)))

(defn- item [& {:as fields}]
  (merge {:id "obs-1" :measurement "spo2" :at "2026-10-13T08:00:00Z" :value 95 :unit "%" :band "normal"
          :by "Patient/pt-sofia"}
         fields))

(def ^:private at (Instant/parse "2026-10-13T08:00:00Z"))

;; ---------------------------------------------------------------------------
;; An item of the api becomes a reading

(deftest an-item-becomes-a-reading-with-an-instant
  (is (= {:at at :value 95} (source/reading-from-item :spo2 (item))))
  (is (= {:at at :systolic 150 :diastolic 95}
         (source/reading-from-item :blood-pressure (item :measurement "blood-pressure" :unit "mm[Hg]"
                                                         :value nil :systolic 150 :diastolic 95)))))

(deftest a-reading-is-put-in-the-unit-the-measurement-is-stored-in
  (testing "glucose in mmol/L"
    (is (= {:at at :value (units/convert 10.0 "mmol/L" "mg/dL")}
           (source/reading-from-item :glucose (item :unit "mmol/L" :value 10.0))))
    (is (< 180 (:value (source/reading-from-item :glucose (item :unit "mmol/L" :value 10.0))) 181)))
  (testing "weight in pounds"
    (is (= {:at at :value (units/convert 176.4 "[lb_av]" "kg")}
           (source/reading-from-item :weight (item :unit "[lb_av]" :value 176.4))))
    (is (< 79.9 (:value (source/reading-from-item :weight (item :unit "[lb_av]" :value 176.4))) 80.1)))
  (testing "no unit means the stored one"
    (is (= {:at at :value 95} (source/reading-from-item :spo2 (item :unit nil))))))

(deftest an-item-that-cannot-be-used-is-no-reading
  (testing "no number, or not a number"
    (is (nil? (source/reading-from-item :spo2 (item :value nil))))
    (is (nil? (source/reading-from-item :spo2 (item :value "95"))))
    (is (nil? (source/reading-from-item :blood-pressure (item :value nil :systolic 150 :diastolic nil :unit "mm[Hg]"))))
    (is (nil? (source/reading-from-item :blood-pressure (item :value nil :systolic nil :diastolic nil :unit "mm[Hg]")))))
  (testing "a unit that does not fit, or that nobody knows"
    (is (nil? (source/reading-from-item :weight (item :unit "mg/dL" :value 80))))
    (is (nil? (source/reading-from-item :spo2 (item :unit "furlong")))))
  (testing "no time, or a time that is not one"
    (is (nil? (source/reading-from-item :spo2 (item :at nil))))
    (is (nil? (source/reading-from-item :spo2 (item :at "yesterday"))))
    (is (nil? (source/reading-from-item :spo2 (item :at "2026-10-13")))))
  (testing "a measurement that does not exist, with a unit or without one"
    (is (nil? (source/reading-from-item :lung-capacity (item))))
    (is (nil? (source/reading-from-item :lung-capacity (item :unit nil))))))

;; ---------------------------------------------------------------------------
;; The api

(defn- json-text [x] (json/write-value-as-string x))

(defn- with-api
  "Calls `(f source stub)` with an `ApiSource` of a stand-in for the api that answers as `handler`
  says, and of a FHIR server in memory holding `resources`."
  [handler resources f]
  (let [service (stub/start! handler)
        c (clock/clock "2026-10-13T08:00:00Z")
        own-token (token/source {:fetch (fn [] {:access-token "the-engines-token" :expires-in 300})
                                 :clock (:clock c)})]
    (try
      (f (source/api-source (http/client {:base-url (:url service) :token own-token})
                            (mem/memory-client resources))
         service)
      (finally (stub/stop! service)))))

(defn- query-of [request]
  (into {} (map (fn [pair] (let [[k v] (str/split pair #"=" 2)] [k (java.net.URLDecoder/decode ^String v "UTF-8")])))
        (str/split (str (:query request)) #"&")))

(deftest the-patients-are-every-page-of-the-list
  ;; the api pages by what it returns, whatever the limit asked for
  (let [pages {"0" {:total 5 :offset 0 :limit 2 :items [{:id "pt-a" :identifier "1"} {:id "pt-b" :identifier "2"}]}
               "2" {:total 5 :offset 2 :limit 2 :items [{:id "pt-c" :identifier "3"} {:id "pt-d" :identifier "4"}]}
               "4" {:total 5 :offset 4 :limit 2 :items [{:id "pt-e" :identifier "5"}]}}]
    (with-api (fn [request] {:body (json-text (get pages (get (query-of request) "offset")))}) []
      (fn [data service]
        (is (= ["pt-a" "pt-b" "pt-c" "pt-d" "pt-e"] (source/patient-ids data)))
        (let [requests @(:requests service)]
          (is (= ["0" "2" "4"] (map #(get (query-of %) "offset") requests)))
          (is (every? #(= "/api/patients" (:path %)) requests))
          (is (= {"limit" "100" "offset" "0" "sort" "family"} (query-of (first requests))))
          (is (= ["Bearer the-engines-token"] (distinct (map #(get-in % [:headers "authorization"]) requests)))
              "with the engine's own token"))))))

(deftest no-patients-is-an-empty-list-and-one-request
  (with-api (fn [_] {:body (json-text {:total 0 :offset 0 :limit 100 :items []})}) []
    (fn [data service]
      (is (= [] (source/patient-ids data)))
      (is (= 1 (count @(:requests service)))))))

(deftest the-readings-are-the-series-of-the-api-newest-first-and-usable
  (with-api (fn [_] {:body (json-text {:items [(item :id "o-3" :at "2026-10-13T08:00:00Z" :value 90)
                                               (item :id "o-2" :at "2026-10-12T20:00:00Z" :value nil)
                                               (item :id "o-1" :at "2026-10-12T08:00:00Z" :value 94)]
                                       :truncated false})})
    []
    (fn [data service]
      (is (= [{:at at :value 90} {:at (Instant/parse "2026-10-12T08:00:00Z") :value 94}]
             (source/readings data "pt-sofia" (rules/query rules/low-spo2 (Instant/parse "2026-10-13T08:00:00Z"))))
          "the one with no number is left out")
      (let [request (first @(:requests service))]
        (is (= "GET" (:method request)))
        (is (= "/api/patients/pt-sofia/observations" (:path request)))
        (is (= {"measurement" "spo2" "from" "2026-10-12T08:00:00Z" "sort" "-date" "limit" "200"}
               (query-of request))
            "from the start of the look-back, newest first: if there are too many it is the old ones that go")))))

(deftest a-patient-id-is-one-segment-of-the-path
  (with-api (fn [_] {:body (json-text {:items [] :truncated false})}) []
    (fn [data service]
      (source/readings data "a/b c" {:measurement :heart-rate :from at})
      (is (= "/api/patients/a%2Fb%20c/observations" (:path (first @(:requests service))))))))

(deftest what-the-api-refuses-or-lacks-is-an-error-of-the-client
  (doseq [status [403 404 500]]
    (with-api (fn [_] {:status status :body (json-text {:title "Nope" :detail "Not so."})}) []
      (fn [data _]
        (let [e (try (source/readings data "pt-sofia" {:measurement :spo2 :from at})
                     (catch clojure.lang.ExceptionInfo e e))]
          (is (= ::http/failed (:type (ex-data e))))
          (is (= status (:status (ex-data e)))))))))

;; ---------------------------------------------------------------------------
;; The conditions, from FHIR

(def ^:private conditions
  [(r/condition {:id "c-1" :patient "pt-maya" :concept :heart-failure})
   (r/condition {:id "c-2" :patient "pt-maya" :concept :hypertension})
   (assoc (r/condition {:id "c-3" :patient "pt-maya" :concept :copd})
          :clinicalStatus (terminology/status-concept :condition-clinical "resolved"))
   (r/condition {:id "c-4" :patient "pt-ana" :concept :hypertension})])

(deftest the-conditions-are-the-active-ones-of-the-patient-from-fhir
  (with-api (fn [_] {}) conditions
    (fn [data service]
      (is (= #{{:system "http://hl7.org/fhir/sid/icd-10-cm" :code "I50.9"}
               {:system "http://hl7.org/fhir/sid/icd-10-cm" :code "I10"}}
             (source/conditions data "pt-maya")) "not the resolved one, nor another patient's")
      (is (= #{{:system "http://hl7.org/fhir/sid/icd-10-cm" :code "I10"}} (source/conditions data "pt-ana")))
      (is (= #{} (source/conditions data "pt-noah")))
      (is (empty? @(:requests service)) "the api is not asked about conditions"))))
