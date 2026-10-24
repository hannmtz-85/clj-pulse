(ns pulse.web.appointments-details-test
  "Details of the appointments of the portal that a mutation analysis found nobody was holding (ADR-0028): the tone and the role of each
  message, which of two links a question posts to, how a list of appointments is drawn, and where the buttons of the page lead through the
  whole handler."
  (:require [clojure.test :refer [deftest is testing]]
            [pulse.design.portal :as portal]
            [pulse.ui.test.hiccup :as hx]
            [pulse.ui.test.html :as parsed]
            [pulse.web.html :as html]
            [pulse.web.pages.appointments :as appointments]
            [pulse.web.test.cohort :refer [csrf-of events main-of page-of signed-in with-cohort]]
            [pulse.web.test.harness :as h]))

(def ^:private links
  {:appointments "/appointments"
   :request-visit "/appointments#ask"
   :cancel-appointment (fn [row] (str "/appointments/" (:id row) "/booked"))
   :cancel-request (fn [row] (str "/appointments/" (:id row) "/request"))
   :retry "/appointments"})

(def ^:private ana (assoc (portal/appointments-model "pt-ana") :csrf-token "tok-en_1"))

(defn- page [model]
  (parsed/document (html/render [:main (appointments/content model links)])))

(defn- cancel-page [model]
  (parsed/document (html/render [:main (appointments/cancel-content model links)])))

(defn- target-of [status]
  (first (filter #(= status (:status %)) (get-in ana [:appointments :items]))))

(defn- messages
  "`[tone role]` of every message of the page, a tone being the class of its surface."
  [tree]
  (for [el (hx/select tree #(and (= :div (hx/tag %)) (some (fn [tone] (hx/has-class? % tone)) ["bg-tone-high-soft" "bg-tone-normal-soft" "bg-tone-info-soft"])))
        :let [tone (first (filter #(hx/has-class? el %) ["bg-tone-high-soft" "bg-tone-normal-soft" "bg-tone-info-soft"]))]]
    [(subs tone 8 (- (count tone) 5)) (:role (hx/attributes el))]))

(deftest each-message-has-the-tone-and-the-role-that-fit-it
  (let [item {:id "x" :status "proposed" :description "Back pain" :start nil :end nil :requestedStart "2026-10-20T00:00:00Z" :requestedEnd "2026-10-27T00:00:00Z"
              :practitioner "Practitioner/pr-rivera" :reason nil :patient "Patient/pt-ana"}]
    (testing "what was done is good news that is said, not an alarm"
      (is (= [["normal" "status"]] (messages (page (assoc ana :notice {:requested item})))))
      (is (= [["normal" "status"]] (messages (page (assoc ana :notice {:cancelled (assoc item :status "cancelled")}))))))
    (testing "what is cancelled already is information that is said"
      (is (= [["info" "status"]] (messages (page (assoc ana :notice {:blocked :already-cancelled}))))))
    (testing "what could not be done is a problem that is announced"
      (doseq [reason [:begun :changed :not-cancellable]]
        (is (= [["high" "alert"]] (messages (page (assoc ana :notice {:blocked reason})))) (name reason))))
    (testing "a request that was not sent, or that nobody knows was, is a problem that is announced"
      (doseq [failure [:refused :unsaved :unconfirmed]]
        (is (= [["high" "alert"]] (messages (page (assoc ana :form {:failure failure :errors (when (= :refused failure) {:reason "x"})}))))
            (name failure))))
    (testing "a patient with no doctor is told as information"
      (is (= [["info" nil]] (messages (page (assoc ana :me (assoc (:me ana) :generalPractitioner nil)))))))
    (testing "what the question could not do is announced inside it"
      (doseq [failure [:refused :unconfirmed]]
        (is (= [["high" "alert"]] (messages (cancel-page (assoc ana :target (target-of "booked") :why {:failure failure}))))
            (name failure))))))

(deftest a-list-of-appointments-reaches-the-edges-of-its-card
  (let [tree (page ana)
        coming (first (hx/select tree #(= "coming" (:aria-labelledby (hx/attributes %)))))]
    (is (seq (hx/select coming #(and (= :div (hx/tag %)) (hx/has-class? % "mt-3") (seq (hx/by-tag % :ul))))) "the body of the card has no padding")
    (is (empty? (hx/select coming #(and (= :div (hx/tag %)) (hx/has-class? % "py-4") (seq (hx/by-tag % :ul))))) "it is not a padded one")))

(deftest the-question-posts-to-the-address-of-what-it-asks-about
  (let [action (fn [status] (:action (hx/attributes (first (hx/by-tag (first (hx/by-tag (cancel-page (assoc ana :target (target-of status))) :dialog)) :form)))))]
    (is (= "/appointments/appt-ana-1/booked" (action "booked")) "an appointment is cancelled at the address of an appointment")
    (is (= "/appointments/appt-ana-2/request" (action "proposed")) "and a request at the address of a request")))

(deftest the-buttons-of-the-page-lead-where-the-handler-says
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [page (page-of (h/visit! (signed-in web) :get "/appointments"))
            buttons (hx/select (main-of page) #(and (hx/has-class? % "btn") (= :a (hx/tag %))))
            href-of (fn [text] (:href (hx/attributes (first (filter #(= text (hx/words %)) buttons)))))]
        (is (= "/appointments#ask" (href-of "Ask for a visit")) "the form, which is in the page")
        (is (= "/appointments/appt-ana-1/cancel" (href-of "Cancel appointment")))
        (is (= "/appointments/appt-ana-2/cancel" (href-of "Cancel request")))))))

(defn- post! [web browser path form]
  (h/visit! browser :post path :form (merge {"__anti-forgery-token" (csrf-of web browser)} form)))

(defn- title-of [response]
  (hx/words (first (hx/by-tag (page-of response) :title))))

(deftest every-page-of-the-appointments-says-what-it-is-whatever-made-it
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [b (signed-in web)]
        (is (= "Appointments · Pulse" (title-of (h/visit! b :get "/appointments"))))
        (testing "the form again, because what was typed is wrong"
          (is (= "Appointments · Pulse" (title-of (post! web b "/appointments" {"reason" "" "from" "2026-10-14" "to" "2026-10-21"})))))
        (testing "the appointments again, because the appointment cannot be cancelled"
          (is (= "Appointments · Pulse" (title-of (post! web b "/appointments/appt-ana-0/cancel" {}))))
          (is (= "Appointments · Pulse" (title-of (h/visit! b :get "/appointments/appt-ana-0/cancel")))))
        (testing "the question"
          (is (= "Cancel appointment · Pulse" (title-of (h/visit! b :get "/appointments/appt-ana-1/cancel")))))))))

(deftest a-list-the-api-could-not-give-is-named-in-the-log
  (with-cohort "pt-ana" {:appointments (constantly (h/json-answer 503 {:title "Service Unavailable" :status 503}))} {}
    (fn [web]
      (let [response (h/visit! (signed-in web) :get "/appointments")]
        (is (= ["appointments"] (map #(get-in % [:data :part]) (events response :pulse.web/part-lost))))))))

(deftest the-form-is-a-section-with-a-name-for-a-screen-reader
  (let [tree (page ana)]
    (is (= "Ask for a visit" (:aria-label (hx/attributes (hx/by-id tree "ask")))))
    (is (= "section" (name (hx/tag (hx/by-id tree "ask")))))))
