(ns pulse.api.authz-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [pulse.api.authz :as authz]
            [pulse.api.identity :as identity]
            [reitit.ring :as ring]))

(def ^:private ana {:type "Patient" :id "pt-ana"})
(def ^:private liam {:type "Patient" :id "pt-liam"})
(def ^:private rivera {:type "Practitioner" :id "pr-rivera"})
(def ^:private okafor {:type "Practitioner" :id "pr-okafor"})

(defn- caller
  "An identity with the given roles and, optionally, the FHIR resource that is them."
  [roles & [fhir-user]]
  {:subject "u1" :roles (set roles) :fhir-user fhir-user})

(def ^:private person-of
  "The FHIR resource each role's demo user is."
  {:patient ana :practitioner rivera :clinic-admin nil :rules-engine nil})

;; ---------------------------------------------------------------------------
;; Annex A, as a table

(def ^:private annex-a
  "Annex A of the plan, row by row, read from the document and not from `rules`: what each role
  may do, as a scope (nil: nothing). The last row, `:appointment/read`, is implied by Annex B."
  [;; action                patient  practitioner  clinic-admin  rules-engine
   [:patient/read          :own     :panel        :all          nil]           ; view own record and summary
   [:observation/read      :own     :panel        :all          :all]          ; ... the rules engine: read observations
   [:patient/list          nil      :panel        :all          :identifiers]  ; list patients
   [:observation/record    :own     :panel        nil           :all]          ; record an observation
   [:appointment/request   :own     nil           nil           nil]           ; request an appointment
   [:appointment/manage    :own     :panel        :all          nil]           ; act on an appointment: a patient only cancels (ADR-0027)
   [:alert/read            nil      :panel        :all          nil]           ; view alerts
   [:alert/ack             nil      :panel        :all          nil]           ; acknowledge alerts
   [:alert/write           nil      nil           nil           :all]          ; the rules engine creates and updates Flags
   [:jobs/read             nil      nil           :all          nil]           ; job status
   [:appointment/read      :own     :panel        :all          nil]])

(deftest the-matrix-is-annex-a
  (doseq [[action & scopes] annex-a
          [role expected] (map vector [:patient :practitioner :clinic-admin :rules-engine] scopes)]
    (testing (str action " for " role)
      (is (= expected (authz/scope-of (caller [role] (person-of role)) action))))))

(deftest the-table-and-the-rules-list-the-same-actions
  (is (= (set (map first annex-a))
         (disj (set (keys authz/rules)) :identity/read))
      "an action in one and not the other is a row someone forgot")
  (testing "every role named in the rules exists"
    (is (every? (conj identity/roles :any)
                (mapcat keys (vals authz/rules))))))

(deftest anyone-who-is-authenticated-can-read-their-identity
  (doseq [roles [[] [:patient] [:practitioner] [:clinic-admin] [:rules-engine] [:patient :practitioner]]]
    (is (some? (authz/scope-of (caller roles) :identity/read)) (pr-str roles))))

;; ---------------------------------------------------------------------------
;; What a scope covers

(def ^:private ana-record {:patient "Patient/pt-ana" :practitioners #{"Practitioner/pr-rivera"}})
(def ^:private maya-record {:patient "Patient/pt-maya" :practitioners #{"Practitioner/pr-okafor"}})

(deftest scopes-cover-what-they-should
  (doseq [[label scope who record expected]
          [["all covers any record" :all (caller [:clinic-admin]) maya-record true]
           ["all needs no FHIR resource of the caller" :all (caller [:clinic-admin] nil) ana-record true]
           ["identifiers covers any patient" :identifiers (caller [:rules-engine]) maya-record true]
           ["own: the caller's record" :own (caller [:patient] ana) ana-record true]
           ["own: someone else's" :own (caller [:patient] ana) maya-record false]
           ["own: with no FHIR resource" :own (caller [:patient]) ana-record false]
           ["own: a practitioner is not the patient with the same id" :own (caller [:patient] {:type "Practitioner" :id "pt-ana"}) ana-record false]
           ["panel: a patient of the caller" :panel (caller [:practitioner] rivera) ana-record true]
           ["panel: a patient of a colleague" :panel (caller [:practitioner] rivera) maya-record false]
           ["panel: the colleague" :panel (caller [:practitioner] okafor) maya-record true]
           ["panel: a record that says nothing about practitioners" :panel (caller [:practitioner] rivera) {:patient "Patient/pt-ana"} false]
           ["panel: with no FHIR resource" :panel (caller [:practitioner]) ana-record false]
           ["an unknown scope covers nothing" :everything (caller [:clinic-admin]) ana-record false]]]
    (testing label
      (is (= expected (authz/permits? scope who record))))))

;; ---------------------------------------------------------------------------
;; Decisions

(deftest the-decision-table
  (doseq [[label who action record expected]
          [["a patient reads their own record" (caller [:patient] ana) :patient/read ana-record {:allow? true :scope :own}]
           ["a patient does not read another's" (caller [:patient] liam) :patient/read ana-record {:allow? false :reason :outside-scope :scope :own}]
           ["a practitioner reads their panel" (caller [:practitioner] rivera) :patient/read ana-record {:allow? true :scope :panel}]
           ["a practitioner does not read a colleague's patient" (caller [:practitioner] rivera) :patient/read maya-record {:allow? false :reason :outside-scope :scope :panel}]
           ["the administrator reads anyone" (caller [:clinic-admin]) :patient/read maya-record {:allow? true :scope :all}]
           ["the rules engine may not read demographics" (caller [:rules-engine]) :patient/read ana-record {:allow? false :reason :role-not-allowed}]
           ["but may read observations" (caller [:rules-engine]) :observation/read ana-record {:allow? true :scope :all}]
           ["a patient may not list patients" (caller [:patient] ana) :patient/list nil {:allow? false :reason :role-not-allowed}]
           ["a practitioner lists with no resource: the answer is the scope to restrict to" (caller [:practitioner] rivera) :patient/list nil {:allow? true :scope :panel}]
           ["the administrator lists everyone" (caller [:clinic-admin]) :patient/list nil {:allow? true :scope :all}]
           ["the rules engine lists identifiers" (caller [:rules-engine]) :patient/list nil {:allow? true :scope :identifiers}]
           ["an action nobody wrote a rule for is denied to the administrator" (caller [:clinic-admin]) :patient/delete-everything ana-record {:allow? false :reason :no-rule-for-action}]
           ["a caller with no role can only read their identity" (caller []) :patient/read ana-record {:allow? false :reason :role-not-allowed}]]]
    (testing label
      (is (= expected (authz/authorize who action record))))))

(deftest the-widest-scope-wins
  (is (= :panel (authz/scope-of (caller [:patient :practitioner] rivera) :patient/read))
      "a practitioner who is also a patient: the panel, which is wider than their own record")
  (is (= :all (authz/scope-of (caller [:practitioner :clinic-admin] rivera) :patient/read))))

(deftest a-scope-about-the-caller-needs-a-fhir-resource-of-the-right-type
  (testing "a practitioner whose token has no fhirUser has no panel, not every panel"
    (is (nil? (authz/scope-of (caller [:practitioner]) :patient/list)))
    (is (= {:allow? false :reason :role-not-allowed} (authz/authorize (caller [:practitioner]) :patient/list nil))))
  (testing "a practitioner whose fhirUser is a Patient has no panel either"
    (is (nil? (authz/scope-of (caller [:practitioner] ana) :patient/list))))
  (testing "a patient with no fhirUser, or the wrong kind, has no own record"
    (is (nil? (authz/scope-of (caller [:patient]) :patient/read)))
    (is (nil? (authz/scope-of (caller [:patient] rivera) :patient/read))))
  (testing "scopes that need nothing from the caller are unaffected"
    (is (= :all (authz/scope-of (caller [:clinic-admin]) :patient/read)))))

;; Callers and actions made up at random.

(def ^:private gen-caller
  (gen/let [roles (gen/set (gen/elements (vec identity/roles)))
            fhir-user (gen/elements [nil ana liam rivera okafor])]
    (caller roles fhir-user)))

(def ^:private gen-record
  (gen/let [patient (gen/elements ["Patient/pt-ana" "Patient/pt-maya" "Patient/pt-liam"])
            practitioners (gen/set (gen/elements ["Practitioner/pr-rivera" "Practitioner/pr-okafor"]))]
    {:patient patient :practitioners practitioners}))

(def ^:private gen-known-action (gen/elements (vec (keys authz/rules))))

(defspec an-action-without-a-rule-is-denied-to-every-caller 300
  (prop/for-all [who gen-caller
                 record (gen/one-of [(gen/return nil) gen-record])
                 action (gen/such-that #(not (contains? authz/rules %)) gen/keyword-ns)]
    (= {:allow? false :reason :no-rule-for-action} (authz/authorize who action record))))

(defspec nothing-is-allowed-without-a-scope 300
  (prop/for-all [who gen-caller
                 record (gen/one-of [(gen/return nil) gen-record])
                 action gen-known-action]
    (or (some? (authz/scope-of who action))
        (false? (:allow? (authz/authorize who action record))))))

(defspec an-allowed-decision-always-names-the-scope-it-rests-on 300
  (prop/for-all [who gen-caller
                 record (gen/one-of [(gen/return nil) gen-record])
                 action gen-known-action]
    (let [decision (authz/authorize who action record)]
      (or (not (:allow? decision))
          (= (authz/scope-of who action) (:scope decision))))))

(defspec another-role-never-narrows-what-a-caller-may-do 300
  (prop/for-all [who gen-caller
                 role (gen/elements (vec identity/roles))
                 action gen-known-action]
    (let [rank {nil 0 :own 1 :identifiers 2 :panel 3 :all 4}]
      (<= (rank (authz/scope-of who action))
          (rank (authz/scope-of (update who :roles conj role) action))))))

(defspec a-patient-is-never-allowed-another-patients-record 300
  (prop/for-all [action (gen/such-that #(not= :identity/read %) gen-known-action)
                 record gen-record]
    (let [who (caller [:patient] ana)
          decision (authz/authorize who action record)]
      (or (not (:allow? decision))
          (= "Patient/pt-ana" (:patient record))
          (nil? record)))))

;; ---------------------------------------------------------------------------
;; Route data

(def ^:private ok (constantly {:status 200}))

(defn- router [routes]
  (ring/router routes {::ring/default-options-endpoint nil}))

(defn- unprotected-endpoints
  "The endpoints `check-routes!` names, or nil when it accepts the router."
  [routes]
  (try (authz/check-routes! (router routes))
       nil
       (catch clojure.lang.ExceptionInfo e
         (is (= ::authz/missing-authz (:type (ex-data e))))
         (:endpoints (ex-data e)))))

(deftest every-endpoint-needs-an-authz
  (testing "valid declarations are accepted"
    (is (nil? (unprotected-endpoints [["/a" {:get {:authz :public :handler ok}}]
                                      ["/b" {:post {:authz {:action :identity/read} :handler ok}}]
                                      ["/c" {:get {:authz {:action :patient/read :resource (fn [_ _] nil)} :handler ok}}]]))))
  (testing "an endpoint with none is named, method and path"
    (is (= ["get /a"] (unprotected-endpoints [["/a" {:get {:handler ok}}]]))))
  (testing "each method is checked on its own"
    (is (= ["post /a"] (unprotected-endpoints [["/a" {:get {:authz :public :handler ok}
                                                      :post {:handler ok}}]]))))
  (testing "a handler for all methods is checked too"
    (is (= ["any /open"] (unprotected-endpoints [["/open" {:handler ok}]]))))
  (testing "a typo does not pass for a rule"
    (is (= ["get /a"] (unprotected-endpoints [["/a" {:get {:authz :publik :handler ok}}]])))
    (is (= ["get /a"] (unprotected-endpoints [["/a" {:get {:authz true :handler ok}}]]))))
  (testing "an action nobody defined is an error at start-up, not a route nobody can call"
    (is (= ["get /a"] (unprotected-endpoints [["/a" {:get {:authz {:action :nobody/defined} :handler ok}}]]))))
  (testing "a :resource that is not a function is an error too"
    (is (= ["get /a"] (unprotected-endpoints [["/a" {:get {:authz {:action :patient/read :resource "pt-ana"} :handler ok}}]])))))

(deftest authz-is-inherited-and-can-be-overridden
  (testing "from a parent"
    (is (nil? (unprotected-endpoints [["/api" {:authz {:action :identity/read}}
                                       ["/me" {:get {:handler ok}}]
                                       ["/other" {:get {:handler ok}}]]]))))
  (testing "a method's own declaration wins"
    (is (nil? (unprotected-endpoints [["/api" {:authz {:action :identity/read}}
                                       ["/health" {:get {:authz :public :handler ok}}]]]))))
  (testing "a bad one on a child is not hidden by a good one on the parent"
    (is (= ["get /api/x"] (unprotected-endpoints [["/api" {:authz {:action :identity/read}}
                                                   ["/x" {:get {:authz :nope :handler ok}}]]])))))
