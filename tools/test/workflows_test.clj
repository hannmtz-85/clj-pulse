(ns workflows-test
  "The two workflows of GitHub Actions (ADR-0030, point 8): CI runs what the documentation says it runs, `bb check`, and the integration
  workflow runs the integration tests, the journeys and the containers in the order they can run in, and is not required to merge. They can
  only run on GitHub, so what can be known here is read from the files."
  (:require [clj-yaml.core :as yaml]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- workflow [file] (yaml/parse-string (slurp (str ".github/workflows/" file))))

(defn- triggers
  "The `on:` of a workflow, which YAML 1.1 reads as the key `true`."
  [workflow]
  (or (:on workflow) (get workflow true)))

(defn- steps [workflow job] (:steps (get-in workflow [:jobs job])))

(defn- commands
  "The commands the `run:` steps run, one for each non-blank line."
  [steps]
  (vec (for [step steps
             line (str/split-lines (str (:run step)))
             :when (not (str/blank? line))]
         (str/trim line))))

(deftest every-action-is-pinned-to-a-version
  (doseq [file ["ci.yml" "integration.yml"]
          job (vals (:jobs (workflow file)))
          step (:steps job)
          :let [uses (:uses step)]
          :when uses]
    (is (re-matches #"[\w./-]+@v?\d+\.\d+(\.\d+)?" uses) (str file ": " uses " must name a version, not a branch or a major"))))

(deftest nothing-can-be-done-to-the-repository-by-either
  (doseq [file ["ci.yml" "integration.yml"]]
    (is (= {:contents "read"} (:permissions (workflow file))) file)))

(deftest ci-runs-what-the-documentation-says-it-runs
  (let [ci (workflow "ci.yml")
        run (commands (steps ci :check))]
    (is (= ["pull_request" "push"] (sort (map name (keys (triggers ci))))))
    (is (some #{"bb check"} run) "the format, the lint, the realm and the design files, the tests on the JVM and those on Node")
    (testing "after the packages that the tests of the workspace need"
      (is (< (.indexOf run "npm ci --prefix apps/spa") (.indexOf run "bb check")))
      (is (not (neg? (.indexOf run "npm ci --prefix apps/spa")))))
    (testing "with the Node the project asks for and Java 21"
      (is (= "22" (some #(get-in % [:with :node-version]) (steps ci :check))))
      (is (= "21" (some #(get-in % [:with :java-version]) (steps ci :check)))))))

(deftest the-integration-workflow-runs-by-hand-weekly-and-when-main-changes-but-not-for-a-pull-request
  (let [triggers (triggers (workflow "integration.yml"))]
    (is (= #{:workflow_dispatch :schedule :push} (set (keys triggers))))
    (is (= ["main"] (get-in triggers [:push :branches])))
    (is (= 1 (count (:schedule triggers))))
    (is (re-matches #"\d+ \d+ \* \* \d" (:cron (first (:schedule triggers)))) "once a week")))

(deftest the-integration-workflow-goes-from-the-stack-to-the-containers-in-an-order-in-which-nothing-is-in-the-way-of-anything
  (let [integration (workflow "integration.yml")
        run (commands (steps integration :integration))
        at (fn [command] (.indexOf run command))
        order ["bb up" "bb seed" "bb test:it" "bb spa:journey" "bb apps" "bb smoke" "bb web:journey"]]
    (is (every? #(not (neg? (at %))) order) "every one of them is run")
    (is (apply < (map at order)) (str "in this order: the tests of the stack and the journey of the workspace use the ports 3000, 3300, 4000 and 5000, "
                                      "which the containers then take, and the contract tests compare HAPI with the cohort as it was seeded, "
                                      "which the simulator and the journey of the containers change"))
    (testing "and the stack is stopped at the end, whatever happened"
      (let [last-step (last (steps integration :integration))]
        (is (= "bb down" (str/trim (:run last-step))))
        (is (= "always()" (:if last-step)))))
    (testing "and when it fails, the logs of the containers are kept"
      (let [with-if (filter #(= "failure()" (:if %)) (steps integration :integration))]
        (is (some #(str/includes? (str (:run %)) "docker compose --profile \"*\" logs") with-if))
        (is (some #(str/starts-with? (str (:uses %)) "actions/upload-artifact@") with-if))))
    (testing "with a time that ends a stuck run"
      (let [minutes (get-in integration [:jobs :integration :timeout-minutes])]
        (is (and (number? minutes) (<= 60 minutes 120)))))))
