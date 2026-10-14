(ns pulse.api.authz-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [pulse.api.authz :as authz]
            [pulse.api.identity :as identity]
            [reitit.ring :as ring]))

(defn- caller [& roles]
  {:subject "u1" :roles (set roles) :fhir-user nil})

;; ---------------------------------------------------------------------------
;; The decision: a table of caller x action -> decision

(deftest the-decision-table
  (doseq [[label who action expected]
          [["a patient may read their identity" (caller :patient) :identity/read {:allow? true}]
           ["so may a practitioner" (caller :practitioner) :identity/read {:allow? true}]
           ["and the admin" (caller :clinic-admin) :identity/read {:allow? true}]
           ["and a service account" (caller :rules-engine) :identity/read {:allow? true}]
           ["and a caller with no application role at all" (caller) :identity/read {:allow? true}]
           ["an action nobody wrote a rule for is denied to the admin" (caller :clinic-admin) :patient/delete-everything
            {:allow? false :reason :no-rule-for-action}]
           ["and to everyone else" (caller :patient :practitioner :clinic-admin :rules-engine) :nope
            {:allow? false :reason :no-rule-for-action}]]]
    (testing label
      (is (= expected (authz/authorize who action nil))))))

(deftest roles-are-checked-when-a-rule-asks-for-them
  (with-redefs [authz/rules {:chart/read {:roles #{:practitioner :clinic-admin}}}]
    (is (= {:allow? true} (authz/authorize (caller :practitioner) :chart/read nil)))
    (is (= {:allow? true} (authz/authorize (caller :patient :clinic-admin) :chart/read nil)) "any one role is enough")
    (is (= {:allow? false :reason :role-not-allowed} (authz/authorize (caller :patient) :chart/read nil)))
    (is (= {:allow? false :reason :role-not-allowed} (authz/authorize (caller) :chart/read nil)))))

(defspec an-action-without-a-rule-is-denied-to-every-caller 300
  (prop/for-all [roles (gen/set (gen/elements (vec identity/roles)))
                 action (gen/such-that #(not (contains? authz/rules %)) gen/keyword-ns)]
    (= {:allow? false :reason :no-rule-for-action}
       (authz/authorize (caller) action nil)
       (authz/authorize {:roles roles} action {:type "Patient" :id "x"}))))

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
                                      ["/b" {:post {:authz {:action :identity/read} :handler ok}}]]))))
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
    (is (= ["get /a"] (unprotected-endpoints [["/a" {:get {:authz {:action :nobody/defined} :handler ok}}]])))))

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
