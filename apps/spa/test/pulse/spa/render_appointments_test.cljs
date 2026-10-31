(ns pulse.spa.render-appointments-test
  "What React says of the appointments and of the jobs once the adapter has made them ready for Reagent (ADR-0029, point 2): nothing, in any
  of their states, the dialog with its fields among them (a field with a value and a function that changes it is a controlled field, which
  React takes without a word), and which page the workspace shows for the routes of both. The pages are rendered to a string on Node, where
  React is the development build and says in the console what it does not like. These run on Node only: there is no React on the JVM."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [day8.re-frame.test :as rf-test]
            [pulse.spa.appointment-command :as command]
            [pulse.spa.appointments :as appointments]
            [pulse.spa.hiccup :as hiccup]
            [pulse.spa.jobs :as jobs]
            [pulse.spa.pages.appointments :as appointments-page]
            [pulse.spa.pages.jobs :as jobs-page]
            [pulse.spa.test.appointments-fixtures :as afx]
            [pulse.spa.test.jobs-fixtures :as jfx]
            [pulse.spa.test.patient-fixtures :as pfx]
            [pulse.spa.views :as views]
            [re-frame.db :as db]
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
(def ^:private sam {:id "s1" :username "admin@pulse.test" :name "Sam Admin" :roles #{:clinic-admin}})
(def ^:private ana {:id "a1" :username "ana.garcia@pulse.test" :name "Ana García" :roles #{:patient}})

(def ^:private links
  {:panel "/" :alerts "/alerts" :appointments "/appointments" :jobs "/jobs"
   :filter (fn [show] (str "/appointments" (when (#{:requests :booked :cancelled} show) (str "?show=" (name show)))))
   :patient (fn [id] (str "/patients/" id))
   :dialog (fn [id action] (str "/appointments/" id "/" (name action)))
   :close "/appointments"})

(def ^:private handlers
  {:retry (fn []) :sign-out (fn []) :edit (fn [_ _]) :submit (fn [_]) :key-down (fn [_])})

(defn- the-appointments [state person show]
  (appointments-page/page state {:person person :show show :alert-count 2 :links links :handlers handlers}))

(defn- the-jobs [state]
  (jobs-page/page state {:person sam :alert-count 2 :links links :handlers handlers}))

(defn- with-dialog [state id action & typed]
  (reduce (fn [state [field value]] (appointments/edit state field value))
          (appointments/sync-dialog state {:id id :action action})
          (partition 2 typed)))

(def ^:private pages
  {"the appointments before they begin" (the-appointments nil rivera :all)
   "the appointments on their way" (the-appointments (appointments/start afx/now) rivera :all)
   "the appointments, loaded" (the-appointments (afx/settled) rivera :all)
   "the appointments of the administrator" (the-appointments (afx/settled) sam :all)
   "the requests only" (the-appointments (afx/settled) rivera :requests)
   "the booked ones only" (the-appointments (afx/settled) rivera :booked)
   "the cancelled ones only" (the-appointments (afx/settled) rivera :cancelled)
   "nothing at all" (the-appointments (assoc (afx/settled) :requests (afx/loaded afx/none) :booked (afx/loaded afx/none) :cancelled (afx/loaded afx/none)) rivera :all)
   "a list that could not be loaded" (the-appointments (assoc (afx/settled) :booked afx/failed) rivera :all)
   "the names that could not be loaded" (the-appointments (assoc (afx/settled) :patients afx/failed) rivera :all)
   "a list that could not be updated" (the-appointments (assoc-in (afx/settled) [:latest :requests] afx/failed) rivera :all)
   "a list that was cut" (the-appointments (assoc (afx/settled) :booked (afx/loaded (afx/page [afx/ana-booked] 130))) rivera :all)
   "a confirmation" (the-appointments (with-dialog (afx/settled) "appt-sofia-1" :confirm) rivera :all)
   "a confirmation with what was typed" (the-appointments (with-dialog (afx/settled) "appt-sofia-1" :confirm :day "2026-10-20" :from "11:15") rivera :requests)
   "a confirmation with words beside the fields" (the-appointments (update (with-dialog (afx/settled) "appt-sofia-1" :confirm) :dialog command/refused
                                                                           {:day "Must not be in the past." :from "Enter a time." :until "Must be after the start."}
                                                                           {:kind :unconfirmed})
                                                                   rivera :all)
   "a rescheduling" (the-appointments (with-dialog (afx/settled) "appt-ana-1" :reschedule) rivera :booked)
   "a cancellation" (the-appointments (with-dialog (afx/settled) "appt-liam-1" :cancel :why "away") rivera :all)
   "a cancellation of a request" (the-appointments (with-dialog (afx/settled) "appt-sofia-1" :cancel) rivera :all)
   "a cancellation with words beside the field" (the-appointments (update (with-dialog (afx/settled) "appt-liam-1" :cancel) :dialog command/refused {:why "Must be 1 to 200 characters."} nil) rivera :all)
   "a command on its way" (the-appointments (first (appointments/submit (with-dialog (afx/settled) "appt-sofia-1" :confirm) afx/now)) rivera :all)
   "a command that cannot be done" (the-appointments (with-dialog (afx/settled) "appt-ana-1" :confirm) rivera :all)
   "an appointment on its way" (the-appointments (with-dialog (assoc (afx/settled) :requests afx/loading) "appt-sofia-1" :confirm) rivera :all)
   "an appointment that could not be loaded" (the-appointments (with-dialog (assoc (afx/settled) :booked afx/failed) "appt-nobody" :confirm) rivera :all)
   "an appointment that is nowhere" (the-appointments (with-dialog (afx/settled) "appt-nobody" :confirm) rivera :all)
   "the jobs before they begin" (the-jobs nil)
   "the jobs on their way" (the-jobs (jobs/start jfx/now))
   "the jobs, loaded" (the-jobs (jfx/settled))
   "no jobs" (the-jobs {:now jfx/now :list (jfx/loaded {:total 0 :items []})})
   "a job that has not run" (the-jobs {:now jfx/now :list (jfx/loaded {:total 1 :items [jfx/never-run]})})
   "jobs that could not be loaded" (the-jobs {:now jfx/now :list jfx/failed})
   "jobs the person may not see" (the-jobs {:now jfx/now :list {:state :failed :kind :forbidden}})})

(deftest a-page-made-ready-for-reagent-says-nothing-to-react
  (doseq [[label page] pages
          :let [{:keys [html messages]} (rendered (hiccup/->reagent page))]]
    (testing label
      (is (> (count html) 100) "it was rendered")
      (is (= [] messages)))))

(deftest the-text-of-the-pages-is-kept
  (doseq [[label page] pages
          :let [made (:html (rendered (hiccup/->reagent page)))]]
    (testing label
      (is (not (str/includes? made "undefined")))
      (is (not (str/includes? made "[object Object]"))))))

(deftest the-fields-of-the-dialog-are-controlled-fields-with-what-was-typed
  (let [{:keys [html messages]} (rendered (hiccup/->reagent (the-appointments (with-dialog (afx/settled) "appt-sofia-1" :confirm :from "11:15") rivera :all)))]
    (is (= [] messages))
    (is (str/includes? html "value=\"2026-10-16\""))
    (is (str/includes? html "value=\"11:15\""))
    (is (str/includes? html "value=\"10:30\""))
    (is (str/includes? html "type=\"date\""))
    (is (str/includes? html "type=\"time\""))
    (is (str/includes? html "noValidate=\"\"") "the form is judged by the lifecycle and the api, which write what is wrong beside the field")))

(deftest what-the-clinic-wrote-is-text-and-never-markup
  (let [hostile "<script>alert(1)</script>"
        state (-> (afx/settled)
                  (assoc-in [:requests :data :items 0 :description] hostile)
                  (assoc-in [:patients :data :items 4 :name] "<img src=x onerror=alert(1)>"))
        {:keys [html messages]} (rendered (hiccup/->reagent (the-appointments (with-dialog state "appt-sofia-1" :confirm) rivera :all)))]
    (is (= [] messages))
    (is (str/includes? html "&lt;script&gt;alert(1)&lt;/script&gt;"))
    (is (not (str/includes? html "<script")))
    (is (not (str/includes? html "<img src")))))

;; ---------------------------------------------------------------------------
;; Which page the workspace shows

(defn- shown
  "The text of the root view when the state is `state`."
  [state]
  (reset! db/app-db state)
  (str/replace (str/replace (server/render-to-string [views/root]) #"<[^>]*>" " ") #"\s+" " "))

(defn- shown-html [state]
  (reset! db/app-db state)
  (server/render-to-string [views/root]))

(defn- says? [text words] (str/includes? text words))

(deftest the-list-of-appointments-is-the-page-of-its-route
  (rf-test/run-test-sync
   (let [state {:status :signed-in :person rivera :route {:name :appointments :params {:show :all}} :appointments (afx/settled)}
         text (shown state)
         html (shown-html state)]
     (is (says? text "Requests to answer"))
     (is (says? text "COPD check-up (requested by the patient)"))
     (is (says? text "Elena Rivera"))
     (testing "with its tabs, its patients and the buttons that lead to the dialogs"
       (is (str/includes? html "href=\"/appointments?show=requests\""))
       (is (str/includes? html "href=\"/patients/pt-sofia\""))
       (is (str/includes? html "href=\"/appointments/appt-sofia-1/confirm\""))
       (is (str/includes? html "href=\"/appointments/appt-ana-1/reschedule\""))
       (is (str/includes? html "href=\"/appointments/appt-ana-1/cancel\"")))
     (testing "and the sidebar has them as the current page"
       (is (re-find #"href=\"/appointments\"[^>]*aria-current=\"page\"|aria-current=\"page\"[^>]*href=\"/appointments\"" html))))))

(deftest before-the-list-has-begun-it-is-the-wait-inside-the-workspace
  (rf-test/run-test-sync
   (let [text (shown {:status :signed-in :person rivera :route {:name :appointments :params {:show :all}}})]
     (is (says? text "Appointments"))
     (is (says? text "Loading the appointments")))))

(deftest a-dialog-is-over-the-list-and-the-way-out-keeps-the-narrowing
  (rf-test/run-test-sync
   (doseq [[route-name action id] [[:appointment-confirm :confirm "appt-sofia-1"] [:appointment-reschedule :reschedule "appt-ana-1"]
                                   [:appointment-cancel :cancel "appt-liam-1"]]
           :let [state {:status :signed-in :person rivera
                        :route {:name route-name :params {:show :all :id id :action action}}
                        :appointments (with-dialog (afx/settled) id action)}
                 html (shown-html state)]]
     (is (str/includes? html "<dialog") (name route-name))
     (is (str/includes? html "Requests to answer") "the list is behind it")
     (is (str/includes? html "id=\"appointment-form\"")))
   (let [html (shown-html {:status :signed-in :person rivera
                           :route {:name :appointment-confirm :params {:show :requests :id "appt-sofia-1" :action :confirm}}
                           :appointments (with-dialog (afx/settled) "appt-sofia-1" :confirm)})]
     (is (re-find #"id=\"appointment-close\"" html))
     (is (re-find #"href=\"/appointments\?show=requests\"[^>]*id=\"appointment-close\"|id=\"appointment-close\"[^>]*href=\"/appointments\?show=requests\"" html)
         "the way out leads to the list as it was narrowed"))))

(deftest the-jobs-are-the-page-of-the-administrators-route
  (rf-test/run-test-sync
   (let [state {:status :signed-in :person sam :route {:name :jobs :params {}} :jobs (jfx/settled)}
         text (shown state)
         html (shown-html state)]
     (is (says? text "Jobs"))
     (is (says? text "evaluate-rules"))
     (is (says? text "The last run of send-reminders failed"))
     (is (says? text "Sam Admin"))
     (is (str/includes? html "href=\"/jobs\"") "the sidebar has them")
     (is (says? text "Administration")))))

(deftest a-practitioner-who-writes-the-address-of-the-jobs-is-told-they-are-not-for-them
  (rf-test/run-test-sync
   (let [text (shown {:status :signed-in :person rivera :route {:name :jobs :params {}} :jobs (jfx/settled)})]
     (is (says? text "You cannot open that page"))
     (is (not (says? text "evaluate-rules")) "nothing of the jobs is shown"))))

(deftest the-sidebar-has-the-appointments-for-everyone-in-and-the-jobs-for-the-administrator-only
  (rf-test/run-test-sync
   (let [practitioner (shown-html {:status :signed-in :person rivera :route {:name :panel :params {:show :all :q ""}}})
         administrator (shown-html {:status :signed-in :person sam :route {:name :panel :params {:show :all :q ""}}})]
     (is (str/includes? practitioner "href=\"/appointments\""))
     (is (not (str/includes? practitioner "href=\"/jobs\"")))
     (is (str/includes? administrator "href=\"/appointments\""))
     (is (str/includes? administrator "href=\"/jobs\"")))))

(deftest a-person-whose-role-does-not-open-the-appointments-is-told-so
  (rf-test/run-test-sync
   (doseq [route-name [:appointments :appointment-confirm :appointment-reschedule :appointment-cancel]]
     (is (says? (shown {:status :signed-in :person ana :route {:name route-name :params {:show :all :id "x" :action :confirm}}}) "You cannot open that page")
         (name route-name)))))

(deftest the-chart-of-a-patient-has-the-buttons-of-its-appointments-that-lead-to-their-dialogs
  (rf-test/run-test-sync
   (let [html (shown-html {:status :signed-in :person rivera
                           :route {:name :patient :params {:id "pt-ana" :measurement nil :days 14}}
                           :chart (pfx/settled-ana)})]
     (is (str/includes? html "href=\"/appointments/appt-ana-1/reschedule\""))
     (is (str/includes? html "href=\"/appointments/appt-ana-1/cancel\"")))))
