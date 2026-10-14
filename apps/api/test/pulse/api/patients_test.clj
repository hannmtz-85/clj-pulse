(ns pulse.api.patients-test
  "The patient endpoints against the demo cohort: Dr. Rivera's panel is Ana, Liam, Sofía and
  Omar; Dr. Okafor's is Maya and Noah. Who sees what follows Annex A of the plan (ADR-0012)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.api.test.harness :as h :refer [audit-events call with-api]]
            [pulse.fhir.synthetic :as synthetic]))

(def ^:private cohort (:patients (synthetic/cohort)))

(defn- expected-ids
  "The ids of the cohort's patients that satisfy `pred`, sorted by `sort-fn`, worked out from the
  cohort description and not from the code under test."
  [pred sort-fn]
  (->> cohort (filter pred) (sort-by sort-fn) (mapv :id)))

(defn- family [patient] (str/lower-case (:family patient)))

(defn- ids [response]
  (mapv :id (get-in response [:json :items])))

(defn- with-spied-api
  "Like `with-api`, with the FHIR client wrapped in a spy: `(f api calls)`."
  [f]
  (let [calls (atom [])]
    (with-api {:fhir (h/spy (h/demo-cohort) calls)}
      (fn [api] (f api calls)))))

;; ---------------------------------------------------------------------------
;; The panel

(deftest each-role-sees-the-panel-it-should
  (with-api {}
    (fn [api]
      (testing "a practitioner: the patients they are the general practitioner of"
        (is (= (expected-ids #(= "pr-rivera" (:gp %)) family) (ids (call api :get "/api/patients" :token (h/token-for :rivera)))))
        (is (= (expected-ids #(= "pr-okafor" (:gp %)) family) (ids (call api :get "/api/patients" :token (h/token-for :okafor))))))
      (testing "the administrator: everyone"
        (let [r (call api :get "/api/patients" :token (h/token-for :admin))]
          (is (= 200 (:status r)))
          (is (= (expected-ids (constantly true) family) (ids r)))
          (is (= 6 (get-in r [:json :total])))))
      (testing "the rules engine: everyone, but only their identifiers"
        (let [r (call api :get "/api/patients" :token (h/token-for :scheduler))]
          (is (= 200 (:status r)))
          (is (= (expected-ids (constantly true) family) (ids r)))
          (is (every? #(= #{:id :identifier} (set (keys %))) (get-in r [:json :items])))
          (is (= "PULSE-0001" (:identifier (first (filter #(= "pt-ana" (:id %)) (get-in r [:json :items]))))))))
      (testing "a patient: nobody's panel"
        (let [r (call api :get "/api/patients" :token (h/token-for :ana))]
          (is (= 403 (:status r)))
          (is (= "application/problem+json" (h/content-type r)))
          (is (nil? (get-in r [:json :items]))))))))

(deftest a-row-of-the-panel
  (with-api {}
    (fn [api]
      (let [ana (first (filter #(= "pt-ana" (:id %))
                               (get-in (call api :get "/api/patients" :token (h/token-for :rivera)) [:json :items])))]
        (is (= {:id "pt-ana" :name "Ana García" :family "García" :given ["Ana"]
                :birthDate "1968-03-14" :gender "female" :identifier "PULSE-0001"}
               ana))))))

(deftest a-practitioner-without-a-fhir-user-has-no-panel-and-sees-nobody
  (with-api {}
    (fn [api]
      (let [r (call api :get "/api/patients" :token (h/token-for :rivera :fhirUser nil))]
        (is (= 403 (:status r)))
        (is (nil? (get-in r [:json :items])))))))

(deftest nothing-outside-the-scope-is-fetched
  (with-spied-api
    (fn [api calls]
      (call api :get "/api/patients" :token (h/token-for :rivera))
      (is (= [[:search "Patient" {:general-practitioner "Practitioner/pr-rivera"}]] @calls)
          "the server is asked for the panel and nothing else")
      (reset! calls [])
      (call api :get "/api/patients" :token (h/token-for :okafor) :query "family=pat")
      (is (= [[:search "Patient" {:general-practitioner "Practitioner/pr-okafor" :family "pat"}]] @calls))
      (reset! calls [])
      (call api :get "/api/patients" :token (h/token-for :ana))
      (is (empty? @calls) "a patient is refused before the server is asked anything"))))

(deftest filtering-sorting-and-paging
  (with-api {}
    (fn [api]
      (let [admin (h/token-for :admin)
            by-birth #(:birth-date %)
            get-ids (fn [query] (ids (call api :get "/api/patients" :token admin :query query)))]
        (testing "a filter on the family name: a prefix, in any case"
          (is (= ["pt-liam"] (get-ids "family=CH")))
          (is (= ["pt-ana"] (get-ids "family=garc")))
          (is (= [] (get-ids "family=zzz"))))
        (testing "sorting"
          (is (= (expected-ids (constantly true) family) (get-ids "sort=family")))
          (is (= (reverse (expected-ids (constantly true) family)) (get-ids "sort=-family")))
          (is (= (expected-ids (constantly true) by-birth) (get-ids "sort=birthDate")))
          (is (= (reverse (expected-ids (constantly true) by-birth)) (get-ids "sort=-birthDate"))))
        (testing "paging, with the total of the whole result"
          (let [all (expected-ids (constantly true) family)
                r (call api :get "/api/patients" :token admin :query "limit=2&offset=1")]
            (is (= (subvec all 1 3) (ids r)))
            (is (= {:total 6 :offset 1 :limit 2} (select-keys (:json r) [:total :offset :limit])))
            (is (= (subvec all 5) (get-ids "limit=2&offset=5")))
            (is (= [] (get-ids "offset=50")))))
        (testing "defaults"
          (is (= {:offset 0 :limit 20} (select-keys (:json (call api :get "/api/patients" :token admin)) [:offset :limit]))))
        (testing "a filter and a page together"
          (is (= 1 (get-in (call api :get "/api/patients" :token admin :query "family=ch&limit=1") [:json :total]))))))))

(deftest bad-query-parameters-are-a-400-that-names-the-parameter
  (with-api {}
    (fn [api]
      (doseq [[query parameter] [["limit=0" :limit] ["limit=101" :limit] ["limit=many" :limit]
                                 ["offset=-1" :offset] ["sort=email" :sort] ["family=" :family]]]
        (testing query
          (let [r (call api :get "/api/patients" :token (h/token-for :admin) :query query)]
            (is (= 400 (:status r)))
            (is (contains? (get-in r [:json :errors]) parameter))))))))

(deftest the-panel-without-credentials-or-with-the-fhir-server-down
  (with-api {}
    (fn [api]
      (is (= 401 (:status (call api :get "/api/patients"))))))
  (with-api {:fhir h/unreachable-fhir}
    (fn [api]
      (let [r (call api :get "/api/patients" :token (h/token-for :admin))]
        (is (= 502 (:status r)))
        (is (not (str/includes? (:text r) "secret-host")))))))

;; ---------------------------------------------------------------------------
;; One patient

(defn- status-of [api who id]
  (:status (call api :get (str "/api/patients/" id) :token (h/token-for who))))

(deftest who-may-read-which-patient
  (with-api {}
    (fn [api]
      (doseq [[who id expected]
              [[:ana "pt-ana" 200] [:ana "pt-liam" 403] [:ana "pt-maya" 403]
               [:liam "pt-liam" 200] [:liam "pt-ana" 403]
               [:rivera "pt-ana" 200] [:rivera "pt-liam" 200] [:rivera "pt-sofia" 200] [:rivera "pt-omar" 200]
               [:rivera "pt-maya" 403] [:rivera "pt-noah" 403]
               [:okafor "pt-maya" 200] [:okafor "pt-noah" 200] [:okafor "pt-ana" 403]
               [:admin "pt-ana" 200] [:admin "pt-noah" 200]
               [:scheduler "pt-ana" 403]]]
        (testing (str who " -> " id)
          (is (= expected (status-of api who id))))))))

(deftest the-patient-comes-back-as-a-fhir-resource
  (with-api {}
    (fn [api]
      (let [r (call api :get "/api/patients/pt-ana" :token (h/token-for :ana))]
        (is (= "application/json" (h/content-type r)))
        (is (= "Patient" (get-in r [:json :resourceType])))
        (is (= "pt-ana" (get-in r [:json :id])))
        (is (= "García" (get-in r [:json :name 0 :family])))
        (is (= [{:reference "Practitioner/pr-rivera"}] (get-in r [:json :generalPractitioner])))))))

(deftest a-patient-that-does-not-exist
  (with-api {}
    (fn [api]
      (testing "for staff who may look: 404"
        (is (= 404 (status-of api :admin "pt-ghost")))
        (is (= 404 (status-of api :rivera "pt-ghost")))
        (is (= "No such resource." (:detail (:json (call api :get "/api/patients/pt-ghost" :token (h/token-for :rivera)))))))
      (testing "for a patient it is the same 403 as for someone else's record: no way to probe which exist"
        (is (= 403 (status-of api :ana "pt-ghost")))
        (is (= (:json (call api :get "/api/patients/pt-ghost" :token (h/token-for :ana) :headers {"x-request-id" "r"}))
               (assoc (:json (call api :get "/api/patients/pt-liam" :token (h/token-for :ana) :headers {"x-request-id" "r"}))
                      :instance "/api/patients/pt-ghost")))))))

(deftest an-id-that-cannot-be-a-fhir-id-is-a-404-and-never-reaches-the-server
  (with-spied-api
    (fn [api calls]
      (doseq [id ["a%20b" "pt-ana%2F.." "..%2F..%2FObservation" "%24everything" (apply str (repeat 65 "a"))]]
        (testing id
          (is (= 404 (status-of api :admin id)))
          (is (= 404 (status-of api :rivera id)))))
      (is (empty? @calls)))))

(deftest only-what-a-decision-needs-is-read
  (with-spied-api
    (fn [api calls]
      (testing "a patient reading themselves: only the patient"
        (call api :get "/api/patients/pt-ana" :token (h/token-for :ana))
        (is (= [[:read "Patient" "pt-ana"]] @calls)))
      (reset! calls [])
      (testing "a patient reading someone else: refused from the ids alone"
        (call api :get "/api/patients/pt-liam" :token (h/token-for :ana))
        (is (empty? @calls)))
      (reset! calls [])
      (testing "the administrator: only the patient"
        (call api :get "/api/patients/pt-maya" :token (h/token-for :admin))
        (is (= [[:read "Patient" "pt-maya"]] @calls)))
      (reset! calls [])
      (testing "a practitioner needs the patient's practitioners first"
        (call api :get "/api/patients/pt-ana" :token (h/token-for :rivera))
        (is (every? #{[:read "Patient" "pt-ana"]} @calls))
        (is (pos? (count @calls))))
      (reset! calls [])
      (testing "the rules engine has no scope here, so nothing is looked up"
        (call api :get "/api/patients/pt-ana" :token (h/token-for :scheduler))
        (is (empty? @calls))))))

(deftest one-patient-without-credentials-or-with-the-fhir-server-down
  (with-api {}
    (fn [api]
      (is (= 401 (:status (call api :get "/api/patients/pt-ana"))))))
  (with-api {:fhir h/unreachable-fhir}
    (fn [api]
      (is (= 502 (:status (call api :get "/api/patients/pt-ana" :token (h/token-for :admin)))))
      (is (= 502 (:status (call api :get "/api/patients/pt-ana" :token (h/token-for :rivera)))))
      (is (= 403 (:status (call api :get "/api/patients/pt-liam" :token (h/token-for :ana)))) "refused before the server is needed"))))

;; ---------------------------------------------------------------------------
;; The audit trail

(deftest every-decision-on-clinical-data-is-audited-without-the-data
  (with-api {}
    (fn [api]
      (let [allowed (call api :get "/api/patients/pt-ana" :token (h/token-for :rivera))
            denied (call api :get "/api/patients/pt-maya" :token (h/token-for :rivera))
            missing (call api :get "/api/patients/pt-ghost" :token (h/token-for :rivera))
            listed (call api :get "/api/patients" :token (h/token-for :rivera))
            refused (call api :get "/api/patients" :token (h/token-for :ana))
            actor {:subject "u-rivera" :username "dr.rivera@pulse.test" :roles ["practitioner"] :client "pulse-spa"}]
        (is (= [{:level :info
                 :data {:actor actor :action :patient/read :outcome :allowed :scope :panel
                        :patient "Patient/pt-ana" :method "GET" :path "/api/patients/pt-ana"}}]
               (map #(select-keys % [:level :data]) (audit-events allowed))))
        (is (= [{:level :warn
                 :data {:actor actor :action :patient/read :outcome :denied :scope :panel :reason :outside-scope
                        :patient "Patient/pt-maya" :method "GET" :path "/api/patients/pt-maya"}}]
               (map #(select-keys % [:level :data]) (audit-events denied))))
        (is (= [{:level :warn
                 :data {:actor actor :action :patient/read :outcome :not-found :scope :panel :reason :no-such-resource
                        :method "GET" :path "/api/patients/pt-ghost"}}]
               (map #(select-keys % [:level :data]) (audit-events missing))))
        (is (= [{:level :info
                 :data {:actor actor :action :patient/list :outcome :allowed :scope :panel
                        :method "GET" :path "/api/patients"}}]
               (map #(select-keys % [:level :data]) (audit-events listed))))
        (is (= :denied (get-in (first (audit-events refused)) [:data :outcome])))
        (is (= :role-not-allowed (get-in (first (audit-events refused)) [:data :reason])))
        (testing "the request id is in the context of each event"
          (is (every? #(string? (get-in % [:ctx :request-id])) (mapcat audit-events [allowed denied missing listed refused]))))
        (testing "nothing from the record is logged"
          (let [logged (pr-str (mapcat audit-events [allowed denied missing listed refused]))]
            (doseq [secret ["García" "Ana" "1968-03-14" "PULSE-0001" "female"]]
              (is (not (str/includes? logged secret)) secret))))))))

(deftest authentication-failures-are-not-audit-events
  (with-api {}
    (fn [api]
      (is (empty? (audit-events (call api :get "/api/patients/pt-ana")))
          "they are in the security log, as :pulse.auth/rejected"))))
