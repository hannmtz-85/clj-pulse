(ns pulse.scheduler.jobs-test
  "The catalogue of the scheduler's jobs: which jobs there are for a configuration, and that the job
  of the rules engine evaluates the rules at the moment it was started and fails when a patient
  could not be evaluated. The api is a stand-in that answers as a function says."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [pulse.fhir.client :as c]
            [pulse.fhir.client.memory :as mem]
            [pulse.fhir.resource :as r]
            [pulse.platform.http.client :as http]
            [pulse.platform.test.clock :as clock]
            [pulse.platform.test.stub :as stub]
            [pulse.platform.token :as token]
            [pulse.scheduler.job :as job]
            [pulse.scheduler.jobs :as jobs]
            [pulse.scheduler.simulator :as simulator]
            [taoensso.telemere :as t])
  (:import (java.time Instant)))

(def ^:private every-half-minute {:type :interval :every-ms 30000})

(defn- json-text [x] (json/write-value-as-string x))

(defn- with-job
  "Calls `(f job stub fhir)` with the job of the rules engine, built for a stand-in for the api that
  answers as `handler` says and a FHIR server in memory, which holds `resources` (none by default)."
  ([handler f] (with-job handler [] f))
  ([handler resources f]
   (let [service (stub/start! handler)
         c (clock/clock "2026-10-13T20:00:00Z")
         own-token (token/source {:fetch (fn [] {:access-token "the-engines-token" :expires-in 300})
                                  :clock (:clock c)})
         api (http/client {:base-url (:url service) :token own-token})
         fhir (mem/memory-client resources)]
     (try
       (let [[the-job :as catalogue] (jobs/catalogue {:api api :fhir fhir
                                                      :evaluate-rules {:enabled? true :schedule every-half-minute}})]
         (is (= 1 (count catalogue)))
         (f the-job service fhir))
       (finally
         (http/close! api)
         (stub/stop! service))))))

(defn- run [the-job started-at]
  ((:handler the-job) {:job-id :evaluate-rules :trigger :schedule :due started-at :started-at started-at}))

(defn- observations? [request]
  (str/ends-with? (:path request) "/observations"))

(deftest a-switched-off-job-is-not-in-the-catalogue
  (is (= [] (jobs/catalogue {:evaluate-rules {:enabled? false :schedule every-half-minute}})))
  (is (= [] (jobs/catalogue {:evaluate-rules {:schedule every-half-minute}})))
  (is (= [] (jobs/catalogue {}))))

(deftest the-job-of-the-rules-engine-is-a-sound-job-with-the-schedule-it-was-given
  (with-job (fn [_] {:body (json-text {:total 0 :items []})})
    (fn [the-job _ _]
      (is (= :evaluate-rules (:id the-job)))
      (is (= every-half-minute (:schedule the-job)))
      (is (not (str/blank? (:description the-job))))
      (is (= [the-job] (job/check! [the-job]))))))

(deftest a-run-reads-the-patients-through-the-api-and-answers-with-the-summary
  (with-job (fn [_] {:body (json-text {:total 0 :offset 0 :limit 100 :items []})})
    (fn [the-job service _]
      (is (= {:patients 0 :raised 0 :already-raised 0 :still-active 0 :not-applicable 0 :failed 0}
             (run the-job (Instant/parse "2026-10-13T20:00:00Z"))))
      (is (= ["/api/patients"] (map :path @(:requests service)))))))

(deftest a-run-evaluates-the-rules-at-the-moment-the-job-was-started
  (let [patients {:total 1 :offset 0 :limit 100 :items [{:id "pt-ana" :identifier "PULSE-0001"}]}]
    (with-job (fn [request]
                {:body (json-text (if (observations? request) {:items [] :truncated false} patients))})
      (fn [the-job service _]
        (is (= {:patients 1 :raised 0} (select-keys (run the-job (Instant/parse "2026-10-13T20:00:00.600Z"))
                                                    [:patients :raised])))
        (let [from-of (fn [measurement]
                        (some (fn [request]
                                (let [query (:query request)]
                                  (when (and (observations? request) (str/includes? (str query) (str "measurement=" measurement)))
                                    (second (re-find #"from=([^&]+)" query)))))
                              @(:requests service)))]
          (is (= "2026-10-10T20%3A00%3A00Z" (from-of "blood-pressure")) "72 hours before it, in whole seconds")
          (is (= "2026-10-12T20%3A00%3A00Z" (from-of "spo2")) "24 hours before it")
          (is (= "2026-10-09T20%3A00%3A00Z" (from-of "weight")) "96 hours before it"))))))

(deftest a-run-reads-through-the-api-and-raises-in-fhir
  ;; the whole of the job: patients and readings from the api, the condition R4 needs and the alert in FHIR
  (let [patients {:total 1 :offset 0 :limit 100 :items [{:id "pt-maya" :identifier "PULSE-0005"}]}
        weights {:items [{:id "o-1" :measurement "weight" :at "2026-10-11T20:00:00Z" :value 80.0 :unit "kg"}
                         {:id "o-2" :measurement "weight" :at "2026-10-13T18:00:00Z" :value 82.3 :unit "kg"}]
                 :truncated false}]
    (with-job (fn [request]
                {:body (json-text (cond
                                    (not (observations? request)) patients
                                    (str/includes? (str (:query request)) "measurement=weight") weights
                                    :else {:items [] :truncated false}))})
      [(r/condition {:id "c-1" :patient "pt-maya" :concept :heart-failure})]
      (fn [the-job _ fhir]
        (let [{:keys [value signals]} (t/with-signals true (run the-job (Instant/parse "2026-10-13T20:00:00Z")))
              [flag :as flags] (vec (c/search fhir "Flag" {}))]
          (is (= {:patients 1 :raised 1 :already-raised 0 :still-active 0 :not-applicable 0 :failed 0} value))
          (is (= 1 (count flags)))
          (is (= "Patient/pt-maya" (get-in flag [:subject :reference])))
          (is (= "R4/pt-maya/2026-10-13T18:00:00Z" (get-in flag [:identifier 0 :value])))
          (is (= "2026-10-13T20:00:00Z" (get-in flag [:period :start])) "raised at the moment the job was started")
          (testing "audited as a write of the scheduler's client"
            (let [audit (first (filter #(= :pulse.audit/write (:id %)) signals))]
              (is (= jobs/service-actor (get-in audit [:data :actor]))))))))))

(deftest a-run-in-which-a-patient-could-not-be-evaluated-fails
  (let [patients {:total 2 :offset 0 :limit 100 :items [{:id "pt-ana" :identifier "PULSE-0001"}
                                                        {:id "pt-liam" :identifier "PULSE-0002"}]}]
    (with-job (fn [request]
                (cond
                  (not (observations? request)) {:body (json-text patients)}
                  (str/includes? (:path request) "pt-liam") {:status 404 :body (json-text {:title "Not Found"})}
                  :else {:body (json-text {:items [] :truncated false})}))
      (fn [the-job _ _]
        (let [e (try (run the-job (Instant/parse "2026-10-13T20:00:00Z"))
                     (catch clojure.lang.ExceptionInfo e e))]
          (is (= "Some patients could not be evaluated" (ex-message e)))
          (is (= {:patients 2 :raised 0 :already-raised 0 :still-active 0 :not-applicable 0 :failed 1}
                 (:summary (ex-data e)))
              "and the summary is in the failure, so that it can be seen"))))))

(deftest a-run-whose-api-refuses-the-token-fails-as-a-whole
  (with-job (fn [_] {:status 403 :body (json-text {:title "Forbidden"})})
    (fn [the-job _ fhir]
      (let [e (try (run the-job (Instant/parse "2026-10-13T20:00:00Z"))
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (= ::http/failed (:type (ex-data e))))
        (is (= 403 (:status (ex-data e))))
        (is (empty? (c/search fhir "Flag" {})))))))

;; ---------------------------------------------------------------------------
;; The simulator

(def ^:private every-quarter-minute {:type :interval :every-ms 15000})

(defn- simulator-options [& {:as changes}]
  (merge {:enabled? true :schedule every-quarter-minute :batch-size 20} changes))

(defn- with-simulator
  "Calls `(f job fhir)` with the job that simulates devices, built for `fhir` (an empty server in memory
  by default)."
  ([f] (with-simulator (mem/memory-client) f))
  ([fhir f]
   (let [[the-job :as catalogue] (jobs/catalogue {:fhir fhir :simulate-devices (simulator-options)})]
     (is (= 1 (count catalogue)))
     (f the-job fhir))))

(defn- run-at [the-job instant]
  (let [at (Instant/parse instant)]
    ((:handler the-job) {:job-id :simulate-devices :trigger :schedule :due at :started-at at})))

(deftest the-simulator-is-in-the-catalogue-only-when-it-is-switched-on
  (is (= [] (jobs/catalogue {:simulate-devices (assoc (simulator-options) :enabled? false)})))
  (is (= [:simulate-devices]
         (map :id (jobs/catalogue {:fhir (mem/memory-client) :simulate-devices (simulator-options)}))))
  (testing "after the rules, and both are sound jobs"
    (let [catalogue (jobs/catalogue {:fhir (mem/memory-client)
                                     :evaluate-rules {:enabled? true :schedule every-half-minute}
                                     :simulate-devices (simulator-options)})]
      (is (= [:evaluate-rules :simulate-devices] (map :id catalogue)))
      (is (= catalogue (job/check! catalogue))))))

(deftest the-job-of-the-simulator-is-a-sound-job-with-the-schedule-it-was-given
  (with-simulator
    (fn [the-job _]
      (is (= :simulate-devices (:id the-job)))
      (is (= every-quarter-minute (:schedule the-job)))
      (is (not (str/blank? (:description the-job)))))))

(defn- saturations-of-sofia
  "The values of her oxygen saturation in `fhir`, oldest first."
  [fhir]
  (->> (c/search fhir "Observation" {:patient "Patient/pt-sofia"})
       (filter #(str/includes? (:id %) "-spo2-"))
       (sort-by :effectiveDateTime)
       (map #(get-in % [:valueQuantity :value]))))

(deftest a-run-is-a-tick-of-the-simulated-world
  (with-simulator
    (fn [the-job fhir]
      (testing "the first tick: seven readings, one of them the first of the episode of Sofía"
        (is (= {:planned 7 :abnormal 1 :read 7 :written 7 :batches 1 :failed 0 :failed-batches 0 :errors 0}
               (run-at the-job "2026-10-15T10:00:00.700Z")))
        (is (= [88] (saturations-of-sofia fhir)))
        (is (= #{"2026-10-15T10:00:00Z"} (set (map :effectiveDateTime (c/search fhir "Observation" {}))))
            "taken in whole seconds"))
      (testing "the next ticks go on with the episode, which is due on every tick"
        (is (= 8 (:planned (run-at the-job "2026-10-15T10:00:15.100Z"))))
        (is (= 7 (:planned (run-at the-job "2026-10-15T10:00:30.200Z"))))
        (is (= [88 89 88] (saturations-of-sofia fhir))))
      (testing "and nothing is written twice"
        (is (= 22 (count (c/search fhir "Observation" {}))))
        (is (= 22 (count (distinct (map :id (c/search fhir "Observation" {}))))))))))

(deftest a-new-simulator-begins-its-cycle-again
  (let [fhir (mem/memory-client)]
    (doseq [round [0 1]]
      (with-simulator fhir
        (fn [the-job _] (run-at the-job (str "2026-10-15T1" round ":00:00Z")))))
    (is (= [88 88] (saturations-of-sofia fhir))
        "the first tick of the cycle, twice: Sofía's episode begins when the scheduler does")))

(deftest a-run-sends-its-readings-in-batches-of-the-size-it-was-given
  (let [[the-job] (jobs/catalogue {:fhir (mem/memory-client) :simulate-devices (simulator-options :batch-size 3)})]
    (is (= {:planned 7 :written 7 :batches 3} (select-keys (run-at the-job "2026-10-15T10:00:00Z") [:planned :written :batches]))
        "three, three and one")))

(deftest a-run-with-readings-that-could-not-be-made-fails
  (with-simulator
    (fn [the-job fhir]
      (let [{:keys [value]} (t/with-signals true
                              (with-redefs [simulator/observation (fn [_] (throw (ex-info "not a reading" {})))]
                                (try (run-at the-job "2026-10-15T10:00:00Z")
                                     (catch clojure.lang.ExceptionInfo e e))))]
        (is (= "Some readings could not be written" (ex-message value)))
        (is (= {:planned 7 :written 0 :failed 0 :errors 7}
               (select-keys (:summary (ex-data value)) [:planned :written :failed :errors])))
        (is (empty? (c/search fhir "Observation" {})))))))

(deftest a-run-whose-readings-could-not-be-written-fails
  (let [refusing (reify c/FhirClient
                   (read-resource [_ _ _] nil)
                   (search [_ _ _] [])
                   (put! [_ r] r)
                   (delete! [_ _ _] nil)
                   (transact! [_ _] (throw (ex-info "connection refused" {})))
                   (validate [_ _] nil))]
    (with-simulator refusing
      (fn [the-job _]
        (let [{:keys [value]} (t/with-signals true
                                (try (run-at the-job "2026-10-15T10:00:00Z")
                                     (catch clojure.lang.ExceptionInfo e e)))]
          (is (= "Some readings could not be written" (ex-message value)))
          (is (= {:planned 7 :read 7 :written 0 :failed 7 :failed-batches 1}
                 (select-keys (:summary (ex-data value)) [:planned :read :written :failed :failed-batches]))
              "and the summary is in the failure, so that it can be seen"))))))

(deftest the-actor-the-audit-names-is-the-schedulers-client-and-its-role
  (is (= {:client "pulse-scheduler" :roles ["rules-engine"]} jobs/service-actor)))
