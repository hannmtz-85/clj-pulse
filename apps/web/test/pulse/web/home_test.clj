(ns pulse.web.home-test
  "The home of the portal over the whole handler (ADR-0028): a person signs in, and the page is made from what the api answers
  for them, asked for with their own token. The stand-in for the api answers from the synthetic cohort of the mock-ups, at the
  moment of the mock-ups, so that what is served can be held to what the owner reviewed; and each way the api can fail is tried:
  a part that is lost leaves the rest of the page, and a failure that is not a part's is a page for it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.design.build :as build]
            [pulse.design.data :as data]
            [pulse.design.portal :as portal]
            [pulse.platform.test.clock :as clock]
            [pulse.platform.test.idp :as idp]
            [pulse.platform.test.oidc :as oidc-fake]
            [pulse.platform.token :as token]
            [pulse.ui.test.a11y :as a11y]
            [pulse.ui.test.hiccup :as hx]
            [pulse.ui.test.html :as parsed]
            [pulse.web.html :as html]
            [pulse.web.session :as session]
            [pulse.web.test.cohort :refer [cohort-api]]
            [pulse.web.test.harness :as h :refer [header with-web]]
            [ring.middleware.session.store :as store]))

(def ^:private context (build/context))

;; ---------------------------------------------------------------------------
;; The stand-in for the api, the cohort and the browser

(defn- at!
  "Sets the clock of the portal, and of the fake provider, to `instant`."
  [web instant]
  (clock/set-time! (:clock web) instant))

(defn- home
  "A person signs in at the moment of the mock-ups, and asks for the home page. Returns `{:browser :response :page}`, where
  `:page` is the page read back from the HTML that was served. `person` is a key of `oidc-fake/people` (Ana by default)."
  [web & {:keys [patient person headers] :or {patient "pt-ana" person :ana}}]
  (when-not (= :ana person) (oidc-fake/sign-in-as! (:idp web) (get oidc-fake/people person)))
  (let [b (h/browser web)]
    (h/sign-in! b)
    (let [started (System/nanoTime)
          response (h/visit! b :get "/" :headers headers)
          elapsed-ms (/ (- (System/nanoTime) started) 1e6)]
      {:browser b :response response :page (parsed/document (:text response)) :patient patient :elapsed-ms elapsed-ms})))

(defn- main-of [page] (first (hx/by-tag page :main)))

(defn- read-back
  "A mock-up read back from the HTML that is written of it, as a served page is: the same renderer and the same reader, so that
  what is compared is the words and not how a space between two inline elements was made."
  [hiccup]
  (parsed/document (html/render hiccup)))

(defn- sections
  "The sections of the page, by the id of their heading, as the words in each."
  [page]
  (into {} (for [section (hx/by-tag (main-of page) :section)
                 :let [id (:aria-labelledby (hx/attributes section))]
                 :when id]
             [id (hx/words section)])))

(defn- alert-words
  "The words of every message of the page that says that something could not be loaded."
  [page]
  (map hx/words (hx/select (main-of page) #(and (= :div (hx/tag %)) (hx/has-class? % "bg-tone-high-soft")))))

(defn- events [response id]
  (filter #(= id (:id %)) (:signals response)))

(defn- api-paths [web]
  (map :path @(:api-requests web)))

(defn- with-cohort
  "Calls `(f web)` with a portal whose api answers the cohort for `patient-id` (and what `overrides` say), at the moment of the
  mock-ups."
  [patient-id overrides options f]
  (with-web options
    (fn [web]
      (reset! (:api-answer web) (apply cohort-api patient-id (mapcat identity overrides)))
      (at! web data/now-text)
      (f web))))

;; ---------------------------------------------------------------------------
;; The home of a patient

(deftest the-home-of-a-patient-is-made-of-what-the-api-answers-for-them
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [{:keys [browser response page]} (home web)
            main (main-of page)]
        (is (= 200 (:status response)))
        (is (= "text/html" (h/content-type response)))
        (is (= "no-store" (header response "Cache-Control")) "it is a patient's page")
        (is (= ["Good afternoon, Ana"] (map hx/words (hx/by-tag page :h1))) "the name is the one the api says, at the hour of the clinic")
        (is (str/includes? (hx/words main) "Tuesday, 13 October 2026"))
        (is (= "Home · Pulse" (hx/words (first (hx/by-tag page :title)))))
        (is (= #{"next" "latest" "recent"} (set (keys (sections page)))))
        (testing "the next appointment, with the doctor's name that the api gave"
          (is (str/includes? (get (sections page) "next") "Hypertension follow-upWed 14 Oct, 10:00–10:30 · Dr. Elena RiveraConfirmed")))
        (testing "the latest reading of each measurement she has, and the five newest of all"
          (is (= ["177/100" "70" "82.3"] (map hx/words (hx/select main #(hx/has-class? % "text-4xl")))))
          (is (= 5 (count (hx/by-tag (first (hx/select main #(= "Recent readings" (:aria-label (hx/attributes %))))) :li)))))
        (testing "and it is for her: her name is in the menu"
          (is (= ["Account menu of Ana García"]
                 (keep #(:aria-label (hx/attributes %)) (hx/by-tag page :summary)))))
        (testing "the session is the one that asked"
          (is (= "Ana García" (get-in (store/read-session (:sessions web) (h/cookie browser session/cookie-name)) [:person :name]))))))))

(deftest the-api-is-asked-with-the-token-of-the-person-and-the-id-of-the-request
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [{:keys [browser response]} (home web :headers {"x-request-id" "visit-42"})
            keeper (:tokens (store/read-session (:sessions web) (h/cookie browser session/cookie-name)))
            requests @(:api-requests web)]
        (is (= "visit-42" (header response "X-Request-Id")))
        (testing "who she is, her summary, and then a series for each measurement she has a reading of, and no other"
          (is (= ["/api/me" "/api/patients/pt-ana/summary"] (map :path (take 2 requests))))
          (is (= #{"measurement=blood-pressure&limit=5" "measurement=heart-rate&limit=5" "measurement=weight&limit=5"}
                 (set (map :query (drop 2 requests)))))
          (is (= 5 (count requests)))
          (is (every? #(= "/api/patients/pt-ana/observations" (:path %)) (drop 2 requests))))
        (testing "each carries her own token, and the id of the request, which the api puts in its log"
          (is (every? #(= (str "Bearer " (token/access-token keeper)) (get-in % [:headers "authorization"])) requests))
          (is (every? #(= "visit-42" (get-in % [:headers "x-request-id"])) requests)))
        (testing "a GET each, and nothing that changes anything"
          (is (every? #(= "GET" (:method %)) requests)))))))

(deftest a-request-has-an-id-in-the-log-of-the-portal-and-in-the-api-even-when-the-browser-sent-none
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [{:keys [response]} (home web)
            id (header response "X-Request-Id")]
        (is (re-matches #"[0-9a-f-]{36}" id))
        (is (every? #(= id (get-in % [:headers "x-request-id"])) @(:api-requests web)))))))

(deftest the-home-moves-with-the-clock
  (testing "in the morning"
    (with-cohort "pt-ana" {} {}
      (fn [web]
        (at! web "2026-10-13T06:30:00Z")
        (let [{:keys [page]} (home web)]
          (is (= ["Good morning, Ana"] (map hx/words (hx/by-tag page :h1))))))))
  (testing "an appointment that has begun is not the next one: the request that waits is"
    (with-cohort "pt-ana" {} {}
      (fn [web]
        (let [{:keys [page]} (do (at! web "2026-10-14T09:00:00Z") (home web))
              next-words (get (sections page) "next")]
          (is (str/includes? next-words "Medication review"))
          (is (str/includes? next-words "Asked for a visit between Oct 20 and Oct 27 · Dr. Elena Rivera"))
          (is (str/includes? next-words "Requested"))
          (is (not (str/includes? next-words "Hypertension follow-up"))))))))

;; ---------------------------------------------------------------------------
;; What is served is what was reviewed

(deftest the-home-that-is-served-is-the-home-of-the-mock-up
  (let [mock (read-back (portal/home context))
        mock-sections (sections mock)]
    (with-cohort "pt-ana" {} {}
      (fn [web]
        (let [{:keys [page]} (home web)]
          (is (= (set (keys mock-sections)) (set (keys (sections page)))) "the same sections")
          (doseq [[id words] (sections page)]
            (is (= (get mock-sections id) words) id))
          (testing "the same header"
            (is (= (hx/words (first (hx/by-tag (main-of mock) :header))) (hx/words (first (hx/by-tag (main-of page) :header))))))
          (testing "the same notice, which the mock-up reviewed"
            (let [notice (fn [doc] (first (hx/select (main-of doc) #(and (= :div (hx/tag %)) (hx/has-class? % "bg-tone-critical-soft")))))]
              (is (some? (notice page)))
              (is (= (hx/words (notice mock)) (hx/words (notice page)))))))))))

(deftest the-home-of-a-new-patient-is-the-home-of-the-mock-up-with-no-readings
  (let [mock (read-back (portal/new-patient context))]
    (with-cohort "pt-noah" {} {}
      (fn [web]
        (let [{:keys [page]} (home web :person :noah :patient "pt-noah")
              served (sections page)]
          (is (= ["Welcome, Noah"] (map hx/words (hx/by-tag page :h1))))
          (is (= (map hx/words (hx/by-tag mock :h1)) (map hx/words (hx/by-tag page :h1))))
          (testing "the sections are all there, as they were reviewed"
            (is (= (set (keys (sections mock))) (set (keys served))))
            (doseq [[id words] served]
              (is (= (get (sections mock) id) words) id)))
          (testing "the first steps are to record a reading and to look at the record, whose pages there are"
            (is (= (str "Your first stepsRecord your first readingBlood pressure, heart rate, oxygen, glucose, weight or temperature."
                        "Look at your recordYour doctor sees what you see there.")
                   (get served "first"))))
          (is (str/includes? (hx/words (main-of page)) "No readings yet"))
          (is (empty? (hx/select page #(hx/has-class? % "text-4xl"))) "no tile of a reading")
          (testing "the request that waits, with the doctor's name the api gave"
            (is (str/includes? (get served "next") "New patient intakeAsked for a visit between Oct 14 and Oct 20 · Dr. Chidi OkaforRequested"))))))))

;; ---------------------------------------------------------------------------
;; Nothing leads nowhere

(deftest nothing-on-the-page-leads-to-a-page-the-portal-does-not-have
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [{:keys [browser page]} (home web)
            links (disj (set (map (comp :href hx/attributes) (hx/by-tag page :a))) "#main")
            forms (map (comp (juxt :method :action) hx/attributes) (hx/by-tag page :form))]
        (is (= #{"/" "/readings" "/readings/new" "/appointments" "/appointments/appt-ana-1/cancel" "/record" "/profile"} links)
            "the home, the readings and the form to record one, the appointments and the question before the next one is cancelled, the record and the profile, which are the pages there are (and the skip link, which goes to the page itself)")
        (is (= [["post" "/sign-out"]] forms))
        (testing "and every one of them is a page that answers"
          (doseq [link links]
            (is (= 200 (:status (h/visit! browser :get link))) link)))))))

(deftest an-appointment-with-no-id-has-no-question-to-be-cancelled-at
  (with-cohort "pt-ana" {:summary (h/json-answer (update (data/summary "pt-ana") :appointments (fn [appointments] (mapv #(assoc % :id nil) appointments))))}
    {}
    (fn [web]
      (let [{:keys [page]} (home web)]
        (is (str/includes? (get (sections page) "next") "Hypertension follow-up") "it is the next appointment all the same")
        (is (not (str/includes? (hx/words (main-of page)) "Cancel appointment")))))))

(deftest what-a-patient-may-cancel-is-offered-where-the-question-is
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [{:keys [page]} (home web)
            button (first (filter #(= "Cancel appointment" (hx/words %)) (hx/by-tag (main-of page) :a)))]
        (is (some? button) "the lifecycle allows it, and the page for it exists")
        (is (= "/appointments/appt-ana-1/cancel" (:href (hx/attributes button))))
        (is (not (str/includes? (hx/words page) "Cancel request")) "she has no request to cancel on the home: the next one is the confirmed appointment"))))
  (testing "a patient with only a request waiting is offered to cancel it, and one with nothing coming up to ask for a visit"
    (with-cohort "pt-noah" {} {}
      (fn [web]
        (let [{:keys [page]} (home web :person :noah :patient "pt-noah")
              link (fn [text] (first (filter #(= text (hx/words %)) (hx/by-tag (main-of page) :a))))]
          (is (= "/appointments/appt-noah-1/cancel" (:href (hx/attributes (link "Cancel request"))))))))
    (with-cohort "pt-ana" {:summary (h/json-answer (assoc (data/summary "pt-ana") :appointments []))} {}
      (fn [web]
        (let [{:keys [page]} (home web)
              ask (first (filter #(= "Ask for a visit" (hx/words %)) (hx/by-tag (main-of page) :a)))]
          (is (= "/appointments#ask" (:href (hx/attributes ask)))))))))

;; ---------------------------------------------------------------------------
;; A part that is lost

(defn- tile-values [page]
  (map hx/words (hx/select (main-of page) #(hx/has-class? % "text-4xl"))))

(defn- retry-links
  "The `Try again` buttons of a page that says that a part could not be loaded: where they lead."
  [page]
  (map (comp :href hx/attributes) (filter #(= "Try again" (hx/words %)) (hx/by-tag page :a))))

(deftest a-summary-the-api-cannot-give-is-one-message-and-the-rest-of-the-page-stays
  (doseq [[what summary] {"fails on its side" (h/json-answer 503 {:title "Service Unavailable" :status 503})
                          "answers what is not JSON" {:status 200 :body "<html>oops</html>"}
                          "drops the connection" (fn [_request _default] (throw (ex-info "the api went away" {})))}]
    (testing what
      (with-cohort "pt-ana" {:summary summary} {}
        (fn [web]
          (let [{:keys [response page]} (home web)]
            (is (= 200 (:status response)))
            (is (= ["Good afternoon, Ana"] (map hx/words (hx/by-tag page :h1))) "who she is, the api said")
            (is (= {} (sections page)) "no section that could be taken for one with nothing in it")
            (is (= 1 (count (alert-words page))))
            (is (str/includes? (first (alert-words page)) "Your appointments and readings could not be loaded"))
            (is (str/includes? (first (alert-words page)) "The problem is on our side, not yours."))
            (is (not (str/includes? (first (alert-words page)) "The rest of the page")) "there is none")
            (is (= ["/"] (retry-links page)) "to try again is to ask for the page again")
            (is (= ["/api/me" "/api/patients/pt-ana/summary"] (distinct (api-paths web))) "the series need the summary, and are not asked for")
            (is (= [] (a11y/problems page {:page? true})))
            (is (= 1 (count (events response :pulse.web/part-lost))))
            (is (= :warn (:level (first (events response :pulse.web/part-lost)))))
            (is (= "summary" (:part (:data (first (events response :pulse.web/part-lost))))))))))))

(deftest the-log-says-why-the-summary-was-lost-and-nothing-the-api-said
  (with-cohort "pt-ana" {:summary (h/json-answer 503 {:title "Database password is hunter2" :status 503})} {}
    (fn [web]
      (let [{:keys [response]} (home web)
            [lost] (events response :pulse.web/part-lost)]
        (is (= {:part "summary" :status 503} (:data lost)))
        (is (not (str/includes? (pr-str (:signals response)) "hunter2")))))))

(deftest a-section-the-summary-says-it-could-not-load-is-a-message-in-its-place
  (let [summary (data/summary "pt-ana")]
    (testing "the appointments"
      (with-cohort "pt-ana" {:summary (h/json-answer (assoc summary :appointments nil :unavailable ["appointments"]))} {}
        (fn [web]
          (let [{:keys [page]} (home web)
                served (sections page)]
            (is (str/includes? (get served "next") "Your appointments could not be loaded"))
            (is (not (str/includes? (get served "next") "Hypertension follow-up")))
            (is (= ["/"] (retry-links page)))
            (is (= 3 (count (tile-values page))) "the readings are there")
            (is (contains? served "recent"))
            (is (= 1 (count (alert-words page))))))))
    (testing "some of the latest readings: the others are shown, and the message says so"
      (with-cohort "pt-ana" {:summary (h/json-answer (-> summary
                                                         (assoc-in [:vitals :heart-rate] nil)
                                                         (assoc :unavailable ["vitals.heart-rate"])))} {}
        (fn [web]
          (let [{:keys [page]} (home web)
                latest (get (sections page) "latest")]
            (is (str/includes? latest "Some of your readings could not be loaded"))
            (is (= ["177/100" "82.3"] (tile-values page)))
            (is (= #{"blood-pressure" "weight"}
                   (set (for [{:keys [query]} (drop 2 @(:api-requests web))] (second (re-find #"measurement=([a-z-]+)" query)))))
                "and no series is asked for what could not be loaded")
            (is (contains? (sections page) "recent"))))))
    (testing "all of them: there is no list of recent readings to show, and nobody is taken for a new patient"
      (with-cohort "pt-ana" {:summary (h/json-answer (-> summary
                                                         (assoc :vitals {:blood-pressure nil :heart-rate nil :glucose nil :spo2 nil :weight nil})
                                                         (assoc :unavailable (mapv #(str "vitals." %) ["blood-pressure" "heart-rate" "glucose" "spo2" "weight"]))))} {}
        (fn [web]
          (let [{:keys [page]} (home web)]
            (is (= ["Good afternoon, Ana"] (map hx/words (hx/by-tag page :h1))))
            (is (str/includes? (get (sections page) "latest") "Your latest readings could not be loaded"))
            (is (not (contains? (sections page) "recent")))
            (is (not (contains? (sections page) "first")))
            (is (not (str/includes? (hx/words page) "No readings yet")))
            (is (= ["/api/me" "/api/patients/pt-ana/summary"] (api-paths web)))))))))

(deftest a-series-the-api-cannot-give-is-a-message-in-the-list-of-recent-readings
  (with-cohort "pt-ana" {:series (fn [request default]
                                   (if (str/includes? (str (:query request)) "heart-rate")
                                     (h/json-answer 500 {:title "Internal Server Error" :status 500})
                                     default))} {}
    (fn [web]
      (let [{:keys [response page]} (home web)
            served (sections page)]
        (is (= 200 (:status response)))
        (is (str/includes? (get served "recent") "Your recent readings could not be loaded"))
        (is (= ["/"] (retry-links page)))
        (is (= ["177/100" "70" "82.3"] (tile-values page)) "the latest readings are the summary's, and are there")
        (is (contains? served "next"))
        (is (= [{:part "series heart-rate" :status 500}] (map :data (events response :pulse.web/part-lost))))))))

(deftest series-that-are-too-slow-are-a-message-and-do-not-hold-the-page
  (with-cohort "pt-ana" {:series (fn [_request default] (assoc default :delay-ms 1500))} {:deps {:series-timeout-ms 200}}
    (fn [web]
      (let [{:keys [response page elapsed-ms]} (home web)]
        (is (= 200 (:status response)))
        (is (str/includes? (get (sections page) "recent") "Your recent readings could not be loaded"))
        (is (< elapsed-ms 1200) "it did not wait for them")
        (is (= #{:timeout} (set (map (comp :reason :data) (events response :pulse.web/part-lost)))))
        (is (= 3 (count (events response :pulse.web/part-lost))) "one for each series that was not done")))))

(deftest the-series-are-asked-for-at-once
  (with-cohort "pt-ana" {:series (fn [_request default] (assoc default :delay-ms 400))} {}
    (fn [web]
      (let [{:keys [response page elapsed-ms]} (home web)]
        (is (= 200 (:status response)))
        (is (contains? (sections page) "recent"))
        (is (< elapsed-ms 1100) "three of 400 ms, one after the other, would take 1200")))))

;; ---------------------------------------------------------------------------
;; A failure that is the whole page's

(def ^:private failures-of-me
  "What the api may answer to who the person is, and the page that it is for: the status, and the title of the card."
  [[(h/json-answer 500 {:title "Internal Server Error" :status 500}) 502 "Something went wrong on our side"]
   [(h/json-answer 502 {:title "Bad Gateway" :status 502}) 502 "Something went wrong on our side"]
   [(h/json-answer 503 {:title "Service Unavailable" :status 503}) 502 "Something went wrong on our side"]
   [(h/json-answer 504 {:title "Gateway Timeout" :status 504}) 504 "Something went wrong on our side"]
   [{:status 200 :body "<html>not JSON</html>"} 502 "Something went wrong on our side"]
   [(fn [_request _default] (throw (ex-info "the api went away" {}))) 502 "Something went wrong on our side"]
   ;; the api refuses the portal's token, or does not know what it was asked: the portal is what is wrong
   [(h/json-answer 401 {:title "Unauthorized" :status 401}) 502 "Something went wrong on our side"]
   [(h/json-answer 404 {:title "Not Found" :status 404}) 502 "Something went wrong on our side"]
   [(h/json-answer 400 {:title "Bad Request" :status 400}) 502 "Something went wrong on our side"]
   ;; the api says that the person may not see it
   [(h/json-answer 403 {:title "Forbidden" :status 403}) 403 "You cannot see this"]])

(deftest when-the-api-cannot-say-who-the-person-is-the-page-is-a-page-for-the-failure
  (doseq [[answer status title] failures-of-me]
    (with-cohort "pt-ana" {:me answer} {}
      (fn [web]
        (let [{:keys [response page]} (home web)]
          (testing (pr-str (if (map? answer) (select-keys answer [:status]) :dropped))
            (is (= status (:status response)))
            (is (= [title] (map hx/words (hx/by-tag page :h1))))
            (is (= ["/api/me"] (distinct (api-paths web))) "and nothing else is asked for")
            (is (empty? (sections page)))
            (is (not (str/includes? (:text response) "Good afternoon")))
            (is (= [] (a11y/problems page {:page? true})))
            (is (= 1 (count (events response :pulse.web/api-failed))))
            (is (not (str/includes? (:text response) "<html>")) "nothing of what the api said")))))))

(deftest a-failure-of-the-api-is-logged-with-its-kind-and-never-with-what-the-api-said
  (testing "a failure on its side is a warning"
    (with-cohort "pt-ana" {:me (h/json-answer 503 {:title "Database password is hunter2" :status 503})} {}
      (fn [web]
        (let [{:keys [response]} (home web)
              [failed] (events response :pulse.web/api-failed)]
          (is (= :warn (:level failed)))
          (is (= {:failure "failed" :path "/api/me" :status 503 :problem {:title "Database password is hunter2"}} (:data failed))
              "the problem is what the api itself calls it; it is the api's own text, and a page never shows it")
          (is (not (str/includes? (:text response) "hunter2")))))))
  (testing "a failure that is the portal's own is an error"
    (with-cohort "pt-ana" {:me (h/json-answer 401 {:title "Unauthorized" :status 401})} {}
      (fn [web]
        (let [{:keys [response]} (home web)]
          (is (= :error (:level (first (events response :pulse.web/api-failed)))))))))
  (testing "an api that cannot be reached says how"
    (with-cohort "pt-ana" {:me (fn [_request _default] (throw (ex-info "the api went away" {})))} {}
      (fn [web]
        (let [{:keys [response]} (home web)
              [failed] (events response :pulse.web/api-failed)]
          (is (= :warn (:level failed)))
          (is (= {:failure "unreachable" :path "/api/me" :reason :connect} (:data failed))))))))

(deftest an-api-that-is-too-slow-is-a-504-and-does-not-hold-the-person-for-long
  (with-cohort "pt-ana" {:me (fn [_request default] (assoc default :delay-ms 1500))} {:api-timeout-ms 300}
    (fn [web]
      (let [{:keys [response page elapsed-ms]} (home web)]
        (is (= 504 (:status response)))
        (is (= ["Something went wrong on our side"] (map hx/words (hx/by-tag page :h1))))
        (is (< elapsed-ms 1200))
        (is (= {:failure "unreachable" :path "/api/me" :reason :timeout} (:data (first (events response :pulse.web/api-failed)))))))))

(deftest a-summary-or-a-series-that-is-refused-is-a-failure-of-the-whole-page
  (testing "the api says that she may not see her own summary"
    (with-cohort "pt-ana" {:summary (h/json-answer 403 {:title "Forbidden" :status 403})} {}
      (fn [web]
        (let [{:keys [response page]} (home web)]
          (is (= 403 (:status response)))
          (is (= ["You cannot see this"] (map hx/words (hx/by-tag page :h1))))
          (is (empty? (events response :pulse.web/part-lost)) "it is not a part that was lost")))))
  (testing "the api does not know the patient it said she was"
    (with-cohort "pt-ana" {:summary (h/json-answer 404 {:title "No such patient." :status 404})} {}
      (fn [web]
        (let [{:keys [response]} (home web)]
          (is (= 502 (:status response)))
          (is (= :error (:level (first (events response :pulse.web/api-failed)))))))))
  (testing "a series the api refuses"
    (with-cohort "pt-ana" {:series (fn [request default]
                                     (if (str/includes? (str (:query request)) "weight") (h/json-answer 403 {:title "Forbidden" :status 403}) default))} {}
      (fn [web]
        (let [{:keys [response page]} (home web)]
          (is (= 403 (:status response)))
          (is (= ["You cannot see this"] (map hx/words (hx/by-tag page :h1)))))))))

(deftest a-patient-whose-account-has-no-record-is-a-failure-on-our-side
  (doseq [fhir-user [nil "" "Practitioner/pr-rivera" "pt-ana" "Patient/"]]
    (with-cohort "pt-ana" {:me (h/json-answer (assoc (data/me "pt-ana") :fhirUser fhir-user))} {}
      (fn [web]
        (let [{:keys [response page]} (home web)]
          (testing (pr-str fhir-user)
            (is (= 500 (:status response)))
            (is (= ["Something went wrong on our side"] (map hx/words (hx/by-tag page :h1))))
            (is (= ["/api/me"] (api-paths web)) "no summary of nobody")
            (is (= 1 (count (events response :pulse.http/unhandled-exception))) "and it is for the clinic to find in the log")))))))

;; ---------------------------------------------------------------------------
;; The token of the person

(defn- providers-sessions-end! [web]
  (doseq [sid (keys (oidc-fake/sessions (:idp web)))]
    (oidc-fake/end-session! (:idp web) sid)))

(defn- signed-in-browser [web]
  (let [b (h/browser web)]
    (h/sign-in! b)
    b))

(deftest a-session-the-provider-ended-is-over-and-the-person-is-told
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [b (signed-in-browser web)]
        (providers-sessions-end! web)
        (clock/advance! (:clock web) (* 301 1000))
        (let [r (h/visit! b :get "/")]
          (is (= 401 (:status r)))
          (is (str/includes? (:text r) "Your session has ended"))
          (is (= {:sessions 0 :pending 0} (session/counts (:sessions web))))
          (is (nil? (h/cookie b session/cookie-name)))
          (is (empty? @(:api-requests web)) "no call was made with a token that could not be had"))))))

(deftest a-token-that-cannot-be-renewed-between-two-calls-ends-the-session-and-is-not-a-part-that-was-lost
  ;; the summary is asked for with the token the person has, and takes long enough for it to end: the series, all at once,
  ;; need another, and the provider has ended the session
  (with-web {}
    (fn [web]
      (at! web data/now-text)
      (reset! (:api-answer web)
              (cohort-api "pt-ana" :summary (fn [_request default] (clock/advance! (:clock web) (* 301 1000)) default)))
      (let [b (signed-in-browser web)]
        (providers-sessions-end! web)
        (let [r (h/visit! b :get "/")]
          (is (= 401 (:status r)))
          (is (str/includes? (:text r) "Your session has ended"))
          (is (= {:sessions 0 :pending 0} (session/counts (:sessions web))))
          (is (empty? (events r :pulse.web/part-lost)))
          (is (= :refresh-rejected (:reason (:data (first (events r :pulse.web/session-ended)))))))))))

(deftest a-provider-that-cannot-be-reached-between-two-calls-is-a-page-and-the-session-stays
  (with-web {}
    (fn [web]
      (at! web data/now-text)
      (reset! (:api-answer web)
              (cohort-api "pt-ana" :summary (fn [_request default]
                                              (idp/serve-token! (:idp web) {:status 503 :body "<html>down</html>"})
                                              (clock/advance! (:clock web) (* 301 1000))
                                              default)))
      (let [b (signed-in-browser web)
            cookie (h/cookie b session/cookie-name)
            r (h/visit! b :get "/")]
        (is (= 503 (:status r)))
        (is (str/includes? (:text r) "Sign-in is not available right now"))
        (is (not (str/includes? (:text r) "down")))
        (is (= {:sessions 1 :pending 0} (session/counts (:sessions web))) "the next try may work")
        (is (= cookie (h/cookie b session/cookie-name)))
        (is (empty? (events r :pulse.web/part-lost)))))))

;; ---------------------------------------------------------------------------
;; What the api says is text

(deftest what-the-api-says-is-text-whatever-it-holds
  (let [hostile "<script>alert(1)</script>"
        summary (data/summary "pt-ana")]
    (with-cohort "pt-ana" {:me (h/json-answer (-> (data/me "pt-ana")
                                                  (assoc-in [:resource :name 0 :given 0] "<img src=x onerror=alert(1)>")
                                                  (assoc-in [:generalPractitioner :name] hostile)))
                           :summary (h/json-answer (update summary :appointments (fn [as] (mapv #(assoc % :description hostile :reason hostile) as))))} {}
      (fn [web]
        (let [{:keys [response page]} (home web)]
          (is (= 200 (:status response)))
          (is (= ["Good afternoon, <img src=x onerror=alert(1)>"] (map hx/words (hx/by-tag page :h1))) "a name, as text")
          (is (empty? (hx/by-tag page :img)))
          (is (not (str/includes? (:text response) "<img")))
          (is (not (str/includes? (:text response) "<script>alert")))
          (is (str/includes? (:text response) "&lt;script&gt;alert(1)&lt;/script&gt;"))
          (is (= 1 (count (re-seq #"<script" (:text response)))) "the only script is htmx, a file of the portal"))))))

(deftest a-reading-that-says-nothing-is-left-out-and-never-fails-the-page
  (let [summary (data/summary "pt-ana")
        odd (-> summary
                ;; no number in a blood pressure: neither a tile nor the notice
                (assoc-in [:vitals :blood-pressure] {:id "bp" :at "2026-10-13T08:00:00Z" :systolic nil :diastolic 90 :unit "mm[Hg]" :band "very-high"})
                ;; a date and no time: the tile, and no time under it
                (assoc-in [:vitals :heart-rate] {:id "hr" :at "2026-10-13" :value 70 :unit "/min" :band "normal"})
                ;; no number and no time
                (assoc-in [:vitals :weight] {:id "w" :at nil :value nil :unit "kg" :band nil}))]
    (with-cohort "pt-ana" {:summary (h/json-answer odd)
                           :series (fn [request default]
                                     (if (str/includes? (str (:query request)) "heart-rate")
                                       (h/json-answer {:items [{:id "a" :measurement "heart-rate" :at "yesterday" :value 70 :unit "/min" :band "normal"}
                                                               {:id "b" :measurement "heart-rate" :at "2026-10-13T08:00:00Z" :value nil :unit "/min" :band nil}
                                                               {:id "c" :measurement "heart-rate" :at "2026-10-13T07:00:00Z" :value 66 :unit "/min" :band "normal"}]
                                                       :truncated false})
                                       default))} {}
      (fn [web]
        (let [{:keys [response page]} (home web)
              main-words (hx/words (main-of page))]
          (is (= 200 (:status response)))
          (is (= ["70"] (tile-values page)))
          (is (not (str/includes? main-words "Your last blood pressure reading is very high")))
          (is (= [] (a11y/problems page {:page? true})))
          (testing "a reading with no time that can be read, and one with no number, are not among the recent ones"
            (is (str/includes? (get (sections page) "recent") "66 bpm · Today, 09:00"))
            (is (not (str/includes? (get (sections page) "recent") "yesterday")))))))))

;; ---------------------------------------------------------------------------
;; Who gets it

(deftest somebody-who-is-not-signed-in-is-sent-to-sign-in-and-the-api-is-not-asked
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [r (h/call web :get "/")]
        (is (= 303 (:status r)))
        (is (= "/sign-in" (header r "Location"))))
      (let [r (h/call web :get "/" :headers {"hx-request" "true" "hx-current-url" "http://localhost:3300/"})]
        (is (= 401 (:status r)))
        (is (= "/sign-in" (header r "HX-Redirect"))))
      (is (empty? @(:api-requests web))))))

(deftest the-home-passes-the-checks-of-accessibility-a-program-can-make
  (testing "a patient with readings"
    (with-cohort "pt-ana" {} {}
      (fn [web]
        (is (= [] (a11y/problems (:page (home web)) {:page? true}))))))
  (testing "a new patient"
    (with-cohort "pt-noah" {} {}
      (fn [web]
        (is (= [] (a11y/problems (:page (home web :person :noah :patient "pt-noah")) {:page? true})))))))

(deftest a-new-patient-is-not-asked-about-any-series
  (with-cohort "pt-noah" {} {}
    (fn [web]
      (let [{:keys [page]} (home web :person :noah :patient "pt-noah")]
        (is (= ["/api/me" "/api/patients/pt-noah/summary"] (api-paths web)))
        (is (= ["Welcome, Noah"] (map hx/words (hx/by-tag page :h1))))))))

(deftest the-person-of-the-session-is-the-one-the-home-is-for
  (with-cohort "pt-noah" {} {}
    (fn [web]
      (let [{:keys [page]} (home web :person :noah :patient "pt-noah")]
        (is (= ["Account menu of Noah Kim"] (keep #(:aria-label (hx/attributes %)) (hx/by-tag page :summary))))))))
