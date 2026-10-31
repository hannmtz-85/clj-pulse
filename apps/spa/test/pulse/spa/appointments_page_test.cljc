(ns pulse.spa.appointments-page-test
  "The appointments as a page (ADR-0029, points 6 and 8): what it says in each of its states, that what could not be loaded is said in its
  place and is never shown as empty, that the buttons of a row are what the lifecycle gives a clinician, that a control with nothing to lead
  to is not there, what the dialog that answers an appointment holds in each of its states, that what the clinic wrote cannot add markup to
  the page, and that every state passes the checks of accessibility. Pure hiccup, so these run on the JVM and on Node, over the same data as
  the tests of the machine."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [pulse.spa.appointment-command :as command]
            [pulse.spa.appointments :as appointments]
            [pulse.spa.pages.appointments :as page]
            [pulse.spa.test.appointments-fixtures :as fx]
            [pulse.ui.layout :as layout]
            [pulse.ui.test.a11y :as a11y]
            [pulse.ui.test.hiccup :as hx]))

(def ^:private rivera {:id "r1" :username "dr.rivera@pulse.test" :name "Elena Rivera" :roles #{:practitioner}})
(def ^:private sam {:id "s1" :username "admin@pulse.test" :name "Sam Admin" :roles #{:clinic-admin}})

(defn- filter-address [show]
  (str "/appointments" (when (#{:requests :booked :cancelled} show) (str "?show=" (name show)))))

(def ^:private links
  {:panel "/" :alerts "/alerts" :appointments "/appointments" :filter filter-address
   :patient (fn [id] (str "/patients/" id))
   :dialog (fn [id action] (str "/appointments/" id "/" (name action)))
   :close "/appointments"})

(def ^:private edited (atom []))

(def ^:private handlers
  {:retry (fn []) :edit (fn [field event] (swap! edited conj [field event])) :submit (fn [_]) :key-down (fn [_])})

(defn- content
  ([state] (content state {}))
  ([state options] (page/content state (merge {:links links :handlers handlers} options))))

(defn- words [tree] (hx/words tree))

(defn- says? [tree text] (str/includes? (words tree) text))

(defn- button-named [tree text]
  (first (filter #(= text (words %)) (hx/by-tag tree :button))))

(defn- link-named [tree text]
  (first (filter #(= text (words %)) (hx/by-tag tree :a))))

(defn- section-named
  "The section of the list whose heading is `title`."
  [tree title]
  (first (filter #(= title (words (first (hx/by-tag % :h2)))) (hx/by-tag tree :section))))

(defn- labelled-sections [tree]
  (filter #(:aria-labelledby (hx/attributes %)) (hx/by-tag tree :section)))

(defn- rows [section] (hx/by-tag section :li))

(defn- row-of [tree id]
  (first (hx/select tree #(= (command/dom-id "appointment" id) (:id (hx/attributes %))))))

(defn- lines [row] (map words (hx/by-tag row :p)))

(defn- tabs [tree]
  (hx/by-tag (first (hx/select tree #(= "Appointments by status" (:aria-label (hx/attributes %))))) :a))

(defn- dialog [tree] (first (hx/by-tag tree :dialog)))

(defn- by-id [tree id] (first (hx/select tree #(= id (:id (hx/attributes %))))))

(defn- status [tree]
  (words (first (hx/select tree #(= "status" (:role (hx/attributes %)))))))

(defn- with-dialog [state id action & typed]
  (reduce (fn [state [field value]] (appointments/edit state field value))
          (appointments/sync-dialog state {:id id :action action})
          (partition 2 typed)))

;; ---------------------------------------------------------------------------
;; The list that has loaded

(deftest the-page-says-what-it-is-and-how-many-requests-wait
  (let [tree (content (fx/settled))]
    (is (= ["Appointments"] (map words (hx/by-tag tree :h1))) "the one heading of the page")
    (is (says? tree "Requests to answer and visits to come."))
    (is (= "2 requests wait for an answer" (status tree)) "told to a screen reader")
    (is (= ["Requests to answer" "Booked" "Cancelled"] (map #(words (first (hx/by-tag % :h2))) (labelled-sections tree))))
    (is (= ["requests" "booked" "cancelled"] (map (comp :aria-labelledby hx/attributes) (labelled-sections tree))))
    (is (= ["requests" "booked" "cancelled"] (map (comp :id hx/attributes) (hx/by-tag tree :h2))))))

(deftest the-administrator-is-told-whose-appointments-they-are
  (is (says? (content (fx/settled) {:admin? true}) "Requests to answer and visits to come, for the clinic's patients.")))

(deftest the-requests-are-rows-with-a-clock-what-they-are-for-whose-they-are-and-when-they-were-asked-for
  (let [section (section-named (content (fx/settled)) "Requests to answer")
        [sofia ana] (rows section)]
    (is (= 2 (count (rows section))))
    (is (= ["COPD check-up (requested by the patient)" "Sofía Rossi · Asked for a visit between Oct 16 and Oct 23"] (lines sofia)))
    (is (= ["Medication review" "Ana García · Asked for a visit between Oct 20 and Oct 27"] (lines ana)))
    (is (= 1 (count (hx/by-tag (first (hx/select sofia #(hx/has-class? % "w-14"))) :svg))) "a clock, and no day")
    (is (= "" (words (first (hx/select sofia #(hx/has-class? % "w-14"))))))
    (is (says? sofia "Requested"))))

(deftest the-booked-appointments-are-rows-with-their-day
  (let [[ana liam] (rows (section-named (content (fx/settled)) "Booked"))]
    (is (= ["Hypertension follow-up" "Ana García · Wed 14 Oct, 10:00–10:30"] (lines ana)))
    (is (= ["Diabetes review" "Liam Chen · Fri 16 Oct, 14:00–14:30"] (lines liam)))
    (is (= "Oct14" (words (first (hx/select ana #(hx/has-class? % "w-14"))))))
    (is (= "Oct16" (words (first (hx/select liam #(hx/has-class? % "w-14"))))))
    (is (says? ana "Confirmed"))))

(deftest a-cancelled-appointment-says-why-it-was
  (let [[row] (rows (section-named (content (fx/settled)) "Cancelled"))]
    (is (= ["Blood pressure check" "Ana García · Tue 29 Sep, 09:00–09:30" "Reason: I had to travel that week."] (lines row)))
    (is (says? row "Cancelled"))))

(deftest the-name-of-the-patient-is-a-link-to-their-chart
  (let [tree (content (fx/settled))
        sofia (row-of tree "appt-sofia-1")]
    (is (= "/patients/pt-sofia" (:href (hx/attributes (link-named sofia "Sofía Rossi")))))
    (is (= "/patients/pt-ana" (:href (hx/attributes (link-named (row-of tree "appt-ana-1") "Ana García")))))
    (is (nil? (link-named (row-of (content (fx/settled) {:links (dissoc links :patient)}) "appt-sofia-1") "Sofía Rossi"))
        "with no chart to lead to it is a name")
    (is (says? (row-of (content (fx/settled) {:links (dissoc links :patient)}) "appt-sofia-1") "Sofía Rossi"))))

(deftest every-row-has-the-buttons-the-lifecycle-gives-a-clinician-as-links-to-their-dialogs
  (let [tree (content (fx/settled))
        buttons-of (fn [id] (map (juxt words #(:href (hx/attributes %)) #(:id (hx/attributes %))) (hx/select (row-of tree id) #(hx/has-class? % "btn"))))]
    (is (= [["Confirm" "/appointments/appt-sofia-1/confirm" "confirm-appt-sofia-1"]
            ["Cancel" "/appointments/appt-sofia-1/cancel" "cancel-appt-sofia-1"]]
           (buttons-of "appt-sofia-1")) "a request is confirmed or cancelled")
    (is (= [["Reschedule" "/appointments/appt-ana-1/reschedule" "reschedule-appt-ana-1"]
            ["Cancel" "/appointments/appt-ana-1/cancel" "cancel-appt-ana-1"]]
           (buttons-of "appt-ana-1")) "a booked appointment is rescheduled or cancelled")
    (is (= [] (buttons-of "appt-ana-0")) "a cancelled one has nothing")))

(deftest a-screen-reader-is-told-which-appointment-each-button-is-for
  (let [tree (content (fx/settled))
        label-of (fn [id text] (:aria-label (hx/attributes (link-named (row-of tree id) text))))]
    (is (= "Confirm COPD check-up (requested by the patient) · Sofía Rossi" (label-of "appt-sofia-1" "Confirm")))
    (is (= "Cancel Medication review · Ana García" (label-of "appt-ana-2" "Cancel")))
    (is (= "Reschedule Diabetes review · Liam Chen" (label-of "appt-liam-1" "Reschedule")))))

(deftest the-buttons-are-different-in-kind-so-that-the-answer-stands-out
  (let [row (row-of (content (fx/settled)) "appt-ana-1")]
    (is (hx/has-class? (link-named row "Reschedule") "btn-outline"))
    (is (hx/has-class? (link-named row "Cancel") "btn-ghost"))
    (is (not (hx/has-class? (link-named (row-of (content (fx/settled)) "appt-sofia-1") "Confirm") "btn-outline")) "the main answer of a request")))

(deftest with-no-dialogs-to-lead-to-there-are-no-buttons
  (let [tree (content (fx/settled) {:links (dissoc links :dialog)})]
    (is (empty? (hx/select tree #(hx/has-class? % "btn"))) "a control with nothing to lead to is not there")
    (is (some? (row-of tree "appt-sofia-1")))))

(deftest a-row-with-an-id-no-address-can-hold-has-no-buttons-and-its-element-has-a-safe-id
  (let [state (assoc-in (fx/settled) [:booked :data :items 0 :id] "a.b")
        tree (content state)
        row (row-of tree "a.b")]
    (is (= "appointment-a_b" (:id (hx/attributes row))) "a dot would be a class in a selector")
    (is (= ["confirm-appt-sofia-1" "cancel-appt-sofia-1" "confirm-appt-ana-2" "cancel-appt-ana-2" "reschedule-a_b" "cancel-a_b"
            "reschedule-appt-liam-1" "cancel-appt-liam-1"]
           (map (comp :id hx/attributes) (hx/select tree #(hx/has-class? % "btn")))) "a dot is allowed in an id of FHIR"))
  (let [state (assoc-in (fx/settled) [:booked :data :items 0 :id] "..")
        row (row-of (content state) "..")]
    (is (empty? (hx/select row #(hx/has-class? % "btn"))))))

(deftest a-row-goes-under-its-text-when-the-room-is-narrow
  (let [tree (content (fx/settled))]
    (is (every? #(hx/has-class? (first (hx/select % (fn [e] (hx/has-class? e "min-w-0")))) "basis-44")
                (mapcat rows (labelled-sections tree))))))

;; ---------------------------------------------------------------------------
;; The tabs

(deftest the-tabs-count-the-appointments-of-each-status
  (let [tree (content (fx/settled))]
    (is (= ["All (5)" "Requests (2)" "Booked (2)" "Cancelled (1)"] (map words (tabs tree))))
    (is (= ["/appointments" "/appointments?show=requests" "/appointments?show=booked" "/appointments?show=cancelled"]
           (map #(:href (hx/attributes %)) (tabs tree))))
    (is (= ["All (5)"] (keep #(when (= "page" (:aria-current (hx/attributes %))) (words %)) (tabs tree))))))

(deftest the-current-tab-is-the-one-the-list-is-narrowed-to
  (doseq [[show current] {:requests "Requests (2)" :booked "Booked (2)" :cancelled "Cancelled (1)"}]
    (is (= [current] (keep #(when (= "page" (:aria-current (hx/attributes %))) (words %)) (tabs (content (fx/settled) {:show show})))) (name show))))

(deftest narrowing-the-list-shows-one-section
  (is (= ["Requests to answer"] (map #(words (first (hx/by-tag % :h2))) (labelled-sections (content (fx/settled) {:show :requests})))))
  (is (= ["Booked"] (map #(words (first (hx/by-tag % :h2))) (labelled-sections (content (fx/settled) {:show :booked})))))
  (is (= ["Cancelled"] (map #(words (first (hx/by-tag % :h2))) (labelled-sections (content (fx/settled) {:show :cancelled})))))
  (is (= 3 (count (labelled-sections (content (fx/settled) {:show :all}))))))

(deftest there-are-no-tabs-when-there-is-nowhere-for-them-to-lead
  (is (empty? (tabs (content (fx/settled) {:links (dissoc links :filter)})))))

(deftest the-tabs-have-no-counts-before-the-lists-are-in
  (is (= ["All" "Requests" "Booked" "Cancelled"] (map words (tabs (content (appointments/start fx/now))))))
  (is (= ["All" "Requests (2)" "Booked" "Cancelled (1)"] (map words (tabs (content (assoc (fx/settled) :booked fx/loading)))))))

;; ---------------------------------------------------------------------------
;; What is on its way, what is empty and what could not be loaded

(deftest before-the-page-begins-it-is-a-title-and-grey-shapes
  (doseq [state [nil (appointments/start fx/now)]]
    (let [tree (content state)]
      (is (= ["Appointments"] (map words (hx/by-tag tree :h1))))
      (is (= "Loading the appointments" (status tree)))
      (is (seq (hx/select tree #(hx/has-class? % "skeleton"))))
      (is (empty? (hx/select tree #(hx/has-class? % "btn"))))
      (is (not (says? tree "Nothing is booked"))))))

(deftest a-list-with-nothing-in-it-says-what-it-is-without
  (let [tree (content (assoc (fx/settled) :requests (fx/loaded fx/none) :booked (fx/loaded fx/none) :cancelled (fx/loaded fx/none)))]
    (is (says? (section-named tree "Requests to answer") "No request is waiting for an answer."))
    (is (says? (section-named tree "Booked") "Nothing is booked."))
    (is (says? (section-named tree "Cancelled") "Nothing has been cancelled."))
    (is (= "No request waits for an answer" (status tree)))
    (is (= ["All (0)" "Requests (0)" "Booked (0)" "Cancelled (0)"] (map words (tabs tree))))))

(deftest a-list-that-could-not-be-loaded-is-a-message-in-its-place-and-not-an-empty-list
  (let [tree (content (assoc (fx/settled) :booked fx/failed))
        booked (section-named tree "Booked")]
    (is (says? booked "The booked appointments could not be loaded"))
    (is (says? booked "Everything else is up to date. The problem is on our side, not yours."))
    (is (some? (button-named booked "Try again")))
    (is (not (says? booked "Nothing is booked")))
    (is (= 2 (count (rows (section-named tree "Requests to answer")))) "the rest is shown"))
  (doseq [[kind title] {:requests "The requests could not be loaded" :cancelled "The cancelled appointments could not be loaded"}]
    (is (says? (content (assoc (fx/settled) kind fx/failed)) title))))

(deftest there-is-no-button-to-try-again-when-there-is-nothing-to-call
  (let [tree (content (assoc (fx/settled) :booked fx/failed) {:handlers {}})]
    (is (says? tree "The booked appointments could not be loaded"))
    (is (nil? (button-named tree "Try again")))))

(deftest the-page-says-when-the-names-could-not-be-loaded-or-the-list-could-not-be-updated
  (let [tree (content (assoc (fx/settled) :patients fx/failed))]
    (is (says? tree "The names of the patients could not be loaded"))
    (is (says? tree "The appointments are up to date."))
    (is (says? (section-named tree "Requests to answer") "Patient pt-sofia")))
  (let [tree (content (assoc-in (fx/settled) [:latest :booked] fx/failed))]
    (is (says? tree "We could not update the list"))
    (is (says? tree "What you see may be out of date."))))

(deftest a-list-that-is-the-first-of-more-says-so
  (let [tree (content (assoc (fx/settled) :booked (fx/loaded (fx/page [fx/ana-booked fx/liam-booked] 130)) :cancelled (fx/loaded (fx/page [fx/ana-cancelled] 101))))]
    (is (says? tree "Showing the first 2 of 130 booked appointments."))
    (is (says? tree "Showing the latest 1 of 101 cancelled appointments."))
    (is (not (says? tree "of 2 requests")) "a list that is all of them says nothing")))

;; ---------------------------------------------------------------------------
;; The dialog

(deftest there-is-no-dialog-until-the-address-names-one
  (is (empty? (hx/by-tag (content (fx/settled)) :dialog))))

(deftest the-dialog-of-a-confirmation-is-over-the-list-with-the-appointment-and-the-fields
  (let [tree (content (with-dialog (fx/settled) "appt-sofia-1" :confirm))
        dialog (dialog tree)]
    (is (some? (section-named tree "Requests to answer")) "the list is behind it")
    (is (true? (:open (hx/attributes dialog))))
    (is (= "true" (:aria-modal (hx/attributes dialog))))
    (is (= "Confirm the appointment" (words (by-id dialog "appointment-dialog-title"))))
    (is (= "appointment-dialog-title" (:aria-labelledby (hx/attributes dialog))))
    (is (says? dialog "COPD check-up (requested by the patient)"))
    (is (says? dialog "Sofía Rossi"))
    (is (says? dialog "Asked for a visit between Oct 16 and Oct 23"))
    (is (= ["2026-10-16" "10:00" "10:30"] (map #(:value (hx/attributes (by-id dialog %))) ["day" "from" "until"])))
    (is (= ["date" "time" "time"] (map #(:type (hx/attributes (by-id dialog %))) ["day" "from" "until"])))
    (is (= ["Day" "From" "Until"] (map #(words (first (hx/by-tag (first (hx/select dialog (fn [e] (= % (:for (hx/attributes e)))))) :label)))
                                       ["day" "from" "until"])))
    (is (= "A visit lasts from 5 minutes to 4 hours." (words (by-id dialog "until-hint"))))
    (is (= "until-hint" (:aria-describedby (hx/attributes (by-id dialog "until")))))
    (is (says? dialog "Sofía sees the new time in the portal."))
    (is (not (says? dialog "reminded")) "no endpoint shows a reminder to the patient")))

(deftest the-buttons-of-the-dialog-are-the-way-out-and-the-one-that-sends
  (let [dialog (dialog (content (with-dialog (fx/settled) "appt-sofia-1" :confirm)))
        close (link-named dialog "Not now")
        submit (by-id dialog "appointment-submit")]
    (is (= "/appointments" (:href (hx/attributes close))))
    (is (= "appointment-close" (:id (hx/attributes close))))
    (is (= "Confirm" (words submit)))
    (is (= "submit" (:type (hx/attributes submit))))
    (is (= "appointment-form" (:form (hx/attributes submit))) "it is outside the form and tied to it")
    (is (some? (by-id dialog "appointment-form")))
    (is (true? (:novalidate (hx/attributes (by-id dialog "appointment-form")))) "the lifecycle and the api judge, and say it beside the field")
    (is (nil? (:disabled (hx/attributes submit))))))

(deftest the-dialog-of-a-rescheduling-starts-from-the-time-the-appointment-has
  (let [dialog (dialog (content (with-dialog (fx/settled) "appt-ana-1" :reschedule)))]
    (is (= "Reschedule the appointment" (words (by-id dialog "appointment-dialog-title"))))
    (is (says? dialog "Wed 14 Oct, 10:00–10:30"))
    (is (= ["2026-10-14" "10:00" "10:30"] (map #(:value (hx/attributes (by-id dialog %))) ["day" "from" "until"])))
    (is (= "Reschedule" (words (by-id dialog "appointment-submit"))))
    (is (says? dialog "Ana sees the new time in the portal."))))

(deftest the-dialog-of-a-cancellation-has-an-optional-reason-and-a-red-button
  (let [dialog (dialog (content (with-dialog (fx/settled) "appt-liam-1" :cancel)))
        why (by-id dialog "why")]
    (is (= "Cancel the appointment" (words (by-id dialog "appointment-dialog-title"))))
    (is (says? dialog "Fri 16 Oct, 14:00–14:30"))
    (is (says? dialog "Why? (optional)"))
    (is (= "text" (:type (hx/attributes why))))
    (is (= "" (:value (hx/attributes why))))
    (is (= "Up to 200 characters." (words (by-id dialog "why-hint"))))
    (is (says? dialog "Liam sees in the portal that it was cancelled, and why if you say."))
    (is (= "Keep it" (words (by-id dialog "appointment-close"))))
    (is (= "Cancel the appointment" (words (by-id dialog "appointment-submit"))))
    (is (hx/has-class? (by-id dialog "appointment-submit") "btn-error") "red, for what cannot be taken back")
    (is (nil? (by-id dialog "day")) "a cancellation has no time")))

(deftest the-dialog-of-a-request-that-is-cancelled-says-request
  (let [dialog (dialog (content (with-dialog (fx/settled) "appt-sofia-1" :cancel)))]
    (is (= "Cancel the request" (words (by-id dialog "appointment-dialog-title"))))
    (is (= "Cancel the request" (words (by-id dialog "appointment-submit"))))))

(deftest a-patient-who-is-not-named-is-the-patient
  (let [state (assoc (fx/settled) :patients fx/failed)
        dialog (dialog (content (with-dialog state "appt-sofia-1" :confirm)))]
    (is (says? dialog "The patient sees the new time in the portal."))
    (is (says? dialog "Patient pt-sofia"))))

(deftest what-is-typed-is-what-the-fields-hold-and-the-handlers-are-told-of-what-is-typed
  (let [dialog (dialog (content (with-dialog (fx/settled) "appt-sofia-1" :confirm :from "11:15")))]
    (is (= "11:15" (:value (hx/attributes (by-id dialog "from")))))
    (reset! edited [])
    ((:on-change (hx/attributes (by-id dialog "day"))) :an-event)
    ((:on-change (hx/attributes (by-id dialog "until"))) :another)
    (is (= [[:day :an-event] [:until :another]] @edited) "the field and the event, for the browser to read the text of")))

(deftest the-form-is-sent-by-the-workspace-and-escape-closes-the-dialog
  (let [sent (atom 0)
        dialog (dialog (content (with-dialog (fx/settled) "appt-sofia-1" :confirm)
                                {:handlers (assoc handlers :submit (fn [_] (swap! sent inc)))}))]
    ((:on-submit (hx/attributes (by-id dialog "appointment-form"))) :event)
    (is (= 1 @sent))
    (is (fn? (:on-key-down (hx/attributes dialog))))))

(deftest with-no-handlers-the-dialog-is-the-form-as-it-is
  (let [dialog (dialog (content (with-dialog (fx/settled) "appt-sofia-1" :confirm) {:handlers {}}))]
    (is (nil? (:on-change (hx/attributes (by-id dialog "day")))))
    (is (nil? (:on-submit (hx/attributes (by-id dialog "appointment-form")))))
    (is (nil? (:on-key-down (hx/attributes dialog))))
    (is (some? (by-id dialog "appointment-submit")))))

(deftest with-no-way-back-to-the-list-there-is-no-link-out-of-the-dialog
  (let [dialog (dialog (content (with-dialog (fx/settled) "appt-sofia-1" :confirm) {:links (dissoc links :close)}))]
    (is (nil? (by-id dialog "appointment-close")))
    (is (some? (by-id dialog "appointment-submit")))))

(deftest what-the-lifecycle-or-the-api-refused-is-beside-the-field-and-the-field-is-marked
  (let [state (-> (with-dialog (fx/settled) "appt-sofia-1" :confirm)
                  (update :dialog command/refused {:day "Must not be in the past." :until "Must be after the start."} nil))
        dialog (dialog (content state))
        day (by-id dialog "day")]
    (is (= "Must not be in the past." (words (by-id dialog "day-error"))))
    (is (= "Must be after the start." (words (by-id dialog "until-error"))))
    (is (= "true" (:aria-invalid (hx/attributes day))))
    (is (= "day-error" (:aria-describedby (hx/attributes day))))
    (is (= "until-hint until-error" (:aria-describedby (hx/attributes (by-id dialog "until")))))
    (is (nil? (:aria-invalid (hx/attributes (by-id dialog "from")))))))

(deftest what-went-wrong-that-is-not-about-a-field-is-in-the-dialog-and-is-announced
  (let [state (-> (with-dialog (fx/settled) "appt-sofia-1" :confirm)
                  (update :dialog command/refused {} {:kind :conflict :text "Only a proposed appointment can be confirmed; this one is booked."}))
        dialog (dialog (content state))
        alert (first (hx/select dialog #(= "alert" (:role (hx/attributes %)))))]
    (is (some? alert))
    (is (says? alert "It was not confirmed"))
    (is (says? alert "Only a proposed appointment can be confirmed; this one is booked."))
    (is (hx/has-class? alert "border-tone-high-edge"))))

(deftest the-button-of-the-dialog-is-busy-while-the-command-is-on-its-way
  (let [sending (first (appointments/submit (with-dialog (fx/settled) "appt-sofia-1" :confirm) fx/now))
        submit (by-id (dialog (content sending)) "appointment-submit")]
    (is (true? (:disabled (hx/attributes submit))))
    (is (= "true" (:aria-busy (hx/attributes submit))))
    (is (true? (:disabled (hx/attributes (by-id (dialog (content (update sending :dialog command/settling))) "appointment-submit")))) "and until the lists are read again")))

(deftest a-dialog-whose-appointment-is-on-its-way-has-grey-shapes-and-a-way-out
  (let [state (assoc (fx/settled) :requests fx/loading)
        dialog (dialog (content (with-dialog state "appt-sofia-1" :confirm)))]
    (is (= "Loading the appointment" (words (by-id dialog "appointment-dialog-title"))))
    (is (seq (hx/select dialog #(hx/has-class? % "skeleton"))))
    (is (= "Back to the list" (words (by-id dialog "appointment-close"))))
    (is (nil? (by-id dialog "appointment-submit")))))

(deftest a-dialog-whose-appointment-could-not-be-loaded-says-so-and-offers-to-try-again
  (let [state (assoc (fx/settled) :booked fx/failed)
        open (dialog (content (with-dialog state "appt-nobody" :confirm)))]
    (is (= "The appointment could not be loaded" (words (by-id open "appointment-dialog-title"))))
    (is (says? open "The problem is on our side, not yours."))
    (is (some? (button-named open "Try again")))
    (is (nil? (button-named (dialog (content (with-dialog state "appt-nobody" :confirm) {:handlers {}})) "Try again")))))

(deftest a-dialog-for-an-appointment-that-is-nowhere-says-so
  (let [dialog (dialog (content (with-dialog (fx/settled) "appt-nobody" :confirm)))]
    (is (= "We cannot find that appointment" (words (by-id dialog "appointment-dialog-title"))))
    (is (says? dialog "It may have been removed."))
    (is (= "Back to the list" (words (by-id dialog "appointment-close"))))))

(deftest a-dialog-for-a-command-that-cannot-be-done-says-why-in-the-lifecycle-s-words
  (let [dialog (dialog (content (with-dialog (fx/settled) "appt-ana-1" :confirm)))]
    (is (= "Confirm the appointment" (words (by-id dialog "appointment-dialog-title"))))
    (is (says? dialog "Hypertension follow-up"))
    (is (says? dialog "That cannot be done now"))
    (is (says? dialog "Only a proposed appointment can be confirmed; this one is booked."))
    (is (nil? (by-id dialog "appointment-submit")))
    (is (= "Back to the list" (words (by-id dialog "appointment-close"))))))

;; ---------------------------------------------------------------------------
;; What the clinic wrote

(deftest what-the-clinic-wrote-cannot-add-markup-to-the-page
  (let [hostile "<script>alert(1)</script>"
        state (-> (fx/settled)
                  (assoc-in [:requests :data :items 0 :description] hostile)
                  (assoc-in [:patients :data :items 4 :name] hostile)
                  (assoc-in [:cancelled :data :items 0 :reason] hostile))
        tree (content state)]
    (is (empty? (hx/by-tag tree :script)))
    (is (says? tree hostile) "the text is the text")
    (is (empty? (hx/by-tag (content (with-dialog state "appt-sofia-1" :confirm)) :script)))))

(deftest a-name-that-is-very-long-wraps-in-the-places-it-can
  (let [long-name (apply str (repeat 120 "Ñandú"))
        state (-> (fx/settled) (assoc-in [:patients :data :items 4 :name] long-name) (assoc-in [:requests :data :items 0 :description] long-name))
        tree (content state)
        row (row-of tree "appt-sofia-1")]
    (is (says? row long-name))
    (is (seq (hx/select row #(and (hx/has-class? % "wrap-anywhere") (= long-name (words %))))) "the description can break anywhere")
    (is (seq (hx/select row #(and (hx/has-class? % "wrap-anywhere") (str/includes? (words %) long-name) (not= :p (hx/tag %))))) "and so can the name")
    (let [dialog (dialog (content (with-dialog state "appt-sofia-1" :confirm)))]
      (is (seq (hx/select dialog #(and (hx/has-class? % "wrap-anywhere") (= long-name (words %))))) "in the dialog too"))))

(deftest a-description-that-is-not-text-is-an-appointment
  (let [state (assoc-in (fx/settled) [:requests :data :items 0 :description] nil)]
    (is (= ["Appointment" "Sofía Rossi · Asked for a visit between Oct 16 and Oct 23"] (lines (row-of (content state) "appt-sofia-1"))))))

;; ---------------------------------------------------------------------------
;; The workspace around it

(def ^:private states
  {"before it begins" nil
   "on its way" (appointments/start fx/now)
   "loaded" (fx/settled)
   "nothing at all" (assoc (fx/settled) :requests (fx/loaded fx/none) :booked (fx/loaded fx/none) :cancelled (fx/loaded fx/none))
   "a list could not be loaded" (assoc (fx/settled) :booked fx/failed)
   "every list could not be loaded" (assoc (fx/settled) :requests fx/failed :booked fx/failed :cancelled fx/failed)
   "the names could not be loaded" (assoc (fx/settled) :patients fx/failed)
   "the list could not be updated" (assoc-in (fx/settled) [:latest :requests] fx/failed)
   "a list that was cut" (assoc (fx/settled) :booked (fx/loaded (fx/page [fx/ana-booked] 130)))
   "a confirmation" (with-dialog (fx/settled) "appt-sofia-1" :confirm)
   "a confirmation with words beside the fields" (update (with-dialog (fx/settled) "appt-sofia-1" :confirm) :dialog command/refused
                                                         {:day "Must not be in the past." :from "Enter a time." :until "Must be after the start."}
                                                         {:kind :unconfirmed})
   "a rescheduling" (with-dialog (fx/settled) "appt-ana-1" :reschedule)
   "a cancellation" (with-dialog (fx/settled) "appt-liam-1" :cancel :why "away")
   "a cancellation with words beside the field" (update (with-dialog (fx/settled) "appt-liam-1" :cancel) :dialog command/refused {:why "Must be 1 to 200 characters."} nil)
   "a command on its way" (first (appointments/submit (with-dialog (fx/settled) "appt-sofia-1" :confirm) fx/now))
   "a command that cannot be done" (with-dialog (fx/settled) "appt-ana-1" :confirm)
   "an appointment on its way" (with-dialog (assoc (fx/settled) :requests fx/loading) "appt-sofia-1" :confirm)
   "an appointment that could not be loaded" (with-dialog (assoc (fx/settled) :booked fx/failed) "appt-nobody" :confirm)
   "an appointment that is nowhere" (with-dialog (fx/settled) "appt-nobody" :confirm)
   "something said of what was done" (assoc (fx/settled) :said "Appointment confirmed.")})

(deftest every-state-of-the-appointments-passes-the-checks-of-accessibility
  (doseq [[label state] states
          admin? [false true]
          show [:all :requests :booked :cancelled]
          :let [person (if admin? sam rivera)
                tree (page/page state {:person person :show show :links links :handlers (assoc handlers :sign-out (fn []))})]]
    (is (= [] (a11y/problems (layout/document {:title "Pulse"} [:div {:id "app"} tree]) {:page? true})) (str label " " admin? " " show))))

(deftest the-workspace-around-the-appointments-has-them-as-the-current-page
  (let [tree (page/page (fx/settled) {:person rivera :alert-count 2 :links links :handlers {:sign-out (fn [])}})
        nav (first (hx/select tree #(= "Workspace" (:aria-label (hx/attributes %)))))
        current (first (hx/select nav #(= "page" (:aria-current (hx/attributes %)))))]
    (is (empty? (hx/by-tag tree :input)) "a box that searched nothing here would be a control with nothing to do")
    (is (= "Appointments" (words current)))
    (is (= ["Panel" "Alerts2" "Appointments"] (map words (hx/by-tag nav :a))) "the administrator's group is not the practitioner's")))

(deftest the-administrator-has-the-jobs-in-the-sidebar-of-the-appointments-too
  (let [tree (page/page (fx/settled) {:person sam :links (assoc links :jobs "/jobs") :handlers {:sign-out (fn [])}})
        nav (first (hx/select tree #(= "Workspace" (:aria-label (hx/attributes %)))))]
    (is (= ["Panel" "Alerts" "Appointments" "Jobs"] (map words (hx/by-tag nav :a))))))
