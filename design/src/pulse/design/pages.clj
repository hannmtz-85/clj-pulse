(ns pulse.design.pages
  "The pages of the mock-ups (ADR-0026): which there are, in the order they are linked, and the function that
  makes each one. The pages of the style tile have a `:render` function that takes the context
  `{:tokens :pages :themes}` and returns the hiccup of the page. A mock-up of an interface has a `:frame` function
  instead, with the same argument, that returns the document of the interface itself, and the page of the review
  around it is made from its `:summary`, its `:notes` and the widths in `:devices` (see `pulse.design.mockup`).
  `:listed? false` keeps a page out of the navigation and the overview, for the ones other pages link to."
  (:require [pulse.design.catalogue :as catalogue]
            [pulse.design.portal :as portal]
            [pulse.design.signin :as signin]
            [pulse.design.tile :as tile]
            [pulse.design.workspace :as workspace]))

(def ^:private phone-and-desk [390 1280])

(def ^:private tablet-and-desk [820 1280])

(def pages
  "Every page: its `:file`, `:title`, the `:group` it is listed under and a `:summary` for the overview."
  [{:file "index.html" :title "Overview" :group "Overview" :summary "" :render tile/index-page}
   {:file "palette.html" :title "Palette" :group "Style tile"
    :summary "Teal in light and dark, with the same components."
    :render tile/palette-page}
   {:file "type.html" :title "Type" :group "Style tile"
    :summary "Inter, the scale, tabular figures and a reading."
    :render tile/type-page}
   {:file "components.html" :title "Components" :group "Style tile"
    :summary "Buttons, tones, messages, forms, tables, tabs, lists, navigation and dialogs."
    :render catalogue/components-page}
   {:file "charts.html" :title "Charts" :group "Style tile"
    :summary "A reading over time: bands, thresholds, two series, and the small version."
    :render catalogue/charts-page}
   {:file "contrast.html" :title "Contrast" :group "Style tile"
    :summary "Every pair of colours, measured, for the palette in both schemes."
    :render tile/contrast-page}

   ;; -------------------------------------------------------------------------
   ;; Sign-in
   {:file "login.html" :title "Sign in" :group "Sign-in" :devices phone-and-desk :frame signin/sign-in
    :summary "The page of Keycloak where a patient or a clinician signs in: the same for the portal and the workspace."
    :notes ["Keycloak serves this page, in the theme of Pulse: a stylesheet compiled from the same tokens and components as the interfaces, and the words of the theme, which are written once."
            "The form is the real one: email and password. The realm allows neither to be kept signed in nor a new password by email (there is no mail server), so the line for them is not here; the template shows it when a realm does."
            "A wrong password, signing out, an error and an expired page are on the next mock-up."]}
   {:file "login-states.html" :title "Other pages of Keycloak" :group "Sign-in" :devices phone-and-desk :frame signin/other-states
    :summary "The error of a wrong password, the question before signing out, the message after it, an error of the request and the expired page."
    :notes ["These are the pages of the flows of the realm that the theme has to cover; each is a card like the sign-in."
            "The error is in words and with an icon, and it is announced to a screen reader."
            "The message after signing out has one way on, back to the application; the page only has it when Keycloak knows which application."]}

   ;; -------------------------------------------------------------------------
   ;; Patient portal
   {:file "portal-home.html" :title "Home" :group "Patient portal" :devices phone-and-desk :frame portal/home
    :summary "A patient whose last readings are very high: the notice, the next appointment with the way to cancel it, and the latest readings."
    :notes ["The notice shows when the latest blood pressure is Very high. It does not say that anyone has been told: the portal cannot know."
            "On a phone the navigation is a dock at the bottom; at a desk it is the bar at the top. The targets are 44 px high or more."
            "Cancel appointment is the button the owner asked for on 2026-10-16."]}
   {:file "portal-readings.html" :title "Readings" :group "Patient portal" :devices phone-and-desk :frame portal/readings
    :summary "The trend of blood pressure over 30 days, drawn for the width of the screen, with the figures and the last readings."
    :notes ["The phone gets the compact chart, with no words in the margin; the desk gets the wide one, with the thresholds of the rules."
            "Under each chart there is a table with the same numbers."]}
   {:file "portal-new-reading.html" :title "Record a reading" :group "Patient portal" :devices phone-and-desk :frame portal/new-reading
    :summary "The form to record a reading, as it looks after the api has refused one."
    :notes ["The limits are the api's: a diastolic between 20 and 200 mmHg, a time up to 30 days back."
            "The error is in a message at the top, and beside the field, with an icon and a sentence."]}
   {:file "portal-appointments.html" :title "Appointments" :group "Patient portal" :devices phone-and-desk :frame portal/appointments
    :summary "What is confirmed, what has been asked for and not answered, what was cancelled, and the form to ask for a visit."
    :notes ["A patient can cancel a confirmed appointment and withdraw a request."
            "A request goes to the patient's own doctor, who confirms a time."]}
   {:file "portal-cancel.html" :title "Cancel an appointment" :group "Patient portal" :devices phone-and-desk :frame portal/cancel
    :summary "The question before an appointment is cancelled: what is cancelled, who is told, and a way back."
    :notes ["The dialog is in the middle of the page here; in a browser it is in the middle of the window."
            "The reason is optional, and goes to the care team."]}
   {:file "portal-record.html" :title "My record" :group "Patient portal" :devices phone-and-desk :frame portal/record
    :summary "The medication, the conditions and the allergies the clinic has on file, in words."
    :notes ["A section with nothing in it says so, and says what to do."]}
   {:file "portal-profile.html" :title "Profile" :group "Patient portal" :devices phone-and-desk :frame portal/profile
    :summary "Who the patient is to the clinic, their doctor and the way to sign out."
    :notes ["Nothing here can be edited: the clinic keeps these details, and the page says so."]}
   {:file "portal-new-patient.html" :title "A new patient" :group "Patient portal" :devices phone-and-desk :frame portal/new-patient
    :summary "The home of a patient with no readings yet: what to do first, and the request that is waiting."
    :notes ["An empty section says what will be there and how to start."]}
   {:file "portal-errors.html" :title "When something goes wrong" :group "Patient portal" :devices phone-and-desk :frame portal/errors
    :summary "Part of a page that could not be loaded, a page that is not there, one the patient may not see, a failure on our side and a session that ended."
    :notes ["Each message says what happened and what to do, and none blames the patient."]}

   ;; -------------------------------------------------------------------------
   ;; Clinical workspace
   {:file "workspace-panel.html" :title "Panel" :group "Clinical workspace" :devices tablet-and-desk :frame workspace/panel
    :summary "The patients of Dr. Elena Rivera, the most urgent first, with the figures that need a look and a filter."
    :notes ["The sidebar is full at a desk and a rail of icons on a tablet; the count of alerts stays on the rail as a dot."
            "Each patient's status is the worst of their latest readings, in words and with an icon; the trend takes its tone."
            "The name of each patient opens their chart."]}
   {:file "workspace-patient.html" :title "Patient chart" :group "Clinical workspace" :devices tablet-and-desk :frame (workspace/patient "pt-ana")
    :summary "Ana García: the alert that is active, the latest readings, a month of blood pressure, her appointments, medication and allergies."
    :notes ["The alert comes first, with the way to acknowledge it."
            "The chart has the thresholds of the rule R1 and a table of the same numbers."
            "The panel opens the chart of each of the four patients of Dr. Rivera: the chart of Sofía, Liam and Omar are made by the same page."]}
   {:file "workspace-patient-sofia.html" :title "Patient chart, Sofía Rossi" :group "Clinical workspace" :listed? false
    :devices tablet-and-desk :frame (workspace/patient "pt-sofia")
    :summary "Sofía Rossi: a low oxygen saturation, an allergy, and a request for a visit that waits."}
   {:file "workspace-patient-liam.html" :title "Patient chart, Liam Chen" :group "Clinical workspace" :listed? false
    :devices tablet-and-desk :frame (workspace/patient "pt-liam")
    :summary "Liam Chen: an alert that was acknowledged, and glucose and blood pressure."}
   {:file "workspace-patient-omar.html" :title "Patient chart, Omar Haddad" :group "Clinical workspace" :listed? false
    :devices tablet-and-desk :frame (workspace/patient "pt-omar")
    :summary "Omar Haddad: everything normal, with no alert and no appointment."}
   {:file "workspace-alerts.html" :title "Alert inbox" :group "Clinical workspace" :devices tablet-and-desk :frame workspace/alerts
    :summary "What the rules raised for the panel: what needs a decision, with the readings behind it, and what was acknowledged."
    :notes ["Each alert says what the rule watches in the words of the rule, and shows the readings that made it fire."
            "The api does not say who acknowledged an alert, so neither does the page."]}
   {:file "workspace-appointments.html" :title "Requests and visits" :group "Clinical workspace" :devices tablet-and-desk :frame workspace/appointments
    :summary "The requests that wait for an answer, what is booked and what was cancelled, with the actions the lifecycle allows."
    :notes ["A request can be confirmed or cancelled; a booked appointment can be rescheduled or cancelled; a cancelled one has no action."
            "The buttons come from the lifecycle in libs/fhir, not from the page."]}
   {:file "workspace-confirm.html" :title "Confirm an appointment" :group "Clinical workspace" :devices tablet-and-desk :frame workspace/confirm
    :summary "The form to confirm a request: the day and the time that are given to it."
    :notes ["A visit lasts from 5 minutes to 4 hours, the limit of the api."]}
   {:file "workspace-jobs.html" :title "Jobs" :group "Clinical workspace" :devices tablet-and-desk :frame workspace/jobs
    :summary "For the administrator: what the scheduler does on its own, and how the last run of each went."
    :notes ["One job is running, and the last run of the reminders failed. The page shows the class of the error, never its message."
            "There is no button to start a job: the api only shows the status."]}
   {:file "workspace-states.html" :title "Empty states and errors" :group "Clinical workspace" :devices tablet-and-desk :frame workspace/states
    :summary "An empty panel and inbox, a page that is loading, a chart that cannot be opened and a section that is not available."
    :notes ["A section of a chart that could not be loaded is named, never shown empty: the api says which."]}])
