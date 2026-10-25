(ns pulse.web.journey-test
  "The acceptance of the portal in a real browser (`apps/web/journey.mjs`, run by `bb web:journey`) is a script that needs Chrome, the
  stack and the portal running, so `bb check` cannot run it. What it can check is that the script is there, that it is JavaScript that
  Node accepts, that the task of `bb.edn` runs it, and that it goes through the pages that the portal has: the pages of its bar are the
  pages of the navigation of the portal, so a page added to the portal and not to the journey fails here."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.web.pages :as pages]
            [pulse.web.routes :as routes]))

(def ^:private script "apps/web/journey.mjs")

(def ^:private source (delay (slurp script)))

(deftest the-journey-is-a-script-that-node-accepts
  (is (.exists (io/file script)))
  (let [{:keys [exit err]} (shell/sh "node" "--check" script)]
    (is (zero? exit) err)))

(deftest bb-runs-the-journey-and-says-what-it-needs
  (let [task (get-in (edn/read-string (slurp "bb.edn")) [:tasks 'web:journey])]
    (is (some? task))
    (is (str/includes? (pr-str (:task task)) "apps/web/journey.mjs"))
    (is (str/includes? (:doc task) "bb up"))
    (is (str/includes? (:doc task) "3300"))))

(deftest the-journey-goes-through-the-pages-of-the-navigation-and-no-other
  (let [in-the-script (map second (re-seq #"\{ name: \"[^\"]+\", path: \"([^\"]+)\"" @source))
        names (map second (re-seq #"\{ name: \"([^\"]+)\", path: \"[^\"]+\"" @source))]
    (is (= (map :href pages/nav) in-the-script) "the same pages, in the order of the bar")
    (is (= (map :label pages/nav) names) "called what the bar calls them")))

(deftest the-journey-knows-the-routes-of-the-portal-it-visits
  (let [patient-paths (set (for [[path methods] (routes/routes {}) :when (= :patient (get-in methods [:get :authz]))] path))]
    (testing "every address it visits that is not a page of the bar is a route of a patient"
      (doseq [path ["/readings/new" "/appointments"]]
        (is (contains? patient-paths path) path))
      (is (some #(str/includes? % "/cancel") patient-paths)))
    (testing "and the sign-out it uses is a route that posts"
      (is (some #(= "/sign-out" (first %)) (routes/routes {}))))))

(deftest the-journey-checks-what-the-acceptance-of-the-phase-says
  (testing "a patient signs in through SSO, sees only their own data, requests an appointment and signs out properly"
    (doseq [phrase ["login page of the Pulse theme" "brought back to the profile" "nothing of the other patients"
                    "request for a visit is sent" "signing out ends the session" "This portal is for patients"]]
      (is (str/includes? @source phrase) phrase)))
  (testing "and it has the control that makes \"no violation\" mean something"
    (is (str/includes? @source "the control: an inline script, an inline style and eval are blocked"))
    (is (str/includes? @source "allowUnsafeEvalBlockedByCSP: false"))))

(deftest the-journey-leaves-the-confirmed-appointment-as-it-found-it
  (testing "it cancels only what it asked for itself: the request, and not the appointment of the cohort"
    (is (not (str/includes? @source "Cancel the appointment")) "the confirmed appointment is only looked at and kept")
    (is (str/includes? @source "Keep it"))))
