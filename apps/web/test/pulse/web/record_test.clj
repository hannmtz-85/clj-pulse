(ns pulse.web.record-test
  "Recording a reading over the whole handler (ADR-0028): the form, what a patient's post becomes when it is sent to the api and what
  comes back of it, every way it can be refused or fail, and who may do it. The stand-in for the api judges a reading as the api does
  (`pulse.fhir.measurement` and the window of time), so what the page says beside a field is what the api said."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [jsonista.core :as json]
            [pulse.design.data :as data]
            [pulse.platform.token :as token]
            [pulse.ui.test.a11y :as a11y]
            [pulse.ui.test.hiccup :as hx]
            [pulse.web.session :as session]
            [pulse.web.test.cohort :refer [alert-words api-paths api-requests at! csrf-of events field-value notice-words page-of signed-in
                                           with-cohort]]
            [pulse.web.test.harness :as h :refer [header]]
            [ring.middleware.session.store :as store]))

(defn- post!
  "The browser posts the form: with the token of its session unless `form` says another, and the `Origin` of the portal."
  [web browser form & {:as options}]
  (apply h/visit! browser :post "/readings/new" :form (merge {"__anti-forgery-token" (csrf-of web browser)} form)
         (mapcat identity options)))

(defn- posted
  "The reading the stand-in for the api was sent, as data, one for each POST."
  [web]
  (for [{:keys [method body]} (api-requests web) :when (= "POST" method)]
    (json/read-value body json/keyword-keys-object-mapper)))

(defn- posts [web] (filter #(= "POST" (:method %)) (api-requests web)))

(defn- form-page [response] (page-of response))

(defn- errors-of
  "The words beside the fields of a form, by the id of the field."
  [page]
  (into {} (for [p (hx/select page #(and (= :p (hx/tag %)) (some-> (:id (hx/attributes %)) (str/ends-with? "-error"))))]
             [(str/replace (:id (hx/attributes p)) #"-error$" "") (hx/words p)])))

;; ---------------------------------------------------------------------------
;; The form

(deftest the-form-is-made-with-no-help-from-the-api
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [b (signed-in web)
            response (h/visit! b :get "/readings/new")
            page (form-page response)]
        (is (= 200 (:status response)))
        (is (= "no-store" (header response "Cache-Control")))
        (is (= "Record a reading · Pulse" (hx/words (first (hx/by-tag page :title)))))
        (is (= ["Record a reading"] (map hx/words (hx/by-tag page :h1))))
        (is (empty? (api-requests web)) "the person's token is not needed to show a form")
        (testing "for a blood pressure, with the time it is now on the clock of the clinic"
          (is (= ["systolic" "diastolic"] (map #(:name (hx/attributes (hx/by-id page %))) ["systolic" "diastolic"])))
          (is (= "2026-10-13T14:00" (field-value page "taken")))
          (is (= ["2026-09-13T14:00" "2026-10-13T14:00"] (map #(get (hx/attributes (hx/by-id page "taken")) %) [:min :max]))))
        (testing "with the token of her session in the form that posts"
          (let [post (first (filter #(= "post" (:method (hx/attributes %))) (hx/by-tag page :form)))]
            (is (= [(csrf-of web b)] (map #(:value (hx/attributes %)) (filter #(= "__anti-forgery-token" (:name (hx/attributes %))) (hx/by-tag post :input)))))))
        (is (= [] (a11y/problems page {:page? true})))))))

(deftest the-form-is-for-the-measurement-asked-for
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [b (signed-in web)
            names (fn [query]
                    (let [page (form-page (h/visit! b :get "/readings/new" :query query))]
                      {:fields (vec (keep #(:name (hx/attributes %))
                                          (hx/select (hx/by-id page "reading-fields") #(and (= :input (hx/tag %)) (= "number" (:type (hx/attributes %)))))))
                       :selected (vec (keep #(when (contains? (hx/attributes %) :selected) (:value (hx/attributes %))) (hx/by-tag page :option)))}))]
        (is (= {:fields ["systolic" "diastolic"] :selected ["blood-pressure"]} (names nil)) "a blood pressure, without asking")
        (doseq [kind ["heart-rate" "spo2" "glucose" "weight" "temperature"]]
          (is (= {:fields ["value"] :selected [kind]} (names (str "measurement=" kind))) kind))
        (doseq [query ["measurement=nothing" "measurement=" "measurement=Heart-Rate" "measurement=heart-rate&measurement=weight" "measurement=../x"]]
          (is (= {:fields ["systolic" "diastolic"] :selected ["blood-pressure"]} (names query)) query))))))

(deftest htmx-asks-for-the-same-page-and-takes-the-fields-from-it
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [b (signed-in web)
            response (h/visit! b :get "/readings/new" :query "measurement=weight" :headers {"hx-request" "true" "hx-current-url" "http://localhost:3300/readings/new"})
            page (form-page response)]
        (is (= 200 (:status response)))
        (is (some? (hx/by-id page "reading-fields")) "the element it takes the fields from is in the answer")
        (is (= "weight" (:value (hx/attributes (first (hx/select (hx/by-id page "reading-fields") #(= "measurement" (:name (hx/attributes %)))))))))
        (is (some? (hx/by-id page "value")))
        (is (nil? (hx/by-id page "systolic")))))))

(deftest a-visitor-is-sent-to-sign-in-and-brought-back-to-the-form-they-asked-for
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [response (h/call web :get "/readings/new" :query "measurement=weight")]
        (is (= 303 (:status response)))
        (is (= "/sign-in?return-to=%2Freadings%2Fnew%3Fmeasurement%3Dweight" (header response "Location")))))))

;; ---------------------------------------------------------------------------
;; A reading that is saved

(deftest each-measurement-is-sent-to-the-api-as-the-api-takes-it
  (doseq [[form expected] [[{"measurement" "blood-pressure" "systolic" "132" "diastolic" "88" "taken" "2026-10-13T13:30"}
                            {:measurement "blood-pressure" :systolic 132 :diastolic 88 :at "2026-10-13T11:30:00Z"}]
                           [{"measurement" "heart-rate" "value" "72" "taken" "2026-10-13T13:30"}
                            {:measurement "heart-rate" :value 72 :at "2026-10-13T11:30:00Z"}]
                           [{"measurement" "spo2" "value" "97" "taken" "2026-10-13T13:30"}
                            {:measurement "spo2" :value 97 :at "2026-10-13T11:30:00Z"}]
                           [{"measurement" "glucose" "value" "110" "taken" "2026-10-13T13:30"}
                            {:measurement "glucose" :value 110 :at "2026-10-13T11:30:00Z"}]
                           [{"measurement" "weight" "value" "82.3" "taken" "2026-10-13T13:30"}
                            {:measurement "weight" :value 82.3 :at "2026-10-13T11:30:00Z"}]
                           [{"measurement" "temperature" "value" "36.6" "taken" "2026-10-13T13:30"}
                            {:measurement "temperature" :value 36.6 :at "2026-10-13T11:30:00Z"}]]]
    (testing (get form "measurement")
      (with-cohort "pt-ana" {} {}
        (fn [web]
          (let [b (signed-in web)
                response (post! web b form)
                keeper (:tokens (store/read-session (:sessions web) (h/cookie b session/cookie-name)))]
            (is (= 303 (:status response)))
            (is (= (str "/readings?measurement=" (get form "measurement")) (header response "Location")))
            (is (= "" (:text response)))
            (testing "one POST, to her own record, with her own token and the id of the request"
              (is (= ["/api/me" "/api/patients/pt-ana/observations"] (api-paths web)))
              (is (= expected (first (posted web))))
              (is (= 1 (count (posts web))))
              (is (= (str "Bearer " (token/access-token keeper)) (get-in (first (posts web)) [:headers "authorization"])))
              (is (= (header response "X-Request-Id") (get-in (first (posts web)) [:headers "x-request-id"])))
              (is (= "application/json" (get-in (first (posts web)) [:headers "content-type"]))))))))))

(deftest a-reading-that-was-saved-is-told-once-on-the-page-it-goes-to
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [b (signed-in web)
            before (h/cookie b session/cookie-name)
            response (post! web b {"measurement" "blood-pressure" "systolic" "132" "diastolic" "88" "taken" "2026-10-13T13:30"})
            after (h/visit! b :get (header response "Location") :query nil)]
        (is (= before (h/cookie b session/cookie-name)) "the session is the same: no new cookie")
        (is (empty? (h/set-cookies response)))
        (testing "the page it goes to says so, in the words of what the api stored"
          (let [page (form-page (h/visit! b :get "/readings" :query "measurement=blood-pressure"))]
            (is (= ["Reading savedBlood pressure, 132/88 mmHg, today, 13:30."] (notice-words page)))
            (is (= [] (a11y/problems page {:page? true})))))
        (testing "and only once"
          (is (empty? (notice-words (form-page (h/visit! b :get "/readings" :query "measurement=blood-pressure"))))))
        (testing "the session is as it was, with the person, the tokens and the token of the forms"
          (let [session (store/read-session (:sessions web) (h/cookie b session/cookie-name))]
            (is (= "Ana García" (get-in session [:person :name])))
            (is (some? (:tokens session)))
            (is (= (csrf-of web b) (:csrf session)))
            (is (not (contains? session :flash)))))
        (is (some? after))))))

(deftest a-reading-is-told-as-the-api-stored-it
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [b (signed-in web)]
        (post! web b {"measurement" "weight" "value" "82.35" "taken" "2026-10-13T13:30"})
        (is (= ["Reading savedWeight, 82.4 kg, today, 13:30."] (notice-words (form-page (h/visit! b :get "/readings" :query "measurement=weight"))))
            "the api rounds to a tenth of a kilo, and the patient is told what it kept")))))

(deftest a-reading-that-was-saved-is-not-told-to-somebody-else
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [ana (signed-in web)
            other (signed-in web)]
        (post! web ana {"measurement" "heart-rate" "value" "72"})
        (is (empty? (notice-words (form-page (h/visit! other :get "/readings")))) "another session of the same person has its own")
        (is (= 1 (count (notice-words (form-page (h/visit! ana :get "/readings"))))))))))

(deftest an-empty-time-is-no-time-and-the-api-takes-now
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [b (signed-in web)]
        (is (= 303 (:status (post! web b {"measurement" "heart-rate" "value" "72" "taken" ""}))))
        (is (= {:measurement "heart-rate" :value 72} (first (posted web))) "no `at` at all")))))

(deftest the-record-is-the-persons-own-whatever-the-form-says
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [b (signed-in web)]
        (post! web b {"measurement" "heart-rate" "value" "72" "patient" "pt-liam" "id" "pt-liam" "subject" "Patient/pt-liam" "performer" "Practitioner/pr-rivera"})
        (is (= ["/api/me" "/api/patients/pt-ana/observations"] (api-paths web)))
        (is (= {:measurement "heart-rate" :value 72} (first (posted web))) "and nothing of the form goes to the api but the reading")))))

;; ---------------------------------------------------------------------------
;; The days the clocks change

(deftest a-time-typed-on-the-day-the-clocks-go-back-is-the-first-time-it-happened
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (at! web "2026-10-25T10:00:00Z")
      (let [b (signed-in web)
            at-of (fn [typed]
                    (reset! (:api-requests web) [])
                    (post! web b {"measurement" "heart-rate" "value" "72" "taken" typed})
                    (:at (first (posted web))))]
        (is (= "2026-10-24T23:59:00Z" (at-of "2026-10-25T01:59")))
        (is (= "2026-10-25T00:30:00Z" (at-of "2026-10-25T02:30")) "02:30 happened twice: the first, in summer time")
        (is (= "2026-10-25T02:00:00Z" (at-of "2026-10-25T03:00")))
        (is (= "2026-10-25T09:00:00Z" (at-of "2026-10-25T10:00")) "winter time")))))

(deftest a-time-typed-on-the-day-the-clocks-go-forward-is-read-an-hour-later
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (at! web "2026-03-29T10:00:00Z")
      (let [b (signed-in web)
            at-of (fn [typed]
                    (reset! (:api-requests web) [])
                    (post! web b {"measurement" "heart-rate" "value" "72" "taken" typed})
                    (:at (first (posted web))))]
        (is (= "2026-03-29T00:59:00Z" (at-of "2026-03-29T01:59")))
        (is (= "2026-03-29T01:30:00Z" (at-of "2026-03-29T02:30")) "02:30 never happened: it is 03:30, in summer time")
        (is (= "2026-03-29T01:00:00Z" (at-of "2026-03-29T03:00")))))))

;; ---------------------------------------------------------------------------
;; What cannot be read, which the api is not bothered with

(deftest what-is-not-a-number-is-said-beside-the-field-and-the-api-is-not-asked
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [b (signed-in web)
            response (post! web b {"measurement" "blood-pressure" "systolic" "132" "diastolic" "eighty" "taken" "2026-10-13T13:30"})
            page (form-page response)]
        (is (= 422 (:status response)))
        (is (empty? (api-requests web)) "not even who she is")
        (is (= {"diastolic" "Enter a number."} (errors-of page)))
        (is (= ["132" "eighty" "2026-10-13T13:30"] (map #(field-value page %) ["systolic" "diastolic" "taken"])) "what was typed is still there")
        (is (= ["The reading was not savedCheck the diastolic pressure and save it again."] (alert-words page)))
        (is (contains? (hx/attributes (hx/by-id page "diastolic")) :autofocus))
        (is (= [] (a11y/problems page {:page? true})))))))

(deftest everything-that-cannot-be-read-is-said-at-once
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [b (signed-in web)
            page (form-page (post! web b {"measurement" "blood-pressure" "systolic" "" "diastolic" "x" "taken" "yesterday"}))]
        (is (= {"systolic" "Enter a number." "diastolic" "Enter a number." "taken" "Enter a date and time."} (errors-of page)))
        (is (= ["The reading was not savedCheck the systolic pressure, the diastolic pressure and the time it was taken and save it again."]
               (alert-words page)))))))

(deftest what-comes-twice-is-no-number-and-no-time
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [b (signed-in web)
            page (form-page (post! web b {"measurement" "heart-rate" "value" ["72" "80"] "taken" ["2026-10-13T13:30" "2026-10-13T13:31"]}))]
        (is (= {"value" "Enter a number." "taken" "Enter a date and time."} (errors-of page)))
        (is (= [nil nil] (map #(field-value page %) ["value" "taken"])) "nothing that is not text is put back in a field")
        (is (empty? (api-requests web)))))))

(deftest a-form-that-the-portal-did-not-make-is-the-form-again-for-the-first-measurement
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [b (signed-in web)]
        (doseq [form [{} {"measurement" "nothing" "value" "72"} {"measurement" "" "value" "72"} {"measurement" ["heart-rate" "weight"] "value" "72"}
                      {"measurement" "../../admin" "value" "72"}]]
          (reset! (:api-requests web) [])
          (let [response (post! web b form)
                page (form-page response)]
            (is (= 422 (:status response)) (pr-str form))
            (is (empty? (api-requests web)) (pr-str form))
            (is (= ["systolic" "diastolic"] (map #(:name (hx/attributes (hx/by-id page %))) ["systolic" "diastolic"])) "a blood pressure, with nothing in it")
            (is (= ["The reading was not savedCheck what you entered and save it again."] (alert-words page)))))))))

;; ---------------------------------------------------------------------------
;; What the api refuses

(deftest what-the-api-refuses-is-said-beside-the-field-in-the-words-of-the-api
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [b (signed-in web)
            response (post! web b {"measurement" "blood-pressure" "systolic" "132" "diastolic" "310" "taken" "2026-10-13T13:30"})
            page (form-page response)]
        (is (= 422 (:status response)))
        (is (= {"diastolic" "Must be between 20 and 200 mmHg."} (errors-of page)))
        (is (= ["132" "310" "2026-10-13T13:30"] (map #(field-value page %) ["systolic" "diastolic" "taken"])) "and what was typed is still there")
        (is (= ["The reading was not savedCheck the diastolic pressure and save it again."] (alert-words page)))
        (is (= "true" (:aria-invalid (hx/attributes (hx/by-id page "diastolic")))))
        (is (contains? (hx/attributes (hx/by-id page "diastolic")) :autofocus))
        (is (= ["/api/me" "/api/patients/pt-ana/observations"] (api-paths web)) "asked once")
        (is (empty? (notice-words (form-page (h/visit! b :get "/readings")))) "and nothing was saved")
        (is (= [] (a11y/problems page {:page? true})))))))

(deftest a-diastolic-that-is-not-below-the-systolic-is-the-api-to-say
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [page (form-page (post! web (signed-in web) {"measurement" "blood-pressure" "systolic" "80" "diastolic" "120"}))]
        (is (= {"diastolic" "Must be lower than the systolic pressure."} (errors-of page)))))))

(deftest a-time-the-api-does-not-take-is-said-beside-the-time
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [b (signed-in web)]
        (let [page (form-page (post! web b {"measurement" "heart-rate" "value" "72" "taken" "2026-12-01T10:00"}))]
          (is (= {"taken" "Must not be in the future."} (errors-of page)))
          (is (= "true" (:aria-invalid (hx/attributes (hx/by-id page "taken")))))
          (is (contains? (hx/attributes (hx/by-id page "taken")) :autofocus))
          (is (= ["The reading was not savedCheck the time it was taken and save it again."] (alert-words page))))
        (let [page (form-page (post! web b {"measurement" "heart-rate" "value" "72" "taken" "2026-08-01T10:00"}))]
          (is (= {"taken" "Must not be more than 30 days ago."} (errors-of page))))))))

(deftest every-field-the-api-refuses-is-said-at-once
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [page (form-page (post! web (signed-in web) {"measurement" "blood-pressure" "systolic" "20" "diastolic" "250" "taken" "2026-12-01T10:00"}))]
        (is (= {"systolic" "Must be between 40 and 300 mmHg."
                "diastolic" "Must be between 20 and 200 mmHg."
                "taken" "Must not be in the future."}
               (errors-of page)))
        (is (= ["The reading was not savedCheck the systolic pressure, the diastolic pressure and the time it was taken and save it again."]
               (alert-words page)))))))

(deftest what-the-api-says-of-the-shape-is-handled-like-what-it-says-of-the-reading
  (doseq [[what answer expected-errors expected-alert]
          [["a 400 that names a field" (h/json-answer 400 {:title "Bad Request" :status 400 :errors {:value ["missing required key"]}})
            {"value" "Missing required key."} "The reading was not savedCheck the heart rate and save it again."]
           ["a 422 about something the form has no field for" (h/json-answer 422 {:title "Unprocessable Content" :status 422 :errors {:unit ["must be one of /min"]}})
            {} "The reading was not savedCheck what you entered and save it again."]
           ["a 400 with nothing to say what is wrong" (h/json-answer 400 {:title "Bad Request" :status 400})
            {} "The reading was not savedCheck what you entered and save it again."]
           ["a 422 whose errors are not what they should be" (h/json-answer 422 {:title "Unprocessable Content" :status 422 :errors "nonsense"})
            {} "The reading was not savedCheck what you entered and save it again."]
           ["a 422 whose body is not JSON" {:status 422 :body "<html>no</html>"}
            {} "The reading was not savedCheck what you entered and save it again."]]]
    (testing what
      (with-cohort "pt-ana" {:record answer} {}
        (fn [web]
          (let [response (post! web (signed-in web) {"measurement" "heart-rate" "value" "72"})
                page (form-page response)]
            (is (= 422 (:status response)))
            (is (= expected-errors (errors-of page)))
            (is (= [expected-alert] (alert-words page)))
            (is (= "72" (field-value page "value")))
            (is (= [] (a11y/problems page {:page? true})))))))))

;; ---------------------------------------------------------------------------
;; When the api fails

(deftest when-the-api-fails-before-the-reading-is-sent-it-was-not-saved-and-nothing-is-lost
  (with-cohort "pt-ana" {:me (h/json-answer 503 {:title "Database password is hunter2" :status 503})} {}
    (fn [web]
      (let [b (signed-in web)
            response (post! web b {"measurement" "heart-rate" "value" "72" "taken" "2026-10-13T13:30"})
            page (form-page response)]
        (is (= 502 (:status response)))
        (is (empty? (posts web)) "nothing was sent")
        (is (= ["The reading was not savedThe problem is on our side, not yours. Try again in a moment."] (alert-words page)))
        (is (= ["72" "2026-10-13T13:30"] (map #(field-value page %) ["value" "taken"])) "what was typed is still there")
        (is (empty? (errors-of page)))
        (testing "the log says what happened, and the page and the log say nothing of what the api said"
          (let [[failed] (events response :pulse.web/api-failed)]
            (is (= :warn (:level failed)))
            (is (= "/api/me" (get-in failed [:data :path])))
            (is (= 503 (get-in failed [:data :status])))
            (is (not (str/includes? (:text response) "hunter2")))))
        (is (= [] (a11y/problems page {:page? true})))))))

(deftest when-the-api-fails-with-the-reading-on-its-way-nobody-knows-if-it-was-saved
  (doseq [[what record status]
          [["it fails on its side" (h/json-answer 503 {:title "Service Unavailable" :status 503}) 502]
           ["it fails with a 500" (h/json-answer 500 {:title "Database password is hunter2" :status 500}) 502]
           ["it answers what is not JSON" {:status 200 :body "<html>oops</html>"} 502]
           ["it answers a success with no body that is JSON" {:status 201 :body "created"} 502]
           ["it drops the connection" (fn [_request _default] (throw (ex-info "the api went away" {}))) 502]]]
    (testing what
      (with-cohort "pt-ana" {:record record} {}
        (fn [web]
          (let [b (signed-in web)
                response (post! web b {"measurement" "heart-rate" "value" "72" "taken" "2026-10-13T13:30"})
                page (form-page response)]
            (is (= status (:status response)))
            (is (= 1 (count (posts web))) "it was sent, once, and the portal does not send it again by itself")
            (is (= [(str "We could not confirm that your reading was savedThe problem is on our side, not yours. "
                         "Look at your readings before you try again, so that it is not recorded twice.")]
                   (alert-words page)))
            (is (= ["72" "2026-10-13T13:30"] (map #(field-value page %) ["value" "taken"])))
            (is (not (str/includes? (:text response) "hunter2")) "what the api said is for the log, and not for the page")
            (is (= 1 (count (events response :pulse.web/api-failed))))
            (is (empty? (notice-words (form-page (h/visit! b :get "/readings")))) "no flash for a reading nobody knows about")))))))

(deftest when-the-api-is-too-slow-it-is-a-gateway-timeout-and-nobody-knows-either
  (with-cohort "pt-ana" {:record (fn [_request default] (assoc default :delay-ms 800))} {:api-timeout-ms 250}
    (fn [web]
      (let [b (signed-in web)
            started (System/nanoTime)
            response (post! web b {"measurement" "heart-rate" "value" "72"})
            elapsed-ms (/ (- (System/nanoTime) started) 1e6)]
        (is (= 504 (:status response)))
        (is (< elapsed-ms 700) "the portal did not wait for the answer")
        (is (str/includes? (first (alert-words (form-page response))) "We could not confirm that your reading was saved"))
        (is (= :timeout (get-in (first (events response :pulse.web/api-failed)) [:data :reason])))))))

(deftest when-the-api-is-too-slow-to-say-who-the-person-is-it-was-not-sent-at-all
  (with-cohort "pt-ana" {:me (fn [_request default] (assoc default :delay-ms 800))} {:api-timeout-ms 250}
    (fn [web]
      (let [response (post! web (signed-in web) {"measurement" "heart-rate" "value" "72"})]
        (is (= 504 (:status response)))
        (is (empty? (posts web)))
        (is (= ["The reading was not savedThe problem is on our side, not yours. Try again in a moment."] (alert-words (form-page response))))))))

(deftest a-failure-that-is-not-a-failure-of-the-api-is-a-page-for-it
  (doseq [[what record status title]
          [["the api refuses the token" (h/json-answer 401 {:title "Unauthorized" :status 401}) 502 "Something went wrong on our side"]
           ["it says the person may not" (h/json-answer 403 {:title "Forbidden" :status 403}) 403 "You cannot see this"]
           ["it does not know the patient" (h/json-answer 404 {:title "Not Found" :status 404}) 502 "Something went wrong on our side"]]]
    (testing what
      (with-cohort "pt-ana" {:record record} {}
        (fn [web]
          (let [response (post! web (signed-in web) {"measurement" "heart-rate" "value" "72"})]
            (is (= status (:status response)))
            (is (str/includes? (hx/words (page-of response)) title))
            (is (empty? (hx/by-id (page-of response) "reading-fields")) "not a form: the person is not given a reading to send again")))))))

(deftest a-person-with-no-record-is-a-failure-on-our-side
  (with-cohort "pt-ana" {:me (h/json-answer (assoc (data/me "pt-ana") :fhirUser nil))} {}
    (fn [web]
      (let [response (post! web (signed-in web) {"measurement" "heart-rate" "value" "72"})]
        (is (= 500 (:status response)))
        (is (empty? (posts web)))))))

;; ---------------------------------------------------------------------------
;; Who may

(deftest a-post-without-the-token-of-the-session-is-refused-and-nothing-is-sent
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [b (signed-in web)
            other (signed-in web)]
        (doseq [[what form] {"none" {"measurement" "heart-rate" "value" "72"}
                             "an empty one" {"measurement" "heart-rate" "value" "72" "__anti-forgery-token" ""}
                             "a wrong one" {"measurement" "heart-rate" "value" "72" "__anti-forgery-token" "wrong"}
                             "that of another session" {"measurement" "heart-rate" "value" "72" "__anti-forgery-token" (csrf-of web other)}}]
          (let [response (h/visit! b :post "/readings/new" :form form)]
            (is (= 403 (:status response)) what)
            (is (str/includes? (hx/words (page-of response)) "We could not do that") what)))
        (is (empty? (api-requests web)))))))

(deftest a-post-from-another-site-is-refused-whatever-it-carries
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [b (signed-in web)]
        (doseq [origin ["https://evil.example" "null" "http://localhost:3301"]]
          (let [response (post! web b {"measurement" "heart-rate" "value" "72"} :headers {"origin" origin})]
            (is (= 403 (:status response)) origin)))
        (is (empty? (api-requests web)))))))

(deftest a-visitor-who-posts-is-sent-to-sign-in-and-nothing-is-sent-to-the-api
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [response (h/call web :post "/readings/new" :headers {"origin" h/base-url} :form {"measurement" "heart-rate" "value" "72"})]
        (is (= 303 (:status response)))
        (is (= "/sign-in" (header response "Location")) "and back to the home page, since a post cannot be asked for again")
        (is (empty? (api-requests web)))))))

(deftest a-session-that-ended-is-said-to-have-ended-and-the-reading-is-not-sent
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [b (signed-in web)
            token (csrf-of web b)]
        (at! web "2026-10-13T13:00:00Z")
        (let [response (h/visit! b :post "/readings/new" :form {"__anti-forgery-token" token "measurement" "heart-rate" "value" "72"})]
          (is (= 401 (:status response)))
          (is (str/includes? (hx/words (page-of response)) "Your session has ended"))
          (is (empty? (api-requests web))))))))

(deftest asking-for-the-form-with-a-reading-in-the-address-records-nothing
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [b (signed-in web)]
        (h/visit! b :get "/readings/new" :query "measurement=heart-rate&value=72&taken=2026-10-13T13:30")
        (is (empty? (api-requests web)))))))

(deftest the-methods-the-form-does-not-have-are-not-allowed
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [b (signed-in web)]
        (doseq [method [:put :delete :patch]]
          (let [response (h/visit! b method "/readings/new")]
            (is (= 405 (:status response)) (name method))
            (is (str/includes? (header response "Allow") "POST"))))
        (is (empty? (api-requests web)))))))

;; ---------------------------------------------------------------------------
;; Whatever is typed

(deftest whatever-is-typed-the-answer-is-a-saved-reading-or-the-form-again
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [b (signed-in web)
            text (gen/one-of [gen/string gen/string-ascii gen/small-integer (gen/fmap str (gen/double* {:infinite? false :NaN? false}))
                              (gen/elements ["" " " "1e999" "-0" "NaN" "1,5" "٣" "<script>alert(1)</script>" "\"><b>" "2026-10-13T13:30"
                                             "2026-10-13T13:30:00" "2026-02-30T10:00" "0000-01-01T00:00" "9999-12-31T23:59"])])
            result (tc/quick-check 150
                                   (prop/for-all [kind (gen/elements ["blood-pressure" "heart-rate" "spo2" "glucose" "weight" "temperature" "nothing" ""])
                                                  fields (gen/vector text 3)]
                                     (let [[a b-field c] (map str fields)
                                           response (post! web b {"measurement" kind "systolic" a "diastolic" b-field "value" a "taken" c})
                                           page (when (= 422 (:status response)) (form-page response))]
                                       (and (contains? #{303 422} (:status response))
                                            (or (= 303 (:status response))
                                                (and (some? (hx/by-id page "reading-fields"))
                                                     (not (str/includes? (:text response) "<script>alert")))
                                                false)))))]
        (is (:pass? result) (pr-str (select-keys result [:fail :shrunk])))))))
