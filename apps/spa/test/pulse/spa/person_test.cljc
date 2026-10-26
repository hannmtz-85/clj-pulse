(ns pulse.spa.person-test
  "Who is signed in, from the claims of the access token (ADR-0029, point 1). These run on the JVM and on Node: the same function
  reads the same claims in the browser."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [pulse.spa.person :as person]))

(def ^:private rivera
  {"sub" "6f2c-rivera" "preferred_username" "dr.rivera@pulse.test" "name" "Elena Rivera"
   "realm_access" {"roles" ["default-roles-pulse" "offline_access" "practitioner" "uma_authorization"]}})

(deftest the-person-is-made-of-the-claims
  (is (= {:id "6f2c-rivera" :username "dr.rivera@pulse.test" :name "Elena Rivera" :roles #{:practitioner}}
         (person/from-claims rivera))))

(deftest only-the-application-roles-count
  (testing "the roles of the provider's own are not roles of the clinic"
    (is (= #{} (:roles (person/from-claims (assoc rivera "realm_access" {"roles" ["default-roles-pulse" "offline_access" "uma_authorization"]}))))))
  (testing "every role of the realm is known, and a person may have several"
    (is (= #{:patient :practitioner :clinic-admin :rules-engine :api-service}
           (:roles (person/from-claims (assoc rivera "realm_access" {"roles" ["patient" "practitioner" "clinic-admin" "rules-engine" "api-service"]}))))))
  (testing "a role that is not written as text is no role, and neither is one with another case"
    (is (= #{:practitioner} (:roles (person/from-claims (assoc rivera "realm_access" {"roles" ["practitioner" 7 nil :admin "Practitioner" "PRACTITIONER"]})))))))

(deftest what-the-token-does-not-have-is-not-invented
  (testing "no claims, or no one in them, is nobody"
    (is (nil? (person/from-claims nil)))
    (is (nil? (person/from-claims {})))
    (is (nil? (person/from-claims (dissoc rivera "sub"))))
    (is (nil? (person/from-claims (assoc rivera "sub" ""))))
    (is (nil? (person/from-claims (assoc rivera "sub" "   "))))
    (is (nil? (person/from-claims (assoc rivera "sub" 42)))))
  (testing "the name is the username when the provider has none, and the id when it has neither"
    (is (= "dr.rivera@pulse.test" (:name (person/from-claims (dissoc rivera "name")))))
    (is (= "dr.rivera@pulse.test" (:name (person/from-claims (assoc rivera "name" "  ")))))
    (is (= "6f2c-rivera" (:name (person/from-claims (dissoc rivera "name" "preferred_username")))))
    (is (nil? (:username (person/from-claims (dissoc rivera "preferred_username"))))))
  (testing "no roles claim, or one that is not a list, is no roles"
    (is (= #{} (:roles (person/from-claims (dissoc rivera "realm_access")))))
    (is (= #{} (:roles (person/from-claims (assoc rivera "realm_access" {"roles" "practitioner"})))))
    (is (= #{} (:roles (person/from-claims (assoc rivera "realm_access" "practitioner")))))))

(deftest the-administrator-is-told-from-the-role
  (is (true? (person/admin? {:roles #{:clinic-admin}})))
  (is (true? (person/admin? {:roles #{:clinic-admin :practitioner}})))
  (is (false? (person/admin? {:roles #{:practitioner}})))
  (is (false? (person/admin? {:roles #{}})))
  (is (false? (person/admin? nil))))

(def ^:private subsets
  "Every set of the roles of the realm: 32 of them."
  (reduce (fn [sets role] (into sets (map #(conj % role) sets))) #{#{}} person/roles))

(deftest the-workspace-is-for-the-people-of-the-clinic
  (is (= #{:practitioner :clinic-admin} person/workspace-roles))
  (is (= 32 (count subsets)) "every combination is tried")
  (doseq [roles subsets]
    (is (= (or (contains? roles :practitioner) (contains? roles :clinic-admin))
           (person/allowed? {:roles roles}))
        (pr-str roles)))
  (testing "a patient is not one of them, and neither is a service account"
    (is (false? (person/allowed? {:roles #{:patient}})))
    (is (false? (person/allowed? {:roles #{:rules-engine :api-service}}))))
  (testing "a person who is both is let in"
    (is (true? (person/allowed? {:roles #{:patient :practitioner}}))))
  (testing "nobody, and nobody with roles, is not let in"
    (is (false? (person/allowed? nil)))
    (is (false? (person/allowed? {})))
    (is (false? (person/allowed? {:roles nil})))
    (is (false? (person/allowed? {:roles #{}})))))

(deftest the-person-of-a-token-is-let-in-by-the-roles-of-the-token
  (is (true? (person/allowed? (person/from-claims rivera))))
  (is (false? (person/allowed? (person/from-claims (assoc rivera "realm_access" {"roles" ["default-roles-pulse" "patient"]})))))
  (is (false? (person/allowed? (person/from-claims (assoc rivera "realm_access" {"roles" ["default-roles-pulse"]})))))
  (is (true? (person/allowed? (person/from-claims (assoc rivera "realm_access" {"roles" ["clinic-admin"]}))))))

(deftest text-is-what-has-something-in-it
  (is (= "x" (person/text-of "x")))
  (is (= " x " (person/text-of " x ")))
  (doseq [x [nil "" " " "\n\t" 7 :x [] {}]]
    (is (nil? (person/text-of x)) (pr-str x))))

(def ^:private any-claims
  "Claims of any shape, such as a provider that is not ours, or a bug, could give, not too large: a structure of a thousand parts
  proves no more than one of ten, and takes minutes to make on Node."
  (gen/resize 8 (gen/map (gen/one-of [(gen/elements ["sub" "name" "preferred_username" "realm_access"]) gen/string-alphanumeric gen/keyword])
                         (gen/recursive-gen (fn [inner] (gen/one-of [(gen/vector inner) (gen/map gen/string-alphanumeric inner)]))
                                            (gen/one-of [gen/string gen/small-integer gen/boolean (gen/return nil)])))))

(defspec whatever-the-claims-are-the-person-is-nil-or-well-formed 300
  (prop/for-all [claims any-claims]
    (let [p (person/from-claims claims)]
      (or (nil? p)
          (and (string? (:id p))
               (string? (:name p))
               (set? (:roles p))
               (every? person/roles (:roles p)))))))
