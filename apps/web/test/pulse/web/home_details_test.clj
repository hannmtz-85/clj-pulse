(ns pulse.web.home-details-test
  "What a mutation analysis found that the tests of the home through the handler did not notice: the token of the session that the
  page carries, what is logged for an account with no record, and a moment that is not a whole second."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [pulse.design.data :as data]
            [pulse.platform.test.clock :as clock]
            [pulse.platform.test.idp :as idp]
            [pulse.ui.test.hiccup :as hx]
            [pulse.ui.test.html :as parsed]
            [pulse.web.session :as session]
            [pulse.web.test.harness :as h :refer [with-web]]
            [ring.middleware.session.store :as store]))

(defn- cohort-api [patient-id & {:keys [me]}]
  (fn [{:keys [path query]}]
    (let [params (idp/form-of query)]
      (cond
        (= "/readyz" path) h/api-ready
        (= "/api/me" path) (or me (h/json-answer (data/me patient-id)))
        (= (str "/api/patients/" patient-id "/summary") path) (h/json-answer (data/summary patient-id))
        (= (str "/api/patients/" patient-id "/observations") path)
        (h/json-answer (data/series patient-id (keyword (get params "measurement")) (parse-long (get params "limit"))))
        :else (h/json-answer 404 {:title "Not Found"})))))

(defn- signed-in-home [web]
  (let [b (h/browser web)]
    (h/sign-in! b)
    {:browser b :response (h/visit! b :get "/")}))

(deftest the-page-carries-the-token-of-the-session-for-the-way-out-and-for-htmx
  (with-web {}
    (fn [web]
      (reset! (:api-answer web) (cohort-api "pt-ana"))
      (clock/set-time! (:clock web) data/now-text)
      (let [{:keys [browser response]} (signed-in-home web)
            token (:csrf (store/read-session (:sessions web) (h/cookie browser session/cookie-name)))
            page (parsed/document (:text response))
            body (first (hx/by-tag page :body))]
        (is (re-matches #"[A-Za-z0-9_-]{43}" token))
        (is (= [{:type "hidden" :name "__anti-forgery-token" :value token}]
               (map hx/attributes (hx/by-tag (first (hx/by-tag page :form)) :input)))
            "the way out has it, as the check of the request reads it")
        (is (str/includes? (:hx-headers (hx/attributes body)) token) "and so has htmx, for what it asks of the portal")))))

(deftest an-account-with-no-record-is-told-to-the-log-with-the-words-of-the-failure
  (with-web {}
    (fn [web]
      (reset! (:api-answer web) (cohort-api "pt-ana" :me (h/json-answer (assoc (data/me "pt-ana") :fhirUser nil))))
      (let [{:keys [response]} (signed-in-home web)
            [event] (filter #(= :pulse.http/unhandled-exception (:id %)) (:signals response))]
        (is (= 500 (:status response)))
        (is (= "The api knows no record of this patient" (ex-message (:error event))))
        (is (= :pulse.web.home/no-patient-record (:type (ex-data (:error event)))))))))

(deftest a-moment-that-is-not-a-whole-second-is-no-matter
  ;; the clock of a real portal says nanoseconds, and the page and the lifecycle read them all the same
  (with-web {}
    (fn [web]
      (reset! (:api-answer web) (cohort-api "pt-ana"))
      (clock/set-time! (:clock web) "2026-10-13T12:00:00.987654321Z")
      (let [{:keys [response]} (signed-in-home web)
            page (parsed/document (:text response))]
        (is (= 200 (:status response)))
        (is (= ["Good afternoon, Ana"] (map hx/words (hx/by-tag page :h1))))
        (is (str/includes? (hx/words page) "Tuesday, 13 October 2026"))
        (is (str/includes? (hx/words page) "Today, 10:00") "the latest readings are of this morning")))))
