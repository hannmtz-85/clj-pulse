(ns pulse.design.portal-test
  "The pages of the patient portal: what each one shows of Ana García, who has hypertension and whose last readings
  are very high, and of Noah Kim, who has no readings yet. What the pages say is held to what the api and the
  libraries say: the limits of a reading, the lifecycle of an appointment."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.design.build :as build]
            [pulse.design.data :as data]
            [pulse.design.portal :as portal]
            [pulse.fhir.appointment :as appointment]
            [pulse.fhir.measurement :as measurement]
            [pulse.ui.test.hiccup :as hx]))

(def ^:private context (build/context))

(defn- words-of-main [doc]
  (hx/words (first (hx/by-tag doc :main))))

(defn- buttons [doc]
  (hx/select doc #(and (hx/has-class? % "btn") (#{:a :button} (hx/tag %)))))

(defn- button-named [doc text]
  (first (filter #(= text (hx/words %)) (buttons doc))))

(defn- href [el] (:href (hx/attributes el)))

(defn- list-named [doc label]
  (first (hx/select doc #(and (= :ul (hx/tag %)) (= label (:aria-label (hx/attributes %)))))))

(defn- rows [list-el] (hx/by-tag list-el :li))

(defn- lines [row] (map hx/words (hx/by-tag row :p)))

(defn- section-of [doc id]
  (first (hx/select doc #(and (= :section (hx/tag %)) (= id (:aria-labelledby (hx/attributes %)))))))

(defn- tile-values [doc]
  (map hx/words (hx/select doc #(hx/has-class? % "text-4xl"))))

;; ---------------------------------------------------------------------------
;; Home

(deftest the-home-greets-the-patient-by-the-part-of-the-day-and-says-the-date
  (let [doc (portal/home context)]
    (is (= ["Good afternoon, Ana"] (map hx/words (hx/by-tag doc :h1))))
    (is (str/includes? (words-of-main doc) "Tuesday, 13 October 2026"))
    (is (= "../portal-new-reading.html" (href (button-named doc "Record a reading"))))))

(deftest the-home-warns-of-a-very-high-pressure-and-does-not-say-anyone-has-been-told
  (let [doc (portal/home context)
        notice (first (hx/select doc #(and (= :div (hx/tag %)) (hx/has-class? % "bg-tone-critical-soft"))))
        text (hx/words notice)]
    (is (str/includes? text "Your last blood pressure reading is very high"))
    (is (str/includes? text "177/100 mmHg, today, 10:00"))
    (is (str/includes? text "call 112"))
    (is (not (re-find #"(?i)told|notified|informed" (str/replace text "tell your care team" ""))) "the portal cannot know it")
    (is (= "../portal-readings.html" (href (button-named notice "See my readings")))))
  (testing "it is the first thing after the title, and it is there only when the reading is very high"
    (let [children (fn [doc] (hx/child-nodes (first (hx/child-nodes (first (hx/by-tag doc :main))))))]
      (is (= [:header :div :section] (map hx/tag (take 3 (children (portal/home context))))))
      (is (hx/has-class? (second (children (portal/home context))) "bg-tone-critical-soft")))
    (let [latest data/latest]
      (with-redefs [data/latest (fn [id kind] (cond-> (latest id kind) (= :blood-pressure kind) (assoc :band "normal")))]
        (is (empty? (hx/select (portal/home context) #(and (= :div (hx/tag %)) (hx/has-class? % "bg-tone-critical-soft")))))
        (is (= [:header :section :section] (map hx/tag (take 3 (hx/child-nodes (first (hx/child-nodes (first (hx/by-tag (portal/home context) :main)))))))))))))

(deftest the-home-shows-the-next-appointment-with-the-way-to-cancel-it
  (let [doc (portal/home context)
        section (section-of doc "next")
        rows (rows (list-named section "Your next appointment"))
        row (first rows)]
    (is (= ["Your next appointment"] (map hx/words (hx/by-tag section :h2))))
    (is (= 1 (count rows)) "the confirmed one, not the request or the one that was cancelled")
    (is (= ["Hypertension follow-up" "Wed 14 Oct, 10:00–10:30 · Dr. Elena Rivera"] (lines row)))
    (is (str/includes? (hx/words row) "Confirmed"))
    (is (= ["Oct" "14"] (map hx/words (hx/by-tag (first (hx/select row #(hx/has-class? % "w-14"))) :span))))
    (is (= "../portal-cancel.html" (href (button-named row "Cancel appointment"))))))

(deftest the-home-shows-the-latest-reading-of-each-measurement-the-patient-has
  (let [doc (portal/home context)
        section (section-of doc "latest")]
    (is (= ["Your latest readings"] (map hx/words (hx/by-tag section :h2))))
    (is (= ["177/100" "70" "82.3"] (tile-values section)))
    (is (= ["Very high" "Normal"] (keep #(when (hx/has-class? % "rounded-full") (hx/words %))
                                        (hx/select section #(and (= :span (hx/tag %)) (hx/has-class? % "border")))))
        "a reading with no band, the weight, has no chip")
    (is (= "../portal-readings.html" (href (first (hx/select section #(and (= :a (hx/tag %)) (= "See all" (hx/words %))))))))))

(deftest the-home-lists-the-last-readings-newest-first
  (let [list-el (list-named (portal/home context) "Recent readings")
        rows (rows list-el)]
    (is (= 5 (count rows)))
    (is (= [["Blood pressure" "177/100 mmHg · Today, 10:00"] ["Heart rate" "70 bpm · Today, 10:00"] ["Weight" "82.3 kg · Today, 10:00"]
            ["Blood pressure" "172/102 mmHg · Yesterday, 22:00"] ["Heart rate" "79 bpm · Yesterday, 22:00"]]
           (map lines rows)))
    (is (str/includes? (hx/words (first rows)) "Very high"))))

(deftest the-home-says-what-the-words-beside-a-reading-are-and-are-not
  (is (str/includes? (words-of-main (portal/home context)) "They are not a diagnosis.")))

;; ---------------------------------------------------------------------------
;; Readings

(defn- figure-of [doc id]
  (first (hx/select doc #(and (= :figure (hx/tag %)) (seq (hx/select % (fn [e] (= (str id "-title") (:id (hx/attributes e))))))))))

(defn- box-of [doc id]
  (first (hx/select doc #(and (= :div (hx/tag %)) (some (fn [c] (= (figure-of doc id) c)) (hx/child-nodes %))))))

(deftest the-readings-have-a-chart-for-the-phone-and-another-for-the-desk
  (let [doc (portal/readings context)
        wide (figure-of doc "bp-wide")
        narrow (figure-of doc "bp-narrow")]
    (is (some? wide))
    (is (some? narrow))
    (testing "each is in a box that is shown from a medium screen on, or only before it"
      (is (every? (hx/classes (box-of doc "bp-wide")) ["hidden" "md:block"]))
      (is (hx/has-class? (box-of doc "bp-narrow") "md:hidden"))
      (is (not (hx/has-class? (box-of doc "bp-narrow") "hidden"))))
    (testing "drawn for the width it has: the wide one with the thresholds of the rule, the compact one without"
      (is (= "0 0 950 320" (:viewBox (hx/attributes (first (hx/by-tag wide :svg))))))
      (is (= "0 0 318 260" (:viewBox (hx/attributes (first (hx/by-tag narrow :svg))))))
      (is (str/includes? (hx/words wide) "Stage 2 · 160"))
      (is (not (str/includes? (hx/words narrow) "Stage 2 · 160"))))
    (testing "each has the table of its numbers"
      (is (= 1 (count (hx/by-tag wide :details))))
      (is (= 1 (count (hx/by-tag narrow :details)))))))

(deftest the-readings-have-the-figures-of-the-last-30-days
  (let [doc (portal/readings context)
        stats (hx/select doc #(and (= :div (hx/tag %)) (hx/has-class? % "shadow-card") (hx/has-class? % "p-4") (not (hx/has-class? % "relative"))))]
    (is (= [["Latest" "177/100 mmHg" "Today, 10:00"] ["Highest, 30 days" "177/100 mmHg" "Today, 10:00"] ["Lowest, 30 days" "131/82 mmHg" "Sep 30, 22:00"]]
           (map #(map hx/words (hx/by-tag % :p)) stats)))))

(defn- row-parts [row] (hx/select row #(#{:th :td} (hx/tag %))))

(deftest the-readings-have-a-table-of-the-last-eight-with-the-band-of-each
  (let [doc (portal/readings context)
        table (first (filter #(= "Blood pressure, the last 8 readings" (hx/words (first (hx/by-tag % :caption)))) (hx/by-tag doc :table)))
        body (first (hx/by-tag table :tbody))
        row-cells (fn [row] (map hx/words (hx/select row #(#{:th :td} (hx/tag %)))))]
    (is (= "Blood pressure, the last 8 readings" (hx/words (first (hx/by-tag table :caption)))))
    (is (= 8 (count (hx/by-tag body :tr))))
    (is (= ["Today, 10:00" "177/100 mmHg" "Very high"] (row-cells (first (hx/by-tag body :tr)))))
    (is (= ["Yesterday, 22:00" "172/102 mmHg" "Very high"] (row-cells (second (hx/by-tag body :tr)))))
    (testing "the heading of the last column is short on a phone"
      (let [heading (last (hx/select (first (hx/by-tag table :thead)) #(= :th (hx/tag %))))]
        (is (= ["Compared with the range" "Range"] (map hx/words (hx/by-tag heading :span))))
        (is (hx/has-class? (first (hx/by-tag heading :span)) "hidden"))
        (is (hx/has-class? (second (hx/by-tag heading :span)) "sm:hidden"))))
    (testing "the time heads its row, for a screen reader"
      (is (= [:th :td :td] (map hx/tag (row-parts (first (hx/by-tag body :tr))))))
      (is (= "row" (:scope (hx/attributes (first (row-parts (first (hx/by-tag body :tr)))))))))
    (testing "its cells do not break in the middle of a time or a reading"
      (is (every? #(hx/has-class? % "whitespace-nowrap") (take 2 (hx/select (first (hx/by-tag body :tr)) #(#{:th :td} (hx/tag %)))))))))

(deftest the-readings-can-be-filtered-by-measurement-and-by-period-with-pills-for-a-finger
  (let [doc (portal/readings context)
        measurement-nav (first (hx/select doc #(= "Measurement" (:aria-label (hx/attributes %)))))
        period-nav (first (hx/select doc #(= "Period" (:aria-label (hx/attributes %)))))]
    (is (= ["Blood pressure" "Heart rate" "Weight"] (map hx/words (hx/by-tag measurement-nav :a))))
    (is (= ["Blood pressure"] (keep #(when (= "page" (:aria-current (hx/attributes %))) (hx/words %)) (hx/by-tag measurement-nav :a))))
    (is (= ["7 days" "14 days" "30 days"] (map hx/words (hx/by-tag period-nav :a))))
    (is (= ["30 days"] (keep #(when (= "page" (:aria-current (hx/attributes %))) (hx/words %)) (hx/by-tag period-nav :a))))
    (is (every? #(hx/has-class? % "min-h-11") (concat (hx/by-tag measurement-nav :a) (hx/by-tag period-nav :a))))))

;; ---------------------------------------------------------------------------
;; Record a reading

(deftest the-form-to-record-a-reading-has-the-fields-of-a-blood-pressure-and-the-error-of-the-api
  (let [doc (portal/new-reading context)
        form (first (hx/by-tag doc :form))
        input (fn [id] (hx/by-id form id))
        [lower upper] (get-in measurement/measurements [:blood-pressure :channels :diastolic :plausible])]
    (is (= "post" (:method (hx/attributes form))))
    (is (= ["132" "310"] (map #(:value (hx/attributes (input %))) ["systolic" "diastolic"])))
    (is (= [nil "true"] (map #(:aria-invalid (hx/attributes (input %))) ["systolic" "diastolic"])) "only the diastolic is wrong")
    (is (str/includes? (hx/words (hx/by-id form "diastolic-error")) (str "between " lower " and " upper " mmHg"))
        "the limits are the api's")
    (is (= "alert" (:role (hx/attributes (first (hx/select form #(hx/has-class? % "bg-tone-high-soft")))))))
    (is (str/includes? (hx/words (first (hx/select form #(hx/has-class? % "bg-tone-high-soft")))) "The reading was not saved"))
    (is (= (set (map name (keys measurement/measurements)))
           (set (map #(:value (hx/attributes %)) (hx/by-tag (input "measurement") :option))))
        "every measurement the api records")
    (is (= "datetime-local" (:type (hx/attributes (input "taken")))))
    (is (= "submit" (:type (hx/attributes (button-named form "Save the reading")))))
    (is (= "../portal-readings.html" (href (button-named form "Cancel"))))))

;; ---------------------------------------------------------------------------
;; Appointments

(deftest the-appointments-are-what-is-coming-and-what-was-cancelled
  (let [doc (portal/appointments context)
        coming (rows (list-named doc "Appointments that are coming"))
        cancelled (rows (list-named doc "Cancelled appointments"))]
    (is (= [["Hypertension follow-up" "Wed 14 Oct, 10:00–10:30 · Dr. Elena Rivera"]
            ["Medication review" "Asked for a visit between Oct 20 and Oct 27 · Dr. Elena Rivera"]]
           (map lines coming)))
    (is (= [["Blood pressure check" "Tue 29 Sep, 09:00–09:30 · Dr. Elena Rivera" "Reason: I had to travel that week."]]
           (map lines cancelled)))
    (is (= ["Confirmed" "Requested"] (map #(first (filter #{"Confirmed" "Requested"} (map hx/words (hx/by-tag % :span)))) coming)))
    (is (str/includes? (hx/words (first cancelled)) "Cancelled"))))

(deftest a-patient-can-cancel-what-the-lifecycle-lets-them-cancel
  (let [doc (portal/appointments context)
        cancelable (set (filter #(contains? (appointment/available-actions {:status %} :patient data/now-text) :cancel)
                                ["proposed" "booked" "cancelled"]))
        statuses (fn [list-el] (map #(count (filter (fn [b] (#{"Cancel appointment" "Cancel request"} (hx/words b))) (buttons %))) (rows list-el)))]
    (is (= #{"proposed" "booked"} cancelable))
    (testing "a confirmed appointment and a request have the button, and what was cancelled has none"
      (is (= [1 1] (statuses (list-named doc "Appointments that are coming"))))
      (is (= [0] (statuses (list-named doc "Cancelled appointments")))))
    (is (= ["Cancel appointment" "Cancel request"]
           (map hx/words (filter #(#{"Cancel appointment" "Cancel request"} (hx/words %)) (buttons doc)))))
    (is (= "../portal-cancel.html" (href (button-named doc "Cancel appointment"))))
    (is (= "../portal-appointments.html" (href (button-named doc "Cancel request"))))))

(deftest a-row-of-appointments-puts-its-chip-and-buttons-under-the-text-on-a-phone
  (doseq [doc [(portal/appointments context) (portal/new-patient context) (portal/home context)]
          row (mapcat rows (hx/select doc #(and (= :ul (hx/tag %)) (str/includes? (str (:aria-label (hx/attributes %))) "ppointment"))))]
    (is (hx/has-class? (first (hx/select row #(hx/has-class? % "min-w-0"))) "basis-44") (hx/words row))))

(deftest the-form-to-ask-for-a-visit-goes-to-the-doctor-of-the-patient-with-the-limits-of-the-api
  (let [doc (portal/appointments context)
        form (first (hx/by-tag doc :form))
        {:keys [window-days text-length]} appointment/limits]
    (is (str/includes? (words-of-main doc) "Your request goes to Dr. Elena Rivera, who will confirm a time."))
    (is (str/includes? (hx/words (hx/by-id form "reason-hint")) (str "up to " text-length " characters")))
    (is (str/includes? (hx/words (hx/by-id form "to-hint")) (str "At most " window-days " days")))
    (is (= ["date" "date"] (map #(:type (hx/attributes (hx/by-id form %))) ["from" "to"])))
    (is (= "When would suit you?" (hx/words (first (hx/by-tag form :legend)))))
    (is (= "submit" (:type (hx/attributes (button-named form "Send the request")))))
    (is (= "#ask" (href (button-named doc "Ask for a visit"))))
    (is (some? (hx/by-id doc "ask")) "and the button leads to the form")))

(deftest the-question-before-cancelling-names-the-appointment-and-gives-a-way-back
  (let [doc (portal/cancel context)
        dialog (first (hx/by-tag doc :dialog))]
    (is (true? (:open (hx/attributes dialog))))
    (is (= "Cancel this appointment?" (hx/words (hx/by-id dialog "cancel-appointment-title"))))
    (is (str/includes? (hx/words dialog) "Hypertension follow-up"))
    (is (str/includes? (hx/words dialog) "Wed 14 Oct, 10:00–10:30 · Dr. Elena Rivera"))
    (testing "it promises what the system does: the care team sees a cancelled appointment, and nobody is notified"
      (is (str/includes? (hx/words dialog) "Your care team will see it."))
      (is (not (re-find #"(?i)told|notified|informed" (hx/words dialog)))))
    (is (= ["Cancel the appointment" "Keep it"]
           (sort-by {"Cancel the appointment" 0 "Keep it" 1} (map hx/words (buttons dialog)))))
    (is (hx/has-class? (button-named dialog "Cancel the appointment") "btn-error"))
    (is (hx/has-class? (button-named dialog "Keep it") "btn-ghost"))
    (is (str/includes? (hx/words (hx/by-id dialog "why-hint")) (str "Up to " (:text-length appointment/limits) " characters")))
    (is (str/includes? (hx/words dialog) "(optional)"))
    (testing "behind it, the appointments that are coming, with no form"
      (is (some? (list-named doc "Appointments that are coming")))
      (is (empty? (hx/by-tag (first (hx/by-tag doc :main)) :form))))))

;; ---------------------------------------------------------------------------
;; The record and the profile

(deftest the-record-has-the-medication-the-conditions-and-the-allergies-in-words
  (let [doc (portal/record context)
        text (words-of-main doc)]
    (is (= [["Lisinopril" "10 mg once daily"] ["Amlodipine" "5 mg once daily"]] (map lines (rows (list-named doc "Medication")))))
    (is (= [["Essential (primary) hypertension" "Active"]] (map lines (rows (list-named doc "Conditions")))))
    (is (= ["Since Nov 2025" "Since Nov 2025"] (map #(last (map hx/words (hx/by-tag % :span))) (rows (list-named doc "Medication")))))
    (is (str/includes? (hx/words (first (rows (list-named doc "Conditions")))) "Since May 2019"))
    (is (str/includes? text "No allergies are recorded. If you have one, tell your clinic."))
    (is (not (str/includes? text "I10")) "a code is for the clinic")))

(deftest the-profile-has-what-the-clinic-knows-and-nothing-to-edit
  (let [doc (portal/profile context)
        text (words-of-main doc)]
    (is (str/includes? text "Ana García"))
    (is (str/includes? text "ana.garcia@pulse.test"))
    (is (str/includes? text "Date of birth14 March 1968"))
    (is (str/includes? text "Patient numberPULSE-0001"))
    (is (str/includes? text "CityValencia"))
    (is (str/includes? text "Dr. Elena RiveraInternal medicine"))
    (is (empty? (hx/by-tag doc :input)) "it is read, not written")
    (is (= "../login.html" (href (button-named doc "Sign out"))))))

;; ---------------------------------------------------------------------------
;; A new patient, and what goes wrong

(deftest a-new-patient-is-told-what-to-do-first
  (let [doc (portal/new-patient context)
        text (words-of-main doc)]
    (is (= ["Welcome, Noah"] (map hx/words (hx/by-tag doc :h1))))
    (is (= [["Record your first reading" "Blood pressure, heart rate, oxygen, glucose, weight or temperature."]
            ["Look at your record" "Your doctor sees what you see there."]]
           (map lines (rows (list-named doc "First steps")))))
    (is (str/includes? text "No readings yet"))
    (is (empty? (hx/select doc #(hx/has-class? % "text-4xl"))) "no tile of a reading")
    (is (= "../portal-new-reading.html" (href (button-named doc "Record a reading"))))
    (testing "the request that is waiting, with the way to cancel it"
      (let [row (first (rows (list-named doc "Your next appointment")))]
        (is (= ["New patient intake" "Asked for a visit between Oct 14 and Oct 20 · Dr. Chidi Okafor"] (lines row)))
        (is (str/includes? (hx/words row) "Requested"))
        (is (some? (button-named row "Cancel request")))))))

(deftest what-goes-wrong-is-said-in-words-with-a-way-out
  (let [doc (portal/errors context)
        main (first (hx/by-tag doc :main))]
    (is (= ["Part of a page could not be loaded" "A whole page"] (map hx/words (hx/by-tag main :h2))))
    (let [alert (first (hx/select main #(and (= :div (hx/tag %)) (hx/has-class? % "bg-tone-high-soft"))))]
      (is (= ["Your appointments could not be loaded"] (map hx/words (hx/by-tag alert :p))))
      (is (str/includes? (hx/words alert) "The rest of the page is up to date. The problem is on our side, not yours."))
      (is (= "Try again" (hx/words (first (buttons alert))))))
    ;; the four of the mock-up the owner approved, and then the five that the sign-in added (ADR-0028, step 4 of Phase 7)
    (is (= [["We cannot find that page" "Go to the home page"]
            ["You cannot see this" "Go to the home page"]
            ["Something went wrong on our side" "Try again"]
            ["Your session has ended" "Sign in again"]
            ["That sign-in has expired" "Start again"]
            ["We could not sign you in" "Try again"]
            ["Sign-in is not available right now" "Try again"]
            ["This portal is for patients" "Sign out"]
            ["We could not do that" "Go to the home page"]]
           (for [card (hx/select main #(and (= :section (hx/tag %)) (hx/has-class? % "shadow-card")))]
             [(hx/words (first (hx/by-tag card :p))) (hx/words (first (buttons card)))])))
    (is (= "../login.html" (href (button-named doc "Sign in again"))))
    (is (= "../login.html" (href (button-named doc "Start again"))) "starting again is signing in")
    (is (= "../login.html" (href (button-named doc "Sign out"))) "in the review, the way out of the portal is the login page")
    (testing "none of them blames the patient"
      (is (not (re-find #"(?i)your fault|you did|you must have|you made" (hx/words main)))))))

(deftest every-page-of-the-portal-has-the-same-navigation-and-the-same-menu
  (doseq [f [portal/home portal/readings portal/new-reading portal/appointments portal/cancel portal/record portal/profile
             portal/new-patient portal/errors]
          :let [doc (f context)
                bar (first (hx/select doc #(= "Main" (:aria-label (hx/attributes %)))))
                dock (first (hx/select doc #(= "Main, on a phone" (:aria-label (hx/attributes %)))))
                menu (first (hx/by-tag doc :details))]]
    (is (= ["Home" "Readings" "Appointments" "My record" "Profile"] (map hx/words (hx/by-tag bar :a))))
    (is (= ["../portal-home.html" "../portal-readings.html" "../portal-appointments.html" "../portal-record.html" "../portal-profile.html"]
           (map href (hx/by-tag bar :a))))
    (is (= (map href (hx/by-tag bar :a)) (map href (hx/by-tag dock :a))) "the dock is the same navigation")
    (is (= ["Profile" "Sign out"] (map hx/words (hx/by-tag menu :a))))
    (is (= ["../portal-profile.html" "../login.html"] (map href (hx/by-tag menu :a))))
    (is (= "../portal-home.html" (href (first (hx/by-tag (first (hx/by-tag doc :header)) :a)))) "the mark leads home")))
