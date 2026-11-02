(ns pulse.design.workspace-test
  "The pages of the clinical workspace: what each one shows of the panel of Dr. Elena Rivera, whose patients are Ana
  (a very high blood pressure), Sofía (a low oxygen saturation), Liam and Omar, and of the jobs for the
  administrator. What the pages offer is held to the lifecycle of an appointment and to the data of the cohort."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.design.build :as build]
            [pulse.design.data :as data]
            [pulse.design.workspace :as workspace]
            [pulse.fhir.appointment :as appointment]
            [pulse.spa.appointments :as appointments]
            [pulse.spa.inbox :as inbox]
            [pulse.spa.pages.alerts :as alerts-page]
            [pulse.spa.pages.appointments :as appointments-page]
            [pulse.spa.pages.jobs :as jobs-page]
            [pulse.spa.pages.panel :as panel-page]
            [pulse.spa.pages.patient :as patient-page]
            [pulse.spa.pages.start :as start]
            [pulse.spa.patient :as patient]
            [pulse.ui.test.hiccup :as hx]))

(def ^:private context (build/context))

(defn- main-of [doc] (first (hx/by-tag doc :main)))

(defn- words-of-main [doc] (hx/words (main-of doc)))

(defn- buttons [tree]
  (hx/select tree #(and (hx/has-class? % "btn") (#{:a :button} (hx/tag %)))))

(defn- button-texts [tree] (map hx/words (buttons tree)))

(defn- href [el] (:href (hx/attributes el)))

(defn- by-label [tree label]
  (first (hx/select tree #(= label (:aria-label (hx/attributes %))))))

(defn- list-named [tree label]
  (first (hx/select tree #(and (= :ul (hx/tag %)) (= label (:aria-label (hx/attributes %)))))))

(defn- section-of [doc id]
  (first (hx/select doc #(and (= :section (hx/tag %)) (= id (:aria-labelledby (hx/attributes %)))))))

(defn- rows [tree] (hx/by-tag tree :li))

(defn- lines [row] (map hx/words (hx/by-tag row :p)))

(defn- stats [doc]
  (for [stat (hx/select (main-of doc) #(and (= :div (hx/tag %)) (hx/has-class? % "shadow-card") (hx/has-class? % "p-4")
                                            (not (hx/has-class? % "relative"))))]
    (map hx/words (hx/by-tag stat :p))))

(def ^:private button-label {:confirm "Confirm" :reschedule "Reschedule" :cancel "Cancel"})

;; ---------------------------------------------------------------------------
;; The panel

(deftest the-panel-says-how-many-patients-and-what-needs-a-look
  (let [doc (workspace/panel context)]
    (is (= ["Panel · Pulse"] (map hx/words (hx/by-tag doc :title))) "the title of the page of the review")
    (is (= ["Your panel"] (map hx/words (hx/by-tag doc :h1))))
    (is (str/includes? (words-of-main doc) "The 4 patients you look after, the most urgent first."))
    (is (= [["Patients" "4"]
            ["Active alerts" "2" "1 acknowledged today"]
            ["To confirm" "2" "requests for a visit"]
            ["Away from normal" "3" "outside the normal range"]]
           (stats doc)))
    (testing "the alerts are in the colour of what needs attention, and nothing else is"
      (let [alert-stat (second (hx/select (main-of doc) #(and (= :div (hx/tag %)) (hx/has-class? % "shadow-card") (hx/has-class? % "p-4"))))]
        (is (hx/has-class? (second (hx/by-tag alert-stat :p)) "text-tone-critical-ink"))))))

(deftest the-panel-can-be-filtered-and-the-counts-are-those-of-the-patients
  (let [pills (by-label (workspace/panel context) "Filter the panel")]
    (is (= ["All4" "With active alerts2" "Away from normal3" "Normal1"] (map hx/words (hx/by-tag pills :a))))
    (is (= ["All4"] (keep #(when (= "page" (:aria-current (hx/attributes %))) (hx/words %)) (hx/by-tag pills :a))))))

(deftest the-panel-has-the-most-urgent-patient-first
  (let [doc (workspace/panel context)
        table (first (hx/by-tag doc :table))
        body (first (hx/by-tag table :tbody))
        table-rows (hx/by-tag body :tr)
        cells (fn [row] (hx/select row #(#{:th :td} (hx/tag %))))]
    (is (= "Patients of your panel, the most urgent first" (hx/words (first (hx/by-tag table :caption)))))
    (is (= ["Patient" "Status" "Latest reading" "Last 14 days" "Alerts" "Next visit"]
           (map hx/words (hx/select (first (hx/by-tag table :thead)) #(= :th (hx/tag %))))))
    (is (= ["Ana GarcíaPULSE-0001 · 58 years" "Sofía RossiPULSE-0003 · 67 years" "Liam ChenPULSE-0002 · 46 years" "Omar HaddadPULSE-0004 · 35 years"]
           (map #(let [c (hx/words (first (cells %)))] (str/replace c #"^[A-Z]{2}" "")) table-rows)))
    (is (= ["Very high" "Low" "Elevated" "Normal"] (map #(hx/words (nth (cells %) 1)) table-rows)))
    (is (= ["177/100 mmHgBlood pressure, today, 10:00" "90 %Oxygen saturation, today, 10:00"]
           (map #(hx/words (nth (cells %) 2)) (take 2 table-rows))))
    (is (= ["1 active" "1 active" "None" "None"] (map #(hx/words (nth (cells %) 4)) table-rows)))
    (is (= ["Wed 14 Oct, 10:00–10:30" "–" "Fri 16 Oct, 14:00–14:30" "–"] (map #(hx/words (nth (cells %) 5)) table-rows)))
    (testing "the reading does not break in the middle of its number and its unit"
      (is (every? #(hx/has-class? (first (hx/by-tag (nth (cells %) 2) :p)) "whitespace-nowrap") table-rows)))
    (testing "the patient heads the row, for a screen reader, and does not break in the middle of a name"
      (is (every? #(= [:th "row"] ((juxt hx/tag (comp :scope hx/attributes)) (first (cells %)))) table-rows))
      (is (every? #(hx/has-class? (first (cells %)) "whitespace-nowrap") table-rows)))
    (testing "each patient opens their chart"
      (is (= (map #(str "../" (get workspace/charts %)) ["pt-ana" "pt-sofia" "pt-liam" "pt-omar"])
             (map #(href (first (hx/by-tag (first (cells %)) :a))) table-rows))))
    (testing "the trend of each is a small chart with a name"
      (is (every? #(str/includes? (str (:aria-label (hx/attributes (first (hx/by-tag (nth (cells %) 3) :svg))))) "over the last 14 days") table-rows)))
    (testing "a narrow screen leaves out the next visit"
      (is (every? #(hx/has-class? % "hidden") [(last (hx/select (first (hx/by-tag table :thead)) #(= :th (hx/tag %)))) (last (cells (first table-rows)))]))
      (is (hx/has-class? (last (cells (first table-rows))) "xl:table-cell")))))

(deftest the-sidebar-counts-the-alerts-that-are-active-for-the-panel
  (let [nav (by-label (workspace/panel context) "Workspace")
        alerts (second (hx/by-tag nav :a))]
    (is (= ["Panel" "Alerts 2 active" "Appointments"] (map #(:aria-label (hx/attributes %)) (hx/by-tag nav :a))))
    (is (= "2" (hx/words (first (hx/select alerts #(hx/has-class? % "tabular-nums"))))))))

;; ---------------------------------------------------------------------------
;; The chart of a patient

(defn- drawings
  "The drawings of charts in `tree`: the svgs that are images, and not the icons."
  [tree]
  (hx/select tree #(and (= :svg (hx/tag %)) (= "img" (:role (hx/attributes %))))))

(deftest the-chart-of-ana-has-the-alert-the-latest-readings-the-chart-and-the-rest-of-her-record
  (let [doc ((workspace/patient "pt-ana") context)
        main (main-of doc)]
    (is (= ["Ana García"] (map hx/words (hx/by-tag doc :h1))))
    (is (str/includes? (hx/words main) "58 years · Female · PULSE-0001 · Valencia"))
    (is (= ["Panel" "Ana García"] (map hx/words (hx/by-tag (by-label doc "Breadcrumb") :li))))
    (is (= ["../workspace-panel.html"] (map href (hx/by-tag (by-label doc "Breadcrumb") :a))))
    (is (= ["Essential (primary) hypertension · I10"] (map hx/words (hx/by-tag (by-label doc "Conditions") :li))))
    (testing "the alert that is active: the rule, the readings behind it, and the buttons to acknowledge it and to see all of them"
      (let [alert (first (hx/select main #(and (= :div (hx/tag %)) (hx/has-class? % "bg-tone-critical-soft"))))]
        (is (= "BP stage 2" (hx/words (first (hx/by-tag alert :p)))) "its title")
        (is (str/includes? (hx/words alert) "Raised yesterday, 22:30."))
        (is (str/includes? (hx/words alert) "177/100 mmHg, today, 10:00; 172/102 mmHg, yesterday, 22:00; 165/106 mmHg, yesterday, 10:00"))
        (is (= ["Acknowledge" "All alerts"] (button-texts alert)))
        (is (= ["../workspace-alerts.html" "../workspace-alerts.html"] (map href (buttons alert))) "the mock-up leads both to the inbox")))
    (testing "the latest readings and the chart of the one furthest from normal"
      (is (= ["177/100" "70" "82.3"] (map hx/words (hx/select (section-of doc "vitals") #(hx/has-class? % "text-4xl")))))
      (is (= #{"chart-blood-pressure-wide-title chart-blood-pressure-wide-desc" "chart-blood-pressure-narrow-title chart-blood-pressure-narrow-desc"}
             (set (map (comp :aria-labelledby hx/attributes) (drawings main)))))
      (is (str/includes? (words-of-main doc) "Last 14 days"))
      (is (str/includes? (words-of-main doc) "Stage 2 · 160")))
    (testing "the pills that choose another measurement or period are there, and the mock-up does not make them work"
      (is (= ["Blood pressure" "Heart rate" "Weight"] (map hx/words (hx/by-tag (by-label main "Measurement") :a))))
      (is (= ["7 days" "14 days" "30 days"] (map hx/words (hx/by-tag (by-label main "Period") :a)))))
    (testing "there is no search box: it would have nothing to search"
      (is (empty? (hx/select doc #(= "search" (:type (hx/attributes %)))))))))

(deftest the-appointments-in-a-chart-are-in-order-and-have-what-the-lifecycle-allows
  (let [doc ((workspace/patient "pt-ana") context)
        list-el (list-named doc "Appointments")
        list-rows (rows list-el)]
    (is (= [["Hypertension follow-up" "Wed 14 Oct, 10:00–10:30"]
            ["Medication review" "Asked for a visit between Oct 20 and Oct 27"]
            ["Blood pressure check" "Tue 29 Sep, 09:00–09:30" "Reason: I had to travel that week."]]
           (map lines list-rows))
        "what is to come first, and what was cancelled last")
    (testing "each says its status in words"
      (is (every? true? (map (fn [row word] (str/includes? (hx/words row) word)) list-rows ["Confirmed" "Requested" "Cancelled"]))))
    (testing "and each has the buttons that the lifecycle gives a clinician, which lead to the dialogs over the list of appointments"
      (is (= [["Reschedule" "Cancel"] ["Confirm" "Cancel"] []] (map #(map hx/words (buttons %)) list-rows)))
      (is (= [["../workspace-reschedule.html" "../workspace-cancel-appointment.html"] ["../workspace-confirm.html" "../workspace-cancel-appointment.html"] []]
             (map #(map href (buttons %)) list-rows))))))

(deftest the-chart-of-sofia-is-of-her-oxygen-and-has-her-allergy
  (let [doc ((workspace/patient "pt-sofia") context)
        main (main-of doc)]
    (is (= ["Sofía Rossi"] (map hx/words (hx/by-tag doc :h1))))
    (is (str/includes? (hx/words main) "Chronic obstructive pulmonary disease, unspecified · J44.9"))
    (is (= "Low SpO₂" (hx/words (first (hx/by-tag (first (hx/select main #(and (= :div (hx/tag %)) (hx/has-class? % "bg-tone-high-soft")))) :p)))))
    (is (some? (hx/by-id doc "chart-spo2-wide-title")))
    (is (str/includes? (hx/words main) "Last 14 days"))
    (is (= [["Penicillin G" "Skin rash"]] (map lines (rows (list-named doc "Allergies")))))
    (is (= [["Albuterol" "2 puffs as needed"] ["Tiotropium" "18 mcg inhaled once daily"]] (map lines (rows (list-named doc "Medication")))) "in the order the summary of the api gives them: by name")
    (is (= [["COPD check-up (requested by the patient)" "Asked for a visit between Oct 16 and Oct 23"]]
           (map lines (rows (list-named doc "Appointments")))))))

(deftest the-chart-of-liam-has-no-alert-because-his-was-acknowledged
  (let [doc ((workspace/patient "pt-liam") context)
        main (main-of doc)]
    (is (empty? (hx/select main #(and (= :div (hx/tag %)) (hx/has-class? % "bg-tone-high-soft")))))
    (is (empty? (hx/select main #(and (= :div (hx/tag %)) (hx/has-class? % "bg-tone-critical-soft")))))
    (is (str/includes? (hx/words main) "Type 2 diabetes mellitus without complications · E11.9"))
    (is (some? (hx/by-id doc "chart-blood-pressure-wide-title")) "the pressure, which is elevated, and not the glucose, which is normal")
    (is (= [["Diabetes review" "Fri 16 Oct, 14:00–14:30"]] (map lines (rows (list-named doc "Appointments")))))))

(deftest the-chart-of-omar-says-what-is-not-there
  (let [doc ((workspace/patient "pt-omar") context)
        text (words-of-main doc)]
    (is (str/includes? text "No conditions recorded."))
    (is (nil? (by-label doc "Conditions")) "no list of conditions")
    (is (not (str/includes? text "Acknowledge")))
    (is (str/includes? text "AppointmentsNone."))
    (is (str/includes? text "MedicationNone."))
    (is (str/includes? text "AllergiesNone recorded."))))

(deftest the-state-of-a-chart-is-what-the-workspace-keeps-and-the-page-is-the-one-it-serves
  (let [state (workspace/patient-state "pt-ana")]
    (is (= "pt-ana" (:id state)))
    (is (nil? (:asked state)) "the headline reading, which the cohort says is the blood pressure")
    (is (= :blood-pressure (get-in state [:series :measurement])))
    (is (= 14 (:days state)))
    (is (= #{:blood-pressure} (set (keys (:evidence state)))) "the readings behind the one alert, of what its rule watches")
    (is (patient/settled? state))
    (is (= {} (:evidence (workspace/patient-state "pt-omar"))))
    (is (nil? (:series (workspace/patient-state "pt-noah"))) "a patient who has nothing measured has nothing to draw")))

(deftest every-chart-of-the-panel-is-a-page-of-the-registry
  (is (= #{"pt-ana" "pt-sofia" "pt-liam" "pt-omar"} (set (keys workspace/charts))))
  (is (= 4 (count (set (vals workspace/charts))))))

;; ---------------------------------------------------------------------------
;; The alerts

(deftest the-inbox-has-what-needs-a-decision-with-the-readings-behind-it
  (let [doc (workspace/alerts context)
        needs (section-of doc "needs")
        alerts (hx/select needs #(and (= :div (hx/tag %)) (hx/has-class? % "grid") (hx/has-class? % "rounded-box")))]
    (is (= ["Alerts"] (map hx/words (hx/by-tag doc :h1))))
    (is (= ["BP stage 2 · Ana García" "Low SpO₂ · Sofía Rossi"] (map #(hx/words (first (hx/by-tag % :p))) alerts))
        "the newest first")
    (is (= [:critical :high] (map #(if (hx/has-class? % "bg-tone-critical-soft") :critical :high) alerts)))
    (let [ana (first alerts)]
      (is (str/includes? (hx/words ana) "Raised yesterday, 22:30."))
      (is (str/includes? (hx/words ana) "Blood pressure at stage 2 (a systolic of 160 or more"))
      (is (= ["177/100 mmHg, today, 10:00" "172/102 mmHg, yesterday, 22:00" "165/106 mmHg, yesterday, 10:00"]
             (map hx/words (hx/by-tag ana :li))))
      (is (= ["Acknowledge" "Open the chart"] (button-texts ana)))
      (is (= "../workspace-patient.html" (href (second (buttons ana))))))
    (is (= "../workspace-patient-sofia.html" (href (second (buttons (second alerts))))))))

(deftest the-inbox-is-the-page-the-workspace-serves-made-from-the-cohort
  (let [state (workspace/inbox-state workspace/practitioner-id)]
    (testing "of the lists the api answers a practitioner"
      (is (= ["flag-pt-ana-R1" "flag-pt-sofia-R2"] (map :id (get-in state [:active :data :items]))) "the newest first")
      (is (= ["flag-pt-liam-R3"] (map :id (get-in state [:acknowledged :data :items]))))
      (is (= #{"pt-ana" "pt-sofia" "pt-liam" "pt-omar"} (set (map :id (get-in state [:patients :data :items])))) "the panel, and not Maya"))
    (testing "and the readings behind the alerts, by the patient and the measurement of the rule"
      (is (= #{["pt-ana" :blood-pressure] ["pt-sofia" :spo2]} (set (keys (:evidence state)))))
      (is (every? #(= 3 (count (get-in % [:data :items]))) (vals (:evidence state)))))
    (testing "settled: nothing waits and nothing is being looked at"
      (is (true? (inbox/settled? state)))
      (is (= {} (:latest state))))
    (testing "the page is the one the workspace serves, and the mock-up only leads its buttons to the pages there are"
      (let [served (alerts-page/content state {:show :all :admin? false :links workspace/inbox-links})]
        (is (= (hx/words served) (hx/words (main-of (workspace/alerts context)))))))))

(deftest the-administrator-s-inbox-has-every-patient-s-alerts
  (let [state (workspace/inbox-state nil)]
    (is (= 3 (get-in state [:active :data :total])))
    (is (contains? (set (keys (:evidence state))) ["pt-maya" :weight]))
    (is (str/includes? (hx/words (alerts-page/content state {:show :all :admin? true :links workspace/inbox-links})) "Rapid weight gain · Maya Patel"))))

(deftest the-inbox-has-what-was-acknowledged-and-says-no-more-than-the-api-does
  (let [doc (workspace/alerts context)
        done (section-of doc "done")
        row (first (rows done))]
    (is (= 1 (count (rows done))))
    (is (= ["Glucose out of range · Liam Chen" "Raised Oct 11, 22:30, acknowledged today, 09:40"] (lines row)))
    (is (str/includes? (hx/words row) "Acknowledged"))
    (is (= ["Open the chart"] (button-texts row)) "nothing left to decide")
    (is (not (re-find #"(?i) by " (hx/words row))) "the api does not say who")))

(deftest the-inbox-has-only-the-alerts-of-the-panel
  (let [text (words-of-main (workspace/alerts context))]
    (is (not (str/includes? text "Rapid weight gain")) "that of Maya Patel is Dr. Okafor's")
    (is (not (str/includes? text "Maya")))
    (let [pills (by-label (workspace/alerts context) "Show")]
      (is (= ["Active2" "Acknowledged1" "All3"] (map hx/words (hx/by-tag pills :a))))
      (is (= ["All3"] (keep #(when (= "page" (:aria-current (hx/attributes %))) (hx/words %)) (hx/by-tag pills :a)))))))

;; ---------------------------------------------------------------------------
;; The appointments

(deftest the-appointments-are-the-requests-what-is-booked-and-what-was-cancelled
  (let [doc (workspace/appointments context)
        section-rows (fn [id] (rows (section-of doc id)))
        text-of (fn [row] (hx/words (first (hx/by-tag row :p))))]
    (is (some? (by-label doc "Appointments by status")))
    (is (= ["COPD check-up (requested by the patient)" "Medication review"] (map text-of (section-rows "requests"))))
    (is (= ["Hypertension follow-up" "Diabetes review"] (map text-of (section-rows "booked"))))
    (is (= ["Blood pressure check"] (map text-of (section-rows "cancelled"))))
    (is (= [["COPD check-up (requested by the patient)" "Sofía Rossi · Asked for a visit between Oct 16 and Oct 23"]
            ["Medication review" "Ana García · Asked for a visit between Oct 20 and Oct 27"]]
           (map lines (section-rows "requests"))))
    (is (= [["Hypertension follow-up" "Ana García · Wed 14 Oct, 10:00–10:30"] ["Diabetes review" "Liam Chen · Fri 16 Oct, 14:00–14:30"]]
           (map lines (section-rows "booked"))))
    (is (= [["Blood pressure check" "Ana García · Tue 29 Sep, 09:00–09:30" "Reason: I had to travel that week."]]
           (map lines (section-rows "cancelled"))))
    (testing "the patient is a link to their chart"
      (is (= ["../workspace-patient-sofia.html" "../workspace-patient.html"]
             (map #(href (first (hx/by-tag (second (hx/by-tag % :p)) :a))) (section-rows "requests")))))))

(deftest the-tabs-count-the-appointments-of-each-status
  (let [tabs (by-label (workspace/appointments context) "Appointments by status")]
    (is (= ["All (5)" "Requests (2)" "Booked (2)" "Cancelled (1)"] (map hx/words (hx/by-tag tabs :a))))
    (is (= ["All (5)"] (keep #(when (= "page" (:aria-current (hx/attributes %))) (hx/words %)) (hx/by-tag tabs :a))))))

(deftest what-a-clinician-may-do-with-an-appointment-is-what-the-lifecycle-says
  (let [doc (workspace/appointments context)
        offered (fn [id] (map #(set (map hx/words (buttons %))) (rows (section-of doc id))))
        allowed (fn [status] (set (map button-label (appointment/available-actions {:status status} :clinician data/now-text))))]
    (is (= [(allowed "proposed") (allowed "proposed")] (offered "requests")))
    (is (= [(allowed "booked") (allowed "booked")] (offered "booked")))
    (is (= [(allowed "cancelled")] (offered "cancelled")))
    (is (= #{"Confirm" "Cancel"} (allowed "proposed")))
    (is (= #{"Reschedule" "Cancel"} (allowed "booked")))
    (is (= #{} (allowed "cancelled")))))

(deftest the-appointments-are-those-of-the-panel-and-not-of-the-other-doctor
  (let [text (words-of-main (workspace/appointments context))]
    (is (not (str/includes? text "Heart failure review")) "that of Maya Patel is Dr. Okafor's")
    (is (not (str/includes? text "New patient intake")) "and so is Noah Kim's")))

(deftest the-state-of-the-appointments-is-what-the-api-answers-a-practitioner
  (let [state (workspace/appointments-state workspace/practitioner-id)]
    (testing "the three lists of the api, by status, in the order the api gives them"
      (is (= ["appt-sofia-1" "appt-ana-2"] (map :id (get-in state [:requests :data :items]))) "the soonest asked-for first")
      (is (= ["appt-ana-1" "appt-liam-1"] (map :id (get-in state [:booked :data :items]))))
      (is (= ["appt-ana-0"] (map :id (get-in state [:cancelled :data :items])))))
    (testing "the patients of the panel, and not Maya or Noah"
      (is (= #{"pt-ana" "pt-sofia" "pt-liam" "pt-omar"} (set (map :id (get-in state [:patients :data :items]))))))
    (testing "nothing waits and the page is settled"
      (is (true? (appointments/settled? state)))
      (is (= {} (:latest state))))
    (testing "the page is the one the workspace serves, and the mock-up only leads its buttons to the pages there are"
      (let [served (appointments-page/content state {:show :all :admin? false :links workspace/appointment-links})]
        (is (= (hx/words served) (hx/words (main-of (workspace/appointments context)))))))))

(deftest the-administrator-s-appointments-are-every-patient-s
  (let [state (workspace/appointments-state nil)]
    (is (= 3 (get-in state [:requests :data :total])) "Noah's request too")
    (is (= 3 (get-in state [:booked :data :total])) "and Maya's appointment")
    (is (str/includes? (hx/words (appointments-page/content state {:show :all :admin? true :links workspace/appointment-links})) "New patient intake"))))

(deftest the-appointment-pages-have-no-search-box-and-lead-their-dialogs-to-the-mock-ups-of-each-command
  (doseq [f [workspace/appointments workspace/confirm workspace/reschedule workspace/cancel-appointment]
          :let [doc (f context)]]
    (is (nil? (hx/by-id doc "search")) "a box that searched nothing here would be a control with nothing to do")
    (is (some? (section-of doc "requests"))))
  (let [row-buttons (fn [id] (map (juxt hx/words href) (buttons (first (hx/select (workspace/appointments context) #(= (str "appointment-" id) (:id (hx/attributes %))))))))]
    (is (= [["Confirm" "../workspace-confirm.html"] ["Cancel" "../workspace-cancel-appointment.html"]] (row-buttons "appt-sofia-1")))
    (is (= [["Reschedule" "../workspace-reschedule.html"] ["Cancel" "../workspace-cancel-appointment.html"]] (row-buttons "appt-ana-1")))))

(deftest the-form-to-confirm-gives-a-time-inside-the-days-that-were-asked-for
  (let [doc (workspace/confirm context)
        dialog (first (hx/by-tag doc :dialog))
        day (:value (hx/attributes (hx/by-id dialog "day")))
        [lower upper] (:slot-minutes appointment/limits)]
    (is (true? (:open (hx/attributes dialog))))
    (is (= "Confirm the appointment" (hx/words (hx/by-id dialog "appointment-dialog-title"))))
    (is (str/includes? (hx/words dialog) "COPD check-up (requested by the patient)"))
    (is (str/includes? (hx/words dialog) "Sofía Rossi"))
    (is (str/includes? (hx/words dialog) "Asked for a visit between Oct 16 and Oct 23"))
    (is (<= (compare "2026-10-16" day) 0) "not before the first day")
    (is (<= (compare day "2026-10-23") 0) "nor after the last")
    (is (= ["10:00" "10:30"] (map #(:value (hx/attributes (hx/by-id dialog %))) ["from" "until"])))
    (is (= ["date" "time" "time"] (map #(:type (hx/attributes (hx/by-id dialog %))) ["day" "from" "until"])))
    (is (str/includes? (hx/words (hx/by-id dialog "until-hint")) (str lower " minutes to " (quot upper 60) " hours")))
    (is (= ["Not now" "Confirm"] (map hx/words (sort-by #(if (hx/has-class? % "btn-ghost") 0 1) (buttons dialog)))))
    (is (= ["../workspace-appointments.html"] (keep href (buttons dialog))) "the way out leads to the list; the other button sends the form")
    (testing "the patient is told what happens, and no more than the system does"
      (is (str/includes? (hx/words dialog) "Sofía sees the new time in the portal."))
      (is (not (str/includes? (hx/words dialog) "reminded")) "no endpoint shows a reminder to the patient"))
    (testing "behind it, the appointments"
      (is (some? (section-of doc "requests"))))))

(deftest the-form-to-reschedule-starts-from-the-time-the-appointment-has
  (let [doc (workspace/reschedule context)
        dialog (first (hx/by-tag doc :dialog))]
    (is (= "Reschedule the appointment" (hx/words (hx/by-id dialog "appointment-dialog-title"))))
    (is (str/includes? (hx/words dialog) "Hypertension follow-up"))
    (is (str/includes? (hx/words dialog) "Wed 14 Oct, 10:00–10:30"))
    (is (= ["2026-10-14" "10:00" "10:30"] (map #(:value (hx/attributes (hx/by-id dialog %))) ["day" "from" "until"])))
    (is (= ["Not now" "Reschedule"] (map hx/words (sort-by #(if (hx/has-class? % "btn-ghost") 0 1) (buttons dialog)))))
    (is (str/includes? (hx/words dialog) "Ana sees the new time in the portal."))))

(deftest the-question-before-an-appointment-is-cancelled-asks-why-if-the-clinician-wants-to-say
  (let [doc (workspace/cancel-appointment context)
        dialog (first (hx/by-tag doc :dialog))
        why (hx/by-id dialog "why")]
    (is (= "Cancel the appointment" (hx/words (hx/by-id dialog "appointment-dialog-title"))))
    (is (str/includes? (hx/words dialog) "Diabetes review"))
    (is (str/includes? (hx/words dialog) "Liam Chen"))
    (is (str/includes? (hx/words dialog) "Why? (optional)"))
    (is (= "" (:value (hx/attributes why))))
    (is (nil? (hx/by-id dialog "day")) "a cancellation has no time")
    (is (= ["Keep it" "Cancel the appointment"] (map hx/words (sort-by #(if (hx/has-class? % "btn-ghost") 0 1) (buttons dialog)))))
    (is (hx/has-class? (hx/by-id dialog "appointment-submit") "btn-error") "red: it cannot be taken back")
    (is (str/includes? (hx/words dialog) "Liam sees in the portal that it was cancelled, and why if you say."))))

;; ---------------------------------------------------------------------------
;; The jobs

(deftest the-jobs-have-what-each-does-when-it-runs-and-how-the-last-run-went
  (let [doc (workspace/jobs context)
        job-rows (rows (by-label doc "Jobs of the scheduler"))
        facts (fn [row] (into {} (map vec) (partition 2 (map hx/words (hx/select (first (hx/by-tag row :dl)) #(#{:dt :dd} (hx/tag %)))))))]
    (is (= ["Jobs"] (map hx/words (hx/by-tag doc :h1))))
    (is (= [["Jobs" "3"] ["Running now" "1"] ["Last run failed" "1"]] (stats doc)))
    (is (= ["evaluate-rules" "simulate-devices" "send-reminders"] (map #(hx/words (first (hx/by-tag % :p))) job-rows)))
    (is (= ["Evaluates the clinical rules over the latest readings and raises alerts"
            "Simulates the readings of the devices of the demo cohort and writes them to FHIR"
            "Sends a reminder to the patients whose confirmed appointment is about to begin"]
           (map #(hx/words (second (hx/by-tag % :p))) job-rows)))
    (is (= ["Every 30 seconds" "Every 15 seconds" "Every minute"] (map #(get (facts %) "Schedule") job-rows)))
    (is (= ["14:00:30" "14:00:30" "14:01:00"] (map #(get (facts %) "Next run") job-rows)))
    (is (= ["214 ok, 0 failed, 0 interrupted, 0 skipped" "428 ok, 1 failed, 0 interrupted, 2 skipped" "105 ok, 1 failed, 0 interrupted, 0 skipped"]
           (map #(get (facts %) "Runs") job-rows)))
    (testing "the last run of each, in words"
      (is (= ["OK14:00:00 · 1.8 s6 patients, 0 raised, 4 already raised, 3 still active, 5 not applicable, 0 failed"
              "OK14:00:00 · 620 ms7 readings, 7 written, 0 failed"
              "Failed14:00:00 · 10 sError: HttpTimeoutException"]
             (map #(get (facts %) "Last run") job-rows))))
    (testing "the state of each"
      (is (= [false true false] (map #(str/includes? (hx/words %) "Running since 14:00:15") job-rows)))
      (is (= [true false true] (map #(str/includes? (hx/words %) "Idle") job-rows))))))

(deftest the-page-says-which-job-failed-and-shows-the-class-of-the-error-and-nothing-else
  (let [doc (workspace/jobs context)
        alert (first (hx/select (main-of doc) #(and (= :div (hx/tag %)) (hx/has-class? % "bg-tone-high-soft"))))]
    (is (= ["The last run of send-reminders failed"] (map hx/words (hx/by-tag alert :p))))
    (is (str/includes? (hx/words alert) "HttpTimeoutException after 10 s. The next run is at 14:01:00."))))

(deftest the-jobs-can-be-seen-and-not-started
  (let [doc (workspace/jobs context)]
    (is (empty? (buttons (main-of doc))) "the api has no way to start one")
    (is (empty? (hx/by-tag (main-of doc) :form)))))

(deftest the-administrator-has-the-jobs-in-the-sidebar-and-every-active-alert-in-its-count
  (let [nav (by-label (workspace/jobs context) "Workspace")
        links (hx/by-tag nav :a)]
    (is (= ["Panel" "Alerts 3 active" "Appointments" "Jobs"] (map #(:aria-label (hx/attributes %)) links)))
    (is (= [nil nil nil "page"] (map #(:aria-current (hx/attributes %)) links)))
    (is (= ["Administration"] (map hx/words (hx/select nav #(= :p (hx/tag %))))))
    (is (str/includes? (hx/words (first (hx/by-tag (workspace/jobs context) :aside))) "Clinic administrator"))))

;; ---------------------------------------------------------------------------
;; What goes wrong

(defn- captions
  "What the specimens of a group are called, in order."
  [group]
  (map #(hx/words (first (hx/by-tag % :p)))
       (hx/select group #(contains? (hx/attributes %) :data-specimen))))

(deftest the-page-of-the-states-has-groups-that-a-person-meets-in-order
  (let [doc (workspace/states context)
        groups ["before" "not-there" "nothing" "loading" "lost" "partial"]]
    (is (= ["Empty states and errors"] (map hx/words (hx/by-tag doc :h1))) "one heading, since each piece gives its own an h3")
    (is (= ["Before the workspace is open" "A page that is not there, or not the person's" "Nothing to show" "While a page loads"
            "Some of it could not be loaded" "A chart with a section that is not available"]
           (map #(hx/words (first (hx/by-tag (section-of doc %) :h2))) groups)))
    (is (= ["Waiting for the identity provider" "A wait that does not end" "A sign-in that could not be made"
            "A person the workspace is not for" "A sign-in the api did not accept" "Someone who has signed out"
            "A page the workspace could not draw"]
           (captions (section-of doc "before"))))
    (is (= ["An address that is no page" "A page for another role" "A chart that cannot be opened"
            "A chart that cannot be opened, for the administrator" "The jobs, for a person who is not the administrator"]
           (captions (section-of doc "not-there"))))
    (is (= ["A panel with no patients" "A panel for the administrator, with no patients" "A filter or a search that finds nobody"
            "An inbox with no alerts" "A patient with no readings" "No jobs"]
           (captions (section-of doc "nothing"))))
    (is (= ["The panel" "The inbox" "The jobs"] (captions (section-of doc "loading"))))
    (is (= 8 (count (captions (section-of doc "lost")))))))

(deftest every-piece-says-what-the-workspace-says-in-that-state
  (let [doc (workspace/states context)
        says (fn [group & texts] (let [words (hx/words (section-of doc group))] (every? #(str/includes? words %) texts)))]
    (testing "before the workspace is open"
      (is (says "before" "Signing in" "This is taking longer than usual" "The sign-in service has not answered yet."
                "We could not sign you in" "This workspace is for the clinic" "Go to the patient portal"
                "Your sign-in was not accepted" "Sign in again" "You have signed out" "Something went wrong" "Reload the page")))
    (testing "a page that is not there says so and leads back"
      (is (says "not-there" "We cannot find that page" "You cannot open that page" "We cannot find that patient"
                "They are not in your panel, or there is no such patient." "There is no such patient."
                "You cannot see the jobs" "Only the clinic's administrator can."))
      (is (= (repeat 5 "../workspace-panel.html") (map href (buttons (section-of doc "not-there"))))
          "every piece leads back to the panel")
      (is (= ["Go to the panel" "Go to the panel" "Go to the panel" "Go to the panel" "Back to the panel"]
             (map hx/words (buttons (section-of doc "not-there"))))))
    (testing "an empty state says what is missing and what to do"
      (is (says "nothing" "You have no patients yet" "Patients appear here when you are their doctor."
                "There are no patients yet" "Patients appear here when they are added to the clinic's records."
                "No patients match" "Show all patients" "No alerts" "Nothing needs a decision right now."
                "See acknowledged alerts" "No readings yet" "No jobs" "The scheduler has no jobs.")))
    (testing "while it loads, grey shapes that are hidden from a screen reader, and the words for it"
      (let [loading (section-of doc "loading")
            shapes (hx/select loading #(hx/has-class? % "skeleton"))]
        (is (seq shapes))
        (is (every? #(= "true" (:aria-hidden (hx/attributes %))) shapes))
        (is (says "loading" "Loading the alerts" "Loading the jobs"))))
    (testing "what could not be loaded is a message in its place, with what is up to date and a way to try again"
      (is (says "lost" "Your panel could not be loaded" "2 patients could not be loaded" "Some figures could not be loaded"
                "We could not check for new alerts" "The names of the patients could not be loaded"
                "The alerts have changed" "Show the changes" "We could not update the list" "The jobs could not be loaded"
                "The problem is on our side, not yours."))
      (is (<= 7 (count (filter #(= "Try again" (hx/words %)) (buttons (section-of doc "lost"))))))
      (is (not (str/includes? (hx/words (section-of doc "lost")) "No jobs")) "and never shown as empty"))
    (testing "a section of a chart that is not available is named, and the other sections are there"
      (let [partial (section-of doc "partial")]
        (is (str/includes? (hx/words partial) "The medication could not be loaded"))
        (is (str/includes? (hx/words partial) "Ana García"))
        (is (str/includes? (hx/words partial) "Allergies"))
        (is (str/includes? (hx/words partial) "Appointments"))
        (is (not (str/includes? (hx/words partial) "None.")) "a medication that could not be loaded is not 'None.'")))))

(deftest the-pieces-are-the-ones-the-workspace-serves
  (let [doc (workspace/states context)
        text (words-of-main doc)]
    (doseq [served [(hx/words (start/crashed-card {:handlers {:reload (fn [])}}))
                    (hx/words (start/not-allowed-card {}))
                    (hx/words (patient-page/no-readings))
                    (hx/words (jobs-page/forbidden {}))
                    (hx/words (panel-page/no-match {}))]]
      (is (str/includes? text served) served))))

(deftest the-buttons-of-the-states-do-nothing-by-themselves
  (let [html (hx/html (workspace/states context))]
    (is (not (re-find #"on-click|onclick|fn@|\$fn" html)) "a function of the workspace is not written into a file")))

;; ---------------------------------------------------------------------------
;; The sidebar of every page

(deftest the-sidebar-of-the-practitioner-has-no-jobs-and-the-menu-signs-out
  (doseq [f [workspace/panel workspace/alerts workspace/appointments workspace/states]
          :let [doc (f context)]]
    (is (= ["Panel" "Alerts" "Appointments"] (map #(:title (hx/attributes %)) (hx/by-tag (by-label doc "Workspace") :a))))
    (is (= ["../login.html"] (map href (hx/by-tag (first (hx/by-tag doc :details)) :a))) "the menu signs out")))

(defn- card-titled [tree title]
  (first (hx/select tree #(and (= :section (hx/tag %)) (= title (hx/words (first (hx/by-tag % :h2))))))))

(deftest every-page-of-the-workspace-has-the-same-sidebar-and-the-mark-leads-to-the-panel
  (doseq [f [workspace/panel workspace/alerts workspace/appointments workspace/confirm workspace/states
             (workspace/patient "pt-ana") (workspace/patient "pt-omar")]
          :let [doc (f context)]]
    (is (= ["../workspace-panel.html" "../workspace-alerts.html" "../workspace-appointments.html"]
           (map href (hx/by-tag (by-label doc "Workspace") :a))))
    (is (= "../workspace-panel.html" (href (first (hx/by-tag (first (hx/by-tag doc :aside)) :a)))))
    (is (= ["Panel" "Alerts" "Appointments"] (map #(:title (hx/attributes %)) (hx/by-tag (by-label doc "Workspace") :a)))))
  (is (= ["../workspace-panel.html" "../workspace-alerts.html" "../workspace-appointments.html" "../workspace-jobs.html"]
         (map href (hx/by-tag (by-label (workspace/jobs context) "Workspace") :a)))))

(deftest a-row-of-appointments-has-a-day-or-a-clock-and-goes-under-its-text-when-narrow
  (let [doc (workspace/appointments context)
        section-rows (fn [id] (rows (section-of doc id)))
        block-of (fn [row] (first (hx/select row #(hx/has-class? % "w-14"))))]
    (is (= ["" ""] (map #(hx/words (block-of %)) (section-rows "requests"))) "a clock, and no day")
    (is (every? #(= 1 (count (hx/by-tag (block-of %) :svg))) (section-rows "requests")))
    (is (= ["Oct14" "Oct16"] (map #(hx/words (block-of %)) (section-rows "booked"))))
    (is (= ["Sep29"] (map #(hx/words (block-of %)) (section-rows "cancelled"))))
    (is (every? #(hx/has-class? (first (hx/select % (fn [e] (hx/has-class? e "min-w-0")))) "basis-44")
                (mapcat section-rows ["requests" "booked" "cancelled"])))))

(deftest the-cards-of-a-chart-hold-their-lists-with-no-padding-of-their-own
  (doseq [patient ["pt-ana" "pt-omar"]
          title ["Appointments" "Medication" "Allergies"]
          :let [card (card-titled ((workspace/patient patient) context) title)]]
    (is (some? (first (hx/select card #(and (= :div (hx/tag %)) (hx/has-class? % "mt-3"))))) (str patient " " title))
    (is (empty? (hx/select card #(and (= :div (hx/tag %)) (hx/has-class? % "px-5") (hx/has-class? % "py-4")))) (str patient " " title))))

(deftest the-count-of-the-patients-with-an-alert-is-counted-and-not-assumed
  (with-redefs [data/alerts (vec (remove #(= "flag-pt-sofia-R2" (:id %)) data/alerts))]
    (let [doc (workspace/panel context)]
      (is (= ["Active alerts" "1" "1 acknowledged today"] (second (stats doc))))
      (is (= ["All4" "With active alerts1" "Away from normal3" "Normal1"]
             (map hx/words (hx/by-tag (by-label doc "Filter the panel") :a))))
      (is (= ["1 active" "None" "None" "None"]
             (map #(hx/words (nth (hx/select % (fn [e] (#{:th :td} (hx/tag e)))) 4))
                  (hx/by-tag (first (hx/by-tag (first (hx/by-tag doc :table)) :tbody)) :tr)))))))

(defn- jobs-with [change]
  (update data/jobs :items (fn [items] (mapv change items))))

(deftest the-jobs-page-has-no-alert-and-no-red-figure-when-no-run-failed
  (let [all-ok (jobs-with #(-> % (assoc-in [:lastRun :outcome] "ok") (update :lastRun dissoc :error)))]
    (with-redefs [data/jobs all-ok]
      (let [doc (workspace/jobs context)]
        (is (= ["Last run failed" "0"] (take 2 (nth (stats doc) 2))))
        (is (not (hx/has-class? (second (hx/by-tag (nth (hx/select (main-of doc) #(and (= :div (hx/tag %)) (hx/has-class? % "shadow-card") (hx/has-class? % "p-4"))) 2) :p)) "text-tone-high-ink")))
        (is (empty? (hx/select (main-of doc) #(and (= :div (hx/tag %)) (hx/has-class? % "bg-tone-high-soft")))))))))

(deftest a-run-that-was-interrupted-is-said-so-and-is-not-a-failure
  (let [interrupted (jobs-with #(-> % (assoc-in [:lastRun :outcome] "interrupted") (update :lastRun dissoc :error)))]
    (with-redefs [data/jobs interrupted]
      (let [doc (workspace/jobs context)
            chips (hx/select (main-of doc) #(and (= :span (hx/tag %)) (hx/has-class? % "bg-tone-high-soft")))]
        (is (= ["Interrupted" "Interrupted" "Interrupted"] (map hx/words chips)))
        (is (= ["Last run failed" "0"] (take 2 (nth (stats doc) 2))))))))
