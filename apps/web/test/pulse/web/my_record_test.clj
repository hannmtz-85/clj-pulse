(ns pulse.web.my-record-test
  "The record of the portal over the whole handler (ADR-0028): a person signs in, and the page is made from what the api answers for them,
  asked for with their own token. The stand-in for the api answers from the synthetic cohort of the mock-ups, so that what is served can
  be held to what the owner reviewed; and each way the api can fail is tried: a list that is lost leaves the rest of the page, and a
  failure that is not a list's is a page for it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.design.build :as build]
            [pulse.design.data :as data]
            [pulse.design.portal :as portal]
            [pulse.platform.test.clock :as clock]
            [pulse.platform.token :as token]
            [pulse.ui.test.a11y :as a11y]
            [pulse.ui.test.hiccup :as hx]
            [pulse.web.session :as session]
            [pulse.web.test.cohort :refer [alert-words api-paths api-requests events main-of page-of read-back signed-in with-cohort]]
            [pulse.web.test.harness :as h :refer [header]]
            [ring.middleware.session.store :as store]))

(def ^:private context (build/context))

(defn- record
  "The person signs in and asks for the record. `{:browser :response :page}`."
  [web & {:keys [person headers] :or {person :ana}}]
  (let [b (signed-in web :person person)
        response (h/visit! b :get "/record" :headers headers)]
    {:browser b :response response :page (page-of response)}))

(defn- lists
  "The words of the cards of the page that have a title of their own, by that title: the lists of the record."
  [page]
  (into {} (for [card (hx/select (main-of page) #(and (= :section (hx/tag %)) (hx/has-class? % "shadow-card")))
                 :let [title (first (hx/by-tag card :h2))]
                 :when title]
             [(hx/words title) (hx/words card)])))

(defn- retry-links [page]
  (map (comp :href hx/attributes) (filter #(= "Try again" (hx/words %)) (hx/by-tag page :a))))

;; ---------------------------------------------------------------------------
;; The record of a patient

(deftest the-record-is-made-of-what-the-api-answers-for-the-patient
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [{:keys [browser response page]} (record web :headers {"x-request-id" "visit-7"})
            keeper (:tokens (store/read-session (:sessions web) (h/cookie browser session/cookie-name)))
            served (lists page)]
        (is (= 200 (:status response)))
        (is (= "text/html" (h/content-type response)))
        (is (= "no-store" (header response "Cache-Control")) "it is a patient's page")
        (is (= "My record · Pulse" (hx/words (first (hx/by-tag page :title)))))
        (is (= ["My record"] (map hx/words (hx/by-tag page :h1))))
        (testing "her medication and her conditions, and that she has no allergies"
          (is (= #{"Medication" "Conditions" "Allergies"} (set (keys served))))
          (is (str/includes? (get served "Medication") "Lisinopril10 mg once daily"))
          (is (str/includes? (get served "Medication") "Amlodipine5 mg once daily"))
          (is (str/includes? (get served "Conditions") "Essential (primary) hypertension"))
          (is (str/includes? (get served "Allergies") "No allergies are recorded. If you have one, tell your clinic.")))
        (testing "the bar says where she is"
          (is (= [["Home" nil] ["Readings" nil] ["Appointments" nil] ["My record" "page"] ["Profile" nil]]
                 (map (juxt hx/words (comp :aria-current hx/attributes))
                      (hx/by-tag (first (hx/select page #(and (= :nav (hx/tag %)) (= "Main" (:aria-label (hx/attributes %)))))) :a)))))
        (testing "who she is, and then her summary, with her own token and the id of the request, and nothing that changes anything"
          (is (= ["/api/me" "/api/patients/pt-ana/summary"] (api-paths web)))
          (is (every? #(= (str "Bearer " (token/access-token keeper)) (get-in % [:headers "authorization"])) (api-requests web)))
          (is (every? #(= "visit-7" (get-in % [:headers "x-request-id"])) (api-requests web)))
          (is (every? #(= "GET" (:method %)) (api-requests web))))
        (is (= [] (a11y/problems page {:page? true})))))))

(deftest no-address-of-the-portal-names-a-patient-so-there-is-no-other-record-to-ask-for
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [b (signed-in web)]
        (h/visit! b :get "/record" :query "patient=pt-liam&id=Patient/pt-liam")
        (is (every? #(not (str/includes? (str (:path %) "?" (:query %)) "liam")) (api-requests web)))
        (is (= ["/api/me" "/api/patients/pt-ana/summary"] (api-paths web)))))))

(deftest what-is-served-is-what-was-reviewed
  (let [mock (read-back (portal/record context))]
    (with-cohort "pt-ana" {} {}
      (fn [web]
        (let [{:keys [page]} (record web)]
          (is (= (hx/words (main-of mock)) (hx/words (main-of page))) "the same words, in the same order")
          (is (= (count (hx/by-tag (main-of mock) :li)) (count (hx/by-tag (main-of page) :li)))))))))

(deftest a-new-patient-has-three-cards-that-say-there-is-nothing
  (with-cohort "pt-noah" {} {}
    (fn [web]
      (let [{:keys [response page]} (record web :person :noah)
            served (lists page)]
        (is (= 200 (:status response)))
        (is (= ["/api/me" "/api/patients/pt-noah/summary"] (api-paths web)))
        (is (empty? (hx/by-tag (main-of page) :li)))
        (is (str/includes? (get served "Medication") "No medication is prescribed."))
        (is (str/includes? (get served "Conditions") "No conditions are recorded."))
        (is (empty? (alert-words page)))
        (is (= [] (a11y/problems page {:page? true})))))))

(deftest a-visitor-is-sent-to-sign-in-and-brought-back-to-the-record
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [response (h/call web :get "/record")]
        (is (= 303 (:status response)))
        (is (= "/sign-in?return-to=%2Frecord" (header response "Location")))
        (is (empty? (api-requests web)))))))

(deftest a-method-the-route-does-not-have-is-not-allowed
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [b (signed-in web)]
        (doseq [method [:post :put :delete :patch]]
          (let [response (h/visit! b method "/record")]
            (is (= 405 (:status response)) (str method))
            (is (= #{"GET"} (set (str/split (header response "Allow") #", "))) (str method))))
        (is (empty? (api-requests web)))))))

;; ---------------------------------------------------------------------------
;; What the api could not give

(deftest a-summary-the-api-cannot-give-is-one-message-and-no-list-that-looks-empty
  (doseq [[what summary] {"fails on its side" (h/json-answer 503 {:title "Service Unavailable" :status 503})
                          "answers what is not JSON" {:status 200 :body "<html>oops</html>"}
                          "drops the connection" (fn [_request _default] (throw (ex-info "the api went away" {})))}]
    (testing what
      (with-cohort "pt-ana" {:summary summary} {}
        (fn [web]
          (let [{:keys [response page]} (record web)]
            (is (= 200 (:status response)))
            (is (= ["My record"] (map hx/words (hx/by-tag page :h1))))
            (is (= {} (lists page)) "no card that could be taken for a record with nothing in it")
            (is (= 1 (count (alert-words page))))
            (is (str/includes? (first (alert-words page)) "Your record could not be loaded"))
            (is (str/includes? (first (alert-words page)) "The problem is on our side, not yours."))
            (is (not (str/includes? (first (alert-words page)) "The rest of the page")))
            (is (= ["/record"] (retry-links page)) "to try again is to ask for the page again")
            (is (= [] (a11y/problems page {:page? true})))
            (is (= [{:part "summary"}] (map #(select-keys (:data %) [:part]) (events response :pulse.web/part-lost))))
            (is (= :warn (:level (first (events response :pulse.web/part-lost)))))))))))

(deftest the-log-says-why-the-summary-was-lost-and-nothing-the-api-said
  (with-cohort "pt-ana" {:summary (h/json-answer 503 {:title "Database password is hunter2" :status 503})} {}
    (fn [web]
      (let [{:keys [response]} (record web)]
        (is (= {:part "summary" :status 503} (:data (first (events response :pulse.web/part-lost)))))
        (is (not (str/includes? (pr-str (:signals response)) "hunter2")))
        (is (not (str/includes? (:text response) "hunter2")))))))

(deftest a-list-the-summary-says-it-could-not-load-is-a-message-in-its-place
  (let [summary (data/summary "pt-ana")]
    (doseq [[name title] {"medications" "Medication" "conditions" "Conditions" "allergies" "Allergies"}]
      (testing name
        (with-cohort "pt-ana" {:summary (h/json-answer (assoc summary (keyword name) nil :unavailable [name]))} {}
          (fn [web]
            (let [{:keys [response page]} (record web)
                  served (lists page)]
              (is (= 200 (:status response)))
              (is (= 1 (count (alert-words page))))
              (is (str/includes? (first (alert-words page)) (str "Your " (str/lower-case title) " could not be loaded")))
              (is (not (contains? served title)) "the card of that list is not there, and no card says it is empty")
              (is (= 2 (count served)) "the other two are")
              (is (= ["/record"] (retry-links page)))
              (is (= [] (a11y/problems page {:page? true}))))))))))

(deftest every-list-lost-says-so-for-each
  (with-cohort "pt-ana" {:summary (h/json-answer (assoc (data/summary "pt-ana")
                                                        :medications nil :conditions nil :allergies nil
                                                        :unavailable ["medications" "conditions" "allergies"]))} {}
    (fn [web]
      (let [{:keys [page]} (record web)]
        (is (= 3 (count (alert-words page))))
        (is (not (str/includes? (hx/words (main-of page)) "The rest of the page")))))))

;; ---------------------------------------------------------------------------
;; A failure that is the whole page's

(def ^:private failures-of-me
  [[(h/json-answer 500 {:title "Internal Server Error" :status 500}) 502 "Something went wrong on our side"]
   [(h/json-answer 503 {:title "Service Unavailable" :status 503}) 502 "Something went wrong on our side"]
   [(h/json-answer 504 {:title "Gateway Timeout" :status 504}) 504 "Something went wrong on our side"]
   [{:status 200 :body "<html>not JSON</html>"} 502 "Something went wrong on our side"]
   [(fn [_request _default] (throw (ex-info "the api went away" {}))) 502 "Something went wrong on our side"]
   [(h/json-answer 401 {:title "Unauthorized" :status 401}) 502 "Something went wrong on our side"]
   [(h/json-answer 403 {:title "Forbidden" :status 403}) 403 "You cannot see this"]])

(deftest when-the-api-cannot-say-who-the-person-is-the-page-is-a-page-for-the-failure
  (doseq [[answer status title] failures-of-me]
    (with-cohort "pt-ana" {:me answer} {}
      (fn [web]
        (let [{:keys [response page]} (record web)]
          (testing (pr-str (if (map? answer) (select-keys answer [:status]) :dropped))
            (is (= status (:status response)))
            (is (= [title] (map hx/words (hx/by-tag page :h1))))
            (is (= ["/api/me"] (distinct (api-paths web))) "and nothing else is asked for")
            (is (empty? (lists page)))
            (is (= [] (a11y/problems page {:page? true})))
            (is (= 1 (count (events response :pulse.web/api-failed))))))))))

(deftest a-summary-that-is-refused-is-a-failure-of-the-whole-page
  (testing "the api says that she may not see her own summary"
    (with-cohort "pt-ana" {:summary (h/json-answer 403 {:title "Forbidden" :status 403})} {}
      (fn [web]
        (let [{:keys [response page]} (record web)]
          (is (= 403 (:status response)))
          (is (= ["You cannot see this"] (map hx/words (hx/by-tag page :h1))))
          (is (empty? (events response :pulse.web/part-lost)) "it is not a part that was lost")))))
  (testing "the api does not know the patient it said she was"
    (with-cohort "pt-ana" {:summary (h/json-answer 404 {:title "No such patient." :status 404})} {}
      (fn [web]
        (let [{:keys [response]} (record web)]
          (is (= 502 (:status response)))
          (is (= :error (:level (first (events response :pulse.web/api-failed))))))))))

(deftest an-api-that-is-too-slow-for-the-summary-is-a-message-and-too-slow-for-who-she-is-a-504
  (with-cohort "pt-ana" {:summary (fn [_request default] (assoc default :delay-ms 1500))} {:api-timeout-ms 300}
    (fn [web]
      (let [{:keys [response page]} (record web)]
        (is (= 200 (:status response)))
        (is (str/includes? (first (alert-words page)) "Your record could not be loaded"))
        (is (= [:timeout] (map (comp :reason :data) (events response :pulse.web/part-lost)))))))
  (with-cohort "pt-ana" {:me (fn [_request default] (assoc default :delay-ms 1500))} {:api-timeout-ms 300}
    (fn [web]
      (is (= 504 (:status (:response (record web))))))))

(deftest a-patient-whose-account-has-no-record-is-a-failure-on-our-side
  (doseq [fhir-user [nil "" "Practitioner/pr-rivera" "pt-ana" "Patient/"]]
    (with-cohort "pt-ana" {:me (h/json-answer (assoc (data/me "pt-ana") :fhirUser fhir-user))} {}
      (fn [web]
        (let [{:keys [response page]} (record web)]
          (testing (pr-str fhir-user)
            (is (= 500 (:status response)))
            (is (= ["Something went wrong on our side"] (map hx/words (hx/by-tag page :h1))))
            (is (= ["/api/me"] (api-paths web)) "no summary of nobody")))))))

;; ---------------------------------------------------------------------------
;; The session

(deftest a-session-that-ended-is-told-so-and-the-api-is-not-asked
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [b (signed-in web)]
        (clock/advance! (:clock web) (* 31 60 1000))
        (let [response (h/visit! b :get "/record")]
          (is (= 401 (:status response)))
          (is (= ["Your session has ended"] (map hx/words (hx/by-tag (page-of response) :h1))))
          (is (empty? (api-requests web))))))))

;; ---------------------------------------------------------------------------
;; What the api says is text

(deftest what-the-api-says-is-text-whatever-it-holds
  (let [hostile "<script>alert(1)</script>"
        summary (data/summary "pt-ana")]
    (with-cohort "pt-ana" {:summary (h/json-answer (-> summary
                                                       (update :medications (fn [ms] (mapv #(assoc % :display hostile :dosage hostile) ms)))
                                                       (update :conditions (fn [cs] (mapv #(assoc % :display hostile) cs)))
                                                       (assoc :allergies [{:id "a" :display hostile :reaction hostile}])))} {}
      (fn [web]
        (let [{:keys [response page]} (record web)]
          (is (= 200 (:status response)))
          (is (not (str/includes? (:text response) "<script>alert")))
          (is (str/includes? (:text response) "&lt;script&gt;alert(1)&lt;/script&gt;"))
          (is (= 1 (count (re-seq #"<script" (:text response)))) "the only script is htmx, a file of the portal")
          (is (= [] (a11y/problems page {:page? true}))))))))

(deftest the-record-of-another-patient-is-never-in-the-page-of-this-one
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [{:keys [response]} (record web)]
        (doseq [other ["Liam" "metformin" "Sofia" "tiotropium" "Penicillin" "Maya" "furosemide" "Omar" "Noah"]]
          (is (not (str/includes? (str/lower-case (:text response)) (str/lower-case other))) other))))))
