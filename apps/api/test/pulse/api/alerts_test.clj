(ns pulse.api.alerts-test
  "GET /api/alerts and POST /api/alerts/{id}/ack against the demo cohort (ADR-0016). The cohort has
  no alerts, because the rules engine raises them, so these tests raise their own: Dr. Rivera's
  patients are Ana, Liam, Sofía and Omar, and Dr. Okafor's are Maya and Noah."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [pulse.api.test.harness :as h :refer [audit-events call with-api]]
            [pulse.fhir.alert :as lifecycle]
            [pulse.fhir.client :as fc]
            [pulse.fhir.client.memory :as mem]
            [pulse.fhir.resource :as r]
            [pulse.fhir.schema :as schema]
            [pulse.fhir.time :as time])
  (:import (java.time Clock Duration Instant ZoneOffset)
           (java.time.temporal ChronoUnit)))

(def ^:private now
  "The time the tests say it is: a moment after the demo cohort was made."
  (do (h/demo-cohort)
      (.plusSeconds (.truncatedTo (Instant/now) ChronoUnit/SECONDS) 1)))

(def ^:private clock (Clock/fixed now ZoneOffset/UTC))

(defn- hours [n] (Duration/ofHours n))
(defn- days [n] (Duration/ofDays n))
(defn- ago [^Duration d] (str (.minus ^Instant now d)))

(defn- raised
  "An alert about `patient`, raised `d` ago."
  [id patient text ^Duration d]
  (r/flag {:id id :patient patient :text text :start (ago d)}))

(defn- acknowledged [flag ^Duration d]
  (:ok (lifecycle/acknowledge flag (ago d))))

(defn- with-alerts
  "The demo cohort with some alerts raised: Ana's, one old and acknowledged and one new, Liam's, Sofía's
  and Maya's, and one that was entered in error."
  []
  (let [client (h/demo-cohort)]
    (doseq [flag [(raised "flag-ana-new" "pt-ana" "BP stage 2" (hours 3))
                  (acknowledged (raised "flag-ana-old" "pt-ana" "BP stage 2" (days 5)) (days 4))
                  (raised "flag-liam" "pt-liam" "Glucose out of range" (hours 1))
                  (raised "flag-sofia" "pt-sofia" "Low SpO2" (days 1))
                  (raised "flag-maya" "pt-maya" "Rapid weight gain" (hours 2))
                  (assoc (raised "flag-error" "pt-omar" "Written by mistake" (hours 5)) :status "entered-in-error")]]
      (fc/put! client flag))
    client))

(defn- list! [api who query]
  (call api :get "/api/alerts" :token (h/token-for who) :query query))

(defn- ack! [api who id]
  (call api :post (str "/api/alerts/" id "/ack") :token (h/token-for who)))

(defn- items [response] (get-in response [:json :items]))
(defn- ids [response] (mapv :id (items response)))
(defn- stored [client id] (fc/read-resource client "Flag" id))

(defn- with-spied-api
  "Like `with-api`, with the alerts raised, a spy on the FHIR client and the clock set:
  `(f api calls client)`."
  [f]
  (let [calls (atom [])
        client (with-alerts)]
    (with-api {:fhir (h/spy client calls) :clock clock}
      (fn [api] (f api calls client)))))

;; ---------------------------------------------------------------------------
;; The inbox

(deftest who-sees-which-alerts
  (with-api {:fhir (with-alerts)}
    (fn [api]
      (testing "a practitioner: the alerts of their patients, the newest first"
        (is (= ["flag-liam" "flag-ana-new" "flag-sofia"] (ids (list! api :rivera ""))))
        (is (= ["flag-maya"] (ids (list! api :okafor "")))))
      (testing "the administrator: everyone's"
        (is (= ["flag-liam" "flag-maya" "flag-ana-new" "flag-sofia"] (ids (list! api :admin "")))))
      (testing "those that were acknowledged, when asked for"
        (is (= ["flag-ana-old"] (ids (list! api :rivera "status=inactive"))))
        (is (= [] (ids (list! api :okafor "status=inactive"))))
        (is (= ["flag-ana-old"] (ids (list! api :admin "status=inactive")))))
      (testing "what was entered in error is never listed"
        (doseq [who [:rivera :admin] status ["active" "inactive"]]
          (is (not (some #{"flag-error"} (ids (list! api who (str "status=" status))))))))
      (testing "a patient, the rules engine, and a practitioner with no panel to speak of"
        (is (= 403 (:status (list! api :ana ""))))
        (is (= 403 (:status (list! api :scheduler ""))))
        (is (= 403 (:status (call api :get "/api/alerts" :token (h/token-for :rivera :fhirUser nil))))))
      (testing "nobody"
        (is (= 401 (:status (call api :get "/api/alerts"))))))))

(deftest an-alert-in-the-inbox
  (with-api {:fhir (with-alerts)}
    (fn [api]
      (is (= {:id "flag-liam" :patient "Patient/pt-liam" :text "Glucose out of range" :status "active"
              :since (ago (hours 1)) :until nil}
             (first (items (list! api :rivera "")))))
      (is (= {:id "flag-ana-old" :patient "Patient/pt-ana" :text "BP stage 2" :status "inactive"
              :since (ago (days 5)) :until (ago (days 4))}
             (first (items (list! api :rivera "status=inactive"))))
          "an acknowledged alert says when"))))

(deftest the-inbox-can-be-sorted-and-paged
  (with-api {:fhir (with-alerts)}
    (fn [api]
      (let [ids-of (fn [query] (ids (list! api :admin query)))]
        (testing "oldest first, when asked"
          (is (= ["flag-sofia" "flag-ana-new" "flag-maya" "flag-liam"] (ids-of "sort=since")))
          (is (= (ids-of "sort=-since") (ids-of ""))))
        (testing "paging, with the total of the whole result"
          (let [page (list! api :admin "limit=2&offset=1")]
            (is (= ["flag-maya" "flag-ana-new"] (ids page)))
            (is (= {:total 4 :offset 1 :limit 2} (select-keys (:json page) [:total :offset :limit])))
            (is (= ["flag-sofia"] (ids-of "limit=2&offset=3")))
            (is (= [] (ids-of "offset=50")))))
        (testing "defaults"
          (is (= {:offset 0 :limit 50} (select-keys (:json (list! api :admin "")) [:offset :limit]))))))))

(deftest bad-query-parameters-are-a-400-that-names-the-parameter
  (with-api {}
    (fn [api]
      (doseq [[query parameter] [["status=done" :status] ["status=" :status] ["status=entered-in-error" :status]
                                 ["status=all" :status] ["sort=date" :sort]
                                 ["limit=0" :limit] ["limit=101" :limit] ["limit=many" :limit] ["offset=-1" :offset]]]
        (testing query
          (let [r (list! api :admin query)]
            (is (= 400 (:status r)))
            (is (contains? (get-in r [:json :errors]) parameter))))))))

(deftest the-inbox-asks-fhir-only-for-what-the-scope-covers
  (with-spied-api
    (fn [api calls _]
      (testing "a practitioner: the panel first, and then the flags of those patients and no others"
        (list! api :rivera "")
        (is (= [[:search "Patient" {:general-practitioner "Practitioner/pr-rivera"}]
                [:search "Flag" {:patient "Patient/pt-ana,Patient/pt-liam,Patient/pt-omar,Patient/pt-sofia"
                                 :_sort "-date" :_count 200}]]
               @calls)))
      (reset! calls [])
      (testing "the administrator: every flag"
        (list! api :admin "")
        (is (= [[:search "Flag" {:_sort "-date" :_count 200}]] @calls)))
      (reset! calls [])
      (testing "a practitioner with no patients asks for no flags: an empty filter would mean everyone"
        (let [r (call api :get "/api/alerts" :token (h/token-for :rivera :fhirUser "Practitioner/pr-nobody"))]
          (is (= 200 (:status r)))
          (is (= {:total 0 :offset 0 :limit 50 :items []} (:json r)))
          (is (= [[:search "Patient" {:general-practitioner "Practitioner/pr-nobody"}]] @calls))))
      (reset! calls [])
      (testing "no scope, no query"
        (list! api :ana "")
        (list! api :scheduler "")
        (call api :get "/api/alerts" :token (h/token-for :rivera :fhirUser nil))
        (is (empty? @calls))))))

(deftest a-big-panel-is-asked-about-fifty-patients-at-a-time
  (let [client (with-alerts)
        calls (atom [])]
    (fc/put! client (r/practitioner {:id "pr-bulk" :given "Bea" :family "Bulk" :gender "female"}))
    (doseq [i (range 120)]
      (fc/put! client (r/patient {:id (str "pt-bulk-" i) :given "Bulk" :family (str "Patient" i) :gender "other"
                                  :birth-date "1990-01-01" :gp "pr-bulk"})))
    (doseq [[i d] [[0 (hours 4)] [60 (hours 2)] [119 (hours 6)]]]
      (fc/put! client (raised (str "flag-bulk-" i) (str "pt-bulk-" i) "Bulk alert" d)))
    (with-api {:fhir (h/spy client calls) :clock clock}
      (fn [api]
        (let [r (call api :get "/api/alerts" :token (h/token-for :rivera :fhirUser "Practitioner/pr-bulk") :query "limit=100")
              flag-searches (filter #(= "Flag" (second %)) @calls)]
          (is (= 200 (:status r)))
          (testing "the alerts of all of them, from the three queries, as one list newest first"
            (is (= ["flag-bulk-60" "flag-bulk-0" "flag-bulk-119"] (ids r))))
          (testing "in groups of 50, 50 and 20 patients"
            (is (= [50 50 20] (map #(count (str/split (:patient (nth % 2)) #",")) flag-searches)))))))))

(deftest the-inbox-when-the-fhir-server-is-down
  (with-api {:fhir h/unreachable-fhir}
    (fn [api]
      (doseq [who [:rivera :admin]]
        (let [r (list! api who "")]
          (is (= 502 (:status r)) (str who))
          (is (not (str/includes? (:text r) "secret-host"))))))))

;; ---------------------------------------------------------------------------
;; Who may acknowledge which alert

(deftest who-may-acknowledge-which-alert
  (with-api {:fhir (with-alerts) :clock clock}
    (fn [api]
      (doseq [[who id expected] [[:ana "flag-ana-new" 403] [:liam "flag-liam" 403] [:scheduler "flag-liam" 403]
                                 [:rivera "flag-maya" 403] [:okafor "flag-sofia" 403] [:okafor "flag-liam" 403]
                                 [:rivera "flag-ana-new" 200] [:rivera "flag-liam" 200]
                                 [:okafor "flag-maya" 200]
                                 [:admin "flag-sofia" 200]]]
        (testing (str who " -> " id)
          (is (= expected (:status (ack! api who id))))))
      (testing "without a token"
        (is (= 401 (:status (call api :post "/api/alerts/flag-liam/ack"))))))))

(deftest an-alert-that-does-not-exist-or-is-not-about-a-patient
  (let [client (with-alerts)
        calls (atom [])]
    (fc/put! client (assoc (raised "flag-org" "pt-ana" "About a clinic" (hours 1))
                           :subject {:reference "Organization/org-pulse-demo"}))
    (fc/put! client (raised "flag-dangling" "pt-ghost" "About nobody" (hours 1)))
    (with-api {:fhir (h/spy client calls) :clock clock}
      (fn [api]
        (testing "for staff who may look: 404"
          (doseq [who [:rivera :okafor :admin]]
            (is (= 404 (:status (ack! api who "flag-ghost"))) (str who))
            (is (= 404 (:status (ack! api who "flag-org"))) "an alert about something that is not a patient")))
        (testing "an alert about a patient that is not there: the administrator may still tidy it, a practitioner has no panel to judge by"
          (is (= 404 (:status (ack! api :rivera "flag-dangling"))))
          (is (= 200 (:status (ack! api :admin "flag-dangling")))))
        (testing "whoever may not acknowledge any: 403, before anything is looked up"
          (reset! calls [])
          (is (= 403 (:status (ack! api :ana "flag-ghost"))))
          (is (= 403 (:status (ack! api :scheduler "flag-ghost"))))
          (is (empty? @calls)))
        (testing "an id that cannot be a FHIR id cannot exist, and never reaches the server"
          (doseq [id ["a%20b" "flag-liam%2F.." "..%2F..%2FPatient" "%24everything" (apply str (repeat 65 "a"))]]
            (is (= 404 (:status (ack! api :admin id))) id))
          (is (empty? @calls)))))))

;; ---------------------------------------------------------------------------
;; Acknowledging

(deftest acknowledging-makes-an-alert-inactive-and-ends-its-period
  (let [client (with-alerts)
        before (stored client "flag-ana-new")]
    (with-api {:fhir client :clock clock}
      (fn [api]
        (let [r (ack! api :rivera "flag-ana-new")
              after (stored client "flag-ana-new")]
          (is (= 200 (:status r)))
          (is (= "application/json" (h/content-type r)))
          (is (= {:id "flag-ana-new" :patient "Patient/pt-ana" :text "BP stage 2" :status "inactive"
                  :since (ago (hours 3)) :until (str now)}
                 (:json r)))
          (testing "the resource, and nothing else about it"
            (is (= "inactive" (:status after)))
            (is (= {:start (ago (hours 3)) :end (str now)} (:period after)))
            (is (= (dissoc before :status :period) (dissoc after :status :period)))
            (is (schema/valid? after)))
          (testing "it is gone from the inbox, and is among those that were acknowledged"
            (is (not (some #{"flag-ana-new"} (ids (list! api :rivera "")))))
            (is (= ["flag-ana-new" "flag-ana-old"] (ids (list! api :rivera "status=inactive")))))
          (testing "and from the patient's summary"
            (is (= [] (get-in (call api :get "/api/patients/pt-ana/summary" :token (h/token-for :ana)) [:json :alerts])))))))))

(deftest an-alert-is-acknowledged-once
  (with-api {:fhir (with-alerts) :clock clock}
    (fn [api]
      (is (= 200 (:status (ack! api :rivera "flag-liam"))))
      (let [again (ack! api :rivera "flag-liam")
            colleague (ack! api :admin "flag-liam")]
        (doseq [r [again colleague]]
          (is (= 409 (:status r)))
          (is (= "application/problem+json" (h/content-type r)))
          (is (= "Only an active alert can be acknowledged; this one is inactive." (get-in r [:json :detail]))))))))

(deftest what-is-not-active-is-a-409-and-changes-nothing
  (let [client (with-alerts)]
    (with-api {:fhir client :clock clock}
      (fn [api]
        (doseq [id ["flag-ana-old" "flag-error"]
                :let [before (stored client id)
                      r (ack! api :admin id)]]
          (testing id
            (is (= 409 (:status r)))
            (is (str/includes? (get-in r [:json :detail]) "Only an active alert can be acknowledged"))
            (is (= before (stored client id)) "nothing was written")))))))

(deftest a-body-that-comes-with-it-is-ignored
  (let [client (with-alerts)]
    (with-api {:fhir client :clock clock}
      (fn [api]
        (let [r (call api :post "/api/alerts/flag-liam/ack" :token (h/token-for :rivera)
                      :body "{\"status\":\"active\",\"id\":\"other\",\"text\":\"changed\"}")]
          (is (= 200 (:status r)))
          (is (= "inactive" (:status (stored client "flag-liam"))))
          (is (= "Glucose out of range" (get-in (stored client "flag-liam") [:code :text])))
          (is (nil? (stored client "other"))))))))

(deftest only-the-methods-that-make-sense
  (with-api {:fhir (with-alerts)}
    (fn [api]
      (let [r (call api :get "/api/alerts/flag-liam/ack" :token (h/token-for :admin))]
        (is (= 405 (:status r)))
        (is (= "POST" (h/header r "Allow"))))
      (let [r (call api :delete "/api/alerts" :token (h/token-for :admin))]
        (is (= 405 (:status r)))
        (is (= "GET" (h/header r "Allow")))))))

(deftest acknowledging-reads-what-the-decision-needs-and-the-same-flag-is-written
  (with-spied-api
    (fn [api calls _]
      (testing "a practitioner: the alert, its patient to know the panel, and then the write"
        (ack! api :rivera "flag-ana-new")
        (is (= [[:read "Flag" "flag-ana-new"] [:read "Patient" "pt-ana"] [:put "Flag" "flag-ana-new"]] @calls)))
      (reset! calls [])
      (testing "the administrator: no patient to read"
        (ack! api :admin "flag-liam")
        (is (= [[:read "Flag" "flag-liam"] [:put "Flag" "flag-liam"]] @calls)))
      (reset! calls [])
      (testing "an alert of another panel: read to decide, and nothing else"
        (ack! api :okafor "flag-sofia")
        (is (= [[:read "Flag" "flag-sofia"] [:read "Patient" "pt-sofia"]] @calls)))
      (reset! calls [])
      (testing "an alert that is not active: read, and no write"
        (ack! api :admin "flag-ana-old")
        (is (= [[:read "Flag" "flag-ana-old"]] @calls))))))

;; ---------------------------------------------------------------------------
;; The audit trail

(deftest acknowledging-is-audited-by-reference-and-never-with-the-alert
  (with-api {:fhir (with-alerts) :clock clock}
    (fn [api]
      (let [r (ack! api :rivera "flag-ana-new")
            actor {:subject "u-rivera" :username "dr.rivera@pulse.test" :roles ["practitioner"] :client "pulse-spa"}
            path "/api/alerts/flag-ana-new/ack"]
        (is (= [{:level :info
                 :data {:actor actor :action :alert/ack :outcome :allowed :scope :panel
                        :patient "Patient/pt-ana" :method "POST" :path path}}]
               (map #(select-keys % [:level :data]) (audit-events r))))
        (is (= [{:level :info
                 :data {:actor actor :action :alert/ack :outcome :updated :change :acknowledge
                        :patient "Patient/pt-ana" :resource "Flag/flag-ana-new"
                        :method "POST" :path path}}]
               (map #(select-keys % [:level :data]) (h/audit-writes r))))
        (is (not (str/includes? (pr-str (:signals r)) "BP stage 2")) "the text of the alert is not logged")))))

(deftest a-refusal-is-a-decision-and-never-a-write
  (with-api {:fhir (with-alerts) :clock clock}
    (fn [api]
      (let [denied (ack! api :rivera "flag-maya")
            conflict (ack! api :admin "flag-ana-old")
            missing (ack! api :rivera "flag-ghost")]
        (is (= [:denied] (map #(get-in % [:data :outcome]) (audit-events denied))))
        (is (= [:allowed] (map #(get-in % [:data :outcome]) (audit-events conflict))))
        (is (= [:not-found] (map #(get-in % [:data :outcome]) (audit-events missing))))
        (is (empty? (mapcat h/audit-writes [denied conflict missing])))))))

(deftest when-the-fhir-server-fails-the-alert-is-not-acknowledged-and-it-says-so
  (testing "the server is down"
    (with-api {:fhir h/unreachable-fhir :clock clock}
      (fn [api]
        (let [r (ack! api :admin "flag-liam")]
          (is (= 502 (:status r)))
          (is (not (str/includes? (:text r) "secret-host")))))))
  (testing "it answers reads and refuses the write"
    (let [client (with-alerts)
          before (stored client "flag-liam")]
      (with-api {:fhir (h/failing-writes client) :clock clock}
        (fn [api]
          (let [r (ack! api :rivera "flag-liam")]
            (is (= 502 (:status r)))
            (is (not (str/includes? (:text r) "secret-host")))
            (is (empty? (h/audit-writes r)) "it is not recorded as written when it was not")
            (is (= before (stored client "flag-liam")))))))))

;; ---------------------------------------------------------------------------
;; Raised, listed, acknowledged

(deftest an-alert-is-raised-seen-and-acknowledged
  (let [client (h/demo-cohort)]
    (with-api {:fhir client :clock clock}
      (fn [api]
        (is (= [] (ids (list! api :rivera ""))) "the cohort has no alerts")
        (fc/put! client (raised "flag-new" "pt-sofia" "Low SpO2" (hours 1)))
        (testing "the doctor of the patient sees it, and the other doctor does not"
          (is (= ["flag-new"] (ids (list! api :rivera ""))))
          (is (= [] (ids (list! api :okafor "")))))
        (testing "the patient's summary has it too"
          (is (= ["flag-new"] (map :id (get-in (call api :get "/api/patients/pt-sofia/summary" :token (h/token-for :admin))
                                               [:json :alerts])))))
        (testing "acknowledged by the other doctor: no"
          (is (= 403 (:status (ack! api :okafor "flag-new")))))
        (testing "acknowledged by the right one: yes, and it is over"
          (is (= 200 (:status (ack! api :rivera "flag-new"))))
          (is (= [] (ids (list! api :rivera ""))))
          (is (= ["flag-new"] (ids (list! api :admin "status=inactive")))))))))

;; ---------------------------------------------------------------------------
;; The inbox against a model

(defn- many-alerts
  "Thirty alerts, of every status, about the six patients, raised seven hours apart."
  [client]
  (doseq [i (range 30)
          :let [flag (raised (str "flag-m-" i) (["pt-ana" "pt-liam" "pt-sofia" "pt-omar" "pt-maya" "pt-noah"] (mod i 6))
                             "Model" (hours (* 7 (inc i))))]]
    (fc/put! client (case (mod i 5)
                      0 (acknowledged flag (hours (* 7 i)))
                      1 (assoc flag :status "entered-in-error")
                      flag))))

(defn- model
  "The alerts of `client` that `who` should see with this `status` and `order`, worked out from all the flags
  and the panels, by the book: a way to the answer that does not go through the queries the endpoint makes."
  [client who status order]
  (let [panel (fn [practitioner] (set (map :id (filter #(some (fn [g] (= practitioner (:reference g))) (:generalPractitioner %))
                                                       (filter #(= "Patient" (:resourceType %)) (mem/contents client))))))
        visible? (case who
                   :rivera (let [p (panel "Practitioner/pr-rivera")] #(contains? p (second (str/split (get-in % [:subject :reference]) #"/"))))
                   :okafor (let [p (panel "Practitioner/pr-okafor")] #(contains? p (second (str/split (get-in % [:subject :reference]) #"/"))))
                   :admin (constantly true))
        flags (->> (mem/contents client)
                   (filter #(= "Flag" (:resourceType %)))
                   (filter visible?)
                   (filter #(= status (:status %)))
                   (sort-by (juxt #(time/->millis (get-in % [:period :start])) :id)))]
    (mapv :id (if (= "-since" order) (reverse flags) flags))))

(deftest the-inbox-is-what-the-model-says-whoever-asks-and-however
  (let [client (h/demo-cohort)]
    (many-alerts client)
    (with-api {:fhir client}
      (fn [api]
        (let [result (tc/quick-check
                      300
                      (prop/for-all [who (gen/elements [:rivera :okafor :admin])
                                     status (gen/elements ["active" "inactive"])
                                     order (gen/elements ["-since" "since"])
                                     limit (gen/choose 1 8)
                                     offset (gen/choose 0 9)]
                        (let [r (list! api who (str "status=" status "&sort=" order "&limit=" limit "&offset=" offset))
                              expected (model client who status order)]
                          (and (= 200 (:status r))
                               (= (count expected) (get-in r [:json :total]))
                               (= (vec (take limit (drop offset expected))) (ids r))))))]
          (is (:pass? result) (pr-str (select-keys result [:fail :shrunk :seed])))
          (testing "the model is not empty by accident"
            (is (seq (model client :rivera "active" "-since")))
            (is (seq (model client :okafor "inactive" "since")))
            (is (seq (model client :admin "inactive" "-since")))))))))
