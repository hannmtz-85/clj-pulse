(ns pulse.web.pages.appointments
  "The appointments of the portal (ADR-0028): what is coming, what has begun, what was cancelled, the form to ask for a visit and the
  question before an appointment is cancelled. It is a function of the data in the shapes of the api, and the mock-ups of the review
  give it the synthetic cohort, so that what is reviewed is what is served. Pure hiccup, free of Ring.

  A page of the portal is `content` here, the part that goes in `main` (`cancel-content` is the same page with the question over it).
  The `model` is

    `:now`           the instant it is, as text
    `:me`            what `GET /api/me` answered: for the name of the doctor beside an appointment, and to whom a request goes
    `:appointments`  what `GET /api/appointments` answered (`{:total :items}`: the newest `list-limit`), or nil when it could not be had
    `:form`          the form to ask for a visit: `:values` (the text of `:reason`, `:from` and `:to`, those of `pulse.web.visits/fresh` when
                     there are none), `:errors` (the words beside the fields that have something wrong) and `:failure`, why the request
                     was not sent: `:refused` (the api would not take it), `:unsaved` (it failed before the request was sent),
                     `:unconfirmed` (it failed with the request on its way, so that nobody knows if it was sent) or `:no-doctor`
                     (the api has no doctor to send it to)
    `:notice`        what to tell, once: `{:requested item}` and `{:cancelled item}`, with the appointment as the api answered, or
                     `{:blocked reason}`, one of `:already-cancelled`, `:begun`, `:not-cancellable` and `:changed`
    `:csrf-token`    the token of the person's session, which the forms carry

  and for the question, `:target`, the appointment of the api that would be cancelled, and `:why`, as `:form` is: `:values` (`:why`),
  `:errors` and `:failure` (`:refused` or `:unconfirmed`). `links` says where the buttons lead: `:appointments` (this page, which the form
  posts to), `:request-visit` (the button of the header), `:cancel-appointment` and `:cancel-request` (functions of the row of an appointment:
  the question, which is also where its answer is posted) and `:retry`. A button whose link is not there is left out (see
  `pulse.web.pages.parts`).

  What the api could not give is said, and never shown as an empty list; and what it cut is said too. A patient with no doctor is told so,
  and has no form."
  (:require [clojure.string :as str]
            [pulse.ui.component :as c]
            [pulse.web.pages :as pages]
            [pulse.web.pages.parts :as parts]
            [pulse.web.refusal :as refusal]
            [pulse.view.views :as views]
            [pulse.web.visits :as visits]))

(def list-limit
  "How many appointments are asked of the api: the most it gives."
  100)

;; ---------------------------------------------------------------------------
;; What the model says

(defn- doctor-name [me]
  (let [name (get-in me [:generalPractitioner :name])]
    (when-not (str/blank? name) name)))

(defn- has-doctor?
  "Whether the patient has a doctor for a request to go to, as `GET /api/me` says."
  [me]
  (some? (get-in me [:generalPractitioner :reference])))

(defn- no-doctor?
  "Whether the form is not offered: what the api says of the patient has no doctor to send a request to (when it was not asked, there
  is nothing to say), or the api has just said so."
  [{:keys [me form]}]
  (or (and (some? me) (not (has-doctor? me))) (= :no-doctor (:failure form))))

(defn- form-values [form now]
  (or (:values form) (visits/fresh now)))

;; ---------------------------------------------------------------------------
;; What the page tells

(def ^:private field-names
  "What the fields of the form to ask for a visit are called in a sentence, in the order of the form."
  [[:reason "the reason"] [:from "the first day"] [:to "the last day"]])

(defn- wrong-fields [errors]
  (for [[field text] field-names :when (contains? errors field)] text))

(defn- requested-notice
  "The message that a request has just been sent, in the words of what the api stored."
  [item me]
  (c/alert {:tone :normal :title "Request sent" :role "status"}
           (str/join " " (remove nil? [(when-not (str/blank? (:description item)) (str (:description item) "."))
                                       (str (or (doctor-name me) "Your clinic") " will confirm a time, and you will see it here.")]))))

(defn- cancelled-notice
  "The message that an appointment, or a request, has just been cancelled, in the words of what the api stored."
  [item me]
  (let [row (views/appointment-row item (views/doctors-of me))]
    (c/alert {:tone :normal :title (if (:start item) "Appointment cancelled" "Request cancelled") :role "status"}
             (str (when-not (str/blank? (:description item)) (str (:description item) (when (:when row) (str ", " (:when row))) ". "))
                  "Your care team will see it."))))

(defn- blocked-notice
  "The message that an appointment was not cancelled, and why."
  [reason]
  (case reason
    :already-cancelled (c/alert {:tone :info :title "That appointment is already cancelled" :role "status"})
    :begun (c/alert {:tone :high :title "The appointment was not cancelled" :role "alert"}
                    "It has already begun, so only the clinic can cancel it.")
    :changed (c/alert {:tone :high :title "The appointment was not cancelled" :role "alert"}
                      "It changed while you were looking at it. Look at it again and try once more.")
    (c/alert {:tone :high :title "The appointment was not cancelled" :role "alert"}
             "It cannot be cancelled here. Tell your clinic.")))

(defn- failure-notice
  "The message at the top of the page about the request that was not sent: what to check, or that nobody knows whether it was sent."
  [{:keys [errors failure]}]
  (case failure
    :unconfirmed (c/alert {:tone :high :title "We could not confirm that your request was sent" :role "alert"}
                          "The problem is on our side, not yours. Look at your appointments before you try again, so that it is not asked for twice.")
    :unsaved (c/alert {:tone :high :title "The request was not sent" :role "alert"}
                      "The problem is on our side, not yours. Try again in a moment.")
    :refused (c/alert {:tone :high :title "The request was not sent" :role "alert"}
                      (if-let [named (seq (wrong-fields errors))]
                        (str "Check " (refusal/join-and named) " and send it again.")
                        "Check what you entered and send it again."))
    nil))

(defn- top-notice
  "The message at the top of the page, if there is one: what has just been done, or why it was not."
  [{:keys [me form] told :notice}]
  (cond
    (:requested told) (requested-notice (:requested told) me)
    (:cancelled told) (cancelled-notice (:cancelled told) me)
    (:blocked told) (blocked-notice (:blocked told))
    :else (failure-notice form)))

;; ---------------------------------------------------------------------------
;; The lists

(defn- appointment-card [label rows links now]
  (c/card {:flush? true}
          (c/list-group {:aria-label label} (for [row rows] (parts/appointment-item row links now)))))

(defn- listed-section
  "A section of the appointments with its heading (`id` and `title`) and its list, or nothing when it has no rows."
  [id title label rows links now]
  (when (seq rows)
    [:section {:aria-labelledby id}
     [:div {:class "mb-3"} (parts/section-heading id title)]
     (appointment-card label rows links now)]))

(defn- coming-section
  "What is coming: the list, a message that there is nothing, or one that the list could not be loaded."
  [{:keys [now appointments]} sections links]
  [:section {:aria-labelledby "coming"}
   [:div {:class "mb-3"} (parts/section-heading "coming" "Coming up")]
   (cond
     (nil? appointments) (parts/not-loaded "Your appointments could not be loaded" links)
     (empty? (:coming sections)) (c/card (c/empty-state {:icon :calendar :title "No appointment coming up"}
                                                        "When your clinic confirms one, you will see it here."))
     :else (appointment-card "Appointments that are coming" (:coming sections) links now))])

(defn- cut-note
  "Says that the list is the latest of more, when the api has more than it gave."
  [appointments]
  (let [shown (count (:items appointments))
        total (:total appointments)]
    (when (and (number? total) (> total shown))
      [:p {:class "text-sm text-muted"} (str "Showing your latest " shown " of " total " appointments.")])))

;; ---------------------------------------------------------------------------
;; The form to ask for a visit

(defn- no-doctor-card []
  (c/alert {:tone :info :title "You cannot ask for a visit yet"}
           "We do not know who your doctor is, so a request would have nowhere to go. Tell your clinic."))

(defn- request-card
  "The form: what it is about and the days that would suit, which go to the patient's doctor."
  [{:keys [now me form csrf-token]} links]
  (let [{:keys [errors failure]} form
        values (form-values form now)
        bounds (visits/bounds now)
        focus (or (first (filter #(contains? errors %) (map first field-names)))
                  (when failure :reason))]
    (c/card {:title "Ask for a visit"
             :subtitle (str "Your request goes to " (or (doctor-name me) "your doctor") ", who will confirm a time.")}
            [:form {:class "flex max-w-xl flex-col gap-5" :action (:appointments links) :method "post" :novalidate true}
             (pages/csrf-field csrf-token)
             (c/textarea-field (cond-> {:id "reason" :name "reason" :label "What is it about?" :rows 3 :maxlength visits/reason-length
                                        :hint (str "A few words are enough, up to " visits/reason-length " characters.")
                                        :value (:reason values)}
                                 (contains? errors :reason) (assoc :error (:reason errors))
                                 (= :reason focus) (assoc :autofocus true)))
             [:fieldset {:class "grid gap-4 sm:grid-cols-2"}
              [:legend {:class "mb-1 text-sm font-medium sm:col-span-2"} "When would suit you?"]
              (c/text-field (cond-> {:id "from" :name "from" :label "From" :type "date" :value (:from values)
                                     :min (get-in bounds [:from :min]) :max (get-in bounds [:from :max])}
                              (contains? errors :from) (assoc :error (:from errors))
                              (= :from focus) (assoc :autofocus true)))
              (c/text-field (cond-> {:id "to" :name "to" :label "Until" :type "date" :value (:to values)
                                     :hint (str "At most " visits/window-days " days in all.")
                                     :min (get-in bounds [:to :min]) :max (get-in bounds [:to :max])}
                              (contains? errors :to) (assoc :error (:to errors))
                              (= :to focus) (assoc :autofocus true)))]
             [:div (c/button {:type "submit" :size :lg :icon :send} "Send the request")]])))

(defn- ask-section [model links]
  [:section {:id "ask" :aria-label "Ask for a visit"}
   (if (no-doctor? model) (no-doctor-card) (request-card model links))])

;; ---------------------------------------------------------------------------
;; The page

(defn- body
  "The appointments: the header, what has just happened, the lists and, when `:form?`, the form."
  [{:keys [now me appointments] :as model} links {:keys [form?]}]
  (let [sections (views/appointment-sections (:items appointments) (views/doctors-of me) now)]
    [:div {:class "space-y-8"}
     (c/page-header {:title "Appointments"
                     :description "Visits with your care team."
                     :actions (when (and form? (not (no-doctor? model)))
                                (when-let [href (:request-visit links)]
                                  (c/button {:size :lg :icon :calendar-plus :href href} "Ask for a visit")))})
     (top-notice model)
     (coming-section model sections links)
     (listed-section "earlier" "Earlier" "Earlier appointments" (:earlier sections) links now)
     (listed-section "cancelled" "Cancelled" "Cancelled appointments" (:cancelled sections) links now)
     (cut-note appointments)
     (when form? (ask-section model links))]))

(defn content
  "The appointments, with the form to ask for a visit. `model` and `links` are described at the top of this namespace."
  [model links]
  (body model links {:form? true}))

;; ---------------------------------------------------------------------------
;; The question before an appointment is cancelled

(defn- cancel-failure
  "The message inside the question about the cancellation that did not go through: it is inside because the question is over the
  page, and a message at the top of the page would be behind it."
  [failure]
  (case failure
    :unconfirmed (c/alert {:tone :high :title "We could not confirm that it was cancelled" :role "alert" :class "mb-4"}
                          "The problem is on our side, not yours. Look at your appointments, or try again: cancelling twice does no harm.")
    :refused (c/alert {:tone :high :title "It was not cancelled" :role "alert" :class "mb-4"}
                      "Check what you wrote and try again.")
    nil))

(defn- cancel-dialog
  "The question: what would be cancelled, what happens, why (if the patient wants to say) and the two ways out. It posts to the same
  address that it was asked at."
  [{:keys [me target why csrf-token]} links]
  (let [row (views/appointment-row target (views/doctors-of me))
        request? (= "proposed" (:status target))
        {:keys [values errors failure]} why
        address (when-let [link (if request? (:cancel-request links) (:cancel-appointment links))] (link row))]
    (c/dialog {:id "cancel-appointment"
               :title (if request? "Cancel this request?" "Cancel this appointment?")
               :open? true
               :actions (list (when-let [href (:appointments links)]
                                (c/button {:variant :ghost :size :lg :href href :autofocus true} "Keep it"))
                              (c/button {:variant :danger :size :lg :icon :calendar-x :type "submit" :form "cancel-form"}
                                        (if request? "Cancel the request" "Cancel the appointment")))}
              (cancel-failure failure)
              [:p {:class "text-base font-medium"} (:description row)]
              [:p {:class "text-base text-muted"} (str/join " · " (remove nil? [(or (:when row) (:asked row)) (:doctor row)]))]
              [:p {:class "mt-3 text-base"} "Your care team will see it. You can ask for another visit at any time."]
              [:form {:id "cancel-form" :method "post" :action address :novalidate true :class "mt-4"}
               (pages/csrf-field csrf-token)
               (c/textarea-field (cond-> {:id "why" :name "why" :label "Why? It helps your care team" :optional? true :rows 2
                                          :maxlength visits/reason-length
                                          :hint (str "Up to " visits/reason-length " characters.") :value (:why values)}
                                   (contains? errors :why) (assoc :error (:why errors) :autofocus true)))])))

(defn cancel-content
  "The appointments with the question before one is cancelled over them: the page behind it is `content` with no form. `model` has the
  `:target` and `:why` besides what `content` has; `links` is described at the top of this namespace."
  [model links]
  (list (body model links {:form? false})
        (cancel-dialog model links)))
