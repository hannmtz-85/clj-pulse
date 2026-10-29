(ns pulse.design.data-panel-test
  "What the panel of the clinical workspace is made from in the mock-ups is what the api answers: the list of the patients of a
  practitioner and the inbox of alerts, held to the handlers of the api over the same cohort (ADR-0029)."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [pulse.api.alerts :as api-alerts]
            [pulse.api.patients :as api-patients]
            [pulse.design.data :as data]
            [pulse.fhir.client.memory :as memory]
            [pulse.fhir.resource :as resource]))

(def ^:private cohort
  (delay (memory/memory-client (data/resources))))

(def ^:private cohort-with-alerts
  "The cohort and the four alerts of the mock-ups as the flags the rules engine would have raised."
  (delay
    (memory/memory-client
     (concat (data/resources)
             (for [{:keys [id patient text status since until]} data/alerts]
               (cond-> (resource/flag {:id id :patient (subs patient (count "Patient/")) :text text :start since})
                 (= "inactive" status) (-> (assoc :status "inactive") (assoc-in [:period :end] until))))))))

(def ^:private rivera {:subject "user-rivera" :roles #{:practitioner} :fhir-user {:type "Practitioner" :id "pr-rivera"}})

(def ^:private okafor {:subject "user-okafor" :roles #{:practitioner} :fhir-user {:type "Practitioner" :id "pr-okafor"}})

(def ^:private admin {:subject "user-admin" :roles #{:clinic-admin} :fhir-user nil})

(def ^:private callers
  "Who asks, and how the api reads their scope: a practitioner's panel, or everyone for the administrator."
  [["pr-rivera" rivera :panel] ["pr-okafor" okafor :panel] [nil admin :all]])

(defn- patients-of-api [caller scope params]
  (:body ((api-patients/list-handler {:fhir @cohort})
          {:identity caller :scope scope :parameters {:query (merge {:sort "family" :limit 20 :offset 0} params)}})))

(defn- alerts-of-api [caller scope params]
  (:body ((api-alerts/list-handler {:fhir @cohort-with-alerts})
          {:identity caller :scope scope :parameters {:query (merge {:status "active" :sort "-since" :limit 50 :offset 0} params)}})))

(deftest the-list-of-patients-is-what-the-api-answers
  (doseq [[practitioner caller scope] callers
          params [{} {:limit 100} {:limit 1} {:limit 2 :offset 1} {:offset 10}]]
    (testing (str (or practitioner "the administrator") " " (pr-str params))
      (let [mine (data/patient-list practitioner params)]
        (is (m/validate api-patients/list-schema mine))
        (is (= (patients-of-api caller scope params) mine) "the same as the handler of the api makes of the same cohort"))))
  (testing "a row has what the panel shows of a patient and nothing more"
    (is (= #{:id :name :family :given :birthDate :gender :identifier} (set (keys (first (:items (data/patient-list "pr-rivera")))))))
    (is (every? #(m/validate api-patients/row-schema %) (:items (data/patient-list nil {:limit 100})))))
  (testing "the panel of Dr. Rivera, by family name, and the clinic's"
    (is (= ["pt-liam" "pt-ana" "pt-omar" "pt-sofia"] (map :id (:items (data/patient-list "pr-rivera")))))
    (is (= 4 (:total (data/patient-list "pr-rivera"))))
    (is (= 6 (:total (data/patient-list nil))))))

(deftest the-inbox-of-alerts-is-what-the-api-answers
  (doseq [[practitioner caller scope] callers
          params [{} {:status "inactive"} {:sort "since"} {:status "inactive" :sort "since"} {:limit 1} {:limit 1 :offset 1} {:limit 100}]]
    (testing (str (or practitioner "the administrator") " " (pr-str params))
      (let [mine (data/alert-page practitioner params)]
        (is (m/validate api-alerts/list-schema mine))
        (is (= (alerts-of-api caller scope params) mine) "the same as the handler of the api makes of the same cohort"))))
  (testing "the alerts of the story: three active, one acknowledged this morning"
    (is (= ["flag-pt-maya-R4" "flag-pt-ana-R1" "flag-pt-sofia-R2"] (map :id (:items (data/alert-page nil)))) "the newest first")
    (is (= ["flag-pt-ana-R1" "flag-pt-sofia-R2"] (map :id (:items (data/alert-page "pr-rivera")))) "Maya is not Dr. Rivera's patient")
    (is (= ["flag-pt-maya-R4"] (map :id (:items (data/alert-page "pr-okafor")))))
    (is (= ["flag-pt-liam-R3"] (map :id (:items (data/alert-page "pr-rivera" {:status "inactive"})))))
    (is (= 3 (:total (data/alert-page nil))))))
