(ns pulse.fhir.client.hapi-test
  "Integration test: the HTTP client against a real HAPI FHIR server, checked against the
  in-memory client. Both must answer the same queries the same way, which is what makes the
  in-memory fake trustworthy as a test double.

  Needs the stack (`bb up`) and runs with `bb test:it`. It writes only resources whose ids
  start with `it-` and deletes them afterwards, so the demo data is untouched."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [pulse.fhir.alert :as alert]
            [pulse.fhir.appointment :as lifecycle]
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
                     :birth-date "1990-01-01"})
         (r/patient {:id "it-pt-2" :given "Integration" :family "Second" :gender "other"
                     :birth-date "1991-01-01"})]
        (concat
         (map (fn [i]
                (obs/quantity-observation
                 {:id (str "it-obs-" i) :patient patient-id :concept :heart-rate :value (+ 60 i)
                  :unit "/min" :at (str "2026-01-0" (inc i) "T08:00:00Z")}))
              (range 7))
         ;; alerts of two patients, for the searches the api makes to list them
         [(r/flag {:id "it-flag-1" :patient patient-id :text "Integration alert one" :start "2026-01-03T08:00:00Z"})
          (r/flag {:id "it-flag-2" :patient "it-pt-2" :text "Integration alert two" :start "2026-01-05T08:00:00Z"})
          (r/flag {:id "it-flag-3" :patient patient-id :text "Integration alert three" :start "2026-01-06T08:00:00Z"})])))

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

(deftest ^:integration the-appointment-searches-of-the-api-agree-on-the-demo-cohort
  ;; What the api asks for to list appointments (needs `bb seed`): a patient's, those a
  ;; practitioner takes part in, by status, and all of them. Only the ids are compared: the times
  ;; of the appointments are relative to the moment of seeding.
  (let [hapi (http/client {:base-url base-url})
        memory (mem/memory-client (synthetic/generate (synthetic/cohort)))
        ids (fn [client params] (set (map :id (c/search client "Appointment" params))))]
    (doseq [[label params] {"a patient's" {:patient "Patient/pt-ana"}
                            "a practitioner's" {:actor "Practitioner/pr-rivera"}
                            "the other practitioner's" {:actor "Practitioner/pr-okafor"}
                            "a practitioner's, booked" {:actor "Practitioner/pr-rivera" :status "booked"}
                            "a practitioner's, proposed" {:actor "Practitioner/pr-rivera" :status "proposed"}
                            "those that are booked" {:status "booked"}
                            "all of them" {}
                            "a practitioner nobody knows" {:actor "Practitioner/pr-nobody"}}]
      (testing label
        (is (= (ids memory params) (ids hapi params)))))
    (testing "the answers are not all empty by accident"
      (is (= #{"appt-ana-1" "appt-liam-1" "appt-sofia-1"} (ids hapi {:actor "Practitioner/pr-rivera"})))
      (is (= #{"appt-sofia-1"} (ids hapi {:actor "Practitioner/pr-rivera" :status "proposed"}))))))

(deftest ^:integration every-appointment-the-api-can-build-is-valid-on-hapi
  ;; A patient's request and what a clinician does to it, as `pulse.fhir.appointment` builds
  ;; them. One at a time: HAPI's validator runs out of memory when it is called concurrently.
  (let [hapi (http/client {:base-url base-url})
        now "2026-10-14T12:00:00Z"
        proposed (lifecycle/proposal {:id "it-appt-valid" :patient patient-id :practitioner "pr-rivera"
                                      :description "Integration test"
                                      :requestedStart "2026-10-20T00:00:00Z" :requestedEnd "2026-10-27T00:00:00Z"})
        booked (:ok (lifecycle/change proposed {:action :confirm :start "2026-10-21T09:00:00Z" :end "2026-10-21T09:30:00Z"} now))
        moved (:ok (lifecycle/change booked {:action :reschedule :start "2026-10-22T10:00:00Z" :end "2026-10-22T10:45:00Z"} now))]
    (doseq [[label appointment] [["a request" proposed]
                                 ["confirmed" booked]
                                 ["rescheduled" moved]
                                 ["a request, cancelled" (:ok (lifecycle/change proposed {:action :cancel} now))]
                                 ["booked, then cancelled with a reason"
                                  (:ok (lifecycle/change booked {:action :cancel :reason "The doctor is away that week"} now))]]
            :let [result (c/validate hapi appointment)]]
      (testing label
        (is (not (outcome/error? result)) (outcome/summary result))))))

(deftest ^:integration the-flag-searches-of-the-api-agree
  ;; What the api asks for to list alerts (ADR-0016): the flags of several patients at once, as
  ;; an OR list of references, newest first and in small pages. HAPI and the fake must agree.
  (let [hapi (http/client {:base-url base-url})
        memory (mem/memory-client fixtures)
        ids-of (fn [client params] (mapv :id (c/search client "Flag" params)))]
    (doseq [[label params expected]
            [["one patient" {:patient "Patient/it-pt-1" :_sort "-date"} ["it-flag-3" "it-flag-1"]]
             ["two patients, as an OR list" {:patient "Patient/it-pt-1,Patient/it-pt-2" :_sort "-date"}
              ["it-flag-3" "it-flag-2" "it-flag-1"]]
             ["a patient that has none in the list" {:patient "Patient/it-pt-2,Patient/it-pt-nobody" :_sort "-date"}
              ["it-flag-2"]]
             ["oldest first, in small pages" {:patient "Patient/it-pt-1,Patient/it-pt-2" :_sort "date" :_count 2}
              ["it-flag-1" "it-flag-2" "it-flag-3"]]
             ["nobody the list names has any" {:patient "Patient/it-pt-nobody,Patient/it-pt-ghost" :_sort "-date"} []]]]
      (testing label
        (is (= expected (ids-of hapi params)))
        (is (= expected (ids-of memory params)))))))

(deftest ^:integration an-acknowledged-alert-is-valid-on-hapi
  ;; What `pulse.fhir.alert/acknowledge` makes of a Flag: inactive, its period ending.
  (let [hapi (http/client {:base-url base-url})
        raised (r/flag {:id "it-flag-ack" :patient patient-id :text "Integration alert" :start "2026-01-03T08:00:00Z"})
        acknowledged (:ok (alert/acknowledge raised "2026-01-04T09:30:00Z"))
        result (c/validate hapi acknowledged)]
    (is (= "inactive" (:status acknowledged)))
    (is (not (outcome/error? result)) (outcome/summary result))))

(deftest ^:integration a-conditional-create-answers-the-same-on-hapi-and-in-memory
  ;; How the rules engine raises an alert once (ADR-0022): a POST with `ifNoneExist` in a
  ;; transaction. No match creates; a match is left as it is and answered with a 200, whatever its
  ;; status; more than one match is a 412. HAPI gives the new resource its own id, which is
  ;; deleted afterwards.
  (let [system "https://pulse.example/identifier/alert"
        ;; as the rules engine writes it, with slashes and colons, and the condition as it writes it:
        ;; encoded (see `pulse.scheduler.alerts/raise!`)
        key (str "R1/it-conditional-" (random-uuid) "/2026-01-07T08:00:00Z")
        identifier {:system system :value key}
        condition (c/query-string {:identifier (str system "|" key)})
        flag (fn [] (r/flag {:id "unused" :patient patient-id :text "Integration conditional"
                             :start "2026-01-07T08:00:00Z" :identifier identifier}))
        raise! (fn [client]
                 (-> (c/transact! client (bundle/transaction-of [(bundle/conditional-create-entry (flag) condition)]))
                     :entry first :response))
        found (fn [client] (vec (c/search client "Flag" {:identifier (str system "|" key)})))
        hapi (http/client {:base-url base-url})
        memory (mem/memory-client)]
    (doseq [[label client] [["HAPI" hapi] ["in memory" memory]]]
      (testing label
        (try
          (let [created (raise! client)
                again (raise! client)
                [_ id] (bundle/location-id (:location created))]
            (is (= "201 Created" (:status created)))
            (is (= "200 OK" (:status again)))
            (is (= [id] (map :id (found client))) "one Flag, however many times it was raised")
            (is (= [id] [(second (bundle/location-id (:location again)))]) "and the second answer points at it")
            (testing "an acknowledged alert is left as it is"
              (c/put! client (:ok (alert/acknowledge (c/read-resource client "Flag" id) "2026-01-08T09:00:00Z")))
              (is (= "200 OK" (:status (raise! client))))
              (is (= ["inactive"] (map :status (found client)))))
            (testing "a condition that matches two is an error and creates nothing"
              (c/put! client (assoc (flag) :id "it-flag-twin"))
              (let [e (try (raise! client) (catch clojure.lang.ExceptionInfo e e))]
                (is (= 412 (:status (ex-data e))))
                (is (= 2 (count (found client)))))))
          (finally
            (doseq [{:keys [id]} (found client)]
              (c/delete! client "Flag" id))))))))

(def ^:private doctor-id "it-pr-1")

(defn- with-temporary
  "Calls `(f hapi memory)` with `resources` in HAPI and in a fresh server in memory, and deletes
  them from HAPI afterwards, whatever happens. They have to be in dependency order."
  [resources f]
  (let [hapi (http/client {:base-url base-url})
        delete-all! #(doseq [{:keys [resourceType id]} (reverse resources)]
                       (c/delete! hapi resourceType id))]
    (delete-all!)
    (c/transact! hapi (bundle/transaction resources))
    (try (f hapi (mem/memory-client resources))
         (finally (delete-all!)))))

(defn- the-doctor [] (r/practitioner {:id doctor-id :given "Integration" :family "Doctor" :gender "other"}))

(defn- booked-at
  "A booked appointment of the integration patient that begins at `start` and lasts half an hour."
  [id start end & {:keys [status] :or {status "booked"}}]
  (assoc (r/appointment {:id id :patient patient-id :practitioner doctor-id :description "Integration window"
                         :start start :end end :minutes 30})
         :status status))

(deftest ^:integration the-appointments-due-for-a-reminder-are-found-the-same-by-hapi-and-in-memory
  ;; What the reminders job asks for (ADR-0024): the booked appointments that begin after a moment
  ;; and no later than 24 hours after it, soonest first, in whole seconds. The edges are where the
  ;; job says they are on both servers, to the second.
  (with-temporary [(the-doctor)
                   (booked-at "it-appt-before" "2026-01-10T09:59:59Z" "2026-01-10T10:29:59Z")
                   (booked-at "it-appt-now" "2026-01-10T10:00:00Z" "2026-01-10T10:30:00Z")
                   (booked-at "it-appt-just-after" "2026-01-10T10:00:01Z" "2026-01-10T10:30:01Z")
                   (booked-at "it-appt-cancelled" "2026-01-10T12:00:00Z" "2026-01-10T12:30:00Z" :status "cancelled")
                   (booked-at "it-appt-24h" "2026-01-11T10:00:00Z" "2026-01-11T10:30:00Z")
                   (booked-at "it-appt-24h-and-a-second" "2026-01-11T10:00:01Z" "2026-01-11T10:30:01Z")]
    (fn [hapi memory]
      (doseq [[label from to expected]
              [["from a moment on an appointment to the same moment a day on"
                "2026-01-10T10:00:00Z" "2026-01-11T10:00:00Z" ["it-appt-just-after" "it-appt-24h"]]
               ["a second later, both ends move with it"
                "2026-01-10T10:00:01Z" "2026-01-11T10:00:01Z" ["it-appt-24h" "it-appt-24h-and-a-second"]]
               ["a second earlier"
                "2026-01-10T09:59:59Z" "2026-01-11T09:59:59Z" ["it-appt-now" "it-appt-just-after"]]
               ["one hour only" "2026-01-10T09:00:00Z" "2026-01-10T10:00:00Z" ["it-appt-before" "it-appt-now"]]
               ["nothing begins in it" "2026-01-10T10:30:00Z" "2026-01-10T11:00:00Z" []]]
              :let [params {:status "booked" :date [(str "gt" from) (str "le" to)] :_sort "date" :_count 1}
                    ids-of (fn [client] (mapv :id (c/search client "Appointment" params)))]]
        (testing label
          (is (= expected (ids-of hapi)))
          (is (= expected (ids-of memory))))))))

(deftest ^:integration a-reminder-is-created-once-on-hapi-and-in-memory
  ;; How the reminders job sends one (ADR-0024): `create-once!` of a Communication by its
  ;; identifier, which has a slash and colons, as for the Flag of an alert. No match creates, a
  ;; match is left as it is whatever has become of it, and two matches are a 412. HAPI names the
  ;; new resource.
  (with-temporary [(the-doctor)
                   (booked-at "it-appt-reminder" "2026-01-10T10:00:00Z" "2026-01-10T10:30:00Z")]
    (fn [hapi memory]
      (let [system "https://pulse.example/identifier/reminder"
            key (str "it-appt-reminder/2026-01-10T10:00:00Z/" (random-uuid))
            reminder (fn [] (r/communication {:id "unused" :patient patient-id :appointment "it-appt-reminder"
                                              :sent "2026-01-09T10:00:00Z" :text "Integration reminder"
                                              :identifier {:system system :value key}}))
            found (fn [client] (vec (c/search client "Communication" {:identifier (str system "|" key)})))]
        (doseq [[label client] [["HAPI" hapi] ["in memory" memory]]]
          (testing label
            (try
              (let [created (c/create-once! client (reminder))
                    again (c/create-once! client (reminder))
                    id (:id created)]
                (is (= :created (:outcome created)))
                (is (= :existing (:outcome again)))
                (is (= [id] (map :id (found client))) "one, however many times it was sent")
                (is (= id (:id again)) "and the second answer points at it")
                (testing "what is there is left as it is"
                  (c/put! client (assoc (c/read-resource client "Communication" id) :status "entered-in-error"))
                  (is (= :existing (:outcome (c/create-once! client (reminder)))))
                  (is (= ["entered-in-error"] (map :status (found client)))))
                (testing "two that match are an error and nothing is created"
                  (c/put! client (assoc (reminder) :id "it-comm-twin"))
                  (let [e (try (c/create-once! client (reminder)) (catch clojure.lang.ExceptionInfo e e))]
                    (is (= 412 (:status (ex-data e))))
                    (is (= 2 (count (found client)))))))
              (finally
                (doseq [{:keys [id]} (found client)]
                  (c/delete! client "Communication" id))))))))))

(deftest ^:integration reminders-created-at-the-same-time-are-created-once
  ;; ADR-0024 says that two runs that overlap send nothing twice. HAPI keeps that: of several
  ;; creators of the same reminder at once, one creates it and the others are answered with a 200
  ;; or, mostly, with a 409, a conflict that is solved by asking again. Never two are stored.
  (with-temporary [(the-doctor)
                   (booked-at "it-appt-race" "2026-01-10T10:00:00Z" "2026-01-10T10:30:00Z")]
    (fn [hapi _]
      (let [system "https://pulse.example/identifier/reminder"]
        (dotimes [round 5]
          (let [key (str "it-appt-race/2026-01-10T10:00:00Z/" round "/" (random-uuid))
                reminder (fn [] (r/communication {:id "unused" :patient patient-id :appointment "it-appt-race"
                                                  :sent "2026-01-09T10:00:00Z" :text "Integration race"
                                                  :identifier {:system system :value key}}))
                found (fn [] (vec (c/search hapi "Communication" {:identifier (str system "|" key)})))
                gate (promise)
                tries (doall (for [_ (range 6)]
                               (future @gate
                                       (try (c/create-once! hapi (reminder))
                                            (catch clojure.lang.ExceptionInfo e e)))))
                _ (deliver gate :go)
                results (mapv deref tries)]
            (try
              (testing (str "round " round)
                (is (= 1 (count (found))) "never two")
                (is (= 1 (count (filter #(= :created (:outcome %)) results))) "and one of them created it")
                (is (every? #(or (map? %) (= 409 (:status (ex-data %)))) results)
                    "the others were told that it exists, or that it is being created"))
              (finally
                (doseq [{:keys [id]} (found)]
                  (c/delete! hapi "Communication" id))))))))))

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
