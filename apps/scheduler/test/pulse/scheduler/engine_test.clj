(ns pulse.scheduler.engine-test
  "The rules engine over the demo cohort, with the data read straight from the cohort and the
  alerts written to an in-memory FHIR server: what Annex C expects of each patient, that an alert is
  raised once, that an acknowledged one is not raised again for the same readings, and that a
  failure of one patient does not stop the others. The same, through the real api, is in
  `end-to-end-test`."
  (:require [clojure.test :refer [deftest is testing]]
            [pulse.fhir.alert :as alert]
            [pulse.fhir.client :as c]
            [pulse.fhir.client.memory :as mem]
            [pulse.fhir.measurement :as measurement]
            [pulse.fhir.observation :as obs]
            [pulse.fhir.reference :as ref]
            [pulse.fhir.schema :as schema]
            [pulse.fhir.synthetic :as synthetic]
            [pulse.platform.http.client :as http]
            [pulse.platform.token :as token]
            [pulse.scheduler.alerts :as alerts]
            [pulse.scheduler.engine :as engine]
            [pulse.scheduler.rules :as rules]
            [pulse.scheduler.source :as source]
            [taoensso.telemere :as t])
  (:import (java.time Instant)))

(def ^:private cohort (synthetic/cohort))

(defn- generate [end] (synthetic/generate cohort {:end-instant (Instant/parse end)}))

(def ^:private end "2026-10-13T20:00:00Z")

(def ^:private actor {:client "pulse-scheduler" :roles ["rules-engine"]})

;; ---------------------------------------------------------------------------
;; A source that reads the data of the cohort, as the api would give it

(defn- reading-of [kind observation]
  (let [{:keys [channels]} (get measurement/measurements kind)]
    (into {:at (Instant/parse (:effectiveDateTime observation))}
          (map (fn [channel]
                 [channel (:value (if (= :value channel)
                                    (obs/value observation)
                                    (obs/component-value observation channel)))]))
          (keys channels))))

(defn- source-of
  "A `DataSource` over `resources`, which `extra` readings may add to: a map of patient id to
  measurement to readings. `failing` is a set of patient ids whose readings cannot be got,
  `unlisted` a set of condition codes the patients are taken not to have, and `having` a map of
  patient id to the set of conditions that patient has, instead of the cohort's."
  [resources & {:keys [extra failing unlisted having] :or {extra {} failing #{} unlisted #{} having {}}}]
  (let [observations (filter #(= "Observation" (:resourceType %)) resources)]
    (reify source/DataSource
      (patient-ids [_]
        (vec (sort (map :id (filter #(= "Patient" (:resourceType %)) resources)))))
      (readings [_ patient-id {:keys [measurement from]}]
        (when (contains? failing patient-id)
          (throw (ex-info "a reading that cannot be had" {:patient patient-id})))
        (let [concept (get-in measurement/measurements [measurement :concept])]
          (->> (concat (->> observations
                            (filter #(and (= patient-id (obs/patient-id %)) (obs/concept? % concept)))
                            (map #(reading-of measurement %)))
                       (get-in extra [patient-id measurement]))
               (remove (fn [{:keys [at]}] (.isBefore ^Instant at ^Instant from)))
               vec)))
      (conditions [_ patient-id]
        (if (contains? having patient-id)
          (get having patient-id)
          (into #{}
                (comp (filter #(and (= "Condition" (:resourceType %))
                                    (= patient-id (:id (ref/target (:subject %))))))
                      (mapcat (comp :coding :code))
                      (map (fn [{:keys [system code]}] {:system system :code code}))
                      (remove (comp unlisted :code)))
                resources))))))

(defn- run!*
  "Runs the engine at `now` over `resources` into `client`: the summary."
  [resources client now & {:as options}]
  (engine/run! {:data (apply source-of resources (mapcat identity options))
                :fhir client
                :actor actor}
               (Instant/parse now)))

(defn- raised
  "The alerts in `client` as `[rule patient]` pairs, from their identifiers."
  [client]
  (set (for [f (c/search client "Flag" {})
             :let [[_ rule patient] (re-find #"^(R\d)/([^/]+)/" (:value (first (:identifier f))))]
             :when rule]
         [rule patient])))

;; ---------------------------------------------------------------------------
;; Annex C: what each patient of the cohort is expected to get

(def ^:private expected
  #{["R1" "pt-ana"] ["R3" "pt-liam"] ["R2" "pt-sofia"] ["R4" "pt-maya"]})

(deftest each-patient-of-the-cohort-gets-the-alert-annex-c-expects
  (let [resources (generate end)
        client (mem/memory-client resources)
        summary (run!* resources client end)]
    (is (= {:patients 6 :raised 4 :already-raised 0 :still-active 0 :not-applicable 0 :failed 0} summary))
    (is (= expected (raised client)) "Ana R1, Liam R3, Sofía R2 and Maya R4; Omar and Noah, none")
    (testing "the alerts are valid active alerts with the title of their rule"
      (let [flags (vec (c/search client "Flag" {}))]
        (is (= 4 (count flags)))
        (is (every? schema/valid? flags))
        (is (every? alert/active? flags))
        (is (= #{"BP stage 2" "Low SpO₂" "Glucose out of range" "Rapid weight gain"}
               (set (map #(get-in % [:code :text]) flags))))))))

(deftest the-alerts-are-raised-whatever-hour-of-the-day-the-cohort-was-seeded
  ;; the same as the scenario tests of the cohort, with the rules and their windows and not just the
  ;; thresholds: a cohort seeded at any hour must have its alerts when the engine first runs
  (doseq [hour (range 24)
          :let [at (format "2026-10-13T%02d:00:00Z" hour)
                resources (generate at)
                client (mem/memory-client resources)
                summary (run!* resources client at)]]
    (is (= expected (raised client)) (str "seeded at " hour ":00"))
    (is (= {:raised 4 :failed 0} (select-keys summary [:raised :failed])) (str "seeded at " hour ":00"))))

(deftest nobody-is-alerted-before-the-data-is-in-the-window
  (let [resources (generate end)
        client (mem/memory-client resources)]
    (is (= 0 (:raised (run!* resources client "2026-11-20T20:00:00Z"))) "five weeks later, all readings are old")
    (is (empty? (raised client)))))

;; ---------------------------------------------------------------------------
;; An alert is raised once

(deftest an-alert-is-raised-once-however-often-the-engine-runs
  (let [resources (generate end)
        client (mem/memory-client resources)]
    (run!* resources client end)
    (let [again (run!* resources client end)]
      (is (= {:raised 0 :already-raised 0 :still-active 4} (select-keys again [:raised :already-raised :still-active])))
      (is (= 4 (count (c/search client "Flag" {}))) "no copies"))
    (testing "a minute later, the same"
      (run!* resources client "2026-10-13T20:01:00Z")
      (is (= 4 (count (c/search client "Flag" {})))))))

(deftest an-alert-that-was-acknowledged-is-not-raised-again-for-the-same-readings
  (let [resources (generate end)
        client (mem/memory-client resources)]
    (run!* resources client end)
    (doseq [f (c/search client "Flag" {})]
      (c/put! client (:ok (alert/acknowledge f "2026-10-13T20:30:00Z"))))
    (let [{again :value :keys [signals]} (t/with-signals true (run!* resources client "2026-10-13T21:00:00Z"))]
      (is (= {:raised 0 :already-raised 4 :still-active 0} (select-keys again [:raised :already-raised :still-active]))
          "the server found each one and left it as it was")
      (is (= ["inactive"] (distinct (map :status (c/search client "Flag" {})))) "still acknowledged")
      (is (= 4 (count (c/search client "Flag" {}))))
      (is (empty? (filter #(#{:pulse.audit/write :pulse.rules/alert-raised} (:id %)) signals))
          "and nothing was written, so nothing is audited as written"))))

(def ^:private high-bp-at-21
  "A new high reading of Ana's, an hour after the cohort's last."
  {"pt-ana" {:blood-pressure [{:at (Instant/parse "2026-10-13T21:00:00Z") :systolic 175 :diastolic 108}]}})

(deftest newer-readings-after-an-acknowledgement-raise-a-new-alert
  (let [resources (generate end)
        client (mem/memory-client resources)]
    (run!* resources client end)
    (doseq [f (c/search client "Flag" {})]
      (c/put! client (:ok (alert/acknowledge f "2026-10-13T20:30:00Z"))))
    (let [again (run!* resources client "2026-10-13T21:05:00Z" :extra high-bp-at-21)
          ana (filter #(= "Patient/pt-ana" (get-in % [:subject :reference])) (c/search client "Flag" {}))]
      (is (= 1 (:raised again)) "Ana's pressure was high again, and that is new")
      (is (= #{"active" "inactive"} (set (map :status ana))))
      (is (= 2 (count ana))))))

(deftest while-an-alert-is-active-newer-readings-do-not-raise-another
  (let [resources (generate end)
        client (mem/memory-client resources)]
    (run!* resources client end)
    (let [again (run!* resources client "2026-10-13T21:05:00Z" :extra high-bp-at-21)]
      (is (= {:raised 0 :already-raised 0 :still-active 4} (select-keys again [:raised :already-raised :still-active]))
          "all four rules still fire, and each patient already has an active alert for it")
      (is (= 4 (count (c/search client "Flag" {}))) "one at a time for each rule and patient"))))

;; ---------------------------------------------------------------------------
;; What an alert is

(deftest an-alert-has-its-rule-its-patient-and-the-time-of-the-readings
  (let [resources (generate end)
        client (mem/memory-client resources)]
    (run!* resources client end)
    (let [ana (first (filter #(= "Patient/pt-ana" (get-in % [:subject :reference])) (c/search client "Flag" {})))
          [identifier] (:identifier ana)]
      (is (= "active" (:status ana)))
      (is (= {:text "BP stage 2"
              :coding [{:system alerts/rule-system :code "R1" :display "BP stage 2"}]}
             (:code ana)))
      (is (= {:start end} (:period ana)) "raised when the engine ran")
      (is (= alerts/identifier-system (:system identifier)))
      (is (re-matches #"R1/pt-ana/2026-10-13T\d\d:00:00Z" (:value identifier))
          "the time of the newest reading that supports it"))))

(deftest the-rule-that-needs-a-condition-wants-it
  (let [resources (generate end)
        client (mem/memory-client resources)
        summary (run!* resources client end :unlisted #{"I50.9"})]
    (is (= #{["R1" "pt-ana"] ["R3" "pt-liam"] ["R2" "pt-sofia"]} (raised client)) "Maya has no heart failure")
    (is (= {:raised 3 :not-applicable 1} (select-keys summary [:raised :not-applicable])))))

(def ^:private icd-10-cm "http://hl7.org/fhir/sid/icd-10-cm")

(deftest only-a-heart-failure-of-icd-10-cm-makes-the-rise-apply
  (let [resources (generate end)]
    (doseq [[label conditions applies?]
            [["heart failure" #{{:system icd-10-cm :code "I50.9"}} true]
             ["another code of heart failure" #{{:system icd-10-cm :code "I50.22"}} true]
             ["and others besides" #{{:system icd-10-cm :code "I10"} {:system icd-10-cm :code "I50.9"}} true]
             ["hypertension only" #{{:system icd-10-cm :code "I10"}} false]
             ["a nearby code that is not heart failure" #{{:system icd-10-cm :code "I51.9"}} false]
             ["a code that only holds the prefix" #{{:system icd-10-cm :code "X-I50.9"}} false]
             ["the code in a system that is not ICD-10-CM" #{{:system "http://loinc.org" :code "I50.9"}} false]
             ["no conditions" #{} false]]
            :let [client (mem/memory-client resources)
                  summary (run!* resources client end :having {"pt-maya" conditions})]]
      (testing label
        (is (= applies? (contains? (raised client) ["R4" "pt-maya"])))
        (is (= (if applies? 0 1) (:not-applicable summary)))))))

(deftest a-rule-can-be-left-out
  (let [resources (generate end)
        client (mem/memory-client resources)
        summary (engine/run! {:data (source-of resources) :fhir client :actor actor
                              :rules [rules/bp-stage-2]}
                             (Instant/parse end))]
    (is (= #{["R1" "pt-ana"]} (raised client)))
    (is (= 1 (:raised summary)))))

;; ---------------------------------------------------------------------------
;; Failures

(deftest a-patient-that-fails-is-counted-and-the-others-are-evaluated
  (let [resources (generate end)
        client (mem/memory-client resources)
        {:keys [value signals]} (t/with-signals true (run!* resources client end :failing #{"pt-liam"}))
        failed (filter #(= :pulse.rules/patient-failed (:id %)) signals)]
    (is (= {:patients 6 :raised 3 :failed 1} (select-keys value [:patients :raised :failed])))
    (is (= #{["R1" "pt-ana"] ["R2" "pt-sofia"] ["R4" "pt-maya"]} (raised client)))
    (testing "logged as a warning, with the patient and the exception"
      (is (= 1 (count failed)))
      (is (= :warn (:level (first failed))))
      (is (= "pt-liam" (get-in (first failed) [:data :patient])))
      (is (= "a reading that cannot be had" (ex-message (:error (first failed))))))))

(deftest a-failure-of-the-infrastructure-ends-the-run
  (doseq [[label e] {"no token" (ex-info "no token" {:type ::token/unavailable :reason :rejected})
                     "the api unreachable" (ex-info "down" {:type ::http/unreachable :reason :connect})
                     "the api refusing the token" (ex-info "refused" {:type ::http/failed :status 401})
                     "the api forbidding the engine" (ex-info "forbidden" {:type ::http/failed :status 403})
                     "the api failing" (ex-info "broken" {:type ::http/failed :status 502})
                     "the api failing, at the first status of a failure" (ex-info "broken" {:type ::http/failed :status 500})
                     "FHIR failing" (ex-info "broken" {:type ::c/fhir-error :status 503})
                     "FHIR failing, at the first status of a failure" (ex-info "broken" {:type ::c/fhir-error :status 500})
                     "FHIR with no answer at all" (ex-info "no answer" {:type ::c/fhir-error})
                     "FHIR unreachable" (java.net.ConnectException. "refused")
                     "an interruption, which a job must not swallow" (InterruptedException. "stop")
                     "an interruption that a client wrapped" (ex-info "wrapped" {} (InterruptedException. "stop"))}
          :let [resources (generate end)
                client (mem/memory-client resources)
                calls (atom 0)
                data (reify source/DataSource
                       (patient-ids [_] ["pt-ana" "pt-liam"])
                       (readings [_ _ _] (swap! calls inc) (throw e))
                       (conditions [_ _] #{}))]]
    (testing label
      (is (identical? e (try (engine/run! {:data data :fhir client :actor actor} (Instant/parse end))
                             (catch Exception thrown thrown))))
      (is (= 1 @calls) "and the second patient was not tried"))))

(deftest a-failure-about-one-patient-is-not-the-infrastructure
  (doseq [e [(ex-info "no such patient" {:type ::http/failed :status 404})
             (ex-info "conflict" {:type ::http/failed :status 409})
             (ex-info "unprocessable" {:type ::http/failed :status 422})
             (ex-info "the last status that is not a failure of the server" {:type ::http/failed :status 499})
             (ex-info "an answer of the api that says no more" {:type ::http/failed})
             (ex-info "bad request" {:type ::c/fhir-error :status 422})
             (ex-info "the last status that is not a failure of the server" {:type ::c/fhir-error :status 499})
             (IllegalStateException. "a bug")]
          :let [resources (generate end)
                client (mem/memory-client resources)
                data (reify source/DataSource
                       (patient-ids [_] ["pt-ana" "pt-liam"])
                       (readings [_ _ _] (throw e))
                       (conditions [_ _] #{}))
                summary (:value (t/with-signals true (engine/run! {:data data :fhir client :actor actor} (Instant/parse end))))]]
    (is (= 2 (:failed summary)) (str e))))

;; ---------------------------------------------------------------------------
;; What the run says

(deftest an-alert-raised-is-in-the-audit-trail-and-the-log
  (let [resources (generate end)
        client (mem/memory-client resources)
        {:keys [signals]} (t/with-signals true (run!* resources client end))
        audit (filter #(= :pulse.audit/write (:id %)) signals)
        raised-events (filter #(= :pulse.rules/alert-raised (:id %)) signals)
        ana (first (filter #(= "Patient/pt-ana" (get-in % [:data :patient])) audit))]
    (is (= 4 (count audit)))
    (is (= 4 (count raised-events)))
    (testing "who, what, for whom, which resource and which rule: no reading"
      (is (= :alert/write (get-in ana [:data :action])))
      (is (= :created (get-in ana [:data :outcome])))
      (is (= actor (get-in ana [:data :actor])))
      (is (= "R1" (get-in ana [:data :rule])))
      (is (re-matches #"Flag/.+" (get-in ana [:data :resource])))
      (is (= :info (:level ana))))
    (testing "an alert that already exists is not written, so it is not audited"
      (let [{:keys [signals]} (t/with-signals true (run!* resources client end))]
        (is (empty? (filter #(#{:pulse.audit/write :pulse.rules/alert-raised} (:id %)) signals)))))))

(deftest a-run-with-no-patients-does-nothing
  (let [client (mem/memory-client)
        summary (run!* [] client end)]
    (is (= {:patients 0 :raised 0 :already-raised 0 :still-active 0 :not-applicable 0 :failed 0} summary))))

(deftest the-engine-does-not-change-what-it-reads-from
  (let [resources (generate end)
        client (mem/memory-client resources)
        before (set (map (juxt :resourceType :id) (mem/contents client)))]
    (run!* resources client end)
    (is (= before (set (map (juxt :resourceType :id) (remove #(= "Flag" (:resourceType %)) (mem/contents client))))))))
