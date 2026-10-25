(ns pulse.web.profile-test
  "The profile of the portal over the whole handler (ADR-0028): a person signs in, and the page is made of what the api answers for them
  in one call, with their own token; the way out is a form that posts with the token of the session and ends it. The stand-in for the api
  answers from the synthetic cohort of the mock-ups, so that what is served can be held to what the owner reviewed."
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
            [pulse.web.test.cohort :refer [alert-words api-paths api-requests csrf-of events main-of page-of read-back signed-in with-cohort]]
            [pulse.web.test.harness :as h :refer [header]]
            [ring.middleware.session.store :as store]))

(def ^:private context (build/context))

(defn- profile
  [web & {:keys [person headers] :or {person :ana}}]
  (let [b (signed-in web :person person)
        response (h/visit! b :get "/profile" :headers headers)]
    {:browser b :response response :page (page-of response)}))

(defn- card-words [page title]
  (some #(when (= title (hx/words (first (hx/by-tag % :h2)))) (hx/words %))
        (hx/select (main-of page) #(and (= :section (hx/tag %)) (hx/has-class? % "shadow-card")))))

;; ---------------------------------------------------------------------------
;; The profile of a patient

(deftest the-profile-is-made-of-what-the-api-answers-about-the-patient-in-one-call
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [{:keys [browser response page]} (profile web :headers {"x-request-id" "visit-9"})
            keeper (:tokens (store/read-session (:sessions web) (h/cookie browser session/cookie-name)))]
        (is (= 200 (:status response)))
        (is (= "text/html" (h/content-type response)))
        (is (= "no-store" (header response "Cache-Control")) "it is a patient's page")
        (is (= "Profile · Pulse" (hx/words (first (hx/by-tag page :title)))))
        (is (= ["Profile"] (map hx/words (hx/by-tag page :h1))))
        (is (str/includes? (card-words page "Who you are") "Ana García"))
        (is (str/includes? (card-words page "Who you are") "ana.garcia@pulse.test"))
        (is (str/includes? (card-words page "Who you are") "Date of birth14 March 1968Patient numberPULSE-0001CityValencia"))
        (is (str/includes? (card-words page "Your care team") "Dr. Elena RiveraInternal medicine"))
        (testing "the bar says where she is"
          (is (= [["Home" nil] ["Readings" nil] ["Appointments" nil] ["My record" nil] ["Profile" "page"]]
                 (map (juxt hx/words (comp :aria-current hx/attributes))
                      (hx/by-tag (first (hx/select page #(and (= :nav (hx/tag %)) (= "Main" (:aria-label (hx/attributes %)))))) :a)))))
        (testing "one call, with her own token and the id of the request; nothing that changes anything"
          (is (= ["/api/me"] (api-paths web)))
          (is (every? #(= (str "Bearer " (token/access-token keeper)) (get-in % [:headers "authorization"])) (api-requests web)))
          (is (every? #(= "visit-9" (get-in % [:headers "x-request-id"])) (api-requests web)))
          (is (every? #(= "GET" (:method %)) (api-requests web))))
        (is (= [] (a11y/problems page {:page? true})))))))

(deftest no-address-of-the-portal-names-a-patient-so-there-is-no-other-profile-to-ask-for
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (h/visit! (signed-in web) :get "/profile" :query "patient=pt-liam&id=Patient/pt-liam")
      (is (every? #(not (str/includes? (str (:path %) "?" (:query %)) "liam")) (api-requests web)))
      (is (= ["/api/me"] (api-paths web))))))

(deftest what-is-served-is-what-was-reviewed
  (let [mock (read-back (portal/profile context))]
    (with-cohort "pt-ana" {} {}
      (fn [web]
        (let [{:keys [page]} (profile web)]
          (is (= (hx/words (main-of mock)) (hx/words (main-of page))) "the same words, in the same order")
          (is (= (count (hx/by-tag (main-of mock) :dd)) (count (hx/by-tag (main-of page) :dd)))))))))

(deftest the-profile-is-the-one-of-the-person-who-signed-in
  (with-cohort "pt-noah" {} {}
    (fn [web]
      (let [{:keys [page]} (profile web :person :noah)]
        (is (str/includes? (card-words page "Who you are") "Noah Kim"))
        (is (str/includes? (card-words page "Your care team") "Dr. Chidi Okafor"))
        (is (not (str/includes? (hx/words page) "Ana García")))
        (is (= ["Account menu of Noah Kim"] (keep #(:aria-label (hx/attributes %)) (hx/by-tag page :summary))))))))

(deftest a-visitor-is-sent-to-sign-in-and-brought-back-to-the-profile
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [response (h/call web :get "/profile")]
        (is (= 303 (:status response)))
        (is (= "/sign-in?return-to=%2Fprofile" (header response "Location")))
        (is (empty? (api-requests web)))))))

(deftest a-method-the-route-does-not-have-is-not-allowed
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [b (signed-in web)]
        (doseq [method [:post :put :delete :patch]]
          (let [response (h/visit! b method "/profile")]
            (is (= 405 (:status response)) (str method))
            (is (= #{"GET"} (set (str/split (header response "Allow") #", "))) (str method))))
        (is (empty? (api-requests web)))))))

;; ---------------------------------------------------------------------------
;; The way out

(deftest the-profile-signs-the-person-out-with-a-form-that-carries-the-token
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [{:keys [browser page]} (profile web)
            forms (hx/by-tag (main-of page) :form)
            token (csrf-of web browser)]
        (is (= 1 (count forms)))
        (is (= {:method "post" :action "/sign-out"} (hx/attributes (first forms))))
        (is (= [{:type "hidden" :name "__anti-forgery-token" :value token}] (map hx/attributes (hx/by-tag (first forms) :input))))
        (testing "and posting it ends the session and leaves for the identity provider"
          (let [response (h/visit! browser :post "/sign-out" :form {"__anti-forgery-token" token})]
            (is (= 303 (:status response)))
            (is (str/includes? (header response "Location") "/logout"))
            (is (= {:sessions 0 :pending 0} (session/counts (:sessions web))))))))))

;; ---------------------------------------------------------------------------
;; What the api could not give

(deftest a-resource-the-api-does-not-have-is-a-message-in-the-first-card-and-the-doctor-and-the-way-out-stay
  (doseq [me [(assoc (data/me "pt-ana") :resource nil) (dissoc (data/me "pt-ana") :resource)]]
    (with-cohort "pt-ana" {:me (h/json-answer me)} {}
      (fn [web]
        (let [{:keys [response page]} (profile web)]
          (is (= 200 (:status response)))
          (is (= 1 (count (alert-words page))))
          (is (str/includes? (first (alert-words page)) "Your details could not be loaded"))
          (is (str/includes? (card-words page "Your care team") "Dr. Elena Rivera"))
          (is (= 1 (count (hx/by-tag (main-of page) :form))))
          (is (= [] (a11y/problems page {:page? true}))))))))

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
        (let [{:keys [response page]} (profile web)]
          (testing (pr-str (if (map? answer) (select-keys answer [:status]) :dropped))
            (is (= status (:status response)))
            (is (= [title] (map hx/words (hx/by-tag page :h1))))
            (is (empty? (hx/by-tag (main-of page) :dl)) "nothing of a profile")
            (is (= [] (a11y/problems page {:page? true})))
            (is (= 1 (count (events response :pulse.web/api-failed))))))))))

(deftest an-api-that-is-too-slow-is-a-504
  (with-cohort "pt-ana" {:me (fn [_request default] (assoc default :delay-ms 1500))} {:api-timeout-ms 300}
    (fn [web]
      (let [{:keys [response]} (profile web)]
        (is (= 504 (:status response)))
        (is (= :timeout (get-in (first (events response :pulse.web/api-failed)) [:data :reason])))))))

;; ---------------------------------------------------------------------------
;; The session

(deftest a-session-that-ended-is-told-so-and-the-api-is-not-asked
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [b (signed-in web)]
        (clock/advance! (:clock web) (* 31 60 1000))
        (let [response (h/visit! b :get "/profile")]
          (is (= 401 (:status response)))
          (is (= ["Your session has ended"] (map hx/words (hx/by-tag (page-of response) :h1))))
          (is (empty? (api-requests web))))))))

;; ---------------------------------------------------------------------------
;; What the api says is text

(deftest what-the-api-says-is-text-whatever-it-holds
  (let [hostile "<script>alert(1)</script>"]
    (with-cohort "pt-ana" {:me (h/json-answer (-> (data/me "pt-ana")
                                                  (assoc-in [:resource :name] [{:given [hostile] :family hostile}])
                                                  (assoc-in [:resource :address] [{:city hostile}])
                                                  (assoc-in [:generalPractitioner :name] hostile)))} {}
      (fn [web]
        (let [{:keys [response page]} (profile web)]
          (is (= 200 (:status response)))
          (is (not (str/includes? (:text response) "<script>alert")))
          (is (str/includes? (:text response) "&lt;script&gt;alert(1)&lt;/script&gt;"))
          (is (= 1 (count (re-seq #"<script" (:text response)))) "the only script is htmx, a file of the portal")
          (is (= [] (a11y/problems page {:page? true}))))))))
