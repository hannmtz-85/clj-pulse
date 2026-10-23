(ns pulse.web.test.cohort
  "A portal in front of a stand-in for the api that answers from the synthetic cohort of the mock-ups, at the moment of the
  mock-ups, and as the api does to a reading that is posted to it, so that what is served can be held to what the owner reviewed;
  and a person's browser signed in to it. What the tests of the pages of a patient share (`pulse.web.test.harness` has the
  portal, the fake provider and the browser).

  Test code only: it is on the classpath of the `:test` alias and nowhere else."
  (:require [jsonista.core :as json]
            [pulse.api.observations :as api-observations]
            [pulse.design.data :as data]
            [pulse.fhir.measurement :as measurement]
            [pulse.platform.test.clock :as clock]
            [pulse.platform.test.idp :as idp]
            [pulse.platform.test.oidc :as oidc-fake]
            [pulse.ui.test.hiccup :as hx]
            [pulse.ui.test.html :as parsed]
            [pulse.web.html :as html]
            [pulse.web.session :as session]
            [pulse.web.test.harness :as h :refer [with-web]]
            [ring.middleware.session.store :as store])
  (:import (java.time Duration Instant)))

(defn as-the-api-records
  "What the api answers to the POST of `body-text` (JSON) for `patient-id`: a 201 with the reading as it stored it, or the 422 for a
  reading that cannot be one, as `pulse.fhir.measurement` and the window of time say. `now` is the instant it is."
  [patient-id body-text now]
  (let [body (json/read-value body-text json/keyword-keys-object-mapper)
        kind (keyword (:measurement body))
        {:keys [ok errors]} (measurement/normalize (assoc body :measurement kind))
        at (or (:at body) now)
        too-late? (.isAfter (Instant/parse at) (.plus (Instant/parse now) (Duration/ofMinutes api-observations/max-future-minutes)))
        too-early? (.isBefore (Instant/parse at) (.minus (Instant/parse now) (Duration/ofDays api-observations/max-age-days)))
        errors (cond-> (or errors {})
                 too-late? (assoc :at ["must not be in the future"])
                 too-early? (assoc :at [(str "must not be more than " api-observations/max-age-days " days ago")]))]
    (if (seq errors)
      (h/json-answer 422 {:type "about:blank" :title "Unprocessable Content" :status 422
                          :detail "The reading cannot be recorded as sent." :errors errors})
      (let [observation (measurement/observation ok {:id "obs-new" :patient patient-id :at at :performer (str "Patient/" patient-id)})]
        (h/json-answer 201 (assoc (api-observations/reading-view kind observation)
                                  :measurement (name kind) :by (str "Patient/" patient-id)))))))

(defn cohort-api
  "What the stand-in for the api answers: the cohort, as the mock-ups have it, for `patient-id`. `:me`, `:summary`, `:series` and
  `:record` are what to answer instead for that endpoint (`:record` is for the POST of a reading): a response, or a function of the
  request and of what it would have answered. `:now` is a function that gives the instant it is, as text, by which a reading that is
  posted is judged (the moment of the mock-ups by default)."
  [patient-id & {:keys [me summary series record now] :or {now (constantly data/now-text)}}]
  (fn [{:keys [method path query body] :as request}]
    (let [params (idp/form-of query)
          answered (fn [override default] (cond (nil? override) default (map? override) override :else (override request default)))
          observations (str "/api/patients/" patient-id "/observations")]
      (cond
        (= "/readyz" path) h/api-ready
        (= "/api/me" path) (answered me (h/json-answer (data/me patient-id)))
        (= (str "/api/patients/" patient-id "/summary") path) (answered summary (h/json-answer (data/summary patient-id)))
        (and (= "GET" method) (= observations path))
        (answered series (h/json-answer (data/series patient-id (keyword (get params "measurement"))
                                                     {:from (get params "from") :limit (parse-long (get params "limit"))})))
        (and (= "POST" method) (= observations path))
        (answered record (as-the-api-records patient-id body (now)))
        :else (h/json-answer 404 {:title "Not Found" :status 404})))))

(defn at!
  "Sets the clock of the portal, and of the fake provider, to `instant`."
  [web instant]
  (clock/set-time! (:clock web) instant))

(defn with-cohort
  "Calls `(f web)` with a portal whose api answers the cohort for `patient-id` (and what `overrides` say, see `cohort-api`), at the
  moment of the mock-ups. `options` are those of `pulse.web.test.harness/with-web`."
  [patient-id overrides options f]
  (with-web options
    (fn [web]
      (reset! (:api-answer web) (apply cohort-api patient-id :now #(str (clock/instant (:clock web))) (mapcat identity overrides)))
      (at! web data/now-text)
      (f web))))

(defn signed-in
  "A browser that has signed in as `person` (a key of `pulse.platform.test.oidc/people`, Ana by default)."
  [web & {:keys [person] :or {person :ana}}]
  (when-not (= :ana person) (oidc-fake/sign-in-as! (:idp web) (get oidc-fake/people person)))
  (let [b (h/browser web)]
    (h/sign-in! b)
    b))

(defn csrf-of
  "The CSRF token of the session of `browser`."
  [web browser]
  (session/csrf-token-of {:session (store/read-session (:sessions web) (h/cookie browser session/cookie-name))}))

(defn page-of
  "The page of a response, read back from the HTML that was served."
  [response]
  (parsed/document (:text response)))

(defn main-of [page] (first (hx/by-tag page :main)))

(defn read-back
  "A mock-up read back from the HTML that is written of it, as a served page is: the same renderer and the same reader, so that what
  is compared is the words and not how a space between two inline elements was made."
  [hiccup]
  (parsed/document (html/render hiccup)))

(defn events
  "The events of the log of a response with the id `id`."
  [response id]
  (filter #(= id (:id %)) (:signals response)))

(defn api-requests
  "The requests the stand-in for the api got, in the order they came."
  [web]
  @(:api-requests web))

(defn api-paths [web] (map :path (api-requests web)))

(defn queries
  "`[path query-parameters]` of each request the stand-in got, the parameters as a map."
  [web]
  (for [{:keys [path query]} (api-requests web)]
    [path (into (sorted-map) (idp/form-of query))]))

(defn alert-words
  "The words of every message of the page that says that something could not be loaded or saved."
  [page]
  (map hx/words (hx/select (main-of page) #(and (= :div (hx/tag %)) (hx/has-class? % "bg-tone-high-soft")))))

(defn notice-words
  "The words of every message of the page that tells something good, such as that a reading was saved."
  [page]
  (map hx/words (hx/select (main-of page) #(= "status" (:role (hx/attributes %))))))

(defn pills
  "`[label current?]` of each pill of the navigation named `label`."
  [page label]
  (let [nav (first (hx/select page #(and (= :nav (hx/tag %)) (= label (:aria-label (hx/attributes %))))))]
    (for [a (hx/by-tag nav :a)]
      [(hx/words a) (= "page" (:aria-current (hx/attributes a)))])))

(defn field-value
  "What the field with the id `id` of the page holds."
  [page id]
  (:value (hx/attributes (hx/by-id page id))))
