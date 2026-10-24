(ns pulse.web.appointments
  "The appointments of the portal, and asking for a visit and cancelling one (ADR-0028): the handlers of `GET /appointments`,
  `POST /appointments`, `GET /appointments/{id}/cancel` and `POST /appointments/{id}/cancel`. The portal asks the api, with the
  patient's own token, who they are (`/api/me`: their doctor) and for their appointments (`/api/appointments`, which is the patient's own
  by the scope of the token), asks for a visit by posting to the same address, and cancels by patching one of them. Which patient it
  is never in an address of the portal: it is the one the api says the token is for; and an appointment that is not in their list is
  an appointment the portal cannot find, whoever's it is.

  The list (`pulse.web.pages.appointments`) can say that it could not be had, and the form is there all the same. Cancelling needs the
  appointment, so a failure to read it is a page for the failure. What the lifecycle says a patient may not do (`pulse.fhir.appointment`,
  ADR-0027) is said before the api is asked, and again by the api (a 409) when it changed meanwhile. The request, the cancellation and
  what they say when something goes wrong are the api's to judge (`pulse.web.visits` only turns text into what it takes): what it
  refuses is written beside the fields, and nothing the patient typed is lost. What was done is told on the page it redirects to, once,
  through the session (`pulse.web.session/with-flash`). A failure of the api while a request was on its way is said as what it is: it may
  have been sent, and the patient is told to look before they send it again; cancelling twice does no harm."
  (:require [pulse.platform.http.client :as http]
            [pulse.web.api :as api]
            [pulse.web.authz :as authz]
            [pulse.web.errors :as errors]
            [pulse.web.html :as html]
            [pulse.web.pages :as pages]
            [pulse.web.pages.appointments :as page]
            [pulse.web.return-to :as return-to]
            [pulse.web.session :as session]
            [pulse.web.views :as views]
            [pulse.web.visits :as visits])
  (:import (java.time Clock Instant)
           (java.time.temporal ChronoUnit)))

(defn- now-of
  "The instant it is, to the second, as text."
  [^Clock clock]
  (str (.truncatedTo (Instant/now clock) ChronoUnit/SECONDS)))

(defn- links-of
  "Where the buttons of the pages lead, for `request`."
  [request]
  {:appointments pages/appointments-path
   :request-visit pages/request-visit-path
   :cancel-appointment (comp pages/cancel-path :id)
   :cancel-request (comp pages/cancel-path :id)
   :retry (return-to/of-request request)})

(defn- respond
  "The page of the appointments, with `status`, in the frame of a patient's page: `render` is `page/content` or `page/cancel-content`
  and `model` what it is a function of, to which the common parts are added."
  [request status title render now me appointments model]
  (html/response status
                 (pages/page {:title title :current :appointments :person (authz/person request) :csrf-token (session/csrf-token-of request)}
                             (render (assoc model :now now :me me :appointments appointments :csrf-token (session/csrf-token-of request))
                                     (links-of request)))))

(defn- me-of [api request]
  (api/get-json api request "/api/me"))

(defn- appointments-from
  "The newest appointments of the patient, as the api gives them."
  [api request]
  (api/get-json api request "/api/appointments" {:sort "-start" :limit page/list-limit}))

(defn- appointments-or-nil
  "`appointments-from`, or nil when the api could not give them and the page can go on without."
  [api request]
  (api/degrading "appointments" #(appointments-from api request)))

(defn- found
  "The appointment of `appointments`, the answer of the api, with the id `id`, or nil."
  [appointments id]
  (some #(when (= id (:id %)) %) (:items appointments)))

(defn- see-other
  "A 303 to the appointments, with `notice` left in the session for that page to tell once."
  [request notice]
  (session/with-flash {:status 303 :headers {"Location" pages/appointments-path} :body ""} request {:appointment notice}))

;; ---------------------------------------------------------------------------
;; The appointments

(defn list-page
  "The handler of `GET /appointments`: the appointments of the patient, in the sections of `pulse.web.views/appointment-sections`, and the
  form to ask for a visit; with what has just been done, if something has, told once. Who the patient is cannot be done without; the
  list can, and says when it could not be had. `deps` has the `:api`, the portal's client of the api, and the `:clock` (a
  `java.time.Clock`, the system's by default)."
  [{:keys [api clock]}]
  (let [^Clock clock (or clock (Clock/systemUTC))]
    (fn [request]
      (let [me (me-of api request)
            appointments (appointments-or-nil api request)
            told (:appointment (session/flash-of request))
            response (respond request 200 "Appointments" page/content (now-of clock) me appointments {:notice told})]
        (cond-> response
          told (session/clear-flash request))))))

;; ---------------------------------------------------------------------------
;; Asking for a visit

(defn- request-again
  "The appointments again, for a request that was not sent: `form` is what the form holds and why it was not sent."
  [api request clock status me form]
  (respond request status "Appointments" page/content (now-of clock) me (appointments-or-nil api request) {:form form}))

(defn request-visit
  "The handler of `POST /appointments`: asks for a visit with the reason and the days the form holds, which go to the patient's doctor.
  What cannot be read from the form (no reason, a day that is not one) is said beside the field without asking the api; the rest is the
  api's to judge. A request that was sent is a 303 to the appointments, which say so. One that was refused is the page again, with a 422,
  what was typed and the api's words beside the fields. A patient with no doctor is told so (409). A failure of the api is the page again
  too, with 502 or 504: `:unsaved` when nothing was sent, and `:unconfirmed` when the request was on its way, since it may have been sent.
  `deps` has the `:api` and the `:clock`."
  [{:keys [api clock]}]
  (let [^Clock clock (or clock (Clock/systemUTC))]
    (fn [request]
      (let [form (:form-params request)
            typed (visits/typed form)
            again (fn [status me form-state] (request-again api request clock status me form-state))
            {:keys [body errors]} (visits/submission form)
            me (errors/attempt #(me-of api request))]
        (cond
          (:failed me)
          (again (errors/gateway-status (:failed me)) nil {:values typed :failure :unsaved})

          errors
          (again 422 (:done me) {:values typed :errors errors :failure :refused})

          :else
          (let [sent (errors/attempt #(api/post-json api request "/api/appointments" body #{409}))]
            (if-let [e (:failed sent)]
              (again (errors/gateway-status e) (:done me) {:values typed :failure :unconfirmed})
              (let [{:keys [created refused]} (:done sent)]
                (cond
                  (nil? refused) (see-other request {:requested created})
                  (= 409 (:status refused)) (again 409 (:done me) {:values typed :failure :no-doctor})
                  :else (again 422 (:done me) {:values typed :errors (visits/refusal (:errors refused)) :failure :refused}))))))))))

;; ---------------------------------------------------------------------------
;; Cancelling

(defn- blocked-again
  "The appointments again, with a 409, for a cancellation that cannot be done, and why."
  [request clock me appointments reason]
  (respond request 409 "Appointments" page/content (now-of clock) me appointments {:notice {:blocked reason}}))

(defn- question
  "The appointments with the question before `target` is cancelled over them, with `status`; `why` is what the field for why holds, and
  why the cancellation did not go through, if it did not."
  [request clock status me appointments target why]
  (respond request status "Cancel appointment" page/cancel-content (now-of clock) me appointments {:target target :why why}))

(defn cancel-question
  "The handler of `GET /appointments/{id}/cancel`: asks whether to cancel the appointment, or the request, with that id: what it is, what
  cancelling does, and a field to say why, if the patient wants. An appointment that is not in the list of the patient is not found
  (404), and one that cannot be cancelled (it is cancelled, or has begun) is the appointments with the reason (409). Reading the
  appointment is what the question is made of: a failure to is a page for the failure. `deps` has the `:api` and the `:clock`."
  [{:keys [api clock]}]
  (let [^Clock clock (or clock (Clock/systemUTC))]
    (fn [request]
      (let [id (get-in request [:path-params :id])
            me (me-of api request)
            appointments (appointments-from api request)
            target (found appointments id)
            now (now-of clock)]
        (cond
          (nil? target) (errors/failure :not-found request)
          (views/cancel-blocker target now) (blocked-again request clock me appointments (views/cancel-blocker target now))
          :else (question request clock 200 me appointments target nil))))))

(defn- why-it-was-not-cancelled
  "Why the api would not cancel `id`, that it answered 409 to, from what the appointments are now: what the lifecycle says of it, or that it
  changed while the patient was looking at it. Nil appointments (they could not be read again) are the second."
  [appointments id now]
  (or (when-let [target (found appointments id)]
        (views/cancel-blocker target now))
      :changed))

(defn cancel
  "The handler of `POST /appointments/{id}/cancel`: cancels the appointment, or the request, with that id, for the reason the form holds,
  if it holds one. A cancellation is a 303 to the appointments, which say so. One the api refuses for the reason (a 400 or a 422) is the
  question again, with a 422 and the api's words beside the field. An appointment that is not in the list of the patient is not found
  (404); one that cannot be cancelled (it is cancelled, or has begun, as the list says, or as the api does with a 409) is the
  appointments with the reason (409). A failure of the api with the cancellation on its way is the question again, with 502 or 504,
  that says that nobody knows if it was done: cancelling twice does no harm, as the second says that it was cancelled. Reading the
  appointment comes first, and a failure to is a page for the failure. `deps` has the `:api` and the `:clock`."
  [{:keys [api clock]}]
  (let [^Clock clock (or clock (Clock/systemUTC))]
    (fn [request]
      (let [id (get-in request [:path-params :id])
            form (:form-params request)
            typed {:values {:why (visits/why-typed form)}}
            me (me-of api request)
            appointments (appointments-from api request)
            target (found appointments id)
            now (now-of clock)]
        (cond
          (nil? target)
          (errors/failure :not-found request)

          (views/cancel-blocker target now)
          (blocked-again request clock me appointments (views/cancel-blocker target now))

          :else
          (let [sent (errors/attempt #(api/patch-json api request (http/path "api" "appointments" (:id target)) (visits/cancellation form)))]
            (if-let [e (:failed sent)]
              (question request clock (errors/gateway-status e) me appointments target (assoc typed :failure :unconfirmed))
              (let [{:keys [changed refused]} (:done sent)]
                (case (:status refused)
                  nil (see-other request {:cancelled changed})
                  404 (errors/failure :not-found request)
                  409 (let [fresh (appointments-or-nil api request)]
                        (blocked-again request clock me fresh (why-it-was-not-cancelled fresh id now)))
                  (question request clock 422 me appointments target
                            (assoc typed :errors (visits/cancellation-refusal (:errors refused)) :failure :refused)))))))))))
