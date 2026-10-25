(ns pulse.spa.person-roles-test
  "The roles that the workspace knows are the api's, which are the realm's (ADR-0029, point 1)."
  (:require [clojure.test :refer [deftest is]]
            [pulse.api.identity :as identity]
            [pulse.spa.person :as person]))

(deftest the-roles-of-the-workspace-are-the-roles-of-the-api
  (is (= identity/roles person/roles)
      "a role the realm has that the workspace does not know would be dropped from the person, and one it invents would never be granted"))
