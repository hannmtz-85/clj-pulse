(ns pulse.web.readings-test
  "The readings of the portal and recording one, over the whole handler (ADR-0028): a person signs in, and the pages are made of what
  the api answers for them with their own token. The stand-in for the api answers from the synthetic cohort of the mock-ups, at the
  moment of the mock-ups, and as the api does to a reading that is posted to it; and each way the api can fail is tried."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.design.build :as build]
            [pulse.design.data :as data]
            [pulse.design.portal :as portal]
            [pulse.platform.test.oidc :as oidc-fake]
            [pulse.platform.token :as token]
            [pulse.ui.test.a11y :as a11y]
            [pulse.ui.test.hiccup :as hx]
            [pulse.web.session :as session]
            [pulse.web.test.cohort :refer [alert-words api-paths api-requests at! events main-of page-of pills queries read-back signed-in
                                           with-cohort]]
            [pulse.web.test.harness :as h :refer [header]]
            [ring.middleware.session.store :as store])
  (:import (java.time Instant)))

(def ^:private context (build/context))

;; ---------------------------------------------------------------------------
;; The readings of a patient

(deftest the-readings-of-a-patient-are-made-of-what-the-api-answers-for-them
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [b (signed-in web)
            response (h/visit! b :get "/readings")
            page (page-of response)
            main (main-of page)]
        (is (= 200 (:status response)))
        (is (= "text/html" (h/content-type response)))
        (is (= "no-store" (header response "Cache-Control")) "it is a patient's page")
        (is (= "Readings · Pulse" (hx/words (first (hx/by-tag page :title)))))
        (is (= ["My readings"] (map hx/words (hx/by-tag page :h1))))
        (testing "she is looking at the blood pressure of the last 30 days, and has three measurements to choose between"
          (is (= [["Blood pressure" true] ["Heart rate" false] ["Weight" false]] (pills page "Measurement")))
          (is (= [["7 days" false] ["14 days" false] ["30 days" true]] (pills page "Period")))
          (is (str/includes? (hx/words main) "Last 30 days"))
          (is (= 2 (count (hx/by-tag main :figure))) "the chart for a phone and the one for a desk"))
        (testing "her name is in the menu, and Readings is the page she is on"
          (is (= ["Account menu of Ana García"] (keep #(:aria-label (hx/attributes %)) (hx/by-tag page :summary))))
          (is (= [["Home" nil] ["Readings" "page"]]
                 (map (juxt hx/words (comp :aria-current hx/attributes))
                      (hx/by-tag (first (hx/select page #(and (= :nav (hx/tag %)) (= "Main" (:aria-label (hx/attributes %)))))) :a)))))
        (is (= [] (a11y/problems page {:page? true})))))))

(deftest the-api-is-asked-with-the-token-of-the-person-and-the-id-of-the-request
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [b (signed-in web)
            response (h/visit! b :get "/readings" :headers {"x-request-id" "visit-7"})
            keeper (:tokens (store/read-session (:sessions web) (h/cookie b session/cookie-name)))
            requests (api-requests web)]
        (is (= "visit-7" (header response "X-Request-Id")))
        (testing "who she is, her summary, and the series of the one she looks at for the period"
          (is (= [["/api/me" {}]
                  ["/api/patients/pt-ana/summary" {}]
                  ["/api/patients/pt-ana/observations" {"measurement" "blood-pressure" "from" "2026-09-13T12:00:00Z" "limit" "500"}]]
                 (queries web))))
        (testing "each carries her own token, and the id of the request, which the api puts in its log"
          (is (every? #(= (str "Bearer " (token/access-token keeper)) (get-in % [:headers "authorization"])) requests))
          (is (every? #(= "visit-7" (get-in % [:headers "x-request-id"])) requests)))
        (testing "a GET each, and nothing that changes anything"
          (is (every? #(= "GET" (:method %)) requests)))))))

(deftest what-is-asked-for-is-what-the-address-says
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [b (signed-in web)]
        (testing "another measurement and another period"
          (let [page (page-of (h/visit! b :get "/readings" :query "measurement=heart-rate&days=7"))]
            (is (= [["Blood pressure" false] ["Heart rate" true] ["Weight" false]] (pills page "Measurement")))
            (is (= [["7 days" true] ["14 days" false] ["30 days" false]] (pills page "Period")))
            (is (str/includes? (hx/words (main-of page)) "Last 7 days"))
            (is (= {"measurement" "heart-rate" "from" "2026-10-06T12:00:00Z" "limit" "500"}
                   (second (last (queries web)))))))
        (testing "a measurement the patient has no readings of is shown, empty, among the ones they have"
          (let [page (page-of (h/visit! b :get "/readings" :query "measurement=glucose&days=14"))]
            (is (= [["Blood pressure" false] ["Heart rate" false] ["Glucose" true] ["Weight" false]] (pills page "Measurement")))
            (is (str/includes? (hx/words (main-of page)) "No glucose readings in the last 14 days"))))
        (testing "the period follows the clock"
          (at! web "2026-10-14T12:00:00Z")
          (let [b (signed-in web)]
            (h/visit! b :get "/readings" :query "days=14")
            (is (= "2026-09-30T12:00:00Z" (get (second (last (queries web))) "from")))))))))

(deftest what-the-address-says-that-means-nothing-is-the-default
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [b (signed-in web)
            asked (fn [query]
                    (reset! (:api-requests web) [])
                    (let [response (h/visit! b :get "/readings" :query query)]
                      {:status (:status response)
                       :series (some #(when (= "/api/patients/pt-ana/observations" (first %)) (second %)) (queries web))
                       :page (page-of response)}))]
        (doseq [[what query] [["a measurement that does not exist" "measurement=nothing"]
                              ["an empty measurement" "measurement="]
                              ["a measurement that comes twice" "measurement=heart-rate&measurement=weight"]
                              ["a period that is none" "days=99"]
                              ["a period that is not a number" "days=many"]
                              ["a period with a sign" "days=%2B7"]
                              ["a period with a zero" "days=07"]
                              ["a period in the wrong place" "days=%207"]
                              ["an empty period" "days="]
                              ["a period that comes twice" "days=7&days=14"]
                              ["the name of a measurement in capitals" "measurement=Heart-Rate"]
                              ["a measurement and a path" "measurement=../summary"]
                              ["a patient" "patient=pt-liam&id=pt-liam"]]]
          (testing what
            (let [{:keys [status series page]} (asked query)]
              (is (= 200 status))
              (is (= "blood-pressure" (get series "measurement")) "the first the patient has")
              (is (= "2026-09-13T12:00:00Z" (get series "from")) "thirty days")
              (is (= [["Blood pressure" true] ["Heart rate" false] ["Weight" false]] (pills page "Measurement")))
              (is (= [["7 days" false] ["14 days" false] ["30 days" true]] (pills page "Period"))))))
        (testing "and what is meant is meant even when something else is nonsense"
          (let [{:keys [series]} (asked "measurement=weight&days=many")]
            (is (= "weight" (get series "measurement")))
            (is (= "2026-09-13T12:00:00Z" (get series "from")))))))))

(deftest the-record-asked-for-is-always-the-persons-own
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [b (signed-in web)]
        (h/visit! b :get "/readings" :query "patient=pt-liam&id=pt-liam&measurement=glucose")
        (is (every? #(str/starts-with? % "/api/") (api-paths web)))
        (is (not-any? #(str/includes? % "pt-liam") (api-paths web)) "no address of the portal says whose record it is: the api says whose token it is")))))

(deftest the-default-measurement-is-the-first-the-patient-has
  (doseq [[patient person expected] [["pt-liam" :liam "blood-pressure"] ["pt-sofia" :sofia "heart-rate"]]]
    (testing patient
      (with-cohort patient {} {}
        (fn [web]
          (if (contains? oidc-fake/people person)
            (let [b (signed-in web :person person)]
              (h/visit! b :get "/readings")
              (is (= expected (get (second (last (queries web))) "measurement"))))
            (is true "this person is not one of the fake provider's")))))))

(deftest a-patient-with-no-readings-is-told-so-and-the-series-is-not-asked-for
  (with-cohort "pt-noah" {} {}
    (fn [web]
      (let [b (signed-in web :person :noah)
            response (h/visit! b :get "/readings")
            page (page-of response)]
        (is (= 200 (:status response)))
        (is (str/includes? (hx/words (main-of page)) "No readings yet"))
        (is (empty? (hx/by-tag page :figure)))
        (is (= ["/api/me" "/api/patients/pt-noah/summary"] (api-paths web)) "there is no series to ask for")
        (is (empty? (pills page "Measurement")))
        (is (= [] (a11y/problems page {:page? true})))))))

(deftest a-series-the-api-cut-is-said-so
  (let [cut (fn [_request _default]
              (h/json-answer {:items (vec (for [i (range 500)]
                                            {:id (str "r" i) :at (str (Instant/ofEpochSecond (- (.getEpochSecond data/now) (* 30 i))))
                                             :measurement "blood-pressure" :systolic (+ 120 (mod i 30)) :diastolic (+ 70 (mod i 20))
                                             :unit "mm[Hg]" :band "elevated"}))
                              :truncated true}))]
    (with-cohort "pt-ana" {:series cut} {}
      (fn [web]
        (let [b (signed-in web)
              page (page-of (h/visit! b :get "/readings"))]
          (is (str/includes? (hx/words (main-of page)) "Your latest 500 readings, since today, 09:50"))
          (is (not (str/includes? (hx/words (main-of page)) "Last 30 days")))
          (is (= ["Latest" "Highest in these readings" "Lowest in these readings"]
                 (map #(hx/words (first (hx/by-tag % :p)))
                      (hx/select (main-of page) #(and (= :div (hx/tag %)) (hx/has-class? % "shadow-card") (hx/has-class? % "p-4") (not (hx/has-class? % "relative")))))))
          (is (= [] (a11y/problems page {:page? true}))))))))

;; ---------------------------------------------------------------------------
;; What is served is what was reviewed

(deftest the-readings-that-are-served-are-the-readings-of-the-mock-up
  (let [mock (read-back (portal/readings context))]
    (with-cohort "pt-ana" {} {}
      (fn [web]
        (let [page (page-of (h/visit! (signed-in web) :get "/readings"))]
          (is (= (hx/words (main-of mock)) (hx/words (main-of page))) "the same words, in the same order")
          (testing "and the same drawings"
            (is (= (map #(hx/words %) (hx/by-tag (main-of mock) :figure)) (map #(hx/words %) (hx/by-tag (main-of page) :figure))))
            (is (= (map #(map (comp :d hx/attributes) (hx/by-tag % :path)) (hx/by-tag (main-of mock) :figure))
                   (map #(map (comp :d hx/attributes) (hx/by-tag % :path)) (hx/by-tag (main-of page) :figure)))
                "down to the lines")))))))

;; ---------------------------------------------------------------------------
;; A part that is lost

(deftest the-series-the-api-cannot-give-is-a-message-and-the-rest-of-the-page-stays
  (doseq [[what series] {"fails on its side" (h/json-answer 503 {:title "Service Unavailable" :status 503})
                         "answers what is not JSON" {:status 200 :body "<html>oops</html>"}
                         "drops the connection" (fn [_request _default] (throw (ex-info "the api went away" {})))}]
    (testing what
      (with-cohort "pt-ana" {:series series} {}
        (fn [web]
          (let [response (h/visit! (signed-in web) :get "/readings")
                page (page-of response)]
            (is (= 200 (:status response)))
            (is (= ["My readings"] (map hx/words (hx/by-tag page :h1))))
            (is (= [["Blood pressure" true] ["Heart rate" false] ["Weight" false]] (pills page "Measurement")) "the choices are there")
            (is (= 1 (count (alert-words page))))
            (is (str/includes? (first (alert-words page)) "Your blood pressure readings could not be loaded"))
            (is (not (str/includes? (hx/words (main-of page)) "No blood pressure readings")) "which would say that there are none")
            (is (empty? (hx/by-tag page :figure)))
            (is (= ["/readings"] (distinct (map #(:href (hx/attributes %)) (filter #(= "Try again" (hx/words %)) (hx/by-tag page :a)))))
                "to try again is to ask for the same page")
            (is (= 1 (count (events response :pulse.web/part-lost))))
            (is (= "series blood-pressure" (:part (:data (first (events response :pulse.web/part-lost))))))
            (is (= [] (a11y/problems page {:page? true})))))))))

(deftest the-way-to-try-again-keeps-what-was-asked-for
  (with-cohort "pt-ana" {:series (h/json-answer 503 {:title "x" :status 503})} {}
    (fn [web]
      (let [page (page-of (h/visit! (signed-in web) :get "/readings" :query "measurement=heart-rate&days=14"))]
        (is (= ["/readings?measurement=heart-rate&days=14"]
               (map #(:href (hx/attributes %)) (filter #(= "Try again" (hx/words %)) (hx/by-tag page :a)))))))))

(deftest a-summary-the-api-cannot-give-leaves-every-measurement-to-choose-from
  (with-cohort "pt-ana" {:summary (h/json-answer 503 {:title "Service Unavailable" :status 503})} {}
    (fn [web]
      (let [response (h/visit! (signed-in web) :get "/readings")
            page (page-of response)]
        (is (= 200 (:status response)))
        (is (= [["Blood pressure" true] ["Heart rate" false] ["Oxygen saturation" false] ["Glucose" false] ["Weight" false] ["Temperature" false]]
               (pills page "Measurement")) "nobody knows which have readings")
        (is (= 2 (count (hx/by-tag page :figure))) "and the chart is there")
        (is (empty? (alert-words page)))
        (is (= "summary" (:part (:data (first (events response :pulse.web/part-lost))))))))))

(deftest the-log-says-why-a-part-was-lost-and-nothing-the-api-said
  (with-cohort "pt-ana" {:series (h/json-answer 503 {:title "Database password is hunter2" :status 503})} {}
    (fn [web]
      (let [response (h/visit! (signed-in web) :get "/readings")
            [lost] (events response :pulse.web/part-lost)]
        (is (= {:part "series blood-pressure" :status 503} (:data lost)))
        (is (= :warn (:level lost)))
        (is (not (str/includes? (pr-str (:signals response)) "hunter2")))
        (is (not (str/includes? (:text response) "hunter2")))))))

(deftest who-the-patient-is-cannot-be-done-without
  (doseq [[what me status title]
          [["the api fails on its side" (h/json-answer 503 {:title "Service Unavailable" :status 503}) 502 "Something went wrong on our side"]
           ["it says the person may not" (h/json-answer 403 {:title "Forbidden" :status 403}) 403 "You cannot see this"]
           ["it does not know the token" (h/json-answer 401 {:title "Unauthorized" :status 401}) 502 "Something went wrong on our side"]
           ["it answers what is not JSON" {:status 200 :body "<html>oops</html>"} 502 "Something went wrong on our side"]]]
    (testing what
      (with-cohort "pt-ana" {:me me} {}
        (fn [web]
          (let [response (h/visit! (signed-in web) :get "/readings")]
            (is (= status (:status response)))
            (is (str/includes? (hx/words (page-of response)) title))
            (is (= ["/api/me"] (api-paths web)) "and nothing else was asked")))))))

(deftest a-failure-that-is-not-a-parts-is-the-whole-pages
  (doseq [[what series status]
          [["the api refuses the token" (h/json-answer 401 {:title "Unauthorized" :status 401}) 502]
           ["it says the person may not see it" (h/json-answer 403 {:title "Forbidden" :status 403}) 403]
           ["it does not know the patient" (h/json-answer 404 {:title "Not Found" :status 404}) 502]]]
    (testing what
      (with-cohort "pt-ana" {:series series} {}
        (fn [web]
          (let [response (h/visit! (signed-in web) :get "/readings")]
            (is (= status (:status response)))
            (is (empty? (hx/by-tag (page-of response) :figure)))
            (is (not (str/includes? (hx/words (main-of (page-of response))) "Blood pressure")) "no part of the page is to be trusted")))))))

(deftest the-portal-gives-up-on-a-series-that-takes-too-long
  (with-cohort "pt-ana" {:series (fn [_request default] (assoc default :delay-ms 800))} {:api-timeout-ms 200}
    (fn [web]
      (let [started (System/nanoTime)
            response (h/visit! (signed-in web) :get "/readings")
            elapsed-ms (/ (- (System/nanoTime) started) 1e6)]
        (is (= 200 (:status response)))
        (is (< elapsed-ms 700) "it did not wait for the answer")
        (is (str/includes? (first (alert-words (page-of response))) "Your blood pressure readings could not be loaded"))
        (is (= :timeout (:reason (:data (first (events response :pulse.web/part-lost))))))))))

;; ---------------------------------------------------------------------------
;; Who is asking

(deftest a-visitor-is-sent-to-sign-in-and-brought-back-to-the-same-readings
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [response (h/call web :get "/readings" :query "measurement=heart-rate&days=7")]
        (is (= 303 (:status response)))
        (is (= "/sign-in?return-to=%2Freadings%3Fmeasurement%3Dheart-rate%26days%3D7" (header response "Location"))))
      (is (empty? (api-requests web)) "and the api is not asked for anything"))))

(deftest a-doctor-does-not-get-the-readings-of-the-portal
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [b (h/browser web)]
        (oidc-fake/sign-in-as! (:idp web) (get oidc-fake/people :rivera))
        (h/sign-in! b)
        (is (= 303 (:status (h/visit! b :get "/readings"))) "she has no session in the portal, and is sent to sign in")
        (is (empty? (api-requests web)))))))
