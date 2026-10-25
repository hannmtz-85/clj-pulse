(ns pulse.web.appointments-test
  "The appointments of the portal over the whole handler (ADR-0028): the list, asking for a visit and cancelling an appointment: what a
  patient's request becomes when it is sent to the api and what comes back of it, every way it can be refused or fail, and who may do it.
  The stand-in for the api keeps the appointments of the cohort and judges a request and a cancellation as the api does (the lifecycle
  of `pulse.fhir.appointment`), so what the page says is what the api said and what the list shows afterwards is what it holds."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [jsonista.core :as json]
            [pulse.design.build :as build]
            [pulse.design.data :as data]
            [pulse.design.portal :as portal]
            [pulse.platform.token :as token]
            [pulse.ui.test.a11y :as a11y]
            [pulse.ui.test.hiccup :as hx]
            [pulse.web.session :as session]
            [pulse.web.test.cohort :refer [alert-words api-paths api-requests at! csrf-of events main-of notice-words page-of queries read-back
                                           signed-in with-cohort]]
            [pulse.web.test.harness :as h :refer [header]]
            [ring.middleware.session.store :as store])
  (:import (java.time LocalDate)))

(def ^:private context (build/context))

(defn- post!
  "The browser posts the form to `path`: with the token of its session unless `form` says another, and the `Origin` of the portal."
  [web browser path form & {:as options}]
  (apply h/visit! browser :post path :form (merge {"__anti-forgery-token" (csrf-of web browser)} form)
         (mapcat identity options)))

(defn- ask! [web browser form & options] (apply post! web browser "/appointments" form options))

(defn- cancel! [web browser id form & options] (apply post! web browser (str "/appointments/" id "/cancel") form options))

(def ^:private good {"reason" "Back pain" "from" "2026-10-14" "to" "2026-10-21"})

(defn- of-method [web method] (filter #(= method (:method %)) (api-requests web)))

(defn- sent
  "What the stand-in for the api was sent with `method`, as data, one for each request."
  [web method]
  (for [{:keys [body]} (of-method web method)]
    (json/read-value body json/keyword-keys-object-mapper)))

(defn- rows [page label]
  (hx/by-tag (first (hx/select page #(and (= :ul (hx/tag %)) (= label (:aria-label (hx/attributes %)))))) :li))

(defn- lines [row] (map hx/words (hx/by-tag row :p)))

(defn- coming [page] (map lines (rows page "Appointments that are coming")))

(defn- errors-of
  "The words beside the fields of a form, by the id of the field."
  [page]
  (into {} (for [p (hx/select page #(and (= :p (hx/tag %)) (some-> (:id (hx/attributes %)) (str/ends-with? "-error"))))]
             [(str/replace (:id (hx/attributes p)) #"-error$" "") (hx/words p)])))

(defn- value-of [page id] (:value (hx/attributes (hx/by-id page id))))

(defn- button-named [tree text]
  (first (filter #(= text (hx/words %)) (hx/select tree #(and (hx/has-class? % "btn") (#{:a :button} (hx/tag %)))))))

(defn- the-page [response] (page-of response))

(defn- alert-words-in
  "The words of every message that says that something could not be done, inside `element`: a question, which is not a page."
  [element]
  (map hx/words (hx/select element #(and (= :div (hx/tag %)) (hx/has-class? % "bg-tone-high-soft")))))

(defn- problem [status title & [errors]]
  (h/json-answer status (cond-> {:type "about:blank" :title title :status status :detail title} errors (assoc :errors errors))))

;; ---------------------------------------------------------------------------
;; The appointments

(deftest the-appointments-are-made-of-what-the-api-answers-for-the-patient
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [b (signed-in web)
            response (h/visit! b :get "/appointments")
            page (the-page response)
            keeper (:tokens (store/read-session (:sessions web) (h/cookie b session/cookie-name)))]
        (is (= 200 (:status response)))
        (is (= "text/html" (h/content-type response)))
        (is (= "no-store" (header response "Cache-Control")) "it is a patient's page")
        (is (= "Appointments · Pulse" (hx/words (first (hx/by-tag page :title)))))
        (is (= ["Appointments"] (map hx/words (hx/by-tag page :h1))))
        (testing "what is coming, what was cancelled, and the form"
          (is (= [["Hypertension follow-up" "Wed 14 Oct, 10:00–10:30 · Dr. Elena Rivera"]
                  ["Medication review" "Asked for a visit between Oct 20 and Oct 27 · Dr. Elena Rivera"]]
                 (coming page)))
          (is (= [["Blood pressure check" "Tue 29 Sep, 09:00–09:30 · Dr. Elena Rivera" "Reason: I had to travel that week."]]
                 (map lines (rows page "Cancelled appointments"))))
          (is (some? (hx/by-id page "reason"))))
        (testing "the bar says where she is"
          (is (= [["Home" nil] ["Readings" nil] ["Appointments" "page"] ["My record" nil] ["Profile" nil]]
                 (map (juxt hx/words (comp :aria-current hx/attributes))
                      (hx/by-tag (first (hx/select page #(and (= :nav (hx/tag %)) (= "Main" (:aria-label (hx/attributes %)))))) :a)))))
        (testing "who she is, and then the newest of her appointments, as many as the api gives: with her own token and the id of the request"
          (is (= ["/api/me" "/api/appointments"] (api-paths web)))
          (is (= [["/api/me" {}] ["/api/appointments" {"limit" "100" "sort" "-start"}]] (queries web)))
          (is (every? #(= (str "Bearer " (token/access-token keeper)) (get-in % [:headers "authorization"])) (api-requests web)))
          (is (every? #(= (header response "X-Request-Id") (get-in % [:headers "x-request-id"])) (api-requests web)))
          (is (every? #(= "GET" (:method %)) (api-requests web)) "nothing that changes anything"))
        (is (= [] (a11y/problems page {:page? true})))))))

(deftest no-address-of-the-portal-names-a-patient-so-there-is-no-other-record-to-ask-for
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [b (signed-in web)]
        (h/visit! b :get "/appointments" :query "patient=pt-liam&patient=Patient/pt-liam")
        (is (every? #(not (str/includes? (str (:path %) "?" (:query %)) "liam")) (api-requests web)))))))

(deftest what-is-served-is-what-was-reviewed
  (let [mock (read-back (portal/appointments context))]
    (with-cohort "pt-ana" {} {}
      (fn [web]
        (let [page (the-page (h/visit! (signed-in web) :get "/appointments"))]
          (is (= (hx/words (main-of mock)) (hx/words (main-of page))) "the same words, in the same order")
          (is (= (count (hx/by-tag (main-of mock) :li)) (count (hx/by-tag (main-of page) :li)))))))))

(deftest a-visitor-is-sent-to-sign-in-and-brought-back-to-the-appointments
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [response (h/call web :get "/appointments")]
        (is (= 303 (:status response)))
        (is (= "/sign-in?return-to=%2Fappointments" (header response "Location")))
        (is (empty? (api-requests web)))))))

(deftest the-appointments-of-a-new-patient-are-the-request-that-waits
  (with-cohort "pt-noah" {} {}
    (fn [web]
      (let [page (the-page (h/visit! (signed-in web :person :noah) :get "/appointments"))]
        (is (= [["New patient intake" "Asked for a visit between Oct 14 and Oct 20 · Dr. Chidi Okafor"]] (coming page)))
        (is (str/includes? (hx/words (hx/by-id page "ask")) "Your request goes to Dr. Chidi Okafor, who will confirm a time."))))))

(deftest what-has-begun-is-earlier-and-cannot-be-cancelled-by-the-patient
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (at! web "2026-10-14T09:00:00Z")
      (let [page (the-page (h/visit! (signed-in web) :get "/appointments"))]
        (is (= [["Medication review" "Asked for a visit between Oct 20 and Oct 27 · Dr. Elena Rivera"]] (coming page)))
        (is (= [["Hypertension follow-up" "Wed 14 Oct, 10:00–10:30 · Dr. Elena Rivera"]] (map lines (rows page "Earlier appointments"))))
        (is (nil? (button-named (first (hx/select page #(= "earlier" (:aria-labelledby (hx/attributes %))))) "Cancel appointment")))
        (is (= [] (a11y/problems page {:page? true})))))))

(deftest an-appointment-with-no-id-has-no-address-and-so-no-button-to-cancel-it
  (let [no-id (fn [item] (assoc item :id nil))
        listed (fn [& items] (h/json-answer {:total (count items) :offset 0 :limit 100 :items (vec items)}))
        booked {:id "x" :status "booked" :description "Hypertension follow-up" :start "2026-10-14T08:00:00Z" :end "2026-10-14T08:30:00Z"
                :requestedStart nil :requestedEnd nil :practitioner "Practitioner/pr-rivera" :reason nil :patient "Patient/pt-ana"}
        request {:id "y" :status "proposed" :description "Medication review" :start nil :end nil :requestedStart "2026-10-20T00:00:00Z"
                 :requestedEnd "2026-10-27T00:00:00Z" :practitioner "Practitioner/pr-rivera" :reason nil :patient "Patient/pt-ana"}]
    (with-cohort "pt-ana" {:appointments (constantly (listed (no-id booked) (no-id request)))} {}
      (fn [web]
        (let [response (h/visit! (signed-in web) :get "/appointments")
              page (the-page response)]
          (is (= 200 (:status response)))
          (is (= [["Hypertension follow-up" "Wed 14 Oct, 10:00–10:30 · Dr. Elena Rivera"]
                  ["Medication review" "Asked for a visit between Oct 20 and Oct 27 · Dr. Elena Rivera"]]
                 (coming page)))
          (is (nil? (button-named page "Cancel appointment")))
          (is (nil? (button-named page "Cancel request"))))))))

;; ---------------------------------------------------------------------------
;; What the api cannot give

(defn- fails-in-each-way
  "The ways a part of the api can fail that the page can go on after, each as what the stand-in answers instead (a function of the
  request alone)."
  []
  {"fails on its side" (constantly (h/json-answer 503 {:title "Service Unavailable" :status 503}))
   "answers what is not JSON" (constantly {:status 200 :body "<html>oops</html>"})
   "drops the connection" (fn [_request] (throw (ex-info "the api went away" {})))})

(deftest a-list-the-api-cannot-give-is-a-message-and-the-form-stays
  (doseq [[what appointments] (fails-in-each-way)]
    (testing what
      (with-cohort "pt-ana" {:appointments appointments} {}
        (fn [web]
          (let [response (h/visit! (signed-in web) :get "/appointments")
                page (the-page response)]
            (is (= 200 (:status response)))
            (is (= ["Your appointments could not be loadedThe rest of the page is up to date. The problem is on our side, not yours.Try again"]
                   (alert-words page)))
            (is (empty? (hx/select page #(and (= :ul (hx/tag %)) (= "Appointments that are coming" (:aria-label (hx/attributes %))))))
                "no list that could be taken for a list with nothing in it")
            (is (some? (hx/by-id page "reason")) "and a request can still be made")
            (is (= "/appointments" (:href (hx/attributes (button-named page "Try again")))))
            (is (= 1 (count (events response :pulse.web/part-lost))) "what was lost is in the log")
            (is (= [] (a11y/problems page {:page? true})))))))))

(deftest a-list-the-api-is-too-slow-to-give-is-lost-too
  (with-cohort "pt-ana" {:appointments (fn [_request] (assoc (h/json-answer {:total 0 :offset 0 :limit 100 :items []}) :delay-ms 800))} {:api-timeout-ms 250}
    (fn [web]
      (let [response (h/visit! (signed-in web) :get "/appointments")]
        (is (= 200 (:status response)))
        (is (= 1 (count (alert-words (the-page response)))))))))

(deftest who-the-patient-is-cannot-be-done-without
  (doseq [[what me status title]
          [["the api fails on its side" (h/json-answer 503 {:title "Service Unavailable" :status 503}) 502 "Something went wrong on our side"]
           ["it says the person may not" (h/json-answer 403 {:title "Forbidden" :status 403}) 403 "You cannot see this"]
           ["it refuses the token" (h/json-answer 401 {:title "Unauthorized" :status 401}) 502 "Something went wrong on our side"]]]
    (testing what
      (with-cohort "pt-ana" {:me me} {}
        (fn [web]
          (let [response (h/visit! (signed-in web) :get "/appointments")]
            (is (= status (:status response)))
            (is (= [title] (map hx/words (hx/by-tag (the-page response) :h1))))
            (is (= ["/api/me"] (api-paths web)) "and nothing else is asked")))))))

;; ---------------------------------------------------------------------------
;; What has been done is told once

(deftest what-was-done-is-told-once-on-the-page-it-goes-to-and-not-to-another-page
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [b (signed-in web)
            other (signed-in web)
            response (ask! web b good)]
        (is (= 303 (:status response)))
        (testing "the readings do not tell it, and do not take it"
          (is (empty? (notice-words (the-page (h/visit! b :get "/readings"))))))
        (testing "the page it goes to does, in the words of what the api stored"
          (is (= ["Request sentBack pain. Dr. Elena Rivera will confirm a time, and you will see it here."]
                 (notice-words (the-page (h/visit! b :get "/appointments"))))))
        (testing "and only once"
          (is (empty? (notice-words (the-page (h/visit! b :get "/appointments"))))))
        (testing "another session of the same person has its own"
          (is (empty? (notice-words (the-page (h/visit! other :get "/appointments"))))))))))

(deftest a-reading-that-was-saved-is-not-told-on-the-appointments
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [b (signed-in web)]
        (post! web b "/readings/new" {"measurement" "heart-rate" "value" "72"})
        (is (empty? (notice-words (the-page (h/visit! b :get "/appointments")))) "it is not theirs to tell")
        (is (= ["Reading savedHeart rate, 72 bpm, today, 14:00."] (notice-words (the-page (h/visit! b :get "/readings"))))
            "and it is still there for the readings")))))

;; ---------------------------------------------------------------------------
;; Asking for a visit

(deftest a-request-is-sent-to-the-api-as-the-api-takes-it
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [b (signed-in web)
            before (h/cookie b session/cookie-name)
            response (ask! web b good)
            keeper (:tokens (store/read-session (:sessions web) (h/cookie b session/cookie-name)))]
        (is (= 303 (:status response)))
        (is (= "/appointments" (header response "Location")))
        (is (= "" (:text response)))
        (is (= before (h/cookie b session/cookie-name)) "the session is the same: no new cookie")
        (is (empty? (h/set-cookies response)))
        (testing "one POST, with the window as the clinic's days and nothing about who it is for or with whom: the api decides that"
          (is (= ["/api/me" "/api/appointments"] (api-paths web)))
          (is (= [{:description "Back pain" :requestedStart "2026-10-13T22:00:00Z" :requestedEnd "2026-10-21T22:00:00Z"}]
                 (sent web "POST")))
          (is (= 1 (count (of-method web "POST"))))
          (is (= (str "Bearer " (token/access-token keeper)) (get-in (first (of-method web "POST")) [:headers "authorization"])))
          (is (= (header response "X-Request-Id") (get-in (first (of-method web "POST")) [:headers "x-request-id"])))
          (is (= "application/json" (get-in (first (of-method web "POST")) [:headers "content-type"]))))))))

(deftest a-request-that-was-sent-is-in-the-list-the-api-gives-next
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [b (signed-in web)]
        (ask! web b good)
        (let [page (the-page (h/visit! b :get "/appointments"))]
          (is (= [["Back pain" "Asked for a visit between Oct 14 and Oct 21 · Dr. Elena Rivera"]
                  ["Hypertension follow-up" "Wed 14 Oct, 10:00–10:30 · Dr. Elena Rivera"]
                  ["Medication review" "Asked for a visit between Oct 20 and Oct 27 · Dr. Elena Rivera"]]
                 (coming page)))
          (is (= "/appointments/appt-new-4/cancel" (:href (hx/attributes (button-named (first (rows page "Appointments that are coming")) "Cancel request"))))
              "and she can cancel it"))))))

(deftest the-request-goes-to-the-doctor-of-the-patient-whoever-the-form-says
  (with-cohort "pt-noah" {} {}
    (fn [web]
      (let [b (signed-in web :person :noah)]
        (ask! web b (assoc good "patient" "pt-ana" "practitioner" "pr-rivera" "status" "booked" "id" "appt-x"))
        (is (= [["description" "requestedEnd" "requestedStart"]] (map (comp sort (partial map name) keys) (sent web "POST")))
            "only the three things that the patient decides are sent, whatever else the form says")
        (testing "and it is with the doctor of the patient, which the api decides"
          (let [page (the-page (h/visit! b :get "/appointments"))]
            (is (= ["Back pain" "Asked for a visit between Oct 14 and Oct 21 · Dr. Chidi Okafor"] (first (coming page))))))))))

(deftest what-cannot-be-read-is-said-beside-its-field-and-the-api-is-not-asked
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [b (signed-in web)
            response (ask! web b {"reason" "  " "from" "2026-02-30" "to" ""})
            page (the-page response)]
        (is (= 422 (:status response)))
        (is (= {"reason" "Write a few words about it." "from" "Enter a date." "to" "Enter a date."} (errors-of page)))
        (is (= ["The request was not sentCheck the reason, the first day and the last day and send it again."] (alert-words page)))
        (is (empty? (of-method web "POST")) "nothing was sent")
        (is (= "2026-02-30" (value-of page "from")) "what was typed is kept")
        (is (= ["/api/me" "/api/appointments"] (api-paths web)) "the page is made again, of what the api says")
        (is (= [] (a11y/problems page {:page? true})))))))

(deftest what-the-api-refuses-is-said-in-its-own-words-beside-the-field
  (doseq [[what form errors] [["a window that is too long" {"from" "2026-11-02" "to" "2026-12-31"} {"to" "Must be at most 30 days after the start."}]
                              ["a last day before the first" {"from" "2026-10-20" "to" "2026-10-19"} {"to" "Must be after the start."}]
                              ["a first day too far ahead" {"from" "2027-06-01" "to" "2027-06-02"} {"from" "Must be within 180 days from now."}]
                              ["a window that is over" {"from" "2026-09-01" "to" "2026-09-10"} {"to" "Must be in the future."}]
                              ["a reason that is too long" {"reason" (apply str (repeat 201 "x"))} {"reason" "Must be 1 to 200 characters."}]]]
    (testing what
      (with-cohort "pt-ana" {} {}
        (fn [web]
          (let [b (signed-in web)
                form (merge good form)
                response (ask! web b form)
                page (the-page response)]
            (is (= 422 (:status response)))
            (is (= errors (errors-of page)))
            (is (= 1 (count (of-method web "POST"))) "the api was asked, and judged it")
            (testing "what was typed is kept"
              (is (= (get form "reason") (hx/words (hx/by-id page "reason"))))
              (is (= [(get form "from") (get form "to")] [(value-of page "from") (value-of page "to")])))
            (is (= [] (a11y/problems page {:page? true})))))))))

(deftest a-shape-the-api-refuses-is-said-too
  (with-cohort "pt-ana" {:ask (constantly (problem 400 "Bad Request" {:requestedStart ["must be a date and time with its offset"]}))} {}
    (fn [web]
      (let [response (ask! web (signed-in web) good)]
        (is (= 422 (:status response)))
        (is (= {"from" "Must be a date and time with its offset."} (errors-of (the-page response))))))))

(deftest a-patient-the-api-has-no-doctor-for-is-told-so-and-has-no-form
  (with-cohort "pt-ana" {:ask (constantly (problem 409 "Conflict"))} {}
    (fn [web]
      (let [response (ask! web (signed-in web) good)
            page (the-page response)]
        (is (= 409 (:status response)))
        (is (= ["You cannot ask for a visit yetWe do not know who your doctor is, so a request would have nowhere to go. Tell your clinic."]
               (map hx/words (hx/select (main-of page) #(and (= :div (hx/tag %)) (hx/has-class? % "bg-tone-info-soft"))))))
        (is (nil? (hx/by-id page "reason")))
        (is (nil? (button-named page "Ask for a visit"))))))
  (testing "and one whose record says so is not offered the form to begin with"
    (with-cohort "pt-ana" {:me (h/json-answer (assoc (data/me "pt-ana") :generalPractitioner nil))} {}
      (fn [web]
        (let [page (the-page (h/visit! (signed-in web) :get "/appointments"))]
          (is (nil? (hx/by-id page "reason")))
          (is (nil? (button-named page "Ask for a visit")))
          (is (= 1 (count (hx/select (main-of page) #(and (= :div (hx/tag %)) (hx/has-class? % "bg-tone-info-soft")))))))))))

(deftest a-request-the-api-fails-on-is-said-so-and-nobody-knows-whether-it-was-sent
  (doseq [[what ask] (fails-in-each-way)]
    (testing what
      (with-cohort "pt-ana" {:ask ask} {}
        (fn [web]
          (let [b (signed-in web)
                response (ask! web b good)
                page (the-page response)]
            (is (= 502 (:status response)))
            (is (= ["We could not confirm that your request was sentThe problem is on our side, not yours. Look at your appointments before you try again, so that it is not asked for twice."]
                   (alert-words page)))
            (testing "and what was typed is kept"
              (is (= "Back pain" (hx/words (hx/by-id page "reason"))))
              (is (= ["2026-10-14" "2026-10-21"] [(value-of page "from") (value-of page "to")])))
            (testing "the page is still the appointments, and the failure is in the log and not in the page"
              (is (= [["Hypertension follow-up" "Wed 14 Oct, 10:00–10:30 · Dr. Elena Rivera"]
                      ["Medication review" "Asked for a visit between Oct 20 and Oct 27 · Dr. Elena Rivera"]]
                     (coming page)))
              (is (= 1 (count (events response :pulse.web/api-failed))))
              (is (not (str/includes? (:text response) "oops"))))
            (testing "and nothing is told on the next page"
              (is (empty? (notice-words (the-page (h/visit! b :get "/appointments"))))))
            (is (= [] (a11y/problems page {:page? true})))))))))

(deftest a-request-the-api-is-too-slow-to-answer-is-a-gateway-timeout-and-nobody-knows-either
  (with-cohort "pt-ana" {:ask (constantly (assoc (h/json-answer 201 {}) :delay-ms 800))} {:api-timeout-ms 250}
    (fn [web]
      (let [started (System/nanoTime)
            response (ask! web (signed-in web) good)
            elapsed-ms (/ (- (System/nanoTime) started) 1e6)]
        (is (= 504 (:status response)))
        (is (< elapsed-ms 700) "the portal did not wait for the answer")
        (is (str/includes? (first (alert-words (the-page response))) "We could not confirm that your request was sent"))
        (is (= :timeout (get-in (first (events response :pulse.web/api-failed)) [:data :reason])))))))

(deftest when-the-api-cannot-say-who-the-person-is-nothing-was-sent
  (doseq [[what me] {"it fails" (h/json-answer 503 {:title "Service Unavailable" :status 503})
                     "it answers what is not JSON" {:status 200 :body "<html>oops</html>"}}]
    (testing what
      (with-cohort "pt-ana" {:me me} {}
        (fn [web]
          (let [response (ask! web (signed-in web) good)
                page (the-page response)]
            (is (= 502 (:status response)))
            (is (empty? (of-method web "POST")))
            (is (= ["The request was not sentThe problem is on our side, not yours. Try again in a moment."] (alert-words page)))
            (is (= "Back pain" (hx/words (hx/by-id page "reason"))) "and what was typed is kept")))))))

(deftest a-failure-that-is-not-a-failure-of-the-api-is-a-page-for-it
  (doseq [[what ask status title]
          [["the api refuses the token" (constantly (problem 401 "Unauthorized")) 502 "Something went wrong on our side"]
           ["it says the person may not" (constantly (problem 403 "Forbidden")) 403 "You cannot see this"]
           ["it does not know the patient" (constantly (problem 404 "Not Found")) 502 "Something went wrong on our side"]]]
    (testing what
      (with-cohort "pt-ana" {:ask ask} {}
        (fn [web]
          (let [response (ask! web (signed-in web) good)]
            (is (= status (:status response)))
            (is (= [title] (map hx/words (hx/by-tag (the-page response) :h1))))))))))

(deftest a-request-needs-the-token-of-the-session-and-the-origin-of-the-portal
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [b (signed-in web)]
        (testing "no token"
          (let [response (h/visit! b :post "/appointments" :form good)]
            (is (= 403 (:status response)))
            (is (= ["We could not do that"] (map hx/words (hx/by-tag (the-page response) :h1))))))
        (testing "another token"
          (is (= 403 (:status (h/visit! b :post "/appointments" :form (assoc good "__anti-forgery-token" "not-it"))))))
        (testing "another origin"
          (is (= 403 (:status (ask! web b good :headers {"origin" "https://evil.example"})))))
        (testing "no origin that is the portal's: null"
          (is (= 403 (:status (ask! web b good :headers {"origin" "null"})))))
        (is (empty? (of-method web "POST")) "nothing of it reached the api")))))

(deftest a-visitor-who-asks-for-a-visit-is-sent-to-sign-in
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [response (h/call web :post "/appointments" :form good :headers {"origin" h/base-url})]
        (is (= 303 (:status response)))
        (is (= "/sign-in" (header response "Location")) "and nowhere in particular to come back to")
        (is (empty? (api-requests web)))))))

(deftest any-text-is-a-request-or-the-form-again-and-never-an-error
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [b (signed-in web)
            day (gen/fmap #(str (.plusDays (LocalDate/parse "2026-10-13") %)) (gen/choose -3 200))
            result (tc/quick-check
                    60
                    (prop/for-all [reason (gen/one-of [gen/string gen/string-ascii (gen/return "a reason")])
                                   from (gen/one-of [day gen/string])
                                   length (gen/choose 0 45)
                                   to (gen/one-of [(gen/return nil) gen/string])]
                      (let [to (or to (str (.plusDays (LocalDate/parse (if (re-matches #"\d{4}-\d{2}-\d{2}" from) from "2026-10-13")) length)))
                            response (ask! web b {"reason" reason "from" from "to" to})]
                        (contains? #{303 422} (:status response)))))]
        (is (:pass? result) (pr-str (:shrunk result)))))))

;; ---------------------------------------------------------------------------
;; The question before an appointment is cancelled

(defn- question [_web b id & {:as options}]
  (apply h/visit! b :get (str "/appointments/" id "/cancel") (mapcat identity options)))

(deftest the-question-is-over-the-appointments-and-posts-to-its-own-address
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [b (signed-in web)
            response (question web b "appt-ana-1")
            page (the-page response)
            dialog (first (hx/by-tag page :dialog))]
        (is (= 200 (:status response)))
        (is (= "no-store" (header response "Cache-Control")))
        (is (= "Cancel appointment · Pulse" (hx/words (first (hx/by-tag page :title)))))
        (is (= "Cancel this appointment?" (hx/words (hx/by-id dialog "cancel-appointment-title"))))
        (is (str/includes? (hx/words dialog) "Hypertension follow-upWed 14 Oct, 10:00–10:30 · Dr. Elena Rivera"))
        (is (= "/appointments/appt-ana-1/cancel" (:action (hx/attributes (first (hx/by-tag dialog :form))))))
        (is (= [(csrf-of web b)] (map #(:value (hx/attributes %)) (filter #(= "__anti-forgery-token" (:name (hx/attributes %))) (hx/by-tag dialog :input)))))
        (is (= "/appointments" (:href (hx/attributes (button-named dialog "Keep it")))))
        (testing "who she is and the list are all it asks, with her own token: nothing is changed by asking"
          (is (= ["/api/me" "/api/appointments"] (api-paths web)))
          (is (every? #(= "GET" (:method %)) (api-requests web))))
        (is (= [] (a11y/problems page {:page? true})))))))

(deftest what-is-served-as-the-question-is-what-was-reviewed
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [b (signed-in web)]
        (is (= (hx/words (main-of (read-back (portal/cancel context)))) (hx/words (main-of (the-page (question web b "appt-ana-1"))))))
        (is (= (hx/words (main-of (read-back (portal/cancel-request context)))) (hx/words (main-of (the-page (question web b "appt-ana-2"))))))))))

(deftest the-question-for-a-request-is-the-question-of-a-request
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [dialog (first (hx/by-tag (the-page (question web (signed-in web) "appt-ana-2")) :dialog))]
        (is (= "Cancel this request?" (hx/words (hx/by-id dialog "cancel-appointment-title"))))
        (is (some? (button-named dialog "Cancel the request")))))))

(deftest an-appointment-that-is-not-hers-is-not-found
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [b (signed-in web)]
        (doseq [id ["appt-liam-1" "nothing" "appt-ana-1%2F..%2Fappt-liam-1" "APPT-ANA-1" (apply str (repeat 300 "x")) "%E2%82%AC" "a%20b" "appt-ana-1%00"]]
          (let [response (question web b id)]
            (is (= 404 (:status response)) id)
            (is (= ["We cannot find that page"] (map hx/words (hx/by-tag (the-page response) :h1))) id)))
        (is (empty? (of-method web "PATCH")) "and nothing is changed")))))

(deftest what-cannot-be-cancelled-is-the-appointments-with-the-reason
  (testing "one that is cancelled already"
    (with-cohort "pt-ana" {} {}
      (fn [web]
        (let [response (question web (signed-in web) "appt-ana-0")
              page (the-page response)]
          (is (= 409 (:status response)))
          (is (= ["That appointment is already cancelled"] (notice-words page)))
          (is (empty? (hx/by-tag page :dialog)) "there is no question")
          (is (some? (hx/by-id page "reason")) "it is the appointments page")
          (is (= [] (a11y/problems page {:page? true})))))))
  (testing "a booked one that has begun: only the clinic can"
    (with-cohort "pt-ana" {} {}
      (fn [web]
        (at! web "2026-10-14T09:00:00Z")
        (let [response (question web (signed-in web) "appt-ana-1")
              page (the-page response)]
          (is (= 409 (:status response)))
          (is (= ["The appointment was not cancelledIt has already begun, so only the clinic can cancel it."] (alert-words page)))
          (is (empty? (hx/by-tag page :dialog))))))))

(deftest when-the-api-cannot-give-the-list-the-question-is-a-page-for-the-failure
  (doseq [[what appointments status] [["it fails" (constantly (h/json-answer 503 {:title "Service Unavailable" :status 503})) 502]
                                      ["it is not JSON" (constantly {:status 200 :body "<html>oops</html>"}) 502]
                                      ["it is too slow" (constantly (assoc (h/json-answer {:total 0 :items []}) :delay-ms 800)) 504]]]
    (testing what
      (with-cohort "pt-ana" {:appointments appointments} {:api-timeout-ms 250}
        (fn [web]
          (let [response (question web (signed-in web) "appt-ana-1")]
            (is (= status (:status response)))
            (is (= ["Something went wrong on our side"] (map hx/words (hx/by-tag (the-page response) :h1))))))))))

(deftest a-visitor-who-asks-the-question-is-sent-to-sign-in-and-brought-back
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [response (h/call web :get "/appointments/appt-ana-1/cancel")]
        (is (= 303 (:status response)))
        (is (= "/sign-in?return-to=%2Fappointments%2Fappt-ana-1%2Fcancel" (header response "Location")))))))

;; ---------------------------------------------------------------------------
;; Cancelling

(deftest an-appointment-is-cancelled-with-the-reason-the-patient-gave
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [b (signed-in web)
            before (h/cookie b session/cookie-name)
            response (cancel! web b "appt-ana-1" {"why" "I had to travel."})
            keeper (:tokens (store/read-session (:sessions web) (h/cookie b session/cookie-name)))]
        (is (= 303 (:status response)))
        (is (= "/appointments" (header response "Location")))
        (is (= before (h/cookie b session/cookie-name)))
        (testing "one PATCH, to that appointment, with her own token, the reason and nothing else"
          (is (= ["/api/me" "/api/appointments" "/api/appointments/appt-ana-1"] (api-paths web)))
          (is (= [{:action "cancel" :reason "I had to travel."}] (sent web "PATCH")))
          (is (= (str "Bearer " (token/access-token keeper)) (get-in (first (of-method web "PATCH")) [:headers "authorization"])))
          (is (= (header response "X-Request-Id") (get-in (first (of-method web "PATCH")) [:headers "x-request-id"])))
          (is (= "application/json" (get-in (first (of-method web "PATCH")) [:headers "content-type"]))))
        (testing "the appointments say so, once, and the appointment is in the cancelled ones"
          (let [page (the-page (h/visit! b :get "/appointments"))]
            (is (= ["Appointment cancelledHypertension follow-up, Wed 14 Oct, 10:00–10:30. Your care team will see it."] (notice-words page)))
            (is (= [["Medication review" "Asked for a visit between Oct 20 and Oct 27 · Dr. Elena Rivera"]] (coming page)))
            (is (= [["Hypertension follow-up" "Wed 14 Oct, 10:00–10:30 · Dr. Elena Rivera" "Reason: I had to travel."]
                    ["Blood pressure check" "Tue 29 Sep, 09:00–09:30 · Dr. Elena Rivera" "Reason: I had to travel that week."]]
                   (map lines (rows page "Cancelled appointments"))))
            (is (empty? (notice-words (the-page (h/visit! b :get "/appointments")))) "and only once")))))))

(deftest a-reason-is-optional
  (doseq [form [{} {"why" ""} {"why" "  \r\n"}]]
    (with-cohort "pt-ana" {} {}
      (fn [web]
        (is (= 303 (:status (cancel! web (signed-in web) "appt-ana-1" form))) (pr-str form))
        (is (= [{:action "cancel"}] (sent web "PATCH")) "no reason is none")))))

(deftest a-request-is-cancelled-in-the-words-of-a-request
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [b (signed-in web)]
        (is (= 303 (:status (cancel! web b "appt-ana-2" {"why" ""}))))
        (is (= ["Request cancelledMedication review. Your care team will see it."] (notice-words (the-page (h/visit! b :get "/appointments"))))))))
  (testing "which leaves the appointment that is confirmed"
    (with-cohort "pt-ana" {} {}
      (fn [web]
        (let [b (signed-in web)]
          (cancel! web b "appt-ana-2" {})
          (is (= [["Hypertension follow-up" "Wed 14 Oct, 10:00–10:30 · Dr. Elena Rivera"]] (coming (the-page (h/visit! b :get "/appointments"))))))))))

(deftest the-appointment-is-read-before-it-is-cancelled-and-one-that-is-not-hers-is-never-sent
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [b (signed-in web)]
        (doseq [id ["appt-liam-1" "nothing" "appt-ana-1%2F..%2Fappt-liam-1"]]
          (let [response (cancel! web b id {})]
            (is (= 404 (:status response)) id)))
        (is (empty? (of-method web "PATCH")))))))

(deftest what-cannot-be-cancelled-is-not-sent-to-the-api
  (testing "one that is cancelled already"
    (with-cohort "pt-ana" {} {}
      (fn [web]
        (let [response (cancel! web (signed-in web) "appt-ana-0" {})]
          (is (= 409 (:status response)))
          (is (= ["That appointment is already cancelled"] (notice-words (the-page response))))
          (is (empty? (of-method web "PATCH")))))))
  (testing "one that began"
    (with-cohort "pt-ana" {} {}
      (fn [web]
        (at! web "2026-10-14T09:00:00Z")
        (let [response (cancel! web (signed-in web) "appt-ana-1" {})]
          (is (= 409 (:status response)))
          (is (= ["The appointment was not cancelledIt has already begun, so only the clinic can cancel it."] (alert-words (the-page response))))
          (is (empty? (of-method web "PATCH"))))))))

(deftest cancelling-twice-says-so-the-second-time
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [b (signed-in web)]
        (is (= 303 (:status (cancel! web b "appt-ana-1" {}))))
        (let [response (cancel! web b "appt-ana-1" {})]
          (is (= 409 (:status response)))
          (is (= ["That appointment is already cancelled"] (notice-words (the-page response))))
          (is (= 1 (count (of-method web "PATCH"))) "the second was not sent: the list says it is cancelled"))))))

(deftest the-api-may-know-better-than-the-list
  (testing "it says it is cancelled, and the list now says so too"
    (with-cohort "pt-ana" {:change (fn [_request]
                                     ;; somebody cancelled it between the list and the change
                                     (problem 409 "Conflict"))
                           :appointments (let [calls (atom 0)]
                                           (fn [_request]
                                             (let [n (swap! calls inc)
                                                   items [{:id "appt-ana-1" :status (if (= 1 n) "booked" "cancelled") :description "Hypertension follow-up"
                                                           :start "2026-10-14T08:00:00Z" :end "2026-10-14T08:30:00Z" :requestedStart nil :requestedEnd nil
                                                           :practitioner "Practitioner/pr-rivera" :reason nil :patient "Patient/pt-ana"}]]
                                               (h/json-answer {:total 1 :offset 0 :limit 100 :items items}))))} {}
      (fn [web]
        (let [response (cancel! web (signed-in web) "appt-ana-1" {})
              page (the-page response)]
          (is (= 409 (:status response)))
          (is (= ["That appointment is already cancelled"] (notice-words page)))
          (is (= ["/api/me" "/api/appointments" "/api/appointments/appt-ana-1" "/api/appointments"] (api-paths web)) "it looked again")
          (is (= [["Hypertension follow-up" "Wed 14 Oct, 10:00–10:30 · Dr. Elena Rivera"]] (map lines (rows page "Cancelled appointments")))
              "and the page is made of what it saw")))))
  (testing "it refuses and the list still says it can be cancelled: it changed meanwhile"
    (with-cohort "pt-ana" {:change (constantly (problem 409 "Conflict"))} {}
      (fn [web]
        (let [response (cancel! web (signed-in web) "appt-ana-1" {})]
          (is (= 409 (:status response)))
          (is (= ["The appointment was not cancelledIt changed while you were looking at it. Look at it again and try once more."]
                 (alert-words (the-page response))))))))
  (testing "it does not know it"
    (with-cohort "pt-ana" {:change (constantly (problem 404 "Not Found"))} {}
      (fn [web]
        (let [response (cancel! web (signed-in web) "appt-ana-1" {})]
          (is (= 404 (:status response)))
          (is (= ["We cannot find that page"] (map hx/words (hx/by-tag (the-page response) :h1)))))))))

(deftest a-reason-the-api-refuses-is-said-beside-the-field-in-the-question
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [response (cancel! web (signed-in web) "appt-ana-1" {"why" (apply str (repeat 201 "x"))})
            page (the-page response)
            dialog (first (hx/by-tag page :dialog))]
        (is (= 422 (:status response)))
        (is (= {"why" "Must be 1 to 200 characters."} (errors-of dialog)))
        (is (= (apply str (repeat 201 "x")) (hx/words (hx/by-id dialog "why"))) "what was typed is kept")
        (is (= ["It was not cancelledCheck what you wrote and try again."] (alert-words-in dialog)))
        (is (= [] (a11y/problems page {:page? true}))))))
  (testing "a shape it refuses"
    (with-cohort "pt-ana" {:change (constantly (problem 400 "Bad Request" {:reason ["must be a string"]}))} {}
      (fn [web]
        (let [response (cancel! web (signed-in web) "appt-ana-1" {"why" "x"})]
          (is (= 422 (:status response)))
          (is (= {"why" "Must be a string."} (errors-of (first (hx/by-tag (the-page response) :dialog))))))))))

(deftest a-cancellation-the-api-fails-on-is-said-so-and-nobody-knows-whether-it-was-done
  (doseq [[what change] (fails-in-each-way)]
    (testing what
      (with-cohort "pt-ana" {:change change} {}
        (fn [web]
          (let [response (cancel! web (signed-in web) "appt-ana-1" {"why" "I had to travel."})
                page (the-page response)
                dialog (first (hx/by-tag page :dialog))]
            (is (= 502 (:status response)))
            (is (= ["We could not confirm that it was cancelledThe problem is on our side, not yours. Look at your appointments, or try again: cancelling twice does no harm."]
                   (alert-words-in dialog)))
            (is (= "I had to travel." (hx/words (hx/by-id dialog "why"))) "what was typed is kept")
            (is (= 1 (count (events response :pulse.web/api-failed))))
            (is (= [] (a11y/problems page {:page? true})))))))))

(deftest a-cancellation-the-api-is-too-slow-to-answer-is-a-gateway-timeout
  (with-cohort "pt-ana" {:change (constantly (assoc (h/json-answer {}) :delay-ms 800))} {:api-timeout-ms 250}
    (fn [web]
      (let [response (cancel! web (signed-in web) "appt-ana-1" {})]
        (is (= 504 (:status response)))
        (is (= :timeout (get-in (first (events response :pulse.web/api-failed)) [:data :reason])))))))

(deftest when-the-list-cannot-be-read-nothing-is-sent
  (with-cohort "pt-ana" {:appointments (constantly (h/json-answer 503 {:title "Service Unavailable" :status 503}))} {}
    (fn [web]
      (let [response (cancel! web (signed-in web) "appt-ana-1" {})]
        (is (= 502 (:status response)))
        (is (empty? (of-method web "PATCH")))
        (is (= ["Something went wrong on our side"] (map hx/words (hx/by-tag (the-page response) :h1))))))))

(deftest a-failure-that-is-not-a-failure-of-the-api-is-a-page-for-it-when-cancelling
  (doseq [[what change status title]
          [["the api refuses the token" (constantly (problem 401 "Unauthorized")) 502 "Something went wrong on our side"]
           ["it says the person may not" (constantly (problem 403 "Forbidden")) 403 "You cannot see this"]]]
    (testing what
      (with-cohort "pt-ana" {:change change} {}
        (fn [web]
          (let [response (cancel! web (signed-in web) "appt-ana-1" {})]
            (is (= status (:status response)))
            (is (= [title] (map hx/words (hx/by-tag (the-page response) :h1))))))))))

(deftest cancelling-needs-the-token-of-the-session-and-the-origin-of-the-portal
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [b (signed-in web)]
        (is (= 403 (:status (h/visit! b :post "/appointments/appt-ana-1/cancel" :form {"why" "x"}))) "no token")
        (is (= 403 (:status (cancel! web b "appt-ana-1" {} :headers {"origin" "https://evil.example"}))) "another origin")
        (is (= 403 (:status (cancel! web b "appt-ana-1" {"__anti-forgery-token" "not-it"}))) "another token")
        (is (empty? (api-requests web)) "nothing of it reached the api, not even to read")))))

(deftest a-visitor-who-cancels-is-sent-to-sign-in
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [response (h/call web :post "/appointments/appt-ana-1/cancel" :form {"why" "x"} :headers {"origin" h/base-url})]
        (is (= 303 (:status response)))
        (is (= "/sign-in" (header response "Location")))
        (is (empty? (api-requests web)))))))

(deftest a-session-that-ended-is-told-so-and-nothing-is-cancelled
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [b (signed-in web)
            token (csrf-of web b)]
        (at! web "2026-10-13T13:00:00Z")
        (let [response (h/visit! b :post "/appointments/appt-ana-1/cancel" :form {"__anti-forgery-token" token})]
          (is (= 401 (:status response)))
          (is (= ["Your session has ended"] (map hx/words (hx/by-tag (the-page response) :h1))))
          (is (empty? (api-requests web))))))))

(deftest a-method-the-route-does-not-have-is-not-allowed
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [b (signed-in web)]
        (doseq [[method path allow] [[:put "/appointments" #{"GET" "POST"}] [:delete "/appointments" #{"GET" "POST"}]
                                     [:patch "/appointments/appt-ana-1/cancel" #{"GET" "POST"}] [:put "/appointments/appt-ana-1/cancel" #{"GET" "POST"}]]]
          (let [response (h/visit! b method path)]
            (is (= 405 (:status response)) (str method " " path))
            (is (= allow (set (str/split (header response "Allow") #", "))))))
        (is (empty? (api-requests web)))))))
