(ns pulse.spa.render-test
  "What React says of the pages of the workspace once the adapter has made them ready for Reagent (ADR-0029, point 2): nothing. The
  pages are rendered to a string on Node, where React is the development build and says in the console what it does not like (a
  list with no keys, an attribute it does not take), and the console is read. These run on Node only: there is no React on the JVM."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.spa.hiccup :as hiccup]
            [pulse.spa.pages.start :as start]
            [pulse.ui.chart :as chart]
            [pulse.ui.component :as c]
            [reagent.dom.server :as server]))

(defn- rendered
  "What rendering `form`, a Reagent form, to a string gave: `{:html :messages}`, the second being everything React and Reagent said
  in the console, with the console put back as it was."
  [form]
  (let [messages (atom [])
        error (.-error js/console)
        warn (.-warn js/console)
        keep-message (fn [& args] (swap! messages conj (str/join " " (map str args))))]
    (set! (.-error js/console) keep-message)
    (set! (.-warn js/console) keep-message)
    (try
      {:html (server/render-to-string form) :messages @messages}
      (finally
        (set! (.-error js/console) error)
        (set! (.-warn js/console) warn)))))

(def ^:private rivera {:id "r1" :username "dr.rivera@pulse.test" :name "Elena Rivera" :roles #{:practitioner}})

(def ^:private links {:panel "/" :alerts "/alerts" :appointments "/appointments"})

(def ^:private a-chart
  (chart/line-chart {:id "bp" :title "Systolic" :description "A month of blood pressure" :x-label str
                     :bands [{:from 90 :to 130 :tone :normal :label "Normal"}]
                     :thresholds [{:at 150 :tone :high :label "R1"}]
                     :series [{:label "Systolic" :unit "mmHg" :points [[1 120] [2 126] [3 131] [4 160]]}]}))

(def ^:private pages
  {"the wait" (start/starting)
   "the failure" (start/failed {:retry (fn [])})
   "the person who is signed in" (start/signed-in rivera {:links links})
   "the person who is signed in, with the menu that signs out and what the api said" (start/signed-in rivera {:links links
                                                                                                              :handlers {:sign-out (fn [])}
                                                                                                              :me {:state :loaded :data {:fhirUser "Practitioner/pr-rivera"}}})
   "a person the workspace is not for" (start/refused {:links {:portal "http://localhost:3300"} :handlers {:sign-out (fn [])}})
   "someone who has signed out" (start/signed-out {:links {:sign-in "/"}})
   "a sign-in that the api did not accept" (start/unauthorized {:links {:sign-in "/"}})
   "a chart and its table" [:div a-chart]
   "a table that scrolls" (c/data-table {:caption "Patients" :columns [{:key :name :label "Name"}] :rows [{:name "Ana"} {:name "Liam"}]})})

(deftest a-page-made-ready-for-reagent-says-nothing-to-react
  (doseq [[label page] pages
          :let [{:keys [html messages]} (rendered (hiccup/->reagent page))]]
    (testing label
      (is (> (count html) 100) "it was rendered")
      (is (= [] messages)))))

(deftest the-check-can-fail-react-says-what-it-does-not-like-of-hiccup-made-for-a-server
  (testing "a list with no keys"
    (is (some #(str/includes? % "key") (:messages (rendered [:ul (for [x ["a" "b"]] [:li x])])))))
  (testing "the components, as they are made, without the adapter"
    (let [{:keys [messages]} (rendered a-chart)]
      (is (seq messages))
      (is (some #(str/includes? % "key") messages)))))

(deftest the-adapter-keeps-the-text-of-the-page
  (doseq [[label page] pages
          :let [made (:html (rendered (hiccup/->reagent page)))]]
    (testing label
      (is (not (str/includes? made "undefined")))
      (is (not (str/includes? made "[object Object]"))))))

(deftest the-person-who-is-signed-in-is-on-the-page
  (let [{:keys [html]} (rendered (hiccup/->reagent (start/signed-in rivera {:links links})))]
    (is (str/includes? html "Elena Rivera"))
    (is (str/includes? html "dr.rivera@pulse.test"))
    (is (str/includes? html "Practitioner"))
    (is (str/includes? html "You are signed in"))))

(deftest what-a-person-typed-or-a-provider-sent-is-text-and-never-markup
  (let [hostile {:id "h1" :username "<script>alert(1)</script>" :name "<img src=x onerror=alert(1)> \"quoted\" & co" :roles #{:practitioner}}
        {:keys [html messages]} (rendered (hiccup/->reagent (start/signed-in hostile {:links links})))]
    (is (= [] messages))
    (is (str/includes? html "&lt;script&gt;alert(1)&lt;/script&gt;"))
    (is (str/includes? html "&lt;img src=x onerror=alert(1)&gt;"))
    (is (not (str/includes? html "<script")))
    (is (not (str/includes? html "<img")))))
