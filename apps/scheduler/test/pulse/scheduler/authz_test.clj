(ns pulse.scheduler.authz-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [pulse.scheduler.authz :as authz]
            [reitit.ring :as ring]))

(def ^:private keycloak-claims
  {:sub "899fbb2a"
   :preferred_username "admin@pulse.test"
   :azp "pulse-api"
   :realm_access {:roles ["default-roles-pulse" "clinic-admin" "offline_access" "uma_authorization"]}})

(deftest who-is-calling
  (is (= {:subject "899fbb2a" :username "admin@pulse.test" :client "pulse-api" :roles #{"clinic-admin"}}
         (authz/caller keycloak-claims)))
  (testing "Keycloak's own roles, and ones nobody here has heard of, mean nothing"
    (is (= #{"practitioner"}
           (:roles (authz/caller (assoc-in keycloak-claims [:realm_access :roles]
                                           ["default-roles-pulse" "practitioner" "offline_access" "made-up"]))))))
  (testing "a token with no roles at all is still a caller"
    (is (= #{} (:roles (authz/caller (dissoc keycloak-claims :realm_access)))))))

(deftest the-administrator-may-do-what-the-rules-name
  (doseq [action [:jobs/read :jobs/run]
          [roles allowed?] [[#{"clinic-admin"} true]
                            [#{"clinic-admin" "practitioner"} true]
                            [#{"practitioner"} false]
                            [#{"patient"} false]
                            [#{"rules-engine"} false]
                            [#{} false]]]
    (is (= allowed? (authz/permits? {:roles roles} action)) (str action " " roles))))

(deftest the-api-may-read-the-status-and-start-nothing
  ;; ADR-0025: its service account has the role api-service and no other
  (is (true? (authz/permits? {:roles #{"api-service"}} :jobs/read)))
  (is (false? (authz/permits? {:roles #{"api-service"}} :jobs/run)))
  (testing "the rules engine, the other service, is neither"
    (is (false? (authz/permits? {:roles #{"rules-engine"}} :jobs/read)))
    (is (false? (authz/permits? {:roles #{"rules-engine"}} :jobs/run))))
  (testing "it is a role the scheduler understands, from a token as Keycloak makes it"
    (is (= #{"api-service"}
           (:roles (authz/caller {:sub "u-api" :azp "pulse-api"
                                  :realm_access {:roles ["default-roles-pulse" "offline_access" "api-service"]}}))))))

(deftest what-has-no-rule-is-permitted-to-nobody
  (is (false? (authz/permits? {:roles #{"clinic-admin"}} :jobs/delete)))
  (is (false? (authz/permits? {:roles #{"clinic-admin"}} nil))))

(deftest the-rules-name-the-actions-the-api-already-knows
  (is (contains? authz/rules :jobs/read) "the action of the api's GET /api/admin/jobs"))

(deftest the-roles-are-the-ones-the-realm-defines
  ;; The realm is generated from tools/realm.clj and committed as JSON; the scheduler keeps its own
  ;; list of the roles it understands. If the two drift apart, either it ignores a role or it
  ;; expects one that no token will ever carry.
  (let [realm (with-open [rdr (io/reader (io/file "infra/keycloak/realm/pulse-realm.json"))]
                (json/read rdr :key-fn keyword))
        realm-roles (into #{} (map :name) (get-in realm [:roles :realm]))]
    (is (seq realm-roles))
    (is (= realm-roles authz/roles))
    (is (every? authz/roles (apply concat (vals authz/rules))) "and the rules use only those")))

;; ---------------------------------------------------------------------------
;; Deny by default

(defn- router [routes]
  (ring/router routes))

(defn- undeclared
  "The endpoints `check-routes!` names, or nil when it accepts the router."
  [routes]
  (try (authz/check-routes! (router routes))
       nil
       (catch clojure.lang.ExceptionInfo e
         (is (= ::authz/missing-authz (:type (ex-data e))))
         (:endpoints (ex-data e)))))

(deftest an-endpoint-needs-a-valid-authz
  (is (nil? (undeclared [["/a" {:get {:authz :public :handler identity}}]
                         ["/b" {:get {:authz {:action :jobs/read} :handler identity}}]])))
  (is (= ["get /a"] (undeclared [["/a" {:get {:handler identity}}]])) "none at all")
  (is (= ["get /a"] (undeclared [["/a" {:get {:authz {:action :jobs/delete} :handler identity}}]]))
      "an action nobody wrote a rule for")
  (is (= ["get /a"] (undeclared [["/a" {:get {:authz {:roles #{"clinic-admin"}} :handler identity}}]]))
      "roles named in the route instead of an action")
  (is (= ["get /a" "post /a"] (undeclared [["/a" {:get {:handler identity} :post {:authz "everybody" :handler identity}}]]))))

(deftest the-message-names-what-to-fix
  (let [e (try (authz/check-routes! (router [["/a" {:get {:handler identity}}]]))
               (catch clojure.lang.ExceptionInfo e e))]
    (is (= "Endpoints without a valid :authz (deny by default): [\"get /a\"]" (ex-message e)))))
