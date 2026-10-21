(ns pulse.web.signin
  "Signing in and out of the portal (ADR-0028): the handlers of `/sign-in`, `/oauth2/callback`, `/sign-out` and `/signed-out`, and of
  the page a person lands on once they are in.

  The sign-in is the authorization-code flow with PKCE, `state` and `nonce`, and it can only happen once and only in the browser
  that began it:

    /sign-in         makes a pending sign-in (the `state`, the verifier, the `nonce` and the place to come back to), keeps it on the
                     server under a new session key, and sends the browser to the identity provider
    /oauth2/callback the browser comes back with a `code` and the `state`. Nothing is sent to the identity provider unless the
                     `state` is the one the pending sign-in holds, compared in constant time; the pending sign-in is then taken out
                     of the store, atomically, so that the same callback cannot be used twice; and only then is the code exchanged.
                     Both tokens are verified (the access token for the api, the id token for this client, with the `nonce`), and
                     only a person with the role `patient` gets a session. It is a new entry under a new key, so nothing from before
                     the sign-in survives it
    /sign-out        a POST: the session is ended here first, and the browser is then sent to the identity provider's end-session
                     address with the last id token as the hint, which sends it back to /signed-out

  What goes wrong is a page in the portal's words, and the cause is in the log: never what the identity provider said about it, and
  never a token."
  (:require [clojure.string :as str]
            [pulse.platform.config :as config]
            [pulse.platform.jwt :as jwt]
            [pulse.web.audit :as audit]
            [pulse.web.authz :as authz]
            [pulse.web.errors :as errors]
            [pulse.web.html :as html]
            [pulse.web.oidc :as oidc]
            [pulse.web.pages :as pages]
            [pulse.web.return-to :as return-to]
            [pulse.web.session :as session]
            [pulse.web.values :as values]
            [taoensso.telemere :as t]))

(defn- redirect [address]
  {:status 303 :headers {"Location" address} :body ""})

;; ---------------------------------------------------------------------------
;; Beginning

(defn sign-in
  "The handler of `GET /sign-in`. A person who is signed in already is sent on to where they were going. Anybody else gets a
  pending sign-in (a new session key, whatever cookie they came with) and is sent to the identity provider."
  [{:keys [oidc]}]
  (fn [request]
    (let [back (return-to/safe (get-in request [:query-params "return-to"]))]
      (if (authz/person request)
        (redirect back)
        (let [values (oidc/new-sign-in)]
          (assoc (redirect (oidc/authorization-address oidc values))
                 :session (session/pending-sign-in (assoc values :return-to back))))))))

;; ---------------------------------------------------------------------------
;; The callback

(defn- refuse!
  "Ends the callback with a page: `kind` is the kind of failure of `pulse.web.pages`, `reason` is why, for the log."
  ([kind reason] (refuse! kind reason nil))
  ([kind reason data]
   (throw (ex-info "The sign-in was refused" (merge {:type ::refused :kind kind :reason reason} data)))))

(defn- refusal? [e]
  (= ::refused (:type (ex-data e))))

(def ^:private provider-text
  "What of the identity provider's words may go in a log: plain."
  #"[A-Za-z0-9_ .,:'/-]+")

(defn- loggable
  "`x`, a parameter of the callback, when it is a text that may go in a log, cut to 100 characters, otherwise nil: a parameter
  that comes twice is not a text, and a text with anything but plain characters is not what the provider says."
  [x]
  (when (string? x)
    (let [x (subs x 0 (min 100 (count x)))]
      (when (re-matches provider-text x) x))))

(defn- exchange!
  "The grant for `code`, or a refusal: the identity provider that cannot be had is not the person's fault, and one that refuses
  the code is not either, but the person has to begin again."
  [oidc code verifier]
  (try
    (oidc/exchange-code! oidc code verifier)
    (catch clojure.lang.ExceptionInfo e
      (if (oidc/unavailable? e)
        (if (oidc/rejected? e)
          (refuse! :sign-in-failed :code-rejected {:status (:status (ex-data e)) :error (:error (ex-data e))})
          (refuse! :sign-in-unavailable (:reason (ex-data e))))
        (throw e)))))

(defn- verified!
  "The claims of `token`, a token of the kind `what` that `verifier` is for, or a refusal. A key set that cannot be had is
  the identity provider being unavailable, and not a token that is wrong."
  [verifier token what]
  (let [{:keys [ok error]} (jwt/verify verifier token)]
    (cond
      ok ok
      (= :keys-unavailable (:type error)) (refuse! :sign-in-unavailable :keys-unavailable)
      :else (refuse! :sign-in-failed (keyword (str what "-token-" (name (:type error))))))))

(def ^:private application-roles
  "The roles of the realm that mean something here; a token carries them next to Keycloak's own."
  #{"patient" "practitioner" "clinic-admin" "rules-engine" "api-service"})

(defn- present [s]
  (when-not (str/blank? s) s))

(defn- person-of
  "The person the tokens are about: `{:id :name :username :roles}`. The name is the one for the header of the pages, as the
  identity provider writes it."
  [access id]
  (let [username (:preferred_username access)]
    {:id (:sub access)
     :username username
     :name (or (present (:name id)) (present (:name access)) (present username) "Patient")
     :roles (into #{} (filter application-roles) (get-in access [:realm_access :roles]))}))

(defn- finish!
  "The session of the person whose browser came back with `code`, or a refusal. `pending` is their pending sign-in, already
  taken out of the store."
  [{:keys [oidc access-verifier id-verifier]} request pending code]
  (let [asked-at (oidc/now oidc)
        grant (exchange! oidc code (:verifier pending))
        access (verified! access-verifier (:access-token grant) "access")
        id (if (:id-token grant)
             (verified! id-verifier (config/reveal (:id-token grant)) "id")
             (refuse! :sign-in-failed :no-id-token))]
    (when-not (values/same? (:nonce id) (:nonce pending))
      (refuse! :sign-in-failed :nonce-mismatch))
    (when-not (= (:sub access) (:sub id))
      (refuse! :sign-in-failed :tokens-of-two-people))
    (let [person (person-of access id)]
      (if (contains? (:roles person) "patient")
        (do (audit/sign-in! request (:client-id oidc) person :allowed nil)
            {:person (select-keys person [:id :username :name])
             :tokens (oidc/keeper oidc grant asked-at)
             :csrf (values/random-value 32)})
        (do (audit/sign-in! request (:client-id oidc) person :denied :not-a-patient)
            (refuse! :not-for-patients :not-a-patient))))))

(defn- refusal-response
  "The page for a refusal. The pending sign-in has been used, so the browser is told to forget its cookie: it begins again."
  [{:keys [oidc]} request e]
  (let [{:keys [kind reason status error]} (ex-data e)]
    (t/event! :pulse.web/sign-in-refused
              {:level (if (= :not-a-patient reason) :info :warn)
               :data (cond-> {:reason reason :kind kind}
                       status (assoc :status status)
                       error (assoc :error error))})
    (cond-> (case kind
              :not-for-patients (errors/failure kind request :sign-out-href (oidc/end-session-address oidc nil))
              (errors/failure kind request))
      ;; A request that lost the race for the pending sign-in has no cookie to end: the one that won has made another, and
      ;; its answer may be the one the browser got first
      (not= :pending-sign-in-taken reason) session/end)))

(defn callback
  "The handler of `GET /oauth2/callback`. See the namespace for what it does and in which order."
  [{:keys [sessions] :as deps}]
  (fn [request]
    (let [params (:query-params request)
          pending (session/sign-in-of request)]
      (if-not (and pending (values/same? (get params "state") (:state pending)))
        ;; Not the answer to a sign-in this browser began (or one that has ended, or was used): the identity provider is not asked.
        ;; The pending sign-in of the browser, if it has one, stays: a forged callback must not end the real one
        (do (t/event! :pulse.web/sign-in-refused
                      {:level :warn :data {:reason (if pending :state-mismatch :no-pending-sign-in) :kind :sign-in-expired}})
            (errors/failure :sign-in-expired request))
        (try
          (let [taken (:sign-in (session/consume! sessions (:session/key request)))]
            (when-not taken
              ;; another request with the same cookie took it between the read and now
              (refuse! :sign-in-expired :pending-sign-in-taken))
            (when-let [error (get params "error")]
              (refuse! :sign-in-failed :provider-error {:error (loggable error)}))
            (let [code (get params "code")]
              ;; a parameter that comes twice is a vector, and is no code
              (when-not (and (string? code) (not (str/blank? code)))
                (refuse! :sign-in-failed :no-code))
              (let [session (finish! deps request taken code)]
                (t/event! :pulse.web/signed-in {:level :info :data {}})
                (assoc (redirect (return-to/safe (:return-to taken)))
                       :session (session/signed-in session)))))
          (catch clojure.lang.ExceptionInfo e
            (if (refusal? e)
              (refusal-response deps request e)
              (throw e))))))))

;; ---------------------------------------------------------------------------
;; Signing out

(defn sign-out
  "The handler of `POST /sign-out`: the session is ended before the browser is sent to the identity provider, so one that does not
  answer leaves the person signed out of the portal all the same."
  [{:keys [oidc]}]
  (fn [request]
    (let [id-token (oidc/id-token (session/tokens-of request))]
      (t/event! :pulse.web/signed-out {:level :info :data {}})
      (session/end (redirect (oidc/end-session-address oidc id-token))))))

(defn signed-out
  "The handler of `GET /signed-out`, where the identity provider sends the browser back to once it has signed the person out."
  [_request]
  (html/response (pages/signed-out-page)))

;; ---------------------------------------------------------------------------
;; The page of a person who is in

(defn home
  "The handler of `GET /`, the page of a person who is signed in, until the home of the portal is made (step 5): who they are
  signed in as, and the way out."
  [request]
  (html/response (pages/home-page {:name (:name (authz/person request))
                                   :csrf-token (session/csrf-token-of request)})))
