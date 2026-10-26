(ns pulse.spa.person-roles-test
  "The roles that the workspace knows are the api's, which are the realm's (ADR-0029, point 1), and the ones it is for are those
  that the api lets read the inbox of alerts."
  (:require [clojure.test :refer [deftest is]]
            [pulse.api.authz :as authz]
            [pulse.api.identity :as identity]
            [pulse.spa.person :as person]))

(deftest the-roles-of-the-workspace-are-the-roles-of-the-api
  (is (= identity/roles person/roles)
      "a role the realm has that the workspace does not know would be dropped from the person, and one it invents would never be granted"))

(deftest the-workspace-is-for-the-roles-that-the-api-lets-read-the-inbox
  (is (= person/workspace-roles (set (keys (get authz/rules :alert/read))))
      "a role the api gave the inbox to would be refused by the workspace, and one the api does not would be let in to be refused by the api")
  (is (every? person/roles person/workspace-roles))
  (is (= person/workspace-roles (disj (set (keys (get authz/rules :patient/list))) :rules-engine))
      "and the roles that see a panel of patients are the same, but for the rules engine, which is no one who signs in"))
