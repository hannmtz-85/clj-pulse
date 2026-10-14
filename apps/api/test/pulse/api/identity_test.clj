(ns pulse.api.identity-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [pulse.api.identity :as identity]))

(def ^:private keycloak-claims
  "The shape of a real access token: the application role sits among Keycloak's own."
  {:sub "899fbb2a"
   :preferred_username "ana.garcia@pulse.test"
   :azp "pulse-spa"
   :fhirUser "Patient/pt-ana"
   :realm_access {:roles ["default-roles-pulse" "patient" "offline_access" "uma_authorization"]}})

(deftest a-patient
  (is (= {:subject "899fbb2a"
          :username "ana.garcia@pulse.test"
          :client "pulse-spa"
          :roles #{:patient}
          :fhir-user {:type "Patient" :id "pt-ana"}}
         (identity/from-claims keycloak-claims))))

(deftest keycloaks-own-roles-mean-nothing-here
  (is (= #{:practitioner}
         (:roles (identity/from-claims
                  (assoc-in keycloak-claims [:realm_access :roles]
                            ["default-roles-pulse" "practitioner" "offline_access" "uma_authorization" "made-up"]))))))

(deftest a-caller-without-the-claims-is-still-an-identity
  (testing "no roles at all"
    (is (= #{} (:roles (identity/from-claims (dissoc keycloak-claims :realm_access))))))
  (testing "no fhirUser, like the admin and the scheduler"
    (is (nil? (:fhir-user (identity/from-claims (dissoc keycloak-claims :fhirUser))))))
  (testing "a fhirUser that is not a relative reference is ignored, not trusted"
    (is (nil? (:fhir-user (identity/from-claims (assoc keycloak-claims :fhirUser "http://evil.test/Patient/x")))))
    (is (nil? (:fhir-user (identity/from-claims (assoc keycloak-claims :fhirUser 42)))))))

(deftest a-service-account
  (is (= #{:rules-engine}
         (:roles (identity/from-claims {:sub "svc" :azp "pulse-scheduler"
                                        :realm_access {:roles ["default-roles-pulse" "rules-engine"]}})))))

(deftest the-roles-are-the-ones-the-realm-defines
  ;; The realm is generated from tools/realm.clj and committed as JSON; the api keeps its own
  ;; list of the roles it understands. If the two drift apart, either the api ignores a role or
  ;; it expects one that no token will ever carry.
  (let [realm (with-open [rdr (io/reader (io/file "infra/keycloak/realm/pulse-realm.json"))]
                (json/read rdr :key-fn keyword))
        realm-roles (into #{} (map (comp keyword :name)) (get-in realm [:roles :realm]))]
    (is (seq realm-roles))
    (is (= realm-roles identity/roles))))
