(ns pulse.scheduler.reminders-test
  "The appointment reminders (ADR-0024) against a FHIR server in memory: the search for the
  appointments that are about to begin and its edges to the second, the text of a reminder, that a
  reminder is sent once however often the job runs and again when the appointment moves, what a
  failure does (one appointment, or the infrastructure), and what the audit trail says."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [pulse.fhir.client :as c]
            [pulse.fhir.client.memory :as mem]
            [pulse.fhir.resource :as r]
            [pulse.fhir.schema :as schema]
            [pulse.fhir.synthetic :as synthetic]
            [pulse.scheduler.reminders :as reminders]
            [taoensso.telemere :as t])
  (:import (java.net ConnectException)
           (java.net.http HttpTimeoutException)
           (java.time Instant)
           (java.time.temporal ChronoUnit)
           (java.util Locale Locale$Category)))

(def ^:private actor {:client "pulse-scheduler" :roles ["rules-engine"]})

(def ^:private now (Instant/parse "2026-10-13T08:00:00Z"))

(defn- later
  "The moment `seconds` after `now`."
  ^Instant [seconds]
  (.plusSeconds ^Instant now (long seconds)))

;; ---------------------------------------------------------------------------
;; A clinic

(def ^:private rivera (r/practitioner {:id "pr-rivera" :given "Elena" :family "Rivera" :gender "female"}))
(def ^:private okafor (r/practitioner {:id "pr-okafor" :given "Chidi" :family "Okafor" :gender "male"}))

(defn- a-patient [id given family]
  (r/patient {:id id :given given :family family :gender "other" :birth-date "1980-01-01"}))

(def ^:private people
  [rivera okafor (a-patient "pt-ana" "Ana" "García") (a-patient "pt-liam" "Liam" "Chen")])

(defn- booked
  "A booked appointment beginning at `start` (an Instant or the text of one) and lasting half an
  hour. It is Ana's with Dr. Rivera unless `options` say otherwise; `:description nil` leaves the
  description out and `:status` changes the status without changing anything else."
  [id start & {:keys [patient practitioner description status]
               :or {patient "pt-ana" practitioner "pr-rivera" description "Hypertension follow-up" status "booked"}}]
  (let [begins (Instant/parse (str start))]
    (cond-> (assoc (r/appointment {:id id :patient patient :practitioner practitioner :description description
                                   :start (str begins) :end (str (.plusSeconds begins 1800)) :minutes 30})
                   :status status)
      (nil? description) (dissoc :description))))

(defn- server
  "A FHIR server in memory with the people of the clinic and `appointments`."
  [& appointments]
  (mem/memory-client (concat people appointments)))

(defn- run!*
  "Runs the reminders over `fhir` at `now`, or at `:now`; the other options change the dependencies of
  the run, whose window is a day and whose zone is Madrid's."
  [fhir & {:as options}]
  (reminders/run! (merge {:fhir fhir :actor actor :window-hours 24 :zone "Europe/Madrid"} (dissoc options :now))
                  (or (:now options) now)))

(defn- trapped
  "`run!*` with its signals trapped, so that nothing is printed: `{:value :error :signals}`."
  [fhir & options]
  (t/with-signals true (apply run!* fhir options)))

(defn- run-quietly
  "The summary of `run!*` with its signals trapped. A run that throws throws."
  [fhir & options]
  (let [{:keys [value error]} (apply trapped fhir options)]
    (when error
      (throw error))
    value))

(defn- reminders-of [fhir]
  (vec (c/search fhir "Communication" {})))

(defn- reminded
  "The ids of the appointments that have a reminder in `fhir`."
  [fhir]
  (set (map #(str/replace (get-in % [:about 0 :reference]) "Appointment/" "") (reminders-of fhir))))

(defn- text-of [reminder]
  (get-in reminder [:payload 0 :contentString]))

(defn- client-with
  "A FHIR client over `base` that, for each of `:read-resource`, `:search` and `:transact!` in
  `overrides`, calls that function with `base` and the arguments of the call instead."
  [base {:keys [read-resource search transact!]}]
  (reify c/FhirClient
    (read-resource [_ resource-type id] ((or read-resource c/read-resource) base resource-type id))
    (search [_ resource-type params] ((or search c/search) base resource-type params))
    (put! [_ resource] (c/put! base resource))
    (delete! [_ resource-type id] (c/delete! base resource-type id))
    (transact! [_ tx] ((or transact! c/transact!) base tx))
    (validate [_ resource] (c/validate base resource))))

(defn- failing-for
  "A client over `base` whose transaction that sends the reminder of the appointment `id` throws `e`."
  [base id e]
  (client-with base {:transact! (fn [base tx]
                                  (if (= (str "Appointment/" id) (get-in tx [:entry 0 :resource :about 0 :reference]))
                                    (throw e)
                                    (c/transact! base tx)))}))

(def ^:private summary-of-one {:appointments 1 :sent 1 :already-sent 0 :failed 0})

;; ---------------------------------------------------------------------------
;; What to ask for

(deftest the-search-is-for-the-booked-appointments-of-the-window-in-whole-seconds
  (is (= {:status "booked"
          :date ["gt2026-10-13T08:00:00Z" "le2026-10-14T08:00:00Z"]
          :_sort "date"
          :_count 100}
         (reminders/query (Instant/parse "2026-10-13T08:00:00.700Z") 24))
      "the moment is cut to the second it falls in, the window is added to that")
  (is (= ["gt2026-10-13T08:00:00Z" "le2026-10-13T09:00:00Z"] (:date (reminders/query now 1))))
  (is (= ["gt2026-10-13T08:00:00Z" "le2026-10-20T08:00:00Z"] (:date (reminders/query now 168))) "a week at most")
  (is (= ["gt2026-10-13T08:00:00Z" "le2026-10-15T08:00:00Z"] (:date (reminders/query now 48)))))

;; ---------------------------------------------------------------------------
;; What a reminder is

(deftest a-reminder-is-one-for-an-appointment-and-the-time-it-starts
  (is (= "appt-ana-1/2026-10-14T04:00:00Z" (reminders/reminder-key (booked "appt-ana-1" "2026-10-14T04:00:00Z"))))
  (testing "in UTC, whatever offset the time is written with"
    (is (= "appt-ana-1/2026-10-14T04:00:00Z"
           (reminders/reminder-key (assoc (booked "appt-ana-1" "2026-10-14T04:00:00Z") :start "2026-10-14T06:00:00+02:00"))))
    (is (= "appt-ana-1/2026-10-13T23:30:00Z"
           (reminders/reminder-key (assoc (booked "appt-ana-1" "2026-10-14T04:00:00Z") :start "2026-10-14T01:30:00+02:00")))
        "also when the date changes"))
  (testing "another time is another reminder, and another appointment is another"
    (is (not= (reminders/reminder-key (booked "appt-ana-1" "2026-10-14T04:00:00Z"))
              (reminders/reminder-key (booked "appt-ana-1" "2026-10-14T04:00:01Z"))))
    (is (not= (reminders/reminder-key (booked "appt-ana-1" "2026-10-14T04:00:00Z"))
              (reminders/reminder-key (booked "appt-ana-2" "2026-10-14T04:00:00Z"))))))

(deftest the-doctor-is-named-as-people-say-it
  (is (= "Elena Rivera" (reminders/practitioner-name rivera)) "the builder gives her a title, which is not said")
  (is (= "Maria Elena Rivera" (reminders/practitioner-name {:name [{:given ["Maria" "Elena"] :family "Rivera" :prefix ["Dr."]}]})))
  (is (= "Rivera" (reminders/practitioner-name {:name [{:family "Rivera"}]})))
  (is (= "Elena" (reminders/practitioner-name {:name [{:given ["Elena"]}]})))
  (testing "the first name that has a word in it"
    (is (= "Elena Rivera" (reminders/practitioner-name {:name [{:use "old"} {:given [" "] :family ""} {:given ["Elena"] :family "Rivera"}]}))))
  (testing "no name, no words, no doctor"
    (is (nil? (reminders/practitioner-name {:name [{:use "official"}]})))
    (is (nil? (reminders/practitioner-name {:name [{:given [""] :family " "}]})))
    (is (nil? (reminders/practitioner-name {:resourceType "Practitioner"})))
    (is (nil? (reminders/practitioner-name nil)))))

(deftest the-text-says-who-when-and-why
  (let [appointment (booked "appt-ana-1" "2026-10-14T04:00:00Z")]
    (is (= "Reminder: your appointment with Elena Rivera is on Wednesday 14 October at 06:00 (Hypertension follow-up)."
           (reminders/message appointment "Elena Rivera" "Europe/Madrid")))
    (testing "without the doctor, if there is none"
      (doseq [nothing [nil "" "  "]]
        (is (= "Reminder: your appointment is on Wednesday 14 October at 06:00 (Hypertension follow-up)."
               (reminders/message appointment nothing "Europe/Madrid")))))
    (testing "without the description, if there is none"
      (doseq [nothing [nil "" "  "]]
        (is (= "Reminder: your appointment with Elena Rivera is on Wednesday 14 October at 06:00."
               (reminders/message (assoc appointment :description nothing) "Elena Rivera" "Europe/Madrid"))))
      (is (= "Reminder: your appointment with Elena Rivera is on Wednesday 14 October at 06:00."
             (reminders/message (dissoc appointment :description) "Elena Rivera" "Europe/Madrid"))))
    (testing "with neither"
      (is (= "Reminder: your appointment is on Wednesday 14 October at 06:00."
             (reminders/message (dissoc appointment :description) nil "Europe/Madrid"))))))

(deftest the-time-is-the-one-of-the-zone-it-is-said-in
  (let [appointment (booked "appt-ana-1" "2026-10-14T04:00:00Z")
        said-in (fn [zone] (reminders/message (dissoc appointment :description) nil zone))]
    (is (= "Reminder: your appointment is on Wednesday 14 October at 04:00." (said-in "UTC")))
    (is (= "Reminder: your appointment is on Wednesday 14 October at 06:00." (said-in "Europe/Madrid")))
    (is (= "Reminder: your appointment is on Wednesday 14 October at 13:00." (said-in "Asia/Tokyo")))
    (is (= "Reminder: your appointment is on Wednesday 14 October at 00:00." (said-in "America/New_York")))
    (testing "and so is the day"
      (let [late (assoc appointment :start "2026-10-14T23:30:00Z")]
        (is (= "Reminder: your appointment is on Wednesday 14 October at 23:30." (reminders/message (dissoc late :description) nil "UTC")))
        (is (= "Reminder: your appointment is on Thursday 15 October at 01:30." (reminders/message (dissoc late :description) nil "Europe/Madrid")))))
    (testing "an offset in the time of the appointment changes nothing"
      (is (= (said-in "Europe/Madrid")
             (reminders/message (dissoc (assoc appointment :start "2026-10-14T06:00:00+02:00") :description) nil "Europe/Madrid"))))))

(deftest the-text-goes-through-the-days-the-clocks-change
  ;; Madrid is two hours ahead of UTC in summer and one in winter. In 2026 the clocks went forward
  ;; on Sunday 29 March and back on Sunday 25 October.
  (let [said (fn [start] (reminders/message (dissoc (booked "appt-ana-1" start) :description) nil "Europe/Madrid"))]
    (testing "before and after they go back"
      (is (= "Reminder: your appointment is on Saturday 24 October at 14:00." (said "2026-10-24T12:00:00Z")))
      (is (= "Reminder: your appointment is on Monday 26 October at 13:00." (said "2026-10-26T12:00:00Z"))))
    (testing "before and after they go forward"
      (is (= "Reminder: your appointment is on Saturday 28 March at 13:00." (said "2026-03-28T12:00:00Z")))
      (is (= "Reminder: your appointment is on Monday 30 March at 14:00." (said "2026-03-30T12:00:00Z"))))
    (testing "on the day itself, either side of the change"
      (is (= "Reminder: your appointment is on Sunday 25 October at 01:30." (said "2026-10-24T23:30:00Z")))
      (is (= "Reminder: your appointment is on Sunday 25 October at 03:30." (said "2026-10-25T02:30:00Z")))
      (is (= "Reminder: your appointment is on Sunday 29 March at 01:30." (said "2026-03-29T00:30:00Z")))
      (is (= "Reminder: your appointment is on Sunday 29 March at 04:30." (said "2026-03-29T02:30:00Z"))))
    (testing "the hour that the clocks repeat reads the same twice, and the two are still two reminders"
      (is (= (said "2026-10-25T00:30:00Z") (said "2026-10-25T01:30:00Z")))
      (is (= "Reminder: your appointment is on Sunday 25 October at 02:30." (said "2026-10-25T00:30:00Z")))
      (is (not= (reminders/reminder-key (booked "appt-ana-1" "2026-10-25T00:30:00Z"))
                (reminders/reminder-key (booked "appt-ana-1" "2026-10-25T01:30:00Z")))))))

(deftest the-text-is-in-english-whatever-the-language-of-the-machine
  (let [default (Locale/getDefault Locale$Category/FORMAT)
        appointment (dissoc (booked "appt-ana-1" "2026-10-14T04:00:00Z") :description)]
    (try
      (doseq [language ["es-ES" "fr-FR" "de-DE" "ja-JP"]]
        (Locale/setDefault Locale$Category/FORMAT (Locale/forLanguageTag language))
        (is (= "Reminder: your appointment is on Wednesday 14 October at 06:00."
               (reminders/message appointment nil "Europe/Madrid"))
            language))
      (finally
        (Locale/setDefault Locale$Category/FORMAT default)))))

(deftest a-communication-is-a-completed-reminder-with-the-identifier-and-no-id
  (let [appointment (booked "appt-ana-1" "2026-10-14T04:00:00Z")
        reminder (reminders/communication appointment "pt-ana" "The text." (Instant/parse "2026-10-13T08:00:00Z"))]
    (is (= (dissoc (r/communication {:id "unused" :patient "pt-ana" :appointment "appt-ana-1" :sent "2026-10-13T08:00:00Z"
                                     :text "The text."
                                     :identifier {:system "https://pulse.example/identifier/reminder"
                                                  :value "appt-ana-1/2026-10-14T04:00:00Z"}})
                   :id)
           reminder))
    (is (not (contains? reminder :id)) "the server names it")
    (is (= "completed" (:status reminder)))
    (is (= "Patient/pt-ana" (get-in reminder [:subject :reference])))
    (is (= [{:reference "Patient/pt-ana"}] (:recipient reminder)))
    (is (= [{:reference "Appointment/appt-ana-1"}] (:about reminder)))
    (is (= "reminder" (get-in reminder [:category 0 :coding 0 :code])))
    (is (= [{:system reminders/identifier-system :value "appt-ana-1/2026-10-14T04:00:00Z"}] (:identifier reminder)))
    (is (= "https://pulse.example/identifier/reminder" reminders/identifier-system))
    (testing "valid once the server has given it an id"
      (is (schema/valid? (assoc reminder :id "co-1"))))))

;; ---------------------------------------------------------------------------
;; A run

(deftest an-appointment-about-to-begin-is-reminded-once
  (let [fhir (server (booked "appt-ana-1" "2026-10-14T04:00:00Z"))]
    (is (= summary-of-one (run-quietly fhir)))
    (let [[reminder :as all] (reminders-of fhir)]
      (is (= 1 (count all)))
      (is (= (dissoc (r/communication
                      {:id "unused" :patient "pt-ana" :appointment "appt-ana-1" :sent "2026-10-13T08:00:00Z"
                       :text "Reminder: your appointment with Elena Rivera is on Wednesday 14 October at 06:00 (Hypertension follow-up)."
                       :identifier {:system "https://pulse.example/identifier/reminder"
                                    :value "appt-ana-1/2026-10-14T04:00:00Z"}})
                     :id)
             (dissoc reminder :id))))
    (testing "and not again, however often it runs"
      (is (= {:appointments 1 :sent 0 :already-sent 1 :failed 0} (run-quietly fhir)))
      (is (= {:appointments 1 :sent 0 :already-sent 1 :failed 0} (run-quietly fhir :now (later 3600))))
      (is (= 1 (count (reminders-of fhir)))))))

(deftest a-run-with-nothing-about-to-begin-sends-nothing
  (is (= {:appointments 0 :sent 0 :already-sent 0 :failed 0} (run-quietly (server))))
  (is (= {:appointments 0 :sent 0 :already-sent 0 :failed 0}
         (run-quietly (server (booked "appt-ana-1" (later (* 30 3600)))))))
  (is (empty? (reminders-of (server)))))

(deftest the-window-begins-after-now-and-ends-with-the-window
  (let [fhir (server (booked "a-before" (later -1)) (booked "a-now" (later 0)) (booked "a-after" (later 1))
                     (booked "a-last" (later 86400)) (booked "a-late" (later 86401)))]
    (is (= {:appointments 2 :sent 2 :already-sent 0 :failed 0} (run-quietly fhir)))
    (is (= #{"a-after" "a-last"} (reminded fhir)) "one second after now is in, and so is a day, to the second")))

(deftest the-window-is-as-long-as-the-setting-says
  (doseq [hours [1 2 24 48 168]
          :let [seconds (* hours 3600)
                fhir (server (booked "a-in" (later seconds)) (booked "a-out" (later (inc seconds))) (booked "a-soon" (later 60)))]]
    (testing (str hours " hours")
      (is (= {:appointments 2 :sent 2 :already-sent 0 :failed 0} (run-quietly fhir :window-hours hours)))
      (is (= #{"a-in" "a-soon"} (reminded fhir))))))

(deftest a-moment-is-taken-in-the-second-it-falls-in
  ;; the moment the job started, with its fraction: the appointment of the second that has begun is
  ;; not to be reminded, and the window ends a day after that second
  (let [fhir (server (booked "a-now" (later 0)) (booked "a-after" (later 1)) (booked "a-last" (later 86400)) (booked "a-late" (later 86401)))
        started (Instant/parse "2026-10-13T08:00:00.700Z")]
    (is (= {:appointments 2 :sent 2 :already-sent 0 :failed 0} (run-quietly fhir :now started)))
    (is (= #{"a-after" "a-last"} (reminded fhir)))
    (testing "and the reminder was sent at that second"
      (is (= #{"2026-10-13T08:00:00Z"} (set (map :sent (reminders-of fhir))))))))

(deftest the-times-of-the-appointments-may-be-written-with-an-offset
  (let [fhir (server (assoc (booked "a-1" (later 3600)) :start "2026-10-13T11:00:00+02:00" :end "2026-10-13T11:30:00+02:00"))
        {:keys [value signals]} (trapped fhir)]
    (is (= summary-of-one value))
    (is (= "a-1/2026-10-13T09:00:00Z" (get-in (first (reminders-of fhir)) [:identifier 0 :value])))
    (is (str/includes? (text-of (first (reminders-of fhir))) "Tuesday 13 October at 11:00"))
    (testing "the log says when it begins in UTC, as the key does"
      (is (= ["2026-10-13T09:00:00Z"]
             (map #(get-in % [:data :starts-at]) (filter #(= :pulse.reminders/sent (:id %)) signals)))))))

(deftest only-a-confirmed-appointment-is-reminded
  (let [others ["proposed" "pending" "arrived" "fulfilled" "cancelled" "noshow" "entered-in-error" "checked-in" "waitlist"]
        fhir (apply server (booked "a-booked" (later 3600))
                    (map-indexed (fn [i status] (booked (str "a-" status) (later (+ 3601 i)) :status status)) others))]
    (is (= summary-of-one (run-quietly fhir)))
    (is (= #{"a-booked"} (reminded fhir)))))

(deftest an-appointment-that-moves-is-reminded-again-for-its-new-time
  (let [fhir (server (booked "appt-ana-1" "2026-10-14T04:00:00Z"))]
    (run-quietly fhir)
    (testing "to another time inside the window"
      (c/put! fhir (booked "appt-ana-1" "2026-10-14T06:00:00Z"))
      (is (= summary-of-one (run-quietly fhir)))
      (is (= #{"Reminder: your appointment with Elena Rivera is on Wednesday 14 October at 06:00 (Hypertension follow-up)."
               "Reminder: your appointment with Elena Rivera is on Wednesday 14 October at 08:00 (Hypertension follow-up)."}
             (set (map text-of (reminders-of fhir))))
          "the one that was sent is not taken back")
      (is (= 2 (count (reminders-of fhir)))))
    (testing "back to a time it had: that reminder is the one it has"
      (c/put! fhir (booked "appt-ana-1" "2026-10-14T04:00:00Z"))
      (is (= {:appointments 1 :sent 0 :already-sent 1 :failed 0} (run-quietly fhir)))
      (is (= 2 (count (reminders-of fhir)))))
    (testing "to a time outside the window: nothing to send"
      (c/put! fhir (booked "appt-ana-1" (later (* 30 3600))))
      (is (= {:appointments 0 :sent 0 :already-sent 0 :failed 0} (run-quietly fhir)))
      (is (= 2 (count (reminders-of fhir)))))))

(deftest each-patient-is-reminded-of-their-own-appointment-by-their-own-doctor
  (let [fhir (server (booked "appt-ana-1" (later 72000) :patient "pt-ana" :practitioner "pr-rivera" :description "Hypertension follow-up")
                     (booked "appt-liam-1" (later 7200) :patient "pt-liam" :practitioner "pr-okafor" :description "Diabetes review"))]
    (is (= {:appointments 2 :sent 2 :already-sent 0 :failed 0} (run-quietly fhir)))
    (let [by-patient (into {} (map (juxt #(get-in % [:subject :reference]) identity)) (reminders-of fhir))
          ana (get by-patient "Patient/pt-ana")
          liam (get by-patient "Patient/pt-liam")]
      (is (= #{"Patient/pt-ana" "Patient/pt-liam"} (set (keys by-patient))))
      (is (= [{:reference "Patient/pt-ana"}] (:recipient ana)))
      (is (= [{:reference "Appointment/appt-ana-1"}] (:about ana)))
      (is (str/includes? (text-of ana) "with Elena Rivera"))
      (is (str/includes? (text-of ana) "(Hypertension follow-up)"))
      (is (= [{:reference "Patient/pt-liam"}] (:recipient liam)))
      (is (= [{:reference "Appointment/appt-liam-1"}] (:about liam)))
      (is (str/includes? (text-of liam) "with Chidi Okafor"))
      (is (str/includes? (text-of liam) "(Diabetes review)")))))

(deftest the-zone-and-the-window-are-those-of-the-settings
  (let [fhir (server (booked "appt-ana-1" "2026-10-14T04:00:00Z"))]
    (is (= {:appointments 0 :sent 0 :already-sent 0 :failed 0} (run-quietly fhir :window-hours 19)) "it is 20 hours away")
    (is (= summary-of-one (run-quietly fhir :window-hours 20 :zone "Asia/Tokyo")))
    (is (str/includes? (text-of (first (reminders-of fhir))) "Wednesday 14 October at 13:00"))))

(deftest the-reminder-is-sent-at-the-moment-of-the-run
  (let [fhir (server (booked "appt-ana-1" "2026-10-14T04:00:00Z"))]
    (run-quietly fhir :now (Instant/parse "2026-10-13T20:15:42.999Z"))
    (is (= ["2026-10-13T20:15:42Z"] (map :sent (reminders-of fhir))))))

;; ---------------------------------------------------------------------------
;; The doctor

(defn- reminded-text
  "The text of the only reminder that a run over `fhir` sends."
  [fhir]
  (run-quietly fhir)
  (text-of (first (reminders-of fhir))))

(deftest a-reminder-is-sent-whether-or-not-the-doctor-can-be-named
  (let [plain "Reminder: your appointment is on Wednesday 14 October at 06:00 (Hypertension follow-up)."
        start "2026-10-14T04:00:00Z"]
    (testing "a doctor who is not on the server"
      (is (= plain (reminded-text (server (booked "a-1" start :practitioner "pr-nobody"))))))
    (testing "a doctor with no name"
      (is (= plain (reminded-text (mem/memory-client (concat [(assoc rivera :name [{:use "official"}])
                                                              (a-patient "pt-ana" "Ana" "García")]
                                                             [(booked "a-1" start)]))))))
    (testing "an appointment with no doctor at all"
      (is (= plain (reminded-text (server (update (booked "a-1" start) :participant #(vec (take 1 %))))))))
    (testing "a doctor the server will not give, which is said in the log"
      (let [refusing (client-with (server (booked "a-1" start))
                                  {:read-resource (fn [_ _ _] (throw (ex-info "forbidden" {:type ::c/fhir-error :status 403})))})
            {:keys [value signals]} (trapped refusing)
            unreadable (filter #(= :pulse.reminders/practitioner-unreadable (:id %)) signals)]
        (is (= summary-of-one value))
        (is (= plain (text-of (first (reminders-of refusing)))))
        (is (= 1 (count unreadable)))
        (is (= :warn (:level (first unreadable))))
        (is (= {:practitioner "pr-rivera"} (:data (first unreadable))))
        (is (= "forbidden" (ex-message (:error (first unreadable)))) "and the exception")))))

(deftest a-doctor-the-infrastructure-cannot-give-ends-the-run
  (doseq [[label e] {"FHIR failing" (ex-info "broken" {:type ::c/fhir-error :status 503})
                     "FHIR unreachable" (ConnectException. "refused")
                     "an interruption" (InterruptedException. "stop")}
          :let [base (server (booked "a-1" "2026-10-14T04:00:00Z"))
                fhir (client-with base {:read-resource (fn [_ _ _] (throw e))})]]
    (testing label
      (is (identical? e (:error (trapped fhir))))
      (is (empty? (reminders-of base)) "and nothing was sent without the name, to be sent again with it"))))

(deftest each-doctor-is-read-once-for-a-run
  (let [reads (atom [])
        base (server (booked "a-1" (later 3600)) (booked "a-2" (later 7200)) (booked "a-3" (later 10800) :practitioner "pr-okafor")
                     (booked "a-4" (later 14400) :practitioner "pr-nobody") (booked "a-5" (later 18000) :practitioner "pr-nobody"))
        fhir (client-with base {:read-resource (fn [base resource-type id]
                                                 (swap! reads conj [resource-type id])
                                                 (c/read-resource base resource-type id))})]
    (run-quietly fhir)
    (is (= [["Practitioner" "pr-rivera"] ["Practitioner" "pr-okafor"] ["Practitioner" "pr-nobody"]] @reads)
        "one read for each doctor, whatever the number of appointments, found or not")
    (testing "and again at the next run, since a doctor may change their name"
      (run-quietly fhir)
      (is (= 6 (count @reads))))))

(deftest a-run-is-one-search-of-appointments-and-a-transaction-for-each-reminder
  (let [searches (atom [])
        transactions (atom [])
        base (server (booked "a-1" (later 3600)) (booked "a-2" (later 7200)))
        fhir (client-with base {:search (fn [base resource-type params]
                                          (swap! searches conj [resource-type params])
                                          (c/search base resource-type params))
                                :transact! (fn [base tx]
                                             (swap! transactions conj tx)
                                             (c/transact! base tx))})]
    (run-quietly fhir)
    (is (= [["Appointment" (reminders/query now 24)]] @searches) "none of reminders, to see whether to send")
    (is (= 2 (count @transactions)))
    (is (every? #(= 1 (count (:entry %))) @transactions) "so that one that fails does not undo the others")
    (is (every? #(= "POST" (get-in % [:entry 0 :request :method])) @transactions))))

;; ---------------------------------------------------------------------------
;; Failures

(deftest an-appointment-that-fails-is-counted-and-the-others-are-reminded
  (let [base (server (booked "a-1" (later 3600)) (booked "a-bad" (later 7200)) (booked "a-3" (later 10800)))
        e (ex-info "invalid" {:type ::c/fhir-error :status 422})
        {:keys [value signals]} (trapped (failing-for base "a-bad" e))
        failed (filter #(= :pulse.reminders/failed (:id %)) signals)]
    (is (= {:appointments 3 :sent 2 :already-sent 0 :failed 1} value))
    (is (= #{"a-1" "a-3"} (reminded base)))
    (testing "logged as a warning, with the appointment and the exception"
      (is (= 1 (count failed)))
      (is (= :warn (:level (first failed))))
      (is (= {:appointment "a-bad"} (:data (first failed))))
      (is (identical? e (:error (first failed)))))
    (testing "and sent by the run that finds that it can be"
      (is (= {:appointments 3 :sent 1 :already-sent 2 :failed 0} (run-quietly base)))
      (is (= #{"a-1" "a-bad" "a-3"} (reminded base))))))

(deftest a-failure-about-one-appointment-is-not-the-infrastructure
  (doseq [e [(ex-info "invalid" {:type ::c/fhir-error :status 422})
             (ex-info "the condition matches several" {:type ::c/fhir-error :status 412})
             (ex-info "the last status that is not a failure of the server" {:type ::c/fhir-error :status 499})
             (IllegalStateException. "a bug")]
          :let [base (server (booked "a-1" (later 3600)) (booked "a-bad" (later 7200)) (booked "a-3" (later 10800)))]]
    (is (= {:appointments 3 :sent 2 :already-sent 0 :failed 1} (run-quietly (failing-for base "a-bad" e))) (str e))))

(deftest a-failure-of-the-infrastructure-ends-the-run
  (doseq [[label e] {"FHIR failing" (ex-info "broken" {:type ::c/fhir-error :status 503})
                     "FHIR failing, at the first status of a failure" (ex-info "broken" {:type ::c/fhir-error :status 500})
                     "FHIR with no answer at all" (ex-info "no answer" {:type ::c/fhir-error})
                     "FHIR unreachable" (ConnectException. "refused")
                     "a request that takes too long" (HttpTimeoutException. "late")
                     "an interruption, which a job must not swallow" (InterruptedException. "stop")}
          :let [base (server (booked "a-1" (later 3600)) (booked "a-bad" (later 7200)) (booked "a-3" (later 10800)))]]
    (testing label
      (is (identical? e (:error (trapped (failing-for base "a-bad" e)))))
      (is (= #{"a-1"} (reminded base)) "the first was sent, and the third was not tried")
      (testing "and the run after it, with FHIR back, sends the rest"
        (is (= {:appointments 3 :sent 2 :already-sent 1 :failed 0} (run-quietly base)))))))

(deftest a-search-that-fails-ends-the-run
  (let [e (ex-info "broken" {:type ::c/fhir-error :status 503})
        base (server (booked "a-1" (later 3600)))]
    (testing "at the first page"
      (is (identical? e (:error (trapped (client-with base {:search (fn [_ _ _] (throw e))})))))
      (is (empty? (reminders-of base))))
    (testing "at a later one, after the reminders of the first were sent"
      (let [fhir (client-with base {:search (fn [base resource-type params]
                                              (concat (c/search base resource-type params)
                                                      (lazy-seq (throw e))))})]
        (is (identical? e (:error (trapped fhir))))
        (is (= #{"a-1"} (reminded base)))))))

(deftest an-appointment-with-no-patient-is-a-failure-and-not-the-end
  (let [orphan (update (booked "a-orphan" (later 7200)) :participant
                       (fn [participants] (vec (remove #(str/starts-with? (get-in % [:actor :reference]) "Patient/") participants))))
        fhir (server (booked "a-1" (later 3600)) orphan (booked "a-3" (later 10800)))
        {:keys [value signals]} (trapped fhir)
        failed (filter #(= :pulse.reminders/failed (:id %)) signals)]
    (is (= {:appointments 3 :sent 2 :already-sent 0 :failed 1} value))
    (is (= #{"a-1" "a-3"} (reminded fhir)))
    (is (= "The appointment has no patient to remind" (ex-message (:error (first failed)))))
    (is (= {:appointment "a-orphan"} (ex-data (:error (first failed)))) "which says which one it was, on its own")
    (is (= "a-orphan" (get-in (first failed) [:data :appointment])))))

;; ---------------------------------------------------------------------------
;; What the run says

(deftest a-reminder-sent-is-in-the-audit-trail-and-the-log-but-its-text-is-not
  (let [fhir (server (booked "appt-ana-1" "2026-10-14T04:00:00Z"))
        {:keys [value signals]} (trapped fhir)
        audit (filter #(= :pulse.audit/write (:id %)) signals)
        sent (filter #(= :pulse.reminders/sent (:id %)) signals)
        id (:id (first (reminders-of fhir)))]
    (is (= summary-of-one value))
    (is (= 1 (count audit)))
    (is (= 1 (count sent)))
    (testing "who, what, for whom, which resource and which rule"
      (is (= {:actor actor :action :reminder/write :outcome :created :patient "Patient/pt-ana"
              :resource (str "Communication/" id) :rule "R5"}
             (:data (first audit))))
      (is (= :info (:level (first audit)))))
    (testing "the log says which appointment, for whom, and when it begins"
      (is (= {:appointment "appt-ana-1" :patient "pt-ana" :communication id :starts-at "2026-10-14T04:00:00Z"}
             (:data (first sent))))
      (is (= :info (:level (first sent)))))
    (testing "never the text of the reminder, nor the name of the doctor"
      (doseq [signal (concat audit sent)]
        (is (not (str/includes? (pr-str (:data signal)) "Reminder")))
        (is (not (str/includes? (pr-str (:data signal)) "Rivera")))))
    (testing "a reminder that exists is not written again, so it is not audited"
      (let [{:keys [signals]} (trapped fhir)]
        (is (empty? (filter #(#{:pulse.audit/write :pulse.reminders/sent :pulse.reminders/failed} (:id %)) signals)))))))

;; ---------------------------------------------------------------------------
;; The demo cohort

(def ^:private ^Instant seeded (Instant/parse "2026-10-13T08:30:00Z"))

(deftest the-cohort-is-reminded-as-time-goes-by
  ;; Ana's appointment is 20 hours after the moment of seeding, Maya's 48 and Liam's 72, all on the hour;
  ;; the requests of Sofía and Noah have no time yet
  (let [fhir (mem/memory-client (synthetic/generate (synthetic/cohort) {:end-instant seeded}))]
    (testing "at the moment of seeding, only Ana's, and with the words of the ADR"
      (is (= summary-of-one (run-quietly fhir :now seeded)))
      (is (= ["Reminder: your appointment with Elena Rivera is on Wednesday 14 October at 06:00 (Hypertension follow-up)."]
             (map text-of (reminders-of fhir)))))
    (testing "hour by hour, each one is reminded when it comes into the window, and once"
      (let [summaries (doall (for [hour (range 1 100)]
                               (run-quietly fhir :now (.plus seeded (long hour) ChronoUnit/HOURS))))
            in-order (sort-by :sent (reminders-of fhir))]
        (is (= 3 (count (reminders-of fhir))))
        (is (= 2 (reduce + (map :sent summaries))) "Maya's and Liam's, after Ana's")
        (is (= ["Appointment/appt-ana-1" "Appointment/appt-maya-1" "Appointment/appt-liam-1"]
               (map #(get-in % [:about 0 :reference]) in-order)))
        (is (= ["2026-10-13T08:30:00Z" "2026-10-14T08:30:00Z" "2026-10-15T08:30:00Z"]
               (map :sent in-order))
            "each when its appointment was exactly a day away")
        (is (= ["Reminder: your appointment with Elena Rivera is on Wednesday 14 October at 06:00 (Hypertension follow-up)."
                "Reminder: your appointment with Chidi Okafor is on Thursday 15 October at 10:00 (Heart failure review)."
                "Reminder: your appointment with Elena Rivera is on Friday 16 October at 10:00 (Diabetes review)."]
               (map text-of in-order)))))))

;; ---------------------------------------------------------------------------
;; Whatever the appointments, the reminders go to those that are confirmed and about to begin

(def ^:private all-statuses
  ["proposed" "pending" "booked" "arrived" "fulfilled" "cancelled" "noshow" "entered-in-error" "checked-in" "waitlist"])

(def ^:private gen-clinic
  "A window and appointments: each a status and the seconds from now to its start, around the edges
  of the window half of the time."
  (gen/let [window (gen/elements [1 24 48 168])
            appointments (let [edge (* window 3600)]
                           (gen/vector (gen/tuple (gen/frequency [[5 (gen/return "booked")] [5 (gen/elements all-statuses)]])
                                                  (gen/frequency [[5 (gen/elements [-1 0 1 (dec edge) edge (inc edge)])]
                                                                  [5 (gen/choose (- edge) (* 2 edge))]]))
                                       0 12))]
    {:window window :appointments appointments}))

(defspec reminders-go-to-the-confirmed-appointments-of-the-window-and-only-once 100
  (prop/for-all [{:keys [window appointments]} gen-clinic]
    (let [indexed (map-indexed (fn [i [status seconds]] {:id (str "a-" i) :status status :seconds seconds}) appointments)
          fhir (apply server (map (fn [{:keys [id status seconds]}] (booked id (later seconds) :status status)) indexed))
          expected (set (for [{:keys [id status seconds]} indexed
                              :when (and (= "booked" status) (< 0 seconds) (<= seconds (* window 3600)))]
                          id))
          n (count expected)
          first-run (run-quietly fhir :window-hours window)
          second-run (run-quietly fhir :window-hours window)]
      (and (= {:appointments n :sent n :already-sent 0 :failed 0} first-run)
           (= expected (reminded fhir))
           (= {:appointments n :sent 0 :already-sent n :failed 0} second-run)
           (= n (count (reminders-of fhir)))))))
