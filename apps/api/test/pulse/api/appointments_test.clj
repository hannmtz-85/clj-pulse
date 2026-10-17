(ns pulse.api.appointments-test
  "GET, POST and PATCH on /api/appointments against the demo cohort (ADR-0015). Dr. Rivera's
  patients are Ana, Liam, Sofía and Omar, and the appointments are Ana's and Liam's (booked) and
  Sofía's (a request); Dr. Okafor's are Maya, whose appointment is booked, and Noah, whose is a
  request. The cohort's times are relative to when it was made, and so is the clock these tests set."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [jsonista.core :as json]
            [pulse.api.test.harness :as h :refer [audit-events call with-api]]
            [pulse.fhir.appointment :as lifecycle]
            [pulse.fhir.client :as fc]
            [pulse.fhir.client.memory :as mem]
            [pulse.fhir.resource :as r]
            [pulse.fhir.schema :as schema]
            [pulse.fhir.time :as time])
  (:import (java.time Clock Duration Instant ZoneOffset)
           (java.time.format DateTimeFormatter)
           (java.time.temporal ChronoUnit)))

(def ^:private now
  "The time the tests say it is: a moment after the demo cohort was made, so that its appointments
  are ahead of it, as they were when it was seeded."
  (do (h/demo-cohort)
      (.plusSeconds (.truncatedTo (Instant/now) ChronoUnit/SECONDS) 1)))

(def ^:private clock (Clock/fixed now ZoneOffset/UTC))

(defn- days ^Duration [n] (Duration/ofDays n))
(defn- hours ^Duration [n] (Duration/ofHours n))
(defn- in [^Duration d] (str (.plus ^Instant now d)))
(defn- ago [^Duration d] (str (.minus ^Instant now d)))

(defn- appointments-in [client]
  (filter #(= "Appointment" (:resourceType %)) (mem/contents client)))

(defn- stored [client id] (fc/read-resource client "Appointment" id))

(defn- involves? [appointment reference]
  (some #(= reference (get-in % [:actor :reference])) (:participant appointment)))

(defn- when-ms
  "When `appointment` is, or is asked to be, as the tests work it out."
  [appointment]
  (some-> (or (:start appointment) (get-in appointment [:requestedPeriod 0 :start])) time/->millis))

(defn- expected-ids
  "The ids of the appointments `client` holds that satisfy `pred`, by the time they are or are asked
  to be: a way to the answer that does not go through the search the endpoint makes."
  [client pred]
  (->> (appointments-in client) (filter pred) (sort-by (juxt when-ms :id)) (mapv :id)))

(defn- list! [api who query]
  (call api :get "/api/appointments" :token (h/token-for who) :query query))

(defn- body-of [body] (if (string? body) body (json/write-value-as-string body)))

(defn- request! [api who body]
  (call api :post "/api/appointments" :token (h/token-for who) :body (body-of body)))

(defn- change! [api who id body]
  (call api :patch (str "/api/appointments/" id) :token (h/token-for who) :body (body-of body)))

(defn- items [response] (get-in response [:json :items]))
(defn- ids [response] (mapv :id (items response)))

(defn- errored-fields
  "The fields the problem names; none for one that does not, such as a body that is not JSON."
  [response]
  (let [errors (get-in response [:json :errors])]
    (if (map? errors) (set (keys errors)) #{})))

(defn- ask
  "A request that can be made, with `overrides`."
  [& {:as overrides}]
  (merge {:description "Blood pressure review" :requestedStart (in (days 2)) :requestedEnd (in (days 6))}
         overrides))

(defn- slot
  "The time to give a confirmation or a move: from `from` to `to` after now."
  [from to]
  {:start (in from) :end (in to)})

(def ^:private ^Duration half-hour (.plusMinutes Duration/ZERO 30))

(defn- a-slot [] (slot (days 3) (.plus (days 3) half-hour)))

(defn- with-spied-api
  "Like `with-api` with the demo cohort behind a spy and the clock set: `(f api calls client)`."
  [f]
  (let [calls (atom [])
        client (h/demo-cohort)]
    (with-api {:fhir (h/spy client calls) :clock clock}
      (fn [api] (f api calls client)))))

(defn- appointment-in
  "A new appointment of Ana's with Dr. Rivera, with the id `id`, in the status `status`."
  [id status]
  (let [proposed (lifecycle/proposal {:id id :patient "pt-ana" :practitioner "pr-rivera" :description "Fuzz"
                                      :requestedStart (in (days 1)) :requestedEnd (in (days 8))})
        booked #(:ok (lifecycle/change proposed (assoc (a-slot) :action :confirm) (str now) :clinician))]
    (case status
      "proposed" proposed
      "booked" (booked)
      "cancelled" (:ok (lifecycle/change (booked) {:action :cancel} (str now) :clinician)))))

;; ---------------------------------------------------------------------------
;; The list

(deftest who-may-list-what
  (let [client (h/demo-cohort)]
    (with-api {:fhir client}
      (fn [api]
        (doseq [[who involved] [[:ana "Patient/pt-ana"] [:liam "Patient/pt-liam"] [:maya "Patient/pt-maya"]
                                [:rivera "Practitioner/pr-rivera"] [:okafor "Practitioner/pr-okafor"]]]
          (testing (name who)
            (let [r (list! api who "limit=100")]
              (is (= 200 (:status r)))
              (is (seq (ids r)))
              (is (= (expected-ids client #(involves? % involved)) (ids r))))))
        (testing "a patient with no appointment has an empty list"
          (is (= [] (ids (call api :get "/api/appointments"
                               :token (h/token-for :ana :fhirUser "Patient/pt-omar"))))))
        (testing "the administrator: all of them"
          (is (= (expected-ids client (constantly true)) (ids (list! api :admin "limit=100")))))
        (testing "the rules engine, and a practitioner whose token says nothing of who they are"
          (is (= 403 (:status (list! api :scheduler ""))))
          (is (= 403 (:status (call api :get "/api/appointments" :token (h/token-for :rivera :fhirUser nil))))))
        (testing "nobody"
          (is (= 401 (:status (call api :get "/api/appointments")))))))))

(deftest an-appointment-in-a-list
  (let [client (h/demo-cohort)]
    (with-api {:fhir client}
      (fn [api]
        (let [by-id (into {} (map (juxt :id identity)) (items (list! api :admin "limit=100")))
              ana (stored client "appt-ana-1")
              sofia (stored client "appt-sofia-1")]
          (is (= {:id "appt-ana-1" :status "booked" :description "Hypertension follow-up"
                  :start (:start ana) :end (:end ana) :requestedStart nil :requestedEnd nil
                  :practitioner "Practitioner/pr-rivera" :patient "Patient/pt-ana" :reason nil}
                 (get by-id "appt-ana-1")))
          (is (= {:id "appt-sofia-1" :status "proposed" :description "COPD check-up (requested by the patient)"
                  :start nil :end nil
                  :requestedStart (get-in sofia [:requestedPeriod 0 :start])
                  :requestedEnd (get-in sofia [:requestedPeriod 0 :end])
                  :practitioner "Practitioner/pr-rivera" :patient "Patient/pt-sofia" :reason nil}
                 (get by-id "appt-sofia-1"))))))))

(deftest a-list-can-be-filtered-sorted-and-paged
  (let [client (h/demo-cohort)
        everything (expected-ids client (constantly true))]
    (with-api {:fhir client}
      (fn [api]
        (let [ids-of (fn [query] (ids (list! api :admin query)))]
          (testing "by time, a request counting from the start of the window it asks for"
            (is (= everything (ids-of "sort=start")))
            (is (= (reverse everything) (ids-of "sort=-start"))))
          (testing "by status"
            (is (= (expected-ids client #(= "booked" (:status %))) (ids-of "status=booked")))
            (is (= (expected-ids client #(= "proposed" (:status %))) (ids-of "status=proposed")))
            (is (= [] (ids-of "status=cancelled"))))
          (testing "paging, with the total of the whole result"
            (let [page (list! api :admin "limit=2&offset=1")]
              (is (= (subvec everything 1 3) (ids page)))
              (is (= {:total 5 :offset 1 :limit 2} (select-keys (:json page) [:total :offset :limit])))
              (is (= (subvec everything 4) (ids-of "limit=2&offset=4")))
              (is (= [] (ids-of "offset=50")))))
          (testing "defaults"
            (is (= {:offset 0 :limit 50} (select-keys (:json (list! api :admin "")) [:offset :limit])))))))))

(deftest bad-query-parameters-are-a-400-that-names-the-parameter
  (with-api {}
    (fn [api]
      (doseq [[query parameter] [["status=done" :status] ["status=" :status] ["sort=date" :sort]
                                 ["limit=0" :limit] ["limit=101" :limit] ["limit=many" :limit] ["offset=-1" :offset]]]
        (testing query
          (let [r (list! api :admin query)]
            (is (= 400 (:status r)))
            (is (contains? (get-in r [:json :errors]) parameter))))))))

(deftest a-list-asks-fhir-only-for-what-the-scope-covers
  (with-spied-api
    (fn [api calls _]
      (list! api :ana "")
      (is (= [[:search "Appointment" {:patient "Patient/pt-ana"}]] @calls) "a patient: their own")
      (reset! calls [])
      (list! api :rivera "status=booked")
      (is (= [[:search "Appointment" {:actor "Practitioner/pr-rivera" :status "booked"}]] @calls)
          "a practitioner: those they take part in, and the status goes to the server")
      (reset! calls [])
      (list! api :admin "")
      (is (= [[:search "Appointment" {}]] @calls) "the administrator: everything")
      (reset! calls [])
      (list! api :scheduler "")
      (call api :get "/api/appointments" :token (h/token-for :rivera :fhirUser nil))
      (is (empty? @calls) "no scope, no query"))))

(deftest a-list-when-the-fhir-server-is-down
  (with-api {:fhir h/unreachable-fhir}
    (fn [api]
      (doseq [who [:ana :rivera :admin]]
        (let [r (list! api who "")]
          (is (= 502 (:status r)) (str who))
          (is (not (str/includes? (:text r) "secret-host"))))))))

;; ---------------------------------------------------------------------------
;; Who may ask

(deftest only-a-patient-asks
  (with-api {:clock clock}
    (fn [api]
      (doseq [[who expected] [[:ana 201] [:liam 201] [:maya 201]
                              [:rivera 403] [:okafor 403] [:admin 403] [:scheduler 403]]]
        (testing (name who)
          (is (= expected (:status (request! api who (ask)))))))
      (is (= 401 (:status (call api :post "/api/appointments" :body (json/write-value-as-string (ask)))))))))

;; ---------------------------------------------------------------------------
;; What is asked

(deftest a-request-becomes-a-proposed-appointment-with-the-patients-own-doctor
  (let [client (h/demo-cohort)]
    (with-api {:fhir client :clock clock}
      (fn [api]
        (doseq [[who patient doctor] [[:ana "pt-ana" "pr-rivera"] [:maya "pt-maya" "pr-okafor"]]]
          (testing (name who)
            (let [r (request! api who (ask :description "Chest pain after a walk"))
                  id (get-in r [:json :id])
                  appointment (stored client id)]
              (is (= 201 (:status r)))
              (is (= "application/json" (h/content-type r)))
              (is (re-matches #"appt-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}" id))
              (is (= {:id id :status "proposed" :description "Chest pain after a walk"
                      :start nil :end nil :requestedStart (in (days 2)) :requestedEnd (in (days 6))
                      :practitioner (str "Practitioner/" doctor) :patient (str "Patient/" patient) :reason nil}
                     (:json r)))
              (testing "the resource"
                (is (some? appointment))
                (is (schema/valid? appointment))
                (is (= "proposed" (:status appointment)))
                (is (= [{:actor {:reference (str "Patient/" patient)} :status "accepted"}
                        {:actor {:reference (str "Practitioner/" doctor)} :status "needs-action"}]
                       (:participant appointment)))
                (is (= [{:start (in (days 2)) :end (in (days 6))}] (:requestedPeriod appointment)))
                (is (not-any? #(contains? appointment %) [:start :end :minutesDuration]))))))))))

(deftest the-patient-decides-why-and-when-and-nothing-else
  (let [client (h/demo-cohort)]
    (with-api {:fhir client :clock clock}
      (fn [api]
        (let [r (request! api :ana (assoc (ask) :id "mine" :status "booked" :start (in (days 3)) :end (in (days 4))
                                          :patient "Patient/pt-liam" :practitioner "Practitioner/pr-okafor"
                                          :participant [{:actor {:reference "Patient/pt-liam"} :status "accepted"}]
                                          :minutesDuration 30))
              appointment (stored client (get-in r [:json :id]))]
          (is (= 201 (:status r)))
          (is (nil? (stored client "mine")))
          (is (= "proposed" (:status appointment)))
          (is (= ["Patient/pt-ana" "Practitioner/pr-rivera"] (map #(get-in % [:actor :reference]) (:participant appointment))))
          (is (not-any? #(contains? appointment %) [:start :end :minutesDuration])))))))

(defn- in-zone-plus-2 [^Duration d]
  (.format ^DateTimeFormatter DateTimeFormatter/ISO_OFFSET_DATE_TIME
           (.atOffset ^Instant (.plus ^Instant now d) (ZoneOffset/ofHours 2))))

(def ^:private refused-requests
  "Requests that are not made: what is wrong with them, the body, the status and the fields named."
  [["no description" (dissoc (ask) :description) 400 #{:description}]
   ["a description that is not text" (ask :description 5) 400 #{:description}]
   ["no start" (dissoc (ask) :requestedStart) 400 #{:requestedStart}]
   ["no end" (dissoc (ask) :requestedEnd) 400 #{:requestedEnd}]
   ["a start that is not a time" (ask :requestedStart "soon") 400 #{:requestedStart}]
   ["a date, which is not a time" (ask :requestedStart "2026-10-20") 400 #{:requestedStart}]
   ["a time with no offset" (ask :requestedEnd "2026-10-20T09:00:00") 400 #{:requestedEnd}]
   ["a leap second" (ask :requestedEnd "2026-10-20T23:59:60Z") 400 #{:requestedEnd}]
   ["a year with more than four digits" (ask :requestedEnd "+999999999-01-01T00:00:00Z") 400 #{:requestedEnd}]
   ["a description of a thousand characters and one" (ask :description (apply str (repeat 1001 "x"))) 400 #{:description}]
   ["a body that is not an object" "[1, 2, 3]" 400 #{}]
   ["a body that is not JSON" "{not json" 400 #{}]
   ["a blank description" (ask :description "   ") 422 #{:description}]
   ["a description of 201 characters" (ask :description (apply str (repeat 201 "x"))) 422 #{:description}]
   ["a window that ends before it starts" (ask :requestedStart (in (days 6)) :requestedEnd (in (days 2))) 422 #{:requestedEnd}]
   ["a window that ends where it starts" (ask :requestedStart (in (days 2)) :requestedEnd (in (days 2))) 422 #{:requestedEnd}]
   ["a window that is over" (ask :requestedStart (ago (days 5)) :requestedEnd (ago (days 1))) 422 #{:requestedEnd}]
   ["a window that ends right now" (ask :requestedStart (ago (days 1)) :requestedEnd (in Duration/ZERO)) 422 #{:requestedEnd}]
   ["a window of 30 days and a second"
    (ask :requestedStart (in (days 1)) :requestedEnd (in (.plusSeconds (days 31) 1))) 422 #{:requestedEnd}]
   ["a window that starts in more than 180 days"
    (ask :requestedStart (in (days 181)) :requestedEnd (in (days 182))) 422 #{:requestedStart}]
   ["everything wrong"
    {:description "" :requestedStart (in (days 6)) :requestedEnd (in (days 2))} 422 #{:description :requestedEnd}]])

(deftest what-cannot-be-asked-is-refused-says-what-is-wrong-and-costs-no-call-to-fhir
  (with-spied-api
    (fn [api calls _]
      (doseq [[label body status fields] refused-requests]
        (testing label
          (let [r (request! api :ana body)]
            (is (= status (:status r)))
            (is (= "application/problem+json" (h/content-type r)))
            (is (= fields (errored-fields r)))
            (is (empty? (h/audit-writes r)))))
        (is (empty? @calls) "not even the patient was read")))))

(deftest what-can-just-be-asked-is-accepted-and-kept-in-utc
  (let [client (h/demo-cohort)]
    (with-api {:fhir client :clock clock}
      (fn [api]
        (doseq [[label body] [["a window of exactly 30 days" (ask :requestedStart (in (days 1)) :requestedEnd (in (days 31)))]
                              ["a window that starts in exactly 180 days" (ask :requestedStart (in (days 180)) :requestedEnd (in (days 181)))]
                              ["a window that began and is still open" (ask :requestedStart (ago (days 3)) :requestedEnd (in (days 1)))]
                              ["a window that ends a second from now" (ask :requestedStart (ago (days 1)) :requestedEnd (in (.plusSeconds Duration/ZERO 1)))]
                              ["a description of 200 characters" (ask :description (apply str (repeat 200 "x")))]]]
          (testing label
            (is (= 201 (:status (request! api :ana body))))))
        (testing "times in another offset are the same times, stored in UTC"
          (let [r (request! api :ana (ask :requestedStart (in-zone-plus-2 (days 2)) :requestedEnd (in-zone-plus-2 (days 6))))]
            (is (= 201 (:status r)))
            (is (= [(in (days 2)) (in (days 6))] ((juxt :requestedStart :requestedEnd) (:json r))))
            (is (= [{:start (in (days 2)) :end (in (days 6))}]
                   (:requestedPeriod (stored client (get-in r [:json :id])))))))))))

(deftest a-patient-with-no-doctor-and-one-that-does-not-exist
  (let [client (h/demo-cohort)
        calls (atom [])]
    (fc/put! client (r/patient {:id "pt-nodoc" :given "Nora" :family "Nodoc" :gender "female" :birth-date "1990-01-01"}))
    (with-api {:fhir (h/spy client calls) :clock clock}
      (fn [api]
        (let [as (fn [patient] (call api :post "/api/appointments"
                                     :token (h/token-for :ana :fhirUser (str "Patient/" patient))
                                     :body (json/write-value-as-string (ask))))
              before (count (appointments-in client))]
          (testing "no general practitioner: there is nobody to ask"
            (let [r (as "pt-nodoc")]
              (is (= 409 (:status r)))
              (is (= "You have no general practitioner to ask for an appointment." (get-in r [:json :detail])))))
          (testing "no such patient"
            (is (= 404 (:status (as "pt-ghost")))))
          (is (= before (count (appointments-in client))) "nothing was created")
          (is (not-any? #(= :put (first %)) @calls)))))))

(deftest asking-reads-the-patient-and-writes-the-appointment
  (with-spied-api
    (fn [api calls _]
      (let [id (get-in (request! api :ana (ask)) [:json :id])]
        (is (= [[:read "Patient" "pt-ana"] [:put "Appointment" id]] @calls))))))

(deftest every-request-gets-an-id-of-its-own
  (with-api {:clock clock}
    (fn [api]
      (is (not= (get-in (request! api :ana (ask)) [:json :id])
                (get-in (request! api :ana (ask)) [:json :id]))))))

(deftest asking-is-audited-by-reference-and-never-with-the-request
  (with-api {:clock clock}
    (fn [api]
      (let [r (request! api :ana (ask :description "Chest pain after a walk"))
            id (get-in r [:json :id])
            actor {:subject "u-ana" :username "ana.garcia@pulse.test" :roles ["patient"] :client "pulse-spa"}]
        (is (= [{:level :info
                 :data {:actor actor :action :appointment/request :outcome :allowed :scope :own
                        :method "POST" :path "/api/appointments"}}]
               (map #(select-keys % [:level :data]) (audit-events r))))
        (is (= [{:level :info
                 :data {:actor actor :action :appointment/request :outcome :created
                        :patient "Patient/pt-ana" :resource (str "Appointment/" id)
                        :method "POST" :path "/api/appointments"}}]
               (map #(select-keys % [:level :data]) (h/audit-writes r))))
        (is (not (str/includes? (pr-str (:signals r)) "Chest pain")) "nothing of the request is logged")))))

(deftest when-the-fhir-server-fails-nothing-is-asked-and-it-says-so
  (testing "the server is down"
    (with-api {:fhir h/unreachable-fhir :clock clock}
      (fn [api]
        (let [r (request! api :ana (ask))]
          (is (= 502 (:status r)))
          (is (not (str/includes? (:text r) "secret-host")))
          (is (empty? (h/audit-writes r)))))))
  (testing "it answers reads and refuses the write"
    (let [client (h/demo-cohort)]
      (with-api {:fhir (h/failing-writes client) :clock clock}
        (fn [api]
          (let [before (count (appointments-in client))
                r (request! api :ana (ask))]
            (is (= 502 (:status r)))
            (is (not (str/includes? (:text r) "secret-host")))
            (is (empty? (h/audit-writes r)) "it is not recorded as written when it was not")
            (is (= before (count (appointments-in client))))))))))

;; ---------------------------------------------------------------------------
;; Who may change an appointment

(deftest who-may-change-which-appointment
  (with-api {:clock clock}
    (fn [api]
      (doseq [[who id expected] [[:rivera "appt-ana-1" 200] [:rivera "appt-liam-1" 200] [:rivera "appt-maya-1" 403]
                                 [:okafor "appt-maya-1" 200] [:okafor "appt-ana-1" 403]
                                 [:admin "appt-ana-1" 200] [:admin "appt-maya-1" 200]
                                 [:ana "appt-ana-1" 403] [:liam "appt-ana-1" 403] [:maya "appt-maya-1" 403]
                                 [:scheduler "appt-ana-1" 403]]]
        (testing (str who " -> " id)
          (is (= expected (:status (change! api who id (assoc (a-slot) :action "reschedule")))))))
      (testing "without a token"
        (is (= 401 (:status (call api :patch "/api/appointments/appt-ana-1" :body "{}"))))))))

(deftest an-appointment-that-does-not-exist
  (with-spied-api
    (fn [api calls _]
      (testing "for whoever may look, a patient included (ADR-0027): 404"
        (doseq [who [:rivera :okafor :admin :ana]]
          (is (= 404 (:status (change! api who "appt-ghost" {:action "cancel"}))) (str who))))
      (is (= 4 (count @calls)) "one read each")
      (reset! calls [])
      (testing "for whoever has no right to change any: 403, before anything is looked up"
        (is (= 403 (:status (change! api :scheduler "appt-ghost" {:action "cancel"}))))
        (is (empty? @calls)))
      (testing "an id that cannot be a FHIR id cannot exist, and never reaches the server"
        (doseq [id ["a%20b" "appt-ana-1%2F.." "..%2F..%2FPatient" "%24everything" (apply str (repeat 65 "a"))]]
          (is (= 404 (:status (change! api :admin id {:action "cancel"}))) id))
        (is (empty? @calls))))))

(deftest the-panel-of-an-appointment-is-the-appointments-the-doctor-takes-part-in
  (let [client (h/demo-cohort)]
    ;; Ana's appointment with Dr. Rivera, which Dr. Okafor takes part in as well
    (fc/put! client (-> (stored client "appt-ana-1")
                        (assoc :id "appt-shared")
                        (update :participant conj {:actor {:reference "Practitioner/pr-okafor"} :status "accepted"})))
    (with-api {:fhir client :clock clock}
      (fn [api]
        (testing "both doctors see it, and may change it, though Ana is only Dr. Rivera's patient"
          (is (some #{"appt-shared"} (ids (list! api :okafor ""))))
          (is (some #{"appt-shared"} (ids (list! api :rivera ""))))
          (is (= 200 (:status (change! api :okafor "appt-shared" (assoc (a-slot) :action "reschedule")))))
          (is (= 200 (:status (change! api :rivera "appt-shared" (assoc (a-slot) :action "reschedule"))))))
        (testing "and Ana's other appointment is still Dr. Rivera's alone"
          (is (not (some #{"appt-ana-1"} (ids (list! api :okafor "")))))
          (is (= 403 (:status (change! api :okafor "appt-ana-1" (assoc (a-slot) :action "reschedule"))))))))))

;; ---------------------------------------------------------------------------
;; What a clinician does

(deftest a-request-is-confirmed-moved-and-cancelled
  (let [client (h/demo-cohort)
        id "appt-sofia-1"
        asked (get-in (stored client id) [:requestedPeriod 0])]
    (with-api {:fhir client :clock clock}
      (fn [api]
        (testing "confirm: the request gets its time"
          (let [r (change! api :rivera id {:action "confirm" :start (in (days 4)) :end (in (.plusMinutes (days 4) 30))})
                appointment (stored client id)]
            (is (= 200 (:status r)))
            (is (= "application/json" (h/content-type r)))
            (is (= {:id id :status "booked" :start (in (days 4)) :end (in (.plusMinutes (days 4) 30))
                    :requestedStart (:start asked) :requestedEnd (:end asked)
                    :practitioner "Practitioner/pr-rivera" :patient "Patient/pt-sofia" :reason nil}
                   (select-keys (:json r) [:id :status :start :end :requestedStart :requestedEnd :practitioner :patient :reason])))
            (is (= "booked" (:status appointment)))
            (is (= 30 (:minutesDuration appointment)))
            (is (= ["accepted" "accepted"] (map :status (:participant appointment))) "the doctor has accepted")
            (is (= [asked] (:requestedPeriod appointment)) "what she asked for is still there")
            (is (schema/valid? appointment))))
        (testing "reschedule: the same appointment, at another time"
          (let [r (change! api :rivera id {:action "reschedule" :start (in (days 5)) :end (in (.plusHours (days 5) 1))})
                appointment (stored client id)]
            (is (= 200 (:status r)))
            (is (= "booked" (:status appointment)))
            (is (= [(in (days 5)) (in (.plusHours (days 5) 1)) 60] [(:start appointment) (:end appointment) (:minutesDuration appointment)]))
            (is (schema/valid? appointment))))
        (testing "cancel, with a reason the patient can read"
          (let [r (change! api :rivera id {:action "cancel" :reason "The doctor is away that week"})
                appointment (stored client id)]
            (is (= 200 (:status r)))
            (is (= "cancelled" (get-in r [:json :status])))
            (is (= "The doctor is away that week" (get-in r [:json :reason])))
            (is (= {:text "The doctor is away that week"} (:cancelationReason appointment)))
            (is (= (in (days 5)) (:start appointment)) "its time stays on record")
            (is (schema/valid? appointment))))
        (testing "and that is the end of it: whatever is asked, it is a 409"
          (doseq [body [{:action "confirm" :start (in (days 6)) :end (in (.plusHours (days 6) 1))}
                        {:action "reschedule" :start (in (days 6)) :end (in (.plusHours (days 6) 1))}
                        {:action "cancel"}]]
            (let [r (change! api :rivera id body)]
              (is (= 409 (:status r)) (pr-str body))
              (is (= "application/problem+json" (h/content-type r))))))))))

(def ^:private allowed-by-status
  "What each status allows, as the ADR says it, apart from the code."
  {"proposed" #{"confirm" "cancel"} "booked" #{"reschedule" "cancel"} "cancelled" #{}})

(defn- command [action]
  (case action
    "cancel" {:action "cancel"}
    (assoc (a-slot) :action action)))

(deftest a-status-that-does-not-allow-the-action-is-a-409-and-changes-nothing
  (doseq [[status id] [["proposed" "appt-sofia-1"] ["booked" "appt-ana-1"] ["cancelled" "appt-liam-1"]]
          action ["confirm" "reschedule" "cancel"]]
    (let [client (h/demo-cohort)]
      (when (= "cancelled" status)
        (fc/put! client (assoc (stored client id) :status "cancelled")))
      (with-api {:fhir client :clock clock}
        (fn [api]
          (testing (str status " " action)
            (let [before (stored client id)
                  r (change! api :admin id (command action))
                  allowed? (contains? (allowed-by-status status) action)]
              (is (= (if allowed? 200 409) (:status r)))
              (if allowed?
                (is (not= before (stored client id)))
                (do (is (= before (stored client id)) "nothing was written")
                    (is (str/includes? (get-in r [:json :detail]) (str "this one is " status)))))))))))
  (testing "the message says what the status allows"
    (with-api {:clock clock}
      (fn [api]
        (is (= "Only a proposed appointment can be confirmed; this one is booked."
               (get-in (change! api :rivera "appt-ana-1" (command "confirm")) [:json :detail])))
        (is (= "Only a booked appointment can be rescheduled; this one is proposed."
               (get-in (change! api :rivera "appt-sofia-1" (command "reschedule")) [:json :detail])))))))

(def ^:private unbookable
  "Times that cannot be booked, with the fields the answer names."
  [["a start in the past" (slot (.negated (Duration/ofMinutes 1)) (Duration/ofMinutes 30)) #{:start}]
   ["a start more than a year ahead" (slot (days 366) (.plusHours (days 366) 1)) #{:start}]
   ["an end that is the start" (slot (days 3) (days 3)) #{:end}]
   ["an end before the start" (slot (days 3) (.minusHours (days 3) 1)) #{:end}]
   ["a slot of 4 minutes" (slot (days 3) (.plusMinutes (days 3) 4)) #{:end}]
   ["a slot of 4 hours and a second" (slot (days 3) (.plusSeconds (.plusHours (days 3) 4) 1)) #{:end}]])

(def ^:private bookable
  "Times that can just be booked."
  [["a start that is now" (slot Duration/ZERO (Duration/ofMinutes 5))]
   ["a start a year ahead" (slot (days 365) (.plusHours (days 365) 1))]
   ["a slot of 5 minutes" (slot (days 3) (.plusMinutes (days 3) 5))]
   ["a slot of 4 hours" (slot (days 3) (.plusHours (days 3) 4))]])

(deftest a-time-that-cannot-be-booked-is-a-422-and-one-that-just-can-is-accepted
  (with-spied-api
    (fn [api calls client]
      (doseq [action ["confirm" "reschedule"]
              :let [id (if (= "confirm" action) "appt-sofia-1" "appt-ana-1")]]
        (testing action
          (doseq [[label body fields] unbookable]
            (testing label
              (let [r (change! api :rivera id (assoc body :action action))]
                (is (= 422 (:status r)))
                (is (= fields (errored-fields r)))
                (is (= "application/problem+json" (h/content-type r))))))
          (is (not-any? #(= :put (first %)) @calls) "nothing was written")
          (doseq [[label body] bookable]
            (testing label
              (let [fresh (stored client id)
                    r (change! api :rivera id (assoc body :action "reschedule"))]
                (is (contains? #{200 409} (:status r)) label)
                (fc/put! client fresh)))))))))

(deftest times-in-another-offset-are-the-same-times-kept-in-utc
  (let [client (h/demo-cohort)]
    (with-api {:fhir client :clock clock}
      (fn [api]
        (let [r (change! api :rivera "appt-ana-1" {:action "reschedule"
                                                   :start (in-zone-plus-2 (days 3))
                                                   :end (in-zone-plus-2 (.plusMinutes (days 3) 45))})]
          (is (= 200 (:status r)))
          (is (= [(in (days 3)) (in (.plusMinutes (days 3) 45))] ((juxt :start :end) (:json r))))
          (is (= 45 (:minutesDuration (stored client "appt-ana-1")))))))))

(def ^:private bad-commands
  "Commands that are not understood: what is wrong, the body, the status and the fields named."
  [["no action" {} 400 #{:action}]
   ["an action that does not exist" {:action "delete"} 400 #{:action}]
   ["the action in another case" {:action "Confirm"} 400 #{:action}]
   ["a confirmation with no time" {:action "confirm"} 400 #{:start :end}]
   ["a move with no end" {:action "reschedule" :start (in (days 3))} 400 #{:end}]
   ["times that are not times" {:action "confirm" :start "tomorrow" :end "later"} 400 #{:start :end}]
   ["a date, which is not a time" {:action "reschedule" :start "2026-10-20" :end (in (days 3))} 400 #{:start}]
   ["a reason that is not text" {:action "cancel" :reason 5} 400 #{:reason}]
   ["a reason of a thousand characters and one" {:action "cancel" :reason (apply str (repeat 1001 "x"))} 400 #{:reason}]
   ["a body that is not an object" "[1, 2, 3]" 400 #{}]
   ["a body that is not JSON" "{not json" 400 #{}]
   ["a blank reason" {:action "cancel" :reason "  "} 422 #{:reason}]
   ["a reason of 201 characters" {:action "cancel" :reason (apply str (repeat 201 "x"))} 422 #{:reason}]])

(deftest a-command-that-is-not-understood-is-refused-and-says-what-is-wrong
  (with-spied-api
    (fn [api calls _]
      (doseq [[label body status fields] bad-commands]
        (testing label
          (let [r (change! api :rivera "appt-ana-1" body)]
            (is (= status (:status r)))
            (is (= "application/problem+json" (h/content-type r)))
            (is (= fields (errored-fields r)))
            (is (empty? (h/audit-writes r))))))
      (is (not-any? #(= :put (first %)) @calls) "nothing was written"))))

(deftest what-the-client-must-not-decide-is-ignored
  (let [client (h/demo-cohort)]
    (with-api {:fhir client :clock clock}
      (fn [api]
        (let [r (change! api :rivera "appt-ana-1" (assoc (a-slot) :action "reschedule"
                                                         :status "cancelled" :id "another" :patient "Patient/pt-liam"
                                                         :participant [] :description "changed"))
              appointment (stored client "appt-ana-1")]
          (is (= 200 (:status r)))
          (is (= "booked" (:status appointment)))
          (is (= "Hypertension follow-up" (:description appointment)))
          (is (= "Patient/pt-ana" (get-in appointment [:participant 0 :actor :reference])))
          (is (nil? (stored client "another"))))))))

(deftest changing-reads-the-appointment-once
  (with-spied-api
    (fn [api calls _]
      (testing "the decision and the change are about the same read"
        (change! api :rivera "appt-ana-1" (assoc (a-slot) :action "reschedule"))
        (is (= [[:read "Appointment" "appt-ana-1"] [:put "Appointment" "appt-ana-1"]] @calls))
        (reset! calls [])
        (change! api :admin "appt-sofia-1" (assoc (a-slot) :action "confirm"))
        (is (= [[:read "Appointment" "appt-sofia-1"] [:put "Appointment" "appt-sofia-1"]] @calls)))
      (reset! calls [])
      (testing "an appointment of another doctor: read to decide, and nothing else"
        (change! api :okafor "appt-ana-1" (assoc (a-slot) :action "reschedule"))
        (is (= [[:read "Appointment" "appt-ana-1"]] @calls)))
      (reset! calls [])
      (testing "an answer that is a 409 or a 422 writes nothing"
        (change! api :rivera "appt-ana-1" (command "confirm"))
        (change! api :rivera "appt-ana-1" (assoc (slot (days 366) (.plusHours (days 366) 1)) :action "reschedule"))
        (is (= [[:read "Appointment" "appt-ana-1"] [:read "Appointment" "appt-ana-1"]] @calls))))))

;; ---------------------------------------------------------------------------
;; The audit trail of a change

(deftest changing-is-audited-with-the-verb-and-never-with-what-was-said
  (with-api {:clock clock}
    (fn [api]
      (let [actor {:subject "u-rivera" :username "dr.rivera@pulse.test" :roles ["practitioner"] :client "pulse-spa"}
            path "/api/appointments/appt-sofia-1"
            confirmed (change! api :rivera "appt-sofia-1" (assoc (a-slot) :action "confirm"))
            cancelled (change! api :rivera "appt-sofia-1" {:action "cancel" :reason "The doctor is away that week"})]
        (is (= [{:level :info
                 :data {:actor actor :action :appointment/manage :outcome :allowed :scope :panel
                        :patient "Patient/pt-sofia" :method "PATCH" :path path}}]
               (map #(select-keys % [:level :data]) (audit-events confirmed))))
        (is (= [{:level :info
                 :data {:actor actor :action :appointment/manage :outcome :updated :change :confirm
                        :patient "Patient/pt-sofia" :resource "Appointment/appt-sofia-1"
                        :method "PATCH" :path path}}]
               (map #(select-keys % [:level :data]) (h/audit-writes confirmed))))
        (is (= [:cancel] (map #(get-in % [:data :change]) (h/audit-writes cancelled))))
        (is (not (str/includes? (pr-str (:signals cancelled)) "away")) "the reason is not logged")))))

(deftest a-refusal-is-a-decision-and-never-a-write
  (with-api {:clock clock}
    (fn [api]
      (let [denied (change! api :okafor "appt-ana-1" (command "cancel"))
            conflict (change! api :rivera "appt-ana-1" (command "confirm"))
            missing (change! api :rivera "appt-ghost" (command "cancel"))]
        (is (= [:denied] (map #(get-in % [:data :outcome]) (audit-events denied))))
        (is (= [:allowed] (map #(get-in % [:data :outcome]) (audit-events conflict))))
        (is (= [:not-found] (map #(get-in % [:data :outcome]) (audit-events missing))))
        (is (empty? (mapcat h/audit-writes [denied conflict missing])))))))

(deftest when-the-fhir-server-fails-the-appointment-is-not-changed-and-it-says-so
  (testing "the server is down"
    (with-api {:fhir h/unreachable-fhir :clock clock}
      (fn [api]
        (let [r (change! api :rivera "appt-ana-1" (command "cancel"))]
          (is (= 502 (:status r)))
          (is (not (str/includes? (:text r) "secret-host")))))))
  (testing "it answers reads and refuses the write"
    (let [client (h/demo-cohort)
          before (stored client "appt-ana-1")]
      (with-api {:fhir (h/failing-writes client) :clock clock}
        (fn [api]
          (let [r (change! api :rivera "appt-ana-1" (command "cancel"))]
            (is (= 502 (:status r)))
            (is (not (str/includes? (:text r) "secret-host")))
            (is (empty? (h/audit-writes r)))
            (is (= before (stored client "appt-ana-1")))))))))

;; ---------------------------------------------------------------------------
;; A patient cancels their own (ADR-0027)

(def ^:private denied-text "You are not allowed to do this.")

(defn- begun-at
  "A copy of Ana's booked appointment with Dr. Rivera that begins `ago` before the time of the tests
  (after it, if negative) and lasts half an hour from then."
  [client id ^Duration ago-by]
  (let [start (.minus ^Instant now ago-by)]
    (assoc (stored client "appt-ana-1") :id id :start (str start) :end (str (.plus start half-hour)))))

(deftest a-patient-withdraws-a-request-and-cancels-a-booked-appointment
  (let [client (h/demo-cohort)]
    (with-api {:fhir client :clock clock}
      (fn [api]
        (testing "a request is withdrawn, with a reason that the doctor can read"
          (let [id (get-in (request! api :ana (ask)) [:json :id])
                r (change! api :ana id {:action "cancel" :reason "I found a time at another clinic"})
                appointment (stored client id)]
            (is (= 200 (:status r)))
            (is (= "application/json" (h/content-type r)))
            (is (= {:id id :status "cancelled" :patient "Patient/pt-ana" :practitioner "Practitioner/pr-rivera"
                    :reason "I found a time at another clinic"}
                   (select-keys (:json r) [:id :status :patient :practitioner :reason])))
            (is (= "cancelled" (:status appointment)))
            (is (= {:text "I found a time at another clinic"} (:cancelationReason appointment)))
            (is (schema/valid? appointment))
            (testing "and the doctor sees it cancelled, with the reason, and no longer as a request"
              (let [seen (first (filter #(= id (:id %)) (items (list! api :rivera "status=cancelled"))))]
                (is (= "cancelled" (:status seen)))
                (is (= "I found a time at another clinic" (:reason seen))))
              (is (empty? (filter #(= id (:id %)) (items (list! api :rivera "status=proposed"))))))))
        (testing "a booked appointment that has not begun is cancelled, with no reason if she gives none"
          (let [r (change! api :ana "appt-ana-1" {:action "cancel"})
                appointment (stored client "appt-ana-1")]
            (is (= 200 (:status r)))
            (is (= "cancelled" (:status appointment)))
            (is (not (contains? appointment :cancelationReason)))
            (is (some? (:start appointment)) "its time stays on record")
            (is (schema/valid? appointment))))
        (testing "and that is the end of it: she cannot cancel it again"
          (let [r (change! api :ana "appt-ana-1" {:action "cancel"})]
            (is (= 409 (:status r)))
            (is (str/includes? (get-in r [:json :detail]) "this one is cancelled"))))))))

(deftest a-patient-cannot-cancel-an-appointment-that-has-begun-but-the-clinic-can
  (let [client (h/demo-cohort)]
    (doseq [[label ago-by] [["an hour ago" (hours 1)] ["a second ago" (Duration/ofSeconds 1)] ["this very moment" Duration/ZERO]]]
      (fc/put! client (begun-at client "appt-begun" ago-by))
      (with-api {:fhir client :clock clock}
        (fn [api]
          (testing label
            (let [before (stored client "appt-begun")
                  r (change! api :ana "appt-begun" {:action "cancel"})]
              (is (= 409 (:status r)))
              (is (= "This appointment has already begun, so only the clinic can cancel it." (get-in r [:json :detail])))
              (is (= "application/problem+json" (h/content-type r)))
              (is (= before (stored client "appt-begun")) "nothing was written")
              (is (empty? (h/audit-writes r))))
            (testing "the clinic can"
              (is (= 200 (:status (change! api :rivera "appt-begun" {:action "cancel"}))))
              (is (= "cancelled" (:status (stored client "appt-begun")))))))))
    (testing "one that is about to begin can still be cancelled"
      (fc/put! client (begun-at client "appt-soon" (.negated (Duration/ofSeconds 1))))
      (with-api {:fhir client :clock clock}
        (fn [api]
          (is (= 200 (:status (change! api :ana "appt-soon" {:action "cancel"})))))))))

(deftest a-patient-may-only-cancel-and-only-their-own
  (let [client (h/demo-cohort)]
    (with-api {:fhir client :clock clock}
      (fn [api]
        (testing "someone else's appointment: a 403, whatever they ask"
          (doseq [who [:liam :maya]
                  body [{:action "cancel"} {:action "cancel" :reason "mine"} (assoc (a-slot) :action "reschedule")]
                  :let [before (stored client "appt-ana-1")
                        r (change! api who "appt-ana-1" body)]]
            (is (= 403 (:status r)) (str who " " (pr-str body)))
            (is (= before (stored client "appt-ana-1")) "nothing was written")))
        (testing "her own, but to confirm or to move it: a 403 that says nothing of why"
          (let [proposed-id (get-in (request! api :ana (ask)) [:json :id])]
            (doseq [[id action] [["appt-ana-1" "reschedule"] ["appt-ana-1" "confirm"]
                                 [proposed-id "confirm"] [proposed-id "reschedule"]]
                    :let [before (stored client id)
                          r (change! api :ana id (assoc (a-slot) :action action))]]
              (testing (str id " " action)
                (is (= 403 (:status r)))
                (is (= denied-text (get-in r [:json :detail])))
                (is (= "application/problem+json" (h/content-type r)))
                (is (= before (stored client id)) "nothing was written")))))
        (testing "the shape of the body is judged first, so a command that is not understood is a 400"
          (is (= 400 (:status (change! api :ana "appt-ana-1" {:action "confirm"}))))
          (is (= 400 (:status (change! api :ana "appt-ana-1" {:action "delete"})))))
        (testing "a reason that cannot be right is a 422, as it is for a clinician"
          (doseq [reason ["  " (apply str (repeat 201 "x"))]
                  :let [r (change! api :ana "appt-ana-1" {:action "cancel" :reason reason})]]
            (is (= 422 (:status r)))
            (is (= #{:reason} (errored-fields r)))
            (is (= "booked" (:status (stored client "appt-ana-1"))))))))))

(deftest a-patients-cancellation-is-audited-with-the-verb-and-never-the-reason
  (with-api {:clock clock}
    (fn [api]
      (let [actor {:subject "u-ana" :username "ana.garcia@pulse.test" :roles ["patient"] :client "pulse-spa"}
            path "/api/appointments/appt-ana-1"
            r (change! api :ana "appt-ana-1" {:action "cancel" :reason "A private reason"})]
        (is (= [{:level :info
                 :data {:actor actor :action :appointment/manage :outcome :allowed :scope :own
                        :patient "Patient/pt-ana" :method "PATCH" :path path}}]
               (map #(select-keys % [:level :data]) (audit-events r))))
        (is (= [{:level :info
                 :data {:actor actor :action :appointment/manage :outcome :updated :change :cancel
                        :patient "Patient/pt-ana" :resource "Appointment/appt-ana-1"
                        :method "PATCH" :path path}}]
               (map #(select-keys % [:level :data]) (h/audit-writes r))))
        (is (not (str/includes? (pr-str (:signals r)) "private")) "the reason is not logged")))))

(deftest a-patient-who-asks-for-more-than-cancelling-is-refused-and-the-refusal-is-audited
  (with-api {:clock clock}
    (fn [api]
      (let [actor {:subject "u-ana" :username "ana.garcia@pulse.test" :roles ["patient"] :client "pulse-spa"}
            r (change! api :ana "appt-ana-1" (assoc (a-slot) :action "reschedule"))
            [allowed denied :as events] (map #(select-keys % [:level :data]) (audit-events r))]
        (is (= 403 (:status r)))
        (testing "the route let her in, and then the command was refused"
          (is (= 2 (count events)))
          (is (= [:info :allowed] [(:level allowed) (get-in allowed [:data :outcome])]))
          (is (= {:level :warn
                  :data {:actor actor :action :appointment/manage :outcome :denied :scope :own
                         :reason :action-not-allowed-for-actor :change :reschedule
                         :patient "Patient/pt-ana" :method "PATCH" :path "/api/appointments/appt-ana-1"}}
                 denied)))
        (is (empty? (h/audit-writes r)))))))

(deftest a-patient-changes-an-appointment-with-one-read-and-one-write
  (with-spied-api
    (fn [api calls _]
      (change! api :ana "appt-ana-1" {:action "cancel"})
      (is (= [[:read "Appointment" "appt-ana-1"] [:put "Appointment" "appt-ana-1"]] @calls))
      (reset! calls [])
      (testing "a command that is refused reads and writes nothing more"
        (change! api :ana "appt-sofia-1" {:action "cancel"})
        (change! api :ana "appt-liam-1" (assoc (a-slot) :action "reschedule"))
        (is (= [[:read "Appointment" "appt-sofia-1"] [:read "Appointment" "appt-liam-1"]] @calls))))))

(defn- appointment-at
  "A new appointment of Ana's with Dr. Rivera, with the id `id` and the status `status`; a booked one
  begins `hours-ahead` hours from the time of the tests (before it, if negative)."
  [id status hours-ahead]
  (let [booked (assoc (appointment-in id "booked")
                      :start (in (hours hours-ahead))
                      :end (in (.plusMinutes (hours hours-ahead) 30)))]
    (case status
      "proposed" (appointment-in id "proposed")
      "booked" booked
      "cancelled" (assoc booked :status "cancelled"))))

(deftest whatever-a-patient-sends-only-cancelling-what-has-not-begun-changes-anything
  (let [client (h/demo-cohort)
        counter (atom 0)]
    (with-api {:fhir client :clock clock}
      (fn [api]
        (let [token (h/token-for :ana)
              result (tc/quick-check
                      300
                      (prop/for-all [status (gen/frequency [[4 (gen/return "proposed")] [4 (gen/return "booked")] [1 (gen/return "cancelled")]])
                                     hours-ahead (gen/choose -48 48)
                                     action (gen/elements ["confirm" "reschedule" "cancel"])
                                     reason (gen/elements [nil "away" "  " (apply str (repeat 201 "x"))])]
                        (let [id (str "appt-patient-fuzz-" (swap! counter inc))
                              _ (fc/put! client (appointment-at id status hours-ahead))
                              before (stored client id)
                              body (cond-> {"action" action}
                                     (not= "cancel" action) (assoc "start" (in (days 3)) "end" (in (.plusMinutes (days 3) 30)))
                                     (and (= "cancel" action) (some? reason)) (assoc "reason" reason))
                              r (call api :patch (str "/api/appointments/" id) :token token
                                      :body (json/write-value-as-string body))
                              after (stored client id)
                              begun? (and (= "booked" status) (<= hours-ahead 0))
                              expected (cond (not= "cancel" action) 403
                                             (= "cancelled" status) 409
                                             begun? 409
                                             (and (some? reason) (or (str/blank? reason) (> (count reason) 200))) 422
                                             :else 200)]
                          (and (= expected (:status r))
                               (if (= 200 expected)
                                 (and (= "cancelled" (:status after))
                                      (= (dissoc before :status :cancelationReason) (dissoc after :status :cancelationReason)))
                                 (= before after))
                               (schema/valid? after)))))]
          (is (:pass? result) (pr-str (select-keys result [:fail :shrunk :seed]))))))))

;; ---------------------------------------------------------------------------
;; Asked, answered and read back

(deftest a-patient-asks-and-the-doctor-answers
  (let [client (h/demo-cohort)]
    (with-api {:fhir client :clock clock}
      (fn [api]
        (let [id (get-in (request! api :ana (ask :description "Follow-up of my new medication")) [:json :id])
              mine (fn [who query] (filter #(= id (:id %)) (items (list! api who query))))
              confirmation {:action "confirm" :start (in (days 3)) :end (in (.plusMinutes (days 3) 20))}]
          (testing "the doctor sees the request, and the other doctor does not"
            (is (= ["proposed"] (map :status (mine :rivera "status=proposed"))))
            (is (empty? (mine :okafor ""))))
          (testing "the doctor confirms it, and the patient sees it booked in the list and in the summary"
            (is (= 200 (:status (change! api :rivera id confirmation))))
            (is (= ["booked"] (map :status (mine :ana ""))))
            (let [summary (:json (call api :get "/api/patients/pt-ana/summary" :token (h/token-for :ana)))
                  appointment (first (filter #(= id (:id %)) (:appointments summary)))]
              (is (= "booked" (:status appointment)))
              (is (= (in (days 3)) (:start appointment)))
              (is (contains? appointment :reason) "the summary says why an appointment was cancelled, too")))
          (testing "the doctor cancels it, and the patient can read why"
            (change! api :rivera id {:action "cancel" :reason "The doctor is away that week"})
            (let [appointment (first (mine :ana "status=cancelled"))]
              (is (= "cancelled" (:status appointment)))
              (is (= "The doctor is away that week" (:reason appointment)))))
          (testing "and the administrator sees all of it"
            (is (= ["cancelled"] (map :status (mine :admin ""))))))))))

;; ---------------------------------------------------------------------------
;; Whatever is sent

(def ^:private gen-junk
  (gen/one-of [gen/small-integer
               (gen/double* {:NaN? false :infinite? false})
               gen/string-alphanumeric
               gen/boolean
               (gen/return nil)
               (gen/elements ["confirm" "reschedule" "cancel" "" "T" "2026-10-14T23:59:60Z" "2026-10-14"
                              "+999999999-01-01T00:00:00Z" "0000-01-01T00:00:00Z"])
               (gen/vector gen/small-integer 0 3)
               (gen/map gen/keyword gen/small-integer {:max-elements 2})]))

(def ^:private gen-asked
  "A request that could be made: a description, and a window that is usually fine and sometimes not."
  (gen/let [description (gen/frequency [[8 (gen/fmap #(apply str (repeat (inc %) "x")) (gen/choose 0 60))]
                                        [1 (gen/return "")]
                                        [1 (gen/return (apply str (repeat 250 "x")))]])
            start-hours (gen/choose -72 (* 24 200))
            length-hours (gen/choose 0 (* 24 40))]
    {"description" description
     "requestedStart" (in (hours start-hours))
     "requestedEnd" (in (hours (+ start-hours length-hours)))}))

(def ^:private gen-mutation
  (let [fields ["description" "requestedStart" "requestedEnd" "status" "id" "patient"]]
    (gen/frequency [[4 (gen/return identity)]
                    [1 (gen/fmap (fn [field] #(dissoc % field)) (gen/elements fields))]
                    [2 (gen/fmap (fn [[field v]] #(assoc % field v)) (gen/tuple (gen/elements fields) gen-junk))]])))

(def ^:private gen-asked-body
  (gen/let [body gen-asked
            mutations (gen/vector gen-mutation 0 2)]
    (reduce (fn [body mutate] (mutate body)) body mutations)))

(deftest whatever-is-asked-the-answer-is-a-proposed-appointment-or-a-problem-and-never-a-failure
  (let [client (h/demo-cohort)]
    (with-api {:fhir client :clock clock}
      (fn [api]
        (let [token (h/token-for :ana)
              result (tc/quick-check
                      300
                      (prop/for-all [body gen-asked-body]
                        (let [r (call api :post "/api/appointments" :token token :body (json/write-value-as-string body))]
                          (case (:status r)
                            201 (let [appointment (stored client (get-in r [:json :id]))]
                                  (and (schema/valid? appointment)
                                       (= "proposed" (:status appointment))
                                       (= "Patient/pt-ana" (get-in appointment [:participant 0 :actor :reference]))
                                       (nil? (lifecycle/request-errors {:description (:description appointment)
                                                                        :requestedStart (get-in appointment [:requestedPeriod 0 :start])
                                                                        :requestedEnd (get-in appointment [:requestedPeriod 0 :end])}
                                                                       (str now)))))
                            (400 422) (= "application/problem+json" (h/content-type r))
                            false))))]
          (is (:pass? result) (pr-str (select-keys result [:fail :shrunk :seed]))))))))

(def ^:private gen-command-body
  (gen/let [action (gen/frequency [[10 (gen/elements ["confirm" "reschedule" "cancel"])]
                                   [1 gen-junk]])
            start-hours (gen/choose -2 (* 24 400))
            length-minutes (gen/choose 0 (* 6 60))
            reason (gen/frequency [[3 (gen/return nil)] [2 (gen/return "away")] [1 gen-junk]])
            junk (gen/frequency [[8 (gen/return nil)] [1 gen-junk]])]
    (cond-> {"action" action
             "start" (in (hours start-hours))
             "end" (in (.plusMinutes (hours start-hours) length-minutes))}
      (some? reason) (assoc "reason" reason)
      (some? junk) (assoc "start" junk))))

(deftest whatever-a-clinician-sends-the-appointment-is-changed-validly-or-not-at-all
  (let [client (h/demo-cohort)
        counter (atom 0)]
    (with-api {:fhir client :clock clock}
      (fn [api]
        (let [token (h/token-for :rivera)
              result (tc/quick-check
                      400
                      (prop/for-all [status (gen/frequency [[4 (gen/return "proposed")] [4 (gen/return "booked")] [1 (gen/return "cancelled")]])
                                     body gen-command-body]
                        ;; each command meets a new appointment, so that every status is met all the time
                        (let [id (str "appt-fuzz-" (swap! counter inc))
                              _ (fc/put! client (appointment-in id status))
                              before (stored client id)
                              r (call api :patch (str "/api/appointments/" id) :token token
                                      :body (json/write-value-as-string body))
                              after (stored client id)]
                          (and (contains? #{200 400 409 422} (:status r))
                               (or (= 200 (:status r)) (= before after))
                               ;; a cancelled appointment is final: no answer is a 200 (the shape of
                               ;; the body is judged before the status, so it need not be a 409)
                               (or (not= "cancelled" (:status before)) (not= 200 (:status r)))
                               (or (not= 200 (:status r))
                                   (= (if (= "cancel" (get body "action")) "cancelled" "booked") (:status after)))
                               (schema/valid? after)
                               (contains? #{"proposed" "booked" "cancelled"} (:status after))
                               (or (not= "booked" (:status after))
                                   (< (time/->millis (:start after)) (time/->millis (:end after))))
                               (or (= 200 (:status r)) (= "application/problem+json" (h/content-type r)))))))]
          (is (:pass? result) (pr-str (select-keys result [:fail :shrunk :seed]))))))))
