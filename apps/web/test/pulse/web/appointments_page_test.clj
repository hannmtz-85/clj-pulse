(ns pulse.web.appointments-page-test
  "The appointments of the portal as a function of the data in the shapes of the api (ADR-0028): what is coming, what began and what was
  cancelled, the form to ask for a visit and what is wrong with it, the question before one is cancelled, what the page says it could not
  load or has just done, where its buttons lead, and that nothing a person typed or the clinic wrote is ever anything but text."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.design.data :as data]
            [pulse.design.portal :as portal]
            [pulse.fhir.appointment :as lifecycle]
            [pulse.ui.test.a11y :as a11y]
            [pulse.ui.test.hiccup :as hx]
            [pulse.ui.test.html :as parsed]
            [pulse.web.html :as html]
            [pulse.web.pages.appointments :as appointments]
            [pulse.view.views :as views]
            [pulse.web.visits :as visits]))

(def ^:private links
  {:appointments "/appointments"
   :request-visit "/appointments#ask"
   :cancel-appointment (fn [row] (str "/appointments/" (:id row) "/cancel"))
   :cancel-request (fn [row] (str "/appointments/" (:id row) "/cancel"))
   :retry "/appointments"})

(def ^:private ana
  "What the page is a function of for Ana, at the moment of the mock-ups: a confirmed appointment, a request that waits and one that was
  cancelled."
  (assoc (portal/appointments-model "pt-ana") :csrf-token "tok-en_1"))

(def ^:private noah (assoc (portal/appointments-model "pt-noah") :csrf-token "tok-en_1"))

(defn- page [model & [page-links]]
  (parsed/document (html/render [:main (appointments/content model (or page-links links))])))

(defn- cancel-page [model & [page-links]]
  (parsed/document (html/render [:main (appointments/cancel-content model (or page-links links))])))

(defn- a11y-of [model] (a11y/problems [:main (appointments/content model links)]))

(defn- with-items
  "`model` with the appointments of the api being `items`."
  [model items]
  (assoc model :appointments {:total (count items) :offset 0 :limit 100 :items (vec items)}))

(defn- item [& {:as fields}]
  (merge {:id "a1" :status "booked" :description "Hypertension follow-up" :start "2026-10-14T08:00:00Z" :end "2026-10-14T08:30:00Z"
          :requestedStart nil :requestedEnd nil :practitioner "Practitioner/pr-rivera" :reason nil :patient "Patient/pt-ana"}
         fields))

(defn- section [tree id]
  (first (hx/select tree #(and (= :section (hx/tag %)) (= id (:aria-labelledby (hx/attributes %)))))))

(defn- section-ids [tree]
  (keep #(:aria-labelledby (hx/attributes %)) (hx/by-tag tree :section)))

(defn- rows [tree label]
  (hx/by-tag (first (hx/select tree #(and (= :ul (hx/tag %)) (= label (:aria-label (hx/attributes %)))))) :li))

(defn- lines [row] (map hx/words (hx/by-tag row :p)))

(defn- buttons [tree]
  (hx/select tree #(and (hx/has-class? % "btn") (#{:a :button} (hx/tag %)))))

(defn- button-named [tree text]
  (first (filter #(= text (hx/words %)) (buttons tree))))

(defn- href [element] (:href (hx/attributes element)))

(defn- alerts [tree]
  (hx/select tree #(and (= :div (hx/tag %)) (some (fn [tone] (hx/has-class? % tone)) ["bg-tone-high-soft" "bg-tone-normal-soft" "bg-tone-info-soft"]))))

(defn- alert-words [tree] (map hx/words (alerts tree)))

;; ---------------------------------------------------------------------------
;; The page of a patient with appointments

(deftest the-appointments-are-what-is-coming-what-was-cancelled-and-the-form
  (let [tree (page ana)]
    (is (= :div (hx/tag (first (hx/child-nodes tree)))))
    (is (= ["Appointments"] (map hx/words (hx/by-tag tree :h1))))
    (is (str/includes? (hx/words tree) "Visits with your care team."))
    (is (= ["coming" "cancelled"] (section-ids tree)) "the sections that have something, in this order")
    (is (= ["Coming up" "Cancelled" "Ask for a visit"] (map hx/words (hx/by-tag tree :h2))))
    (testing "what is coming, the soonest first: the confirmed appointment and the request that has not been answered"
      (is (= [["Hypertension follow-up" "Wed 14 Oct, 10:00–10:30 · Dr. Elena Rivera"]
              ["Medication review" "Asked for a visit between Oct 20 and Oct 27 · Dr. Elena Rivera"]]
             (map lines (rows tree "Appointments that are coming"))))
      (is (= ["Confirmed" "Requested"]
             (map #(first (filter #{"Confirmed" "Requested"} (map hx/words (hx/by-tag % :span)))) (rows tree "Appointments that are coming")))))
    (testing "what was cancelled, with the reason that was given"
      (is (= [["Blood pressure check" "Tue 29 Sep, 09:00–09:30 · Dr. Elena Rivera" "Reason: I had to travel that week."]]
             (map lines (rows tree "Cancelled appointments"))))
      (is (str/includes? (hx/words (first (rows tree "Cancelled appointments"))) "Cancelled")))
    (is (= [] (a11y-of ana)))))

(deftest a-patient-may-cancel-what-the-lifecycle-lets-them-and-the-button-goes-to-the-question
  (let [tree (page ana)]
    (is (= "/appointments/appt-ana-1/cancel" (href (button-named tree "Cancel appointment"))))
    (is (= "/appointments/appt-ana-2/cancel" (href (button-named tree "Cancel request"))))
    (testing "and nothing that is cancelled has a button"
      (is (empty? (filter #(#{"Cancel appointment" "Cancel request"} (hx/words %)) (buttons (first (hx/select tree #(= "cancelled" (:aria-labelledby (hx/attributes %))))))))))
    (testing "the question is asked at the address of the appointment, which is a function of its row"
      (let [seen (atom [])
            tree (page ana (assoc links :cancel-appointment (fn [row] (swap! seen conj (:id row)) (str "/a/" (:id row)))))]
        (is (= "/a/appt-ana-1" (href (button-named tree "Cancel appointment"))))
        (is (= ["appt-ana-1"] @seen))))))

(deftest what-has-begun-is-earlier-and-has-no-button-to-cancel
  (let [model (with-items ana [(item :id "past" :description "Last week" :start "2026-10-06T08:00:00Z" :end "2026-10-06T08:30:00Z")
                               (item :id "soon")])
        tree (page model)]
    (is (= ["coming" "earlier"] (section-ids tree)))
    (is (= ["Coming up" "Earlier" "Ask for a visit"] (map hx/words (hx/by-tag tree :h2))))
    (is (= [["Last week" "Tue 6 Oct, 10:00–10:30 · Dr. Elena Rivera"]] (map lines (rows tree "Earlier appointments"))))
    (is (empty? (filter #(#{"Cancel appointment" "Cancel request"} (hx/words %)) (buttons (section tree "earlier")))) "only the clinic can cancel it")
    (is (= [] (a11y-of model)))))

(deftest nothing-coming-is-said-so-and-the-form-is-still-there
  (let [model (with-items ana [])
        tree (page model)]
    (is (= ["coming"] (section-ids tree)) "no section with nothing in it but the one that says so")
    (is (str/includes? (hx/words (section tree "coming")) "No appointment coming up"))
    (is (str/includes? (hx/words (section tree "coming")) "When your clinic confirms one, you will see it here."))
    (is (some? (hx/by-id tree "reason")))
    (is (= [] (a11y-of model)))))

(deftest what-the-api-could-not-give-is-a-message-and-not-an-empty-list
  (let [model (assoc ana :appointments nil)
        tree (page model)]
    (is (= ["coming"] (section-ids tree)))
    (is (= ["Your appointments could not be loadedThe rest of the page is up to date. The problem is on our side, not yours.Try again"]
           (alert-words tree)))
    (is (not (str/includes? (hx/words (section tree "coming")) "No appointment coming up")) "it is not that there are none")
    (is (= "/appointments" (href (button-named tree "Try again"))))
    (is (some? (hx/by-id tree "reason")) "and a request can still be made")
    (is (= [] (a11y-of model)))
    (testing "with nowhere to try again from, there is no button"
      (is (nil? (button-named (page model (dissoc links :retry)) "Try again"))))))

(deftest what-the-api-cut-is-said
  (let [items (mapv #(item :id (str "a" %)) (range 3))]
    (is (str/includes? (hx/words (page (assoc-in (with-items ana items) [:appointments :total] 130))) "Showing your latest 3 of 130 appointments."))
    (testing "and when nothing was cut, nothing is said"
      (doseq [total [3 2 nil]]
        (is (not (str/includes? (hx/words (page (assoc-in (with-items ana items) [:appointments :total] total))) "Showing your latest"))
            (pr-str total))))))

;; ---------------------------------------------------------------------------
;; The buttons are where the pages are

(deftest a-button-whose-page-is-not-there-is-not-there
  (testing "with no link at all"
    (let [tree (page ana {})]
      (is (nil? (button-named tree "Ask for a visit")))
      (is (nil? (button-named tree "Cancel appointment")))
      (is (nil? (button-named tree "Cancel request")))
      (is (some? (button-named tree "Send the request")) "the form is the page's own")))
  (testing "the button to ask for a visit takes the patient to the form, which is in the page"
    (is (= "/appointments#ask" (href (button-named (page ana) "Ask for a visit"))))
    (is (some? (hx/by-id (page ana) "ask")))))

;; ---------------------------------------------------------------------------
;; What the page tells

(deftest nothing-is-told-when-nothing-has-happened
  (is (empty? (alerts (page ana))))
  (is (empty? (alerts (page (assoc ana :notice nil :form nil))))))

(deftest a-request-that-was-sent-is-told-in-the-words-of-what-the-api-kept
  (let [sent (item :id "new" :status "proposed" :description "Back pain" :start nil :end nil
                   :requestedStart "2026-10-19T22:00:00Z" :requestedEnd "2026-10-27T23:00:00Z")
        tree (page (assoc ana :notice {:requested sent}))]
    (is (= ["Request sentBack pain. Dr. Elena Rivera will confirm a time, and you will see it here."] (alert-words tree)))
    (is (= "status" (:role (hx/attributes (first (alerts tree))))) "it is announced")
    (is (= [] (a11y-of (assoc ana :notice {:requested sent}))))
    (testing "with no doctor's name, it is the clinic that will"
      (is (= ["Request sentBack pain. Your clinic will confirm a time, and you will see it here."]
             (alert-words (page (assoc ana :notice {:requested sent} :me (assoc (:me ana) :generalPractitioner {:reference "Practitioner/pr-rivera"})))))))
    (testing "with no description, it says no more of it"
      (is (= ["Request sentDr. Elena Rivera will confirm a time, and you will see it here."]
             (alert-words (page (assoc ana :notice {:requested (assoc sent :description nil)}))))))))

(deftest a-cancellation-is-told-in-the-words-of-what-the-api-kept
  (let [cancelled (item :status "cancelled" :reason "I had to travel.")]
    (is (= ["Appointment cancelledHypertension follow-up, Wed 14 Oct, 10:00–10:30. Your care team will see it."]
           (alert-words (page (assoc ana :notice {:cancelled cancelled})))))
    (is (= "status" (:role (hx/attributes (first (alerts (page (assoc ana :notice {:cancelled cancelled}))))))))
    (testing "and of a request, which has no time"
      (is (= ["Request cancelledMedication review. Your care team will see it."]
             (alert-words (page (assoc ana :notice {:cancelled (item :id "r" :status "cancelled" :description "Medication review" :start nil :end nil
                                                                     :requestedStart "2026-10-20T00:00:00Z" :requestedEnd "2026-10-27T00:00:00Z")}))))))
    (is (= [] (a11y-of (assoc ana :notice {:cancelled cancelled}))))))

(deftest what-could-not-be-cancelled-is-told-with-the-reason
  (doseq [[reason words role] [[:already-cancelled "That appointment is already cancelled" "status"]
                               [:begun "The appointment was not cancelledIt has already begun, so only the clinic can cancel it." "alert"]
                               [:changed "The appointment was not cancelledIt changed while you were looking at it. Look at it again and try once more." "alert"]
                               [:not-cancellable "The appointment was not cancelledIt cannot be cancelled here. Tell your clinic." "alert"]]
          :let [model (assoc ana :notice {:blocked reason})
                tree (page model)]]
    (testing (name reason)
      (is (= [words] (alert-words tree)))
      (is (= role (:role (hx/attributes (first (alerts tree))))))
      (is (= [] (a11y-of model)))))
  (testing "a reason that nobody knows is the same as no way to cancel it here"
    (is (= ["The appointment was not cancelledIt cannot be cancelled here. Tell your clinic."]
           (alert-words (page (assoc ana :notice {:blocked :whatever})))))))

;; ---------------------------------------------------------------------------
;; The form to ask for a visit

(defn- ask [tree] (hx/by-id tree "ask"))

(defn- field [tree id] (hx/by-id tree id))

(defn- attrs [tree id] (hx/attributes (field tree id)))

(deftest the-form-goes-to-the-doctor-of-the-patient-and-is-posted-to-the-appointments
  (let [tree (page ana)
        form (first (hx/by-tag (ask tree) :form))]
    (is (str/includes? (hx/words (ask tree)) "Your request goes to Dr. Elena Rivera, who will confirm a time."))
    (is (= {:method "post" :action "/appointments" :novalidate true} (select-keys (hx/attributes form) [:method :action :novalidate])))
    (is (= [{:type "hidden" :name "__anti-forgery-token" :value "tok-en_1"}]
           (map hx/attributes (filter #(= "__anti-forgery-token" (:name (hx/attributes %))) (hx/by-tag form :input))))
        "with the token of the session, which the post needs")
    (testing "the doctor of Noah is his"
      (is (str/includes? (hx/words (ask (page noah))) "Your request goes to Dr. Chidi Okafor, who will confirm a time.")))
    (testing "a doctor with no name is the doctor"
      (is (str/includes? (hx/words (ask (page (assoc ana :me (assoc (:me ana) :generalPractitioner {:reference "Practitioner/pr-rivera" :name " "})))))
                         "Your request goes to your doctor, who will confirm a time.")))))

(deftest the-fields-are-the-reason-and-the-days-with-the-limits-of-the-api
  (let [tree (page ana)]
    (is (= "What is it about?" (hx/words (first (hx/select tree #(and (= :label (hx/tag %)) (= "reason" (:for (hx/attributes %)))))))))
    (is (= {:name "reason" :rows "3" :maxlength "200"} (select-keys (attrs tree "reason") [:name :rows :maxlength])) "as many characters as the api takes")
    (is (= "A few words are enough, up to 200 characters." (hx/words (hx/by-id tree "reason-hint"))))
    (is (= "When would suit you?" (hx/words (first (hx/by-tag (ask tree) :legend)))))
    (is (= ["From" "Until"] (map hx/words (hx/select (ask tree) #(and (= :label (hx/tag %)) (#{"from" "to"} (:for (hx/attributes %))))))))
    (is (= {:type "date" :name "from" :value "2026-10-13" :min "2026-10-13" :max "2027-04-11"} (select-keys (attrs tree "from") [:type :name :value :min :max]))
        "today, which is the first day to ask for and at most 180 days ahead")
    (is (= {:type "date" :name "to" :value "2026-10-20" :min "2026-10-13" :max "2027-05-10"} (select-keys (attrs tree "to") [:type :name :value :min :max]))
        "a week after it, and no later than a window of 30 days from the last first day")
    (is (= "At most 30 days in all." (hx/words (hx/by-id tree "to-hint"))))
    (is (= "submit" (:type (hx/attributes (button-named tree "Send the request")))))
    (testing "the limits are the lifecycle's, so that a change there is a change here"
      (is (= (str (:text-length lifecycle/limits)) (:maxlength (attrs tree "reason"))))
      (is (= (str "At most " (:window-days lifecycle/limits) " days in all.") (hx/words (hx/by-id tree "to-hint")))))
    (testing "and none of them is judged by the browser: the api says what is wrong, in words"
      (is (not-any? #(contains? (attrs tree %) :required) ["reason" "from" "to"])))))

(deftest the-days-of-the-form-are-the-days-of-the-clinic
  (let [tree (page (assoc ana :now "2026-10-13T22:30:00Z"))]
    (is (= ["2026-10-14" "2026-10-21"] (map #(:value (attrs tree %)) ["from" "to"])) "it is already tomorrow in Madrid")
    (is (= "2026-10-14" (:min (attrs tree "from"))))))

(deftest what-was-typed-is-kept
  (let [tree (page (assoc ana :form {:values {:reason "My back <hurts>" :from "2026-11-02" :to "2026-11-09"}}))]
    (is (= "My back <hurts>" (hx/words (field tree "reason"))))
    (is (= ["2026-11-02" "2026-11-09"] (map #(:value (attrs tree %)) ["from" "to"]))))
  (testing "and what was not is empty"
    (let [tree (page (assoc ana :form {:values {:reason nil :from nil :to nil}}))]
      (is (= "" (hx/words (field tree "reason"))))
      (is (= [nil nil] (map #(:value (attrs tree %)) ["from" "to"]))))))

(deftest nothing-is-wrong-with-a-form-that-has-not-been-sent
  (let [tree (page ana)]
    (is (empty? (alerts tree)))
    (is (every? #(nil? (hx/by-id tree (str % "-error"))) ["reason" "from" "to"]))
    (is (every? #(nil? (:aria-invalid (attrs tree %))) ["reason" "from" "to"]))
    (is (every? #(not (contains? (attrs tree %) :autofocus)) ["reason" "from" "to"]))))

(deftest what-is-wrong-is-said-beside-the-field-and-at-the-top
  (let [model (assoc ana :form {:values {:reason "" :from "2026-11-02" :to "soon"}
                                :errors {:reason "Write a few words about it." :to "Enter a date."}
                                :failure :refused})
        tree (page model)]
    (testing "beside each field that is wrong, tied to it"
      (is (= "Write a few words about it." (hx/words (hx/by-id tree "reason-error"))))
      (is (= "Enter a date." (hx/words (hx/by-id tree "to-error"))))
      (is (nil? (hx/by-id tree "from-error")))
      (is (= ["true" "true" nil] (map #(:aria-invalid (attrs tree %)) ["reason" "to" "from"])))
      (is (str/includes? (:aria-describedby (attrs tree "to")) "to-error")))
    (testing "and at the top, which says which"
      (is (= ["The request was not sentCheck the reason and the last day and send it again."] (alert-words tree))))
    (testing "the cursor is in the first that is wrong"
      (is (= [true nil nil] (map #(:autofocus (attrs tree %)) ["reason" "from" "to"]))))
    (is (= [] (a11y-of model)))))

(deftest the-message-at-the-top-names-the-fields-that-are-wrong
  (doseq [[errors words] [[{:reason "x"} "Check the reason and send it again."]
                          [{:from "x"} "Check the first day and send it again."]
                          [{:to "x"} "Check the last day and send it again."]
                          [{:from "x" :to "y"} "Check the first day and the last day and send it again."]
                          [{:reason "x" :from "y" :to "z"} "Check the reason, the first day and the last day and send it again."]
                          [{} "Check what you entered and send it again."]]]
    (is (= [(str "The request was not sent" words)] (alert-words (page (assoc ana :form {:errors errors :failure :refused})))) (pr-str errors))))

(deftest the-cursor-goes-to-the-first-field-that-is-wrong-in-the-order-of-the-form
  (doseq [[errors focus] [[{:to "x"} "to"] [{:from "x" :to "y"} "from"] [{:reason "x" :to "y"} "reason"] [{:to "y" :from "x" :reason "z"} "reason"]]]
    (let [tree (page (assoc ana :form {:errors errors :failure :refused}))]
      (is (= [focus] (filter #(:autofocus (attrs tree %)) ["reason" "from" "to"])) (pr-str errors)))))

(deftest a-request-that-failed-on-our-side-is-said-so-and-the-form-keeps-what-was-typed
  (let [typed {:reason "Back pain" :from "2026-11-02" :to "2026-11-09"}]
    (testing "when nothing was sent"
      (let [tree (page (assoc ana :form {:values typed :failure :unsaved}))]
        (is (= ["The request was not sentThe problem is on our side, not yours. Try again in a moment."] (alert-words tree)))
        (is (= "alert" (:role (hx/attributes (first (alerts tree))))))
        (is (= "Back pain" (hx/words (field tree "reason"))))
        (is (= [true nil nil] (map #(:autofocus (attrs tree %)) ["reason" "from" "to"])) "the cursor goes to the form, which is at the bottom")))
    (testing "when it was on its way, nobody knows, and the patient is told to look before they send it again"
      (let [model (assoc ana :form {:values typed :failure :unconfirmed})
            tree (page model)]
        (is (= ["We could not confirm that your request was sentThe problem is on our side, not yours. Look at your appointments before you try again, so that it is not asked for twice."]
               (alert-words tree)))
        (is (= "Back pain" (hx/words (field tree "reason"))))
        (is (= [] (a11y-of model)))))))

(deftest a-patient-with-no-doctor-has-no-form-and-is-told-why
  (doseq [[what model] {"no doctor in what the api says of them" (assoc ana :me (assoc (:me ana) :generalPractitioner nil))
                        "a doctor with no reference" (assoc ana :me (assoc (:me ana) :generalPractitioner {:name "Dr. X"}))
                        "the api having just said so" (assoc ana :form {:values {:reason "x"} :failure :no-doctor})}
          :let [tree (page model)]]
    (testing what
      (is (empty? (hx/by-tag (ask tree) :form)))
      (is (nil? (hx/by-id tree "reason")))
      (is (nil? (button-named tree "Send the request")))
      (is (nil? (button-named tree "Ask for a visit")) "and the header does not offer it either")
      (is (= ["You cannot ask for a visit yetWe do not know who your doctor is, so a request would have nowhere to go. Tell your clinic."]
             (alert-words tree)))
      (is (= [] (a11y/problems [:main (appointments/content model links)]))))))

;; ---------------------------------------------------------------------------
;; The question before an appointment is cancelled

(defn- target-of [model status]
  (first (filter #(= status (:status %)) (get-in model [:appointments :items]))))

(def ^:private booked-model (assoc ana :target (target-of ana "booked")))

(def ^:private request-model (assoc ana :target (target-of ana "proposed")))

(defn- dialog-of [tree] (first (hx/by-tag tree :dialog)))

(deftest the-question-is-over-the-appointments-and-names-what-is-cancelled
  (let [tree (cancel-page booked-model)
        dialog (dialog-of tree)]
    (is (true? (:open (hx/attributes dialog))))
    (is (= "true" (:aria-modal (hx/attributes dialog))))
    (is (= "Cancel this appointment?" (hx/words (hx/by-id dialog "cancel-appointment-title"))))
    (is (str/includes? (hx/words dialog) "Hypertension follow-up"))
    (is (str/includes? (hx/words dialog) "Wed 14 Oct, 10:00–10:30 · Dr. Elena Rivera"))
    (testing "it promises what the system does: the care team sees a cancelled appointment, and nobody is told"
      (is (str/includes? (hx/words dialog) "Your care team will see it. You can ask for another visit at any time."))
      (is (not (re-find #"(?i)notified|informed|\btold\b" (hx/words dialog)))))
    (testing "behind it, the appointments, with no form to ask for a visit"
      (is (some? (section tree "coming")))
      (is (empty? (hx/by-tag (section tree "coming") :form)))
      (is (nil? (hx/by-id tree "reason")))
      (is (nil? (button-named tree "Ask for a visit"))))
    (is (= [] (a11y/problems [:main (appointments/cancel-content booked-model links)])))))

(deftest the-question-is-for-a-request-in-the-words-of-a-request
  (let [tree (cancel-page request-model)
        dialog (dialog-of tree)]
    (is (= "Cancel this request?" (hx/words (hx/by-id dialog "cancel-appointment-title"))))
    (is (str/includes? (hx/words dialog) "Medication review"))
    (is (str/includes? (hx/words dialog) "Asked for a visit between Oct 20 and Oct 27 · Dr. Elena Rivera"))
    (is (some? (button-named dialog "Cancel the request")))
    (is (nil? (button-named dialog "Cancel the appointment")))
    (is (= [] (a11y/problems [:main (appointments/cancel-content request-model links)])))))

(deftest the-answer-is-posted-to-the-address-of-the-question-with-the-token
  (doseq [[model address] [[booked-model "/appointments/appt-ana-1/cancel"] [request-model "/appointments/appt-ana-2/cancel"]]
          :let [tree (cancel-page model)
                form (first (hx/by-tag (dialog-of tree) :form))]]
    (is (= {:method "post" :action address :novalidate true :id "cancel-form"} (select-keys (hx/attributes form) [:method :action :novalidate :id])))
    (is (= [{:type "hidden" :name "__anti-forgery-token" :value "tok-en_1"}]
           (map hx/attributes (filter #(= "__anti-forgery-token" (:name (hx/attributes %))) (hx/by-tag form :input)))))
    (testing "the reason is a field of the form, and optional"
      (is (some? (hx/by-id form "why")))
      (is (= "why" (:name (attrs form "why"))))
      (is (= "200" (:maxlength (attrs form "why"))))
      (is (str/includes? (hx/words (first (hx/select form #(and (= :label (hx/tag %)) (= "why" (:for (hx/attributes %))))))) "Why? It helps your care team (optional)"))
      (is (= "Up to 200 characters." (hx/words (hx/by-id form "why-hint")))))))

(deftest there-is-a-way-to-keep-it-and-a-button-that-cancels-it-which-submits-the-form
  (let [tree (cancel-page booked-model)
        dialog (dialog-of tree)
        keep (button-named dialog "Keep it")
        cancel (button-named dialog "Cancel the appointment")]
    (is (= "/appointments" (href keep)) "back to the appointments")
    (is (= [true] (map :autofocus (map hx/attributes [keep]))) "the cursor is on the safe one")
    (is (hx/has-class? keep "btn-ghost"))
    (is (= {:type "submit" :form "cancel-form"} (select-keys (hx/attributes cancel) [:type :form])) "it submits the form, which is beside it")
    (is (some? (hx/by-id dialog "cancel-form")) "and the form is there")
    (is (hx/has-class? cancel "btn-error"))
    (testing "each is as large as every other button of the portal: a finger"
      (is (every? #(hx/has-class? % "btn-lg") [keep cancel])))
    (testing "keeping it needs somewhere to go back to"
      (is (nil? (button-named (dialog-of (cancel-page booked-model (dissoc links :appointments))) "Keep it"))))))

(deftest the-reason-that-was-typed-is-kept-and-what-is-wrong-with-it-is-said-beside-it
  (let [model (assoc booked-model :why {:values {:why "I had to <travel>"} :errors {:why "Must be 1 to 200 characters."} :failure :refused})
        tree (cancel-page model)
        dialog (dialog-of tree)]
    (is (= "I had to <travel>" (hx/words (hx/by-id dialog "why"))))
    (is (= "Must be 1 to 200 characters." (hx/words (hx/by-id dialog "why-error"))))
    (is (= "true" (:aria-invalid (attrs dialog "why"))))
    (is (true? (:autofocus (attrs dialog "why"))))
    (testing "and at the top of the question, which is not behind it"
      (is (= ["It was not cancelledCheck what you wrote and try again."] (alert-words dialog)))
      (is (= "alert" (:role (hx/attributes (first (alerts dialog)))))))
    (is (= [] (a11y/problems [:main (appointments/cancel-content model links)]))))
  (testing "a question that has not been answered has nothing wrong"
    (let [dialog (dialog-of (cancel-page booked-model))]
      (is (empty? (alerts dialog)))
      (is (nil? (hx/by-id dialog "why-error")))
      (is (not (contains? (attrs dialog "why") :autofocus))))))

(deftest a-cancellation-that-nobody-knows-the-fate-of-is-said-so-in-the-question
  (let [model (assoc booked-model :why {:values {:why "x"} :failure :unconfirmed})
        dialog (dialog-of (cancel-page model))]
    (is (= ["We could not confirm that it was cancelledThe problem is on our side, not yours. Look at your appointments, or try again: cancelling twice does no harm."]
           (alert-words dialog)))
    (is (= "x" (hx/words (hx/by-id dialog "why"))) "what was typed is kept")
    (is (= [] (a11y/problems [:main (appointments/cancel-content model links)])))))

;; ---------------------------------------------------------------------------
;; Text is text

(def ^:private hostile "<script>alert(1)</script><img src=x onerror=alert(2)>")

(deftest what-the-clinic-and-the-patient-wrote-is-never-markup
  (let [bad (item :id "bad" :description hostile :reason hostile)
        model (-> ana
                  (with-items [bad (item :id "bad2" :status "proposed" :description hostile :start nil :end nil
                                         :requestedStart "2026-10-20T00:00:00Z" :requestedEnd "2026-10-27T00:00:00Z")
                               (item :id "bad3" :status "cancelled" :description hostile :reason hostile)])
                  (assoc :me (assoc (:me ana) :generalPractitioner {:reference "Practitioner/pr-rivera" :name hostile})
                         :form {:values {:reason hostile :from hostile :to hostile} :errors {:reason hostile :from hostile :to hostile} :failure :refused}))]
    (doseq [[what html-text] {"the page" (html/render [:main (appointments/content model links)])
                              "the question" (html/render [:main (appointments/cancel-content (assoc model :target (first (get-in model [:appointments :items]))
                                                                                                     :why {:values {:why hostile} :errors {:why hostile} :failure :refused}) links)])
                              "what was told" (html/render [:main (appointments/content (assoc model :notice {:requested (item :description hostile)}) links)])}
            :let [tree (parsed/document html-text)]]
      (testing what
        (is (empty? (hx/by-tag tree :script)))
        (is (empty? (hx/by-tag tree :img)))
        (is (not (str/includes? html-text "<script>")))
        (is (not (str/includes? html-text "<img src=x")))
        (is (str/includes? (hx/words tree) hostile) "it is on the page as text")))))

(deftest anything-the-api-gives-that-is-odd-is-never-an-error
  (doseq [[what model] {"an appointment that is only an id" (with-items ana [{:id "x"}])
                        "a time that cannot be read" (with-items ana [(item :start "soon" :end "later") (item :id "r" :status "proposed" :start nil :requestedStart "next week")])
                        "no id" (with-items ana [(item :id nil)])
                        "no list at all" (assoc ana :appointments {})
                        "no me" (assoc ana :me nil)
                        "a total that is not a number" (assoc-in ana [:appointments :total] "many")}]
    (testing what
      (is (some? (page model)))
      (is (= [] (a11y-of model))))))

(deftest when-the-api-was-not-asked-who-the-patient-is-the-form-is-there-all-the-same
  (let [model (assoc ana :me nil :form {:values {:reason "Back pain" :from "2026-11-02" :to "2026-11-09"} :failure :unsaved})
        tree (page model)]
    (is (some? (hx/by-id tree "reason")) "a request that was not sent can be sent again")
    (is (= "Back pain" (hx/words (hx/by-id tree "reason"))))
    (is (str/includes? (hx/words (ask tree)) "Your request goes to your doctor, who will confirm a time."))
    (is (= [] (a11y-of model)))))

(deftest every-state-of-the-page-is-accessible
  (doseq [[what model] {"as it is" ana
                        "a new patient" noah
                        "with nothing" (with-items ana [])
                        "with a notice" (assoc ana :notice {:requested (item :status "proposed" :start nil :end nil)})
                        "with a form that failed" (assoc ana :form {:errors {:reason "x" :from "y" :to "z"} :failure :refused})}]
    (testing what
      (is (= [] (a11y-of model))))))

(deftest the-appointments-the-mock-ups-have-are-the-ones-the-page-is-made-of
  (testing "Ana: one that is confirmed, a request, and one that was cancelled; Noah: the request that waits"
    (let [sections (views/appointment-sections (get-in ana [:appointments :items]) (views/doctors-of (:me ana)) data/now-text)]
      (is (= ["appt-ana-1" "appt-ana-2"] (map :id (:coming sections))))
      (is (= ["appt-ana-0"] (map :id (:cancelled sections))))
      (is (= [] (:earlier sections))))
    (is (= ["appt-noah-1"] (map :id (:coming (views/appointment-sections (get-in noah [:appointments :items]) (views/doctors-of (:me noah)) data/now-text)))))))

(deftest the-form-is-first-shown-as-the-visits-say
  (let [tree (page ana)
        {:keys [reason from to]} (visits/fresh data/now-text)]
    (is (= [reason from to] [(hx/words (field tree "reason")) (:value (attrs tree "from")) (:value (attrs tree "to"))]))))
