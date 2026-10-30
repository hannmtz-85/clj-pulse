(ns pulse.spa.render-test
  "What React says of the pages of the workspace once the adapter has made them ready for Reagent (ADR-0029, point 2): nothing. The
  pages are rendered to a string on Node, where React is the development build and says in the console what it does not like (a
  list with no keys, an attribute it does not take), and the console is read. These run on Node only: there is no React on the JVM."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.spa.hiccup :as hiccup]
            [pulse.spa.pages.panel :as panel-page]
            [pulse.spa.pages.patient :as patient-page]
            [pulse.spa.pages.shell :as shell]
            [pulse.spa.pages.start :as start]
            [pulse.spa.panel :as panel]
            [pulse.spa.patient :as patient]
            [pulse.spa.test.panel-fixtures :as fx]
            [pulse.spa.test.patient-fixtures :as pfx]
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

(defn- frame [person options]
  (shell/frame (merge {:current :panel :person person :links links} options) [:h1 "Panel"]))

(def ^:private sam {:id "s1" :username "admin@pulse.test" :name "Sam Admin" :roles #{:clinic-admin}})

(defn- the-panel [state params person]
  (panel-page/page state params {:person person
                                 :links {:panel "/" :filter (fn [show] (str "/?show=" (name show)))}
                                 :handlers {:retry (fn []) :sign-out (fn []) :search (fn [_])}}))

(defn- the-chart [state person]
  (patient-page/page state {:person person
                            :links {:panel "/" :chart (fn [{:keys [measurement days]}] (str "/patients/pt-ana?days=" days "&measurement=" (name measurement)))}
                            :handlers {:retry (fn []) :sign-out (fn [])}}))

(def ^:private pages
  {"the wait" (start/starting)
   "the failure" (start/failed {:retry (fn [])})
   "the frame of the person who is signed in" (frame rivera {})
   "the frame, with the menu that signs out and what the api said" (frame rivera {:handlers {:sign-out (fn [])}
                                                                                  :me {:state :loaded :data {:resource {:name [{:prefix ["Dr."] :given ["Elena"] :family "Rivera"}]}}}})
   "a person the workspace is not for" (start/refused {:links {:portal "http://localhost:3300"} :handlers {:sign-out (fn [])}})
   "someone who has signed out" (start/signed-out {:links {:sign-in "/"}})
   "a sign-in that the api did not accept" (start/unauthorized {:links {:sign-in "/"}})
   "an address that is no page" (start/not-found {:person rivera :links {:panel "/"} :handlers {:sign-out (fn [])}})
   "a page that is not for the person" (start/not-allowed {:person rivera :links {:panel "/"}})
   "the panel before it begins" (the-panel nil {:show :all :q ""} rivera)
   "the panel with the list on its way" (the-panel (panel/start fx/now) {:show :all :q ""} rivera)
   "the panel, loaded" (the-panel (fx/settled) {:show :all :q ""} rivera)
   "the panel of the administrator" (the-panel (fx/settled) {:show :all :q ""} sam)
   "the panel, narrowed by a filter" (the-panel (fx/settled) {:show :alerts :q ""} rivera)
   "the panel, narrowed by a search that finds nobody" (the-panel (fx/settled) {:show :all :q "zzz"} rivera)
   "the panel whose list could not be loaded" (the-panel (assoc (fx/settled) :list fx/failed :rows {} :order []) {:show :all :q ""} rivera)
   "the panel with patients that could not be loaded" (the-panel (-> (fx/settled)
                                                                     (assoc-in [:rows "pt-liam" :summary] fx/failed)
                                                                     (assoc-in [:rows "pt-ana" :series] fx/failed))
                                                                 {:show :all :q ""} rivera)
   "the panel with figures that could not be loaded" (the-panel (assoc-in (fx/settled) [:inbox :active] fx/failed) {:show :all :q ""} rivera)
   "the panel with no patients" (the-panel (-> (fx/settled) (assoc-in [:list :data] {:total 0 :offset 0 :limit 100 :items []}) (assoc :rows {} :order []))
                                           {:show :all :q ""} rivera)
   "the chart of a patient before it begins" (the-chart nil rivera)
   "the chart of a patient on its way" (the-chart (patient/start pfx/now {:id "pt-ana" :measurement nil :days 14}) rivera)
   "the chart of a patient, loaded" (the-chart (pfx/settled-ana) rivera)
   "the chart of a patient, of another measurement and period" (the-chart (pfx/settled-ana {:asked :heart-rate :days 30}) sam)
   "the chart of a patient with a low saturation" (the-chart (pfx/settled-sofia) rivera)
   "the chart of a patient with nothing measured" (the-chart (pfx/settled-noah) rivera)
   "the chart of a patient who is not the person's" (the-chart (assoc (pfx/settled-ana) :patient pfx/forbidden) rivera)
   "the chart of a patient with parts that could not be loaded" (the-chart (-> (pfx/settled-ana)
                                                                               (assoc :summary {:state :failed :kind :server})
                                                                               (assoc :series {:state :failed :kind :timeout :measurement :blood-pressure :days 14}))
                                                                           rivera)
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
  (let [{:keys [html]} (rendered (hiccup/->reagent (frame rivera {})))]
    (is (str/includes? html "Elena Rivera"))
    (is (str/includes? html "Practitioner"))))

(deftest the-panel-is-on-the-page-with-its-patients-and-its-sparklines
  (let [{:keys [html messages]} (rendered (hiccup/->reagent (the-panel (fx/settled) {:show :all :q ""} rivera)))]
    (is (= [] messages))
    (doseq [text ["Your panel" "Ana García" "Sofía Rossi" "Liam Chen" "Omar Haddad" "Noah Kim" "Very high" "177/100" "Active alerts"
                  "Away from normal" "With active alerts" "Blood pressure over the last 14 days"]]
      (is (str/includes? html text) text))
    (is (= 4 (count (re-seq #"<svg[^>]*role=\"img\"[^>]*aria-label=\"[^\"]* over the last 14 days\"" html))) "a sparkline for each patient who has readings")))

(deftest the-search-box-is-a-field-whose-text-comes-from-the-address
  (let [{:keys [html messages]} (rendered (hiccup/->reagent (the-panel (fx/settled) {:show :all :q "ana"} rivera)))]
    (is (= [] messages) "a field with a value and a function to change it is a controlled field, which React takes without a word")
    (is (str/includes? html "value=\"ana\""))))

(deftest what-a-person-typed-or-a-provider-sent-is-text-and-never-markup
  (let [hostile {:id "h1" :username "<script>alert(1)</script>" :name "<img src=x onerror=alert(1)> \"quoted\" & co" :roles #{:practitioner}}
        {:keys [html messages]} (rendered (hiccup/->reagent (frame hostile {})))]
    (is (= [] messages))
    (is (str/includes? html "&lt;img src=x onerror=alert(1)&gt;"))
    (is (not (str/includes? html "<script")))
    (is (not (str/includes? html "<img"))))
  (testing "nor can the name of a patient"
    (let [state (-> (fx/settled)
                    (assoc-in [:list :data :items 1 :name] "<script>alert(1)</script>")
                    (assoc-in [:list :data :items 1 :identifier] "<img src=x onerror=alert(1)>"))
          {:keys [html messages]} (rendered (hiccup/->reagent (the-panel state {:show :all :q ""} rivera)))]
      (is (= [] messages))
      (is (str/includes? html "&lt;script&gt;alert(1)&lt;/script&gt;"))
      (is (not (str/includes? html "<script")))
      (is (not (str/includes? html "<img src"))))))
