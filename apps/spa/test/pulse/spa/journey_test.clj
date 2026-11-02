(ns pulse.spa.journey-test
  "The acceptance of the workspace in a real browser (`apps/spa/journey.mjs`, run by `bb spa:journey`) needs Chrome, the stack and the api,
  so `bb check` cannot run it. What it can check is that the scripts are there and that Node accepts them, that the task of `bb.edn` runs
  it and says what it needs, that the journey goes through every page the workspace has (a route added to the table and not to the
  journey fails here), that it has the controls that make \"no violation\" mean something, and that the dependency it adds, axe-core, is
  pinned to an exact version as every other is."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [pulse.spa.route :as route]))

(def ^:private script "apps/spa/journey.mjs")

(def ^:private source (delay (slurp script)))

(deftest the-journey-and-its-library-are-scripts-that-node-accepts
  (doseq [file [script "tools/cdp.mjs"]]
    (is (.exists (io/file file)) file)
    (let [{:keys [exit err]} (shell/sh "node" "--check" file)]
      (is (zero? exit) (str file ": " err)))))

(deftest bb-runs-the-journey-and-says-what-it-needs
  (let [task (get-in (edn/read-string (slurp "bb.edn")) [:tasks 'spa:journey])]
    (is (some? task))
    (is (= '([spa-journey]) (:requires task)))
    (is (str/includes? (pr-str (:task task)) "spa-journey/run!"))
    (doseq [word ["bb up" "bb dev lite" "3000" "3300" "4000" "5000" "--no-build" "--shots" "--only"]]
      (is (str/includes? (:doc task) word) word))))

(def ^:private addresses
  "Every address in the script, as text: the strings that begin with a slash."
  (delay (set (map second (re-seq #"\"(/[^\"\s]*)\"" @source)))))

(defn- reached-by?
  "Whether `address` is a page of the route `pattern` (`/appointments/:id/confirm`): the address names an id by a word in braces
  (`{REQUEST}`) or by one that is real (`pt-ana`); its query does not count."
  [pattern address]
  (let [as-regex (re-pattern (str "^" (str/replace pattern #":[a-z]+" "[^/?]+") "$"))]
    (boolean (re-find as-regex (first (str/split address #"\?"))))))

(deftest the-journey-visits-every-route-of-the-workspace
  (doseq [[pattern {route-name :name}] route/routes]
    (is (some #(reached-by? pattern %) @addresses) (str route-name " " pattern " is visited by no address of the journey"))))

(deftest the-journey-visits-a-page-that-is-no-page
  (is (contains? @addresses "/nothing-here")))

(deftest an-address-is-reached-by-the-route-whose-pattern-it-fits
  (is (reached-by? "/" "/"))
  (is (reached-by? "/" "/?show=alerts"))
  (is (reached-by? "/patients/:id" "/patients/pt-ana"))
  (is (not (reached-by? "/patients/:id" "/patients")))
  (is (reached-by? "/appointments/:id/confirm" "/appointments/{REQUEST}/confirm"))
  (is (not (reached-by? "/appointments/:id/confirm" "/appointments/{REQUEST}/cancel")))
  (is (not (reached-by? "/jobs" "/jobs/1"))))

(deftest the-journey-checks-what-the-acceptance-of-the-phase-says
  (testing "a clinician signs in through SSO, filters their panel, opens a chart with graphs, acknowledges an alert and confirms an appointment"
    (doseq [phrase ["a visitor is sent to the login page of Keycloak" "the filter is in the address, narrows the list and loads nothing"
                    "the chart of Ana is her name, her alert and a graph of her blood pressure" "the alert is gone from the chart"
                    "the dialog closes when the api has answered" "the appointment is among the booked ones, confirmed"]]
      (is (str/includes? @source phrase) phrase)))
  (testing "a doctor sees only what is theirs, an administrator the jobs, and a patient is told the workspace is not for them"
    (doseq [phrase ["nobody else's name was in any page of the walk" "the chart of a patient of another doctor"
                    "the jobs of the scheduler are listed" "a patient is told the workspace is for the clinic"
                    "nothing at all was asked of the api for them"]]
      (is (str/includes? @source phrase) phrase)))
  (testing "no token is anywhere but where it goes"
    (is (str/includes? @source "no token in either storage"))
    (is (str/includes? @source "a token goes to the api and to nobody else"))))

(deftest the-journey-looks-at-what-goes-wrong-and-not-only-at-what-goes-right
  (doseq [phrase ["when the list of patients fails the panel says so" "it is never shown as a panel with nobody in it"
                  "when the alerts cannot be loaded the inbox says so for both lists" "when one list of appointments fails the others are there"
                  "a call that never answers gives up" "a wait that does not end says so"
                  "when a page cannot be drawn the person is told so, and the page is not white"
                  "an api that does not accept the sign-in even after it was renewed"]]
    (is (str/includes? @source phrase) phrase)))

(deftest the-journey-has-the-controls-that-make-nothing-found-mean-something
  (testing "what the policy must block is blocked"
    (is (str/includes? @source "allowUnsafeEvalBlockedByCSP: false"))
    (is (str/includes? @source "the control: ${what} is blocked by the policy"))
    (doseq [what ["an inline script" "an inline style" "a style attribute" "eval" "a request to another server" "a frame of another server"
                  "a form posted to another server"]]
      (is (str/includes? @source (str "\"" what "\"")) what)))
  (testing "something moves while a page is on its way, so that nothing moving when the person asks for less is a finding"
    (is (str/includes? @source "the control: while a page is on its way something moves"))))

(deftest the-journey-looks-at-the-four-widths-and-both-schemes-and-asks-axe
  (is (str/includes? @source "const widths = [320, 390, 820, 1280];"))
  (is (str/includes? @source "const schemes = [\"light\", \"dark\"];"))
  (is (str/includes? @source "axe-core finds nothing wrong"))
  (is (str/includes? (slurp "tools/cdp.mjs") "wcag22aa") "the rules are those of WCAG 2.2, and the best practice")
  (testing "and the keyboard"
    (doseq [phrase ["the first stop is the link that skips to the content" "the keyboard reaches every control" "every stop shows that the focus is there"
                    "the keyboard is not trapped" "Escape closes the dialog and the focus goes back"]]
      (is (str/includes? @source phrase) phrase))))

(deftest the-journey-changes-only-what-it-says-it-does
  (testing "it presses the button that sends once, to confirm the request that waits, and acknowledges one alert"
    (is (= 1 (count (re-seq #"#appointment-submit" @source))))
    (is (= 1 (count (re-seq #"click\(page, `#\$\{ackId\}`\)" @source))))))

(defn- json-file [path]
  (json/read-value (io/file path) json/keyword-keys-object-mapper))

(deftest axe-core-is-a-dev-dependency-pinned-to-an-exact-version-as-every-other-is
  (let [package (json-file "apps/spa/package.json")
        lock (json-file "apps/spa/package-lock.json")
        exact? #(boolean (re-matches #"\d+\.\d+\.\d+" %))]
    (is (exact? (get-in package [:devDependencies :axe-core])))
    (is (every? exact? (vals (:dependencies package))) "no range of versions among what the release bundles")
    (is (every? exact? (vals (:devDependencies package))))
    (testing "it is for the journey: the release does not bundle it"
      (is (nil? (get-in package [:dependencies :axe-core]))))
    (testing "and the lock holds the version the package says"
      (is (= (get-in package [:devDependencies :axe-core]) (get-in lock [:packages :node_modules/axe-core :version]))))))

(deftest the-journey-looks-at-less-motion-and-forced-colours
  (doseq [phrase ["when the person asks for less motion nothing moves" "in forced colours the text is not the colour of its background"
                  "in forced colours the page and the filter that are current are told from the others by more than a colour"]]
    (is (str/includes? @source phrase) phrase)))
