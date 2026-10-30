(ns pulse.spa.fetch
  "The calls to the api, as an effect of re-frame (ADR-0029, point 4). A call is data, `{:method :path :query :body :id :on-success
  :on-refusal :on-failure}`, and what comes back is an event: the `:on-success` event with `{:status :body :request-id}` added to
  it, the `:on-refusal` one (or `:on-failure`, when there is none) with `{:kind :refused :status :problem :request-id}` when the
  api said no and why, and the `:on-failure` one with `{:kind :status :request-id}` for the rest (`pulse.spa.http/failure-kinds`).

  What the effect does for the page, so that no page has to:
  - the token comes from `pulse.spa.auth` at the moment of the call and goes in the `Authorization` header and nowhere else: not in
    what an event is given, not in the console, and the effect writes nothing to the console at all;
  - a token that cannot be had is the person's sign-in having ended, and they are sent to sign in again, with nothing told to the
    page that is going away;
  - an answer of 401 is one renewal of the token and one repeat of the call (a request that was refused for its token was not
    handled, so the repeat does no harm); a second 401 is a failure of kind `:unauthorized`, which is also told to
    `:on-unauthorized`, for the page that says so: nobody is sent to sign in by an api that refuses every token of this client;
  - a call has 6 seconds, the renewal and the repeat included, and is aborted when they are up;
  - a call with an `:id` stops the one before it with the same `:id`, and `::cancel` stops one by its `:id`: a call that was
    stopped tells nobody anything, so that the latest answer is the one that is shown.

  Nothing here runs when the page loads. The environment of a call (`request!`) is a map, so that the tests give it a server
  of their own and a token that is not Keycloak's."
  (:require [clojure.string :as str]
            [pulse.spa.auth :as auth]
            [pulse.spa.http :as http]
            [pulse.spa.settings :as settings]
            [re-frame.core :as rf]))

(def default-timeout-ms
  "What a call has, from the moment it is made: the same as the portal's calls to the api."
  6000)

(defn- parse
  "The text of a body as data with keyword keys: nil when there is none, `http/not-json` when it is not JSON."
  [text]
  (if (str/blank? text)
    nil
    (try
      (js->clj (js/JSON.parse text) :keywordize-keys true)
      (catch :default _ http/not-json))))

(defn- cancel-run!
  "Stops the call `run` (an atom): nothing is told to anyone, and a request on its way is aborted. A call that has settled is no
  longer in flight, so it is never one that is stopped."
  [run]
  (let [{:keys [timer ^js controller]} @run]
    (swap! run assoc :cancelled? true)
    (js/clearTimeout timer)
    (when controller (.abort controller))))

(defn cancel!
  "Stops the call with `id` among those in `inflight` (an atom of calls by their `:id`), if there is one."
  [inflight id]
  (when-let [run (get @inflight id)]
    (cancel-run! run)
    (swap! inflight dissoc id)))

(defn request!
  "Makes the call `spec` in the environment `env`: `:base-url` of the api, `:token!` and `:renew!` (functions that give a promise
  of an access token: `pulse.spa.auth`'s), `:login!` (sends the person to sign in), `:dispatch` (of an event), `:fetch` (the
  browser's), `:timeout-ms`, `:inflight` (an atom, where the calls with an `:id` are kept) and `:on-unauthorized`, a function of no
  arguments. It answers nil, and what happens is told by dispatching the events of `spec`."
  [{:keys [base-url token! renew! login! dispatch fetch timeout-ms inflight on-unauthorized]}
   {:keys [method path query body id on-success on-refusal on-failure] :or {method :get}}]
  (let [run (atom {:settled? false :cancelled? false :timer nil :controller nil})
        address (http/url base-url path query)
        done? (fn [] (let [{:keys [settled? cancelled?]} @run] (or settled? cancelled?)))
        ;; a call that was stopped is never settled, so a call that settles is always the one that is kept under its id
        release! (fn []
                   (when id
                     (swap! inflight dissoc id)))
        ;; every end of a call goes through here, once: what comes later (a timer, an answer that was on its way) is dropped
        settle! (fn [tell]
                  (when-not (done?)
                    (swap! run assoc :settled? true)
                    (js/clearTimeout (:timer @run))
                    (release!)
                    (tell)))
        tell-failure (fn [failure]
                       (when on-failure (dispatch (conj on-failure failure)))
                       (when (and (= :unauthorized (:kind failure)) on-unauthorized) (on-unauthorized)))
        options (fn [^js controller token request-id]
                  (clj->js (cond-> {:method (str/upper-case (name method))
                                    :headers {"Authorization" (str "Bearer " token)
                                              "Accept" "application/json"
                                              "X-Request-Id" request-id}
                                    :signal (.-signal controller)
                                    ;; the api is told who the person is by the token and by nothing else
                                    :credentials "omit"
                                    :cache "no-store"
                                    :redirect "error"}
                             (some? body) (-> (update :headers assoc "Content-Type" "application/json")
                                              (assoc :body (js/JSON.stringify (clj->js body)))))))]
    (letfn [(attempt! [retried?]
              (.then (js/Promise. (fn [resolve _] (resolve (if retried? (renew!) (token!)))))
                     (fn [token] (if (string? token) (send! token retried?) (sign-in-again!)))
                     (fn [_] (sign-in-again!))))
            (sign-in-again! []
              (settle! login!))
            (send! [token retried?]
              (when-not (done?)
                (try
                  (let [controller (js/AbortController.)
                        request-id (http/request-id)]
                    (swap! run assoc :controller controller)
                    (.then (fetch address (options controller token request-id))
                           (fn [^js response] (read! response retried? request-id))
                           broken!))
                  (catch :default _
                    (settle! #(tell-failure {:kind :unexpected}))))))
            (read! [^js response retried? request-id]
              (let [status (.-status response)]
                (.then (.text response)
                       (fn [text] (answered! status (parse text) retried? request-id))
                       broken!)))
            (broken! [^js error]
              ;; a call that was stopped, or whose time was up, has been settled already and is not heard of again
              (settle! #(tell-failure {:kind (if (and error (= "AbortError" (.-name error))) :aborted :unreachable)})))
            (answered! [status body retried? request-id]
              (let [{:keys [result kind problem] :as outcome} (http/outcome status body)]
                (case result
                  :ok (settle! #(when on-success (dispatch (conj on-success {:status status :body (:body outcome) :request-id request-id}))))
                  :refused (settle! #(when-let [handler (or on-refusal on-failure)]
                                       (dispatch (conj handler {:kind :refused :status status :problem problem :request-id request-id}))))
                  :failed (if (and (= :unauthorized kind) (not retried?))
                            (attempt! true)
                            (settle! #(tell-failure {:kind kind :status status :request-id request-id}))))))]
      (when id
        (when-let [previous (get @inflight id)] (cancel-run! previous))
        (swap! inflight assoc id run))
      ;; settling and stopping a call clear its timer, so it fires only for a call that is still on its way
      (swap! run assoc :timer (js/setTimeout (fn []
                                               (when-let [^js controller (:controller @run)] (.abort controller))
                                               (settle! #(tell-failure {:kind :timeout})))
                                             timeout-ms))
      (attempt! false)
      nil)))

(defonce ^:private inflight (atom {}))

(defn- live-env []
  {:base-url settings/api-base-url
   :token! auth/token!
   :renew! auth/renew!
   :login! auth/login!
   :dispatch rf/dispatch
   :fetch js/fetch
   :timeout-ms default-timeout-ms
   :inflight inflight
   ;; the event is named here and not required: the events require this namespace
   :on-unauthorized #(rf/dispatch [:pulse.spa.events/unauthorized])})

(rf/reg-fx
 ::request
 (fn [spec]
   (request! (live-env) spec)))

(defn cancel-effect!
  "What the effect `::cancel` does with its value, `{:id id}`, among the calls in `inflight`: stops the one with that `:id`. The value is a
  map, as the value of `::request` is: a bare id would destructure to nothing, and nothing would be stopped."
  [inflight {:keys [id]}]
  (cancel! inflight id))

(rf/reg-fx
 ::cancel
 (fn [value]
   (cancel-effect! inflight value)))
