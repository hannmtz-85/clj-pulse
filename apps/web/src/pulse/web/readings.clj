(ns pulse.web.readings
  "The readings of the portal, and recording one (ADR-0028): the handlers of `GET /readings`, `GET /readings/new` and
  `POST /readings/new`. The portal asks the api, with the patient's own token, who they are (`/api/me`), which measurements they
  have (`/api/patients/{id}/summary`) and the readings of the one they look at (`/api/patients/{id}/observations`), and records
  a reading by posting it there. Which patient is never in an address of the portal: it is the one the api says the token is for,
  so there is no other record to ask for.

  The trend (`pulse.web.pages.readings`) can go on without the summary, which only says what to offer, and says when it could not
  have the series; who the patient is cannot be done without. Recording is the api's to judge (`pulse.web.recording` only turns
  text into what it takes): what it refuses is written beside the fields, and nothing the patient typed is lost, whatever
  happens. A reading that was saved is told on the page it redirects to, once, through the session (`pulse.web.session/with-flash`).
  A failure of the api while the reading was on its way is said as what it is: it may be saved, and the patient is told to look
  before they send it again."
  (:require [pulse.platform.http.client :as http]
            [pulse.web.api :as api]
            [pulse.web.authz :as authz]
            [pulse.web.errors :as errors]
            [pulse.web.format :as fmt]
            [pulse.web.html :as html]
            [pulse.web.pages :as pages]
            [pulse.web.pages.reading-form :as reading-form]
            [pulse.web.pages.readings :as readings]
            [pulse.web.recording :as recording]
            [pulse.web.return-to :as return-to]
            [pulse.web.session :as session]
            [pulse.web.views :as views])
  (:import (java.time Clock Duration Instant)
           (java.time.temporal ChronoUnit)))

(def ^:private default-kind
  "What the form is for when no measurement is asked for."
  (first views/kinds))

(defn- text-param
  "The query parameter `k` of `request` when it is text: one that comes twice is a vector, and is not."
  [request k]
  (let [value (get-in request [:query-params k])]
    (when (string? value) value)))

(defn- days-of
  "The period asked for, one of `readings/periods` written as the page writes it (`7`, and not `+7` or `07`), or the default."
  [request]
  (let [text (text-param request "days")]
    (or (first (filter #(= text (str %)) readings/periods))
        readings/default-days)))

(defn- now-of
  "The instant it is, to the second."
  ^Instant [^Clock clock]
  (.truncatedTo (Instant/now clock) ChronoUnit/SECONDS))

(defn- framed
  "The page `content` in the frame of a patient's page."
  [request title content]
  (pages/page {:title title :current :readings :person (authz/person request) :csrf-token (session/csrf-token-of request)}
              content))

;; ---------------------------------------------------------------------------
;; The readings

(defn trend
  "The handler of `GET /readings`: the trend of the measurement in `measurement`, over the last `days` days. Without a measurement it
  is the first the patient has; without days, 30. `deps` has the `:api`, the portal's client of the api, and the `:clock` (a
  `java.time.Clock`, the system's by default)."
  [{:keys [api clock]}]
  (let [^Clock clock (or clock (Clock/systemUTC))]
    (fn [request]
      (let [me (api/get-json api request "/api/me")
            id (api/patient-id me)
            summary (api/degrading "summary" #(api/get-json api request (http/path "api" "patients" id "summary")))
            kind (or (recording/kind-of (text-param request "measurement"))
                     (first (readings/offered-kinds summary nil)))
            days (days-of request)
            now (now-of clock)
            series (when kind
                     (api/degrading (str "series " (name kind))
                                    #(api/get-json api request (http/path "api" "patients" id "observations")
                                                   {:measurement (name kind)
                                                    :from (str (.minus now (Duration/ofDays days)))
                                                    :limit readings/series-limit})))
            saved (session/flash-of request)
            response (html/response
                      (framed request "Readings"
                              (readings/content {:now (str now) :kind kind :days days :summary summary :series series :saved saved}
                                                {:readings pages/readings-path
                                                 :new-reading pages/new-reading-path
                                                 :retry (return-to/of-request request)})))]
        (cond-> response
          saved (session/clear-flash request))))))

;; ---------------------------------------------------------------------------
;; Recording one

(defn- form-page
  "The page of the form, with `status`, for a `model` of `pulse.web.pages.reading-form`."
  [request status model]
  (html/response status
                 (framed request "Record a reading"
                         (reading-form/content (assoc model :csrf-token (session/csrf-token-of request))
                                               {:new-reading pages/new-reading-path :readings pages/readings-path}))))

(defn form
  "The handler of `GET /readings/new`: the form to record a reading of the measurement in `measurement` (the first, without one),
  for the time that it is now. It asks the api for nothing. `deps` has the `:clock`."
  [{:keys [clock]}]
  (let [^Clock clock (or clock (Clock/systemUTC))]
    (fn [request]
      (let [now (str (now-of clock))]
        (form-page request 200 {:kind (or (recording/kind-of (text-param request "measurement")) default-kind)
                                :values {:taken (fmt/local-input now)}
                                :errors {}
                                :now now})))))

(defn- attempt
  "`{:done (f)}`, or `{:failed e}` when `f` fails in a way that a page can go on after (`pulse.web.api/degradable?`): the failure is
  in the log. Another failure is thrown."
  [f]
  (try
    {:done (f)}
    (catch clojure.lang.ExceptionInfo e
      (if (api/degradable? e)
        (do (errors/log-api-failure! e) {:failed e})
        (throw e)))))

(defn- form-again
  "The form again, for `kind`, with what was typed in `form` and why the reading is not saved: `failure` is the kind of the page
  (see `pulse.web.pages.reading-form`) and `field-errors` the words beside the fields."
  [request status kind form now failure field-errors]
  (form-page request status {:kind kind :values (recording/typed kind form) :errors field-errors :failure failure :now now}))

(defn- failed-with
  "The status of the form that answers a failure of the api: a gateway timeout when it was too slow, a bad gateway otherwise."
  [e]
  (if (api/timeout? e) 504 502))

(defn- saved
  "The answer to a reading that was saved: a 303 to the readings of its measurement, and the reading `created`, as the api stored it,
  left in the session for that page to tell once."
  [request kind created]
  (-> {:status 303
       :headers {"Location" (str pages/readings-path "?measurement=" (name kind))}
       :body ""}
      (session/with-flash request created)))

(defn- post-reading
  "Asks the api who the person is and posts `body`, a reading of `kind`, to their record, and answers: the form again when the api
  failed (`:unsaved` if nothing was sent, `:unconfirmed` if the reading was on its way) or refused it, and `saved` when it took it."
  [api request kind form now body]
  (let [again (fn [status failure field-errors] (form-again request status kind form now failure field-errors))
        me (attempt #(api/get-json api request "/api/me"))]
    (if-let [e (:failed me)]
      (again (failed-with e) :unsaved {})
      (let [id (api/patient-id (:done me))
            sent (attempt #(api/post-json api request (http/path "api" "patients" id "observations") body))]
        (if-let [e (:failed sent)]
          (again (failed-with e) :unconfirmed {})
          (let [{:keys [created refused]} (:done sent)]
            (if refused
              (again 422 :refused (recording/refusal kind (:errors refused)))
              (saved request kind created))))))))

(defn record
  "The handler of `POST /readings/new`: records the reading the form holds. What cannot be read from the form (a number that is not
  one) is said beside the field without asking the api; the rest is the api's to judge. A reading that was saved is a 303 to its
  trend, which says so. One that was refused is the form again, with a 422, what was typed and the api's words beside the fields.
  A failure of the api is the form again too, with 502 or 504: `:unsaved` when nothing was sent, and `:unconfirmed` when the
  reading was on its way, since it may have been saved. `deps` has the `:api` and the `:clock`."
  [{:keys [api clock]}]
  (let [^Clock clock (or clock (Clock/systemUTC))]
    (fn [request]
      (let [form (:form-params request)
            now (str (now-of clock))
            kind (recording/kind-of (get form "measurement"))]
        (if (nil? kind)
          ;; a form the portal did not make: the page again, for the first measurement and with nothing in it
          (form-page request 422 {:kind default-kind :values {:taken (fmt/local-input now)} :errors {} :failure :refused :now now})
          (let [{:keys [body errors]} (recording/submission kind form)]
            (if errors
              (form-again request 422 kind form now :refused errors)
              (post-reading api request kind form now body))))))))
