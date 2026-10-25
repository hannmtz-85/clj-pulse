(ns pulse.spa.person-details-test
  "What the mutation analysis of the claims found that `person-test` did not pin (Phase 8, step 3): a role that is not text is no role,
  and a roles claim that is not a list is no roles, whatever it is."
  (:require [clojure.test :refer [deftest is testing]]
            [pulse.spa.person :as person]))

(def ^:private rivera
  {"sub" "6f2c-rivera" "preferred_username" "dr.rivera@pulse.test" "name" "Elena Rivera"
   "realm_access" {"roles" ["practitioner"]}})

(defn- roles-of [roles-claim]
  (:roles (person/from-claims (assoc rivera "realm_access" {"roles" roles-claim}))))

(deftest a-role-that-is-not-written-as-text-is-no-role
  (testing "a keyword or a symbol that spells a role is not the text of one: a token is JSON, and says it in text"
    (is (= #{} (roles-of [:practitioner])))
    (is (= #{} (roles-of ['clinic-admin])))
    (is (= #{:practitioner} (roles-of [:practitioner "practitioner"])))))

(deftest a-roles-claim-that-is-not-a-list-is-no-roles-and-no-failure
  (doseq [claim [7 0 true false 1.5 {"practitioner" true} {"practitioner" "practitioner"} "practitioner" :practitioner nil]]
    (is (= #{} (roles-of claim)) (pr-str claim))))

(deftest a-list-of-roles-may-be-any-kind-of-sequence
  (is (= #{:practitioner :patient} (roles-of ["practitioner" "patient"])))
  (is (= #{:practitioner :patient} (roles-of '("practitioner" "patient"))))
  (is (= #{:practitioner} (roles-of (list "practitioner" "practitioner"))) "and a role is there once"))
