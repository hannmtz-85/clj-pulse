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
    (is (= ["Panel" "Alerts, 2 active" "Appointments"] (map #(:aria-label (hx/attributes %)) (hx/by-tag nav :a))))
    (is (= "2" (hx/words (first (hx/select alerts #(hx/has-class? % "tabular-nums"))))))))

;; ---------------------------------------------------------------------------
;; The chart of a patient

(deftest the-chart-of-ana-has-the-alert-the-latest-readings-the-chart-and-the-rest-of-her-record
  (let [doc ((workspace/patient "pt-ana") context)
        main (main-of doc)]
    (is (= ["Ana García"] (map hx/words (hx/by-tag doc :h1))))
    (is (str/includes? (hx/words main) "58 years · Female · PULSE-0001 · Valencia"))
    (is (= ["Panel" "Ana García"] (map hx/words (hx/by-tag (by-label doc "Breadcrumb") :li))))
    (is (= ["../workspace-panel.html"] (map href (hx/by-tag (by-label doc "Breadcrumb") :a))))
    (is (= ["Essential (primary) hypertension · I10"] (map hx/words (hx/by-tag (by-label doc "Conditions") :li))))
    (testing "the alert that is active, with the way to acknowledge it"
      (let [alert (first (hx/select main #(and (= :div (hx/tag %)) (hx/has-class? % "bg-tone-critical-soft"))))]
        (is (= "BP stage 2" (hx/words (first (hx/by-tag alert :p)))) "its title")
        (is (str/includes? (hx/words alert) "Raised yesterday, 22:30."))
        (is (str/includes? (hx/words alert) "177/100 mmHg, today, 10:00; 172/102 mmHg, yesterday, 22:00; 165/106 mmHg, yesterday, 10:00"))
        (is (= ["Acknowledge" "All alerts"] (button-texts alert)))
        (is (= ["../workspace-alerts.html" "../workspace-alerts.html"] (map href (buttons alert))))))
    (testing "the latest readings and the chart of the one furthest from normal"
      (is (= ["177/100" "70" "82.3"] (map hx/words (hx/select (section-of doc "vitals") #(hx/has-class? % "text-4xl")))))
      (is (some? (hx/by-id doc "chart-blood-pressure-pt-ana-title")))
      (is (str/includes? (words-of-main doc) "Last 30 days"))
      (is (str/includes? (words-of-main doc) "Stage 2 · 160")))))

(deftest the-appointments-in-a-chart-are-in-order-and-have-what-the-lifecycle-allows
  (let [doc ((workspace/patient "pt-ana") context)
        list-el (list-named doc "Appointments")
        list-rows (rows list-el)]
    (is (= [["Hypertension follow-up" "Wed 14 Oct, 10:00–10:30"]
            ["Medication review" "Asked for a visit between Oct 20 and Oct 27"]
            ["Blood pressure check" "Tue 29 Sep, 09:00–09:30" "Reason: I had to travel that week."]]
           (map lines list-rows))
        "what is to come first, the patient's own name left out")
    (testing "what a clinician may do is what the lifecycle says of the status"
      (is (= (for [status ["booked" "proposed" "cancelled"]]
               (set (map button-label (appointment/available-actions {:status status} :clinician data/now-text))))
             (map (fn [row] (set (map hx/words (buttons row)))) list-rows)))
      (is (= [["Reschedule" "Cancel"] ["Confirm" "Cancel"] []]
             (map (fn [row] (map hx/words (buttons row))) list-rows))))
    (is (= ["../workspace-confirm.html"] (map href (filter #(= "Confirm" (hx/words %)) (buttons list-el)))))))

(deftest the-chart-of-sofia-is-of-her-oxygen-and-has-her-allergy
  (let [doc ((workspace/patient "pt-sofia") context)
        main (main-of doc)]
    (is (= ["Sofía Rossi"] (map hx/words (hx/by-tag doc :h1))))
    (is (str/includes? (hx/words main) "Chronic obstructive pulmonary disease, unspecified · J44.9"))
    (is (= "Low SpO₂" (hx/words (first (hx/by-tag (first (hx/select main #(and (= :div (hx/tag %)) (hx/has-class? % "bg-tone-high-soft")))) :p)))))
    (is (some? (hx/by-id doc "chart-spo2-pt-sofia-title")))
    (is (str/includes? (hx/words main) "Last 14 days"))
    (is (= [["penicillin G" "Skin rash"]] (map lines (rows (list-named doc "Allergies")))))
    (is (= [["Tiotropium" "18 mcg inhaled once daily"] ["Albuterol" "2 puffs as needed"]] (map lines (rows (list-named doc "Medication")))))
    (is (= [["COPD check-up (requested by the patient)" "Asked for a visit between Oct 16 and Oct 23"]]
           (map lines (rows (list-named doc "Appointments")))))))

(deftest the-chart-of-liam-has-no-alert-because-his-was-acknowledged
  (let [doc ((workspace/patient "pt-liam") context)
        main (main-of doc)]
    (is (empty? (hx/select main #(and (= :div (hx/tag %)) (hx/has-class? % "bg-tone-high-soft")))))
    (is (empty? (hx/select main #(and (= :div (hx/tag %)) (hx/has-class? % "bg-tone-critical-soft")))))
    (is (str/includes? (hx/words main) "Type 2 diabetes mellitus without complications · E11.9"))
    (is (some? (hx/by-id doc "chart-blood-pressure-pt-liam-title")) "the pressure, which is elevated, and not the glucose, which is normal")
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

(deftest the-form-to-confirm-gives-a-time-inside-the-days-that-were-asked-for
  (let [doc (workspace/confirm context)
        dialog (first (hx/by-tag doc :dialog))
        day (:value (hx/attributes (hx/by-id dialog "day")))
        [lower upper] (:slot-minutes appointment/limits)]
    (is (true? (:open (hx/attributes dialog))))
    (is (= "Confirm the appointment" (hx/words (hx/by-id dialog "confirm-appointment-title"))))
    (is (str/includes? (hx/words dialog) "COPD check-up (requested by the patient)"))
    (is (str/includes? (hx/words dialog) "Sofía Rossi"))
    (is (str/includes? (hx/words dialog) "Asked for a visit between Oct 16 and Oct 23"))
    (is (<= (compare "2026-10-16" day) 0) "not before the first day")
    (is (<= (compare day "2026-10-23") 0) "nor after the last")
    (is (= ["10:00" "10:30"] (map #(:value (hx/attributes (hx/by-id dialog %))) ["start" "end"])))
    (is (= ["date" "time" "time"] (map #(:type (hx/attributes (hx/by-id dialog %))) ["day" "start" "end"])))
    (is (str/includes? (hx/words (hx/by-id dialog "end-hint")) (str lower " minutes to " (quot upper 60) " hours")))
    (is (= ["Not now" "Confirm"] (map hx/words (sort-by #(if (hx/has-class? % "btn-ghost") 0 1) (buttons dialog)))))
    (is (= ["../workspace-appointments.html" "../workspace-appointments.html"] (map href (buttons dialog))))
    (testing "behind it, the appointments"
      (is (some? (section-of doc "requests"))))))

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
    (is (= ["Panel" "Alerts, 3 active" "Appointments" "Jobs"] (map #(:aria-label (hx/attributes %)) links)))
    (is (= [nil nil nil "page"] (map #(:aria-current (hx/attributes %)) links)))
    (is (= ["Administration"] (map hx/words (hx/select nav #(= :p (hx/tag %))))))
    (is (str/includes? (hx/words (first (hx/by-tag (workspace/jobs context) :aside))) "Clinic administrator"))))

;; ---------------------------------------------------------------------------
;; What goes wrong

(deftest the-workspace-says-what-it-does-when-there-is-nothing-or-something-goes-wrong
  (let [doc (workspace/states context)
        main (main-of doc)
        cards (hx/select main #(and (= :section (hx/tag %)) (hx/has-class? % "shadow-card")))]
    (is (= ["A panel with no patients" "An inbox with no alerts" "While the panel loads" "A chart that cannot be opened"
            "A patient that does not exist" "A list that could not be loaded"]
           (map #(hx/words (first (hx/by-tag % :h2))) cards)))
    (testing "an empty state says what is missing and what to do"
      (is (str/includes? (hx/words (first cards)) "No patients in your panel yet"))
      (is (str/includes? (hx/words (second cards)) "Nothing needs a decision right now."))
      (is (= "../workspace-panel.html" (href (first (buttons (nth cards 3))))))
      (is (= "../workspace-panel.html" (href (first (buttons (nth cards 4)))))))
    (testing "while it loads, a spinner with a name and shapes that are hidden from a screen reader"
      (let [loading (nth cards 2)]
        (is (= "status" (:role (hx/attributes (first (hx/select loading #(= :span (hx/tag %))))))))
        (is (str/includes? (hx/words loading) "Loading the panel"))
        (is (= 3 (count (hx/select loading #(hx/has-class? % "skeleton")))))
        (is (every? #(= "true" (:aria-hidden (hx/attributes %))) (hx/select loading #(hx/has-class? % "skeleton"))))))
    (testing "a section that is not available is named, and the other sections are there"
      (let [partial (section-of doc "partial")]
        (is (str/includes? (hx/words partial) "Medication is not available right now"))
        (is (= 3 (count (hx/select partial #(hx/has-class? % "text-4xl")))))))))

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
