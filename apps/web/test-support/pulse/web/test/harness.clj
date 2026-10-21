(ns pulse.web.test.harness
  "What the portal's handler tests share: a fake OpenID Connect provider (the portal asks it for its key set, sends the browser to
  it to sign in, and exchanges the code with it), a stand-in for the api, a clock the tests move by hand, the portal's Ring handler
  wired to them, and `call` to make a request and read the answer. No server of the portal and no network beyond localhost.

  `browser` and `sign-in!` are a person's browser in front of the handler: they keep the cookies the portal sets and send them
  back, and go through the redirects of the sign-in, so that a test reads like what happened.

  Test code only: it is on the classpath of the `:test` alias and nowhere else."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jsonista.core :as json]
            [pulse.platform.config :as config]
            [pulse.platform.http.client :as http]
            [pulse.platform.jwt :as jwt]
            [pulse.platform.test.clock :as clock]
            [pulse.platform.test.idp :as idp]
            [pulse.platform.test.oidc :as oidc-fake]
            [pulse.platform.test.stub :as stub]
            [pulse.web.handler :as handler]
            [pulse.web.oidc :as oidc]
            [pulse.web.routes :as routes]
            [pulse.web.session :as session]
            [ring.middleware.session.store :as store]
            [taoensso.telemere :as t])
  (:import (java.io File InputStream)
           (java.net URI)))

(def signing-key (idp/signing-key "k1"))

(def issuer
  "The identity provider as a browser reaches it: the origin that the policy of the pages names, and the issuer of its tokens."
  "http://localhost:8080/realms/pulse")

(def base-url
  "The address the portal is served at: where the realm registers it."
  "http://localhost:3300")

(def client-secret "test-secret")

(def api-ready
  "What the stand-in for the api answers to a readiness probe, until a test changes it."
  {:status 200 :body "{\"status\":\"ready\",\"checks\":{\"keycloak\":\"up\",\"fhir\":\"up\"}}"})

(defn- epoch-second [the-clock]
  (.getEpochSecond (clock/instant the-clock)))

(defn with-web
  "Calls `(f web)` with a fake identity provider that is also an OpenID Connect one, a stand-in for the api, a client of it that has
  no token, a clock that begins at 2026-10-19 10:00 UTC and moves only when a test says so, and the portal's handler wired to
  them, and stops it all afterwards. `web` is

    `:handler`  the Ring handler
    `:idp`      the fake provider (`pulse.platform.test.idp`, and `pulse.platform.test.oidc` for who signs in)
    `:jwks`     the key cache, `:api` the client of the api, `:api-answer` an atom with what the stand-in answers (`api-ready` at
                first) and `:api-requests` an atom with the requests it got
    `:clock`    the clock (`pulse.platform.test.clock`): the sessions, the keepers and the tokens of the provider go by it
    `:sessions` the store of the sessions, `:oidc` the portal's client of the provider, `:access-verifier` and
    `:id-verifier` the two verifiers of the tokens of a sign-in

  Options: `:routes` (more routes, added to the portal's), `:sessions` (limits of the store, to try them small) and
  `:base-url` (the address the portal is served at, `base-url` here by default: an https one makes the cookie and the headers
  those of a portal served over https)."
  [{:keys [routes sessions] served-at :base-url} f]
  (let [base-url (or served-at base-url)
        server (idp/start! [signing-key])
        the-clock (clock/clock "2026-10-19T10:00:00Z")
        now-s #(epoch-second the-clock)
        _ (oidc-fake/enable! server {:key signing-key
                                     :issuer issuer
                                     :now-s now-s
                                     :clients {"pulse-web" {:secret client-secret
                                                            :redirect-uris #{(str base-url "/oauth2/callback")}
                                                            :post-logout-redirect-uris [(str base-url "/*")]}}})
        source (jwt/jwks-source {:uri (:uri server) :min-refresh-interval-ms 200})
        verifier (fn [audience token-type]
                   (jwt/verifier {:jwks-source source :issuer issuer :audience audience :token-type token-type :clock now-s}))
        api-answer (atom api-ready)
        api-stub (stub/start! (fn [_] @api-answer))
        api (http/client {:base-url (:url api-stub)})
        sessions-store (session/memory-store (assoc sessions :clock (:clock the-clock)))
        the-oidc (oidc/client {:client-id "pulse-web"
                               :client-secret (config/secret client-secret)
                               :base-url base-url
                               :authorization-uri (oidc-fake/authorization-uri server)
                               :end-session-uri (oidc-fake/end-session-uri server)
                               :token-uri (:token-uri server)
                               :timeout-ms 2000
                               :connect-timeout-ms 1000
                               :clock (:clock the-clock)})
        access-verifier (verifier "pulse-api" "Bearer")
        id-verifier (verifier "pulse-web" "ID")]
    (try
      (f {:handler (handler/ring-handler (into (routes/routes {:jwks source :api api :oidc the-oidc :sessions sessions-store
                                                               :access-verifier access-verifier :id-verifier id-verifier})
                                               routes)
                                         {:issuer issuer :quiet-paths #{"/healthz" "/readyz"} :sessions sessions-store :oidc the-oidc})
          :idp server
          :jwks source
          :api api
          :api-answer api-answer
          :api-requests (:requests api-stub)
          :clock the-clock
          :sessions sessions-store
          :oidc the-oidc
          :access-verifier access-verifier
          :id-verifier id-verifier})
      (finally
        (http/close! api)
        (stub/stop! api-stub)
        (jwt/close! source)
        (idp/stop! server)))))

(defn bytes-of
  "The bytes of a response body: a string, a file or a stream (what Ring's file responses are)."
  ^bytes [body]
  (cond (nil? body) (byte-array 0)
        (string? body) (.getBytes ^String body "UTF-8")
        (instance? File body) (java.nio.file.Files/readAllBytes (.toPath ^File body))
        (instance? InputStream body) (with-open [in ^InputStream body] (.readAllBytes in))
        :else (.getBytes (str body) "UTF-8")))

(defn session-cookie
  "The `Cookie` header of a browser that has the session `data` in the store of `web`: the entry is made there, as the portal would
  have made it, and the header carries its key."
  [{:keys [sessions]} data]
  (str session/cookie-name "=" (store/write-session sessions nil data)))

(defn call
  "The response to a request, with `:bytes`, `:text` (the body as UTF-8), `:json` (the body as JSON, if it is) and
  `:signals` (what was logged, trapped so nothing is printed) added. Options: `:headers`, `:query` (a query string),
  `:session` (the data of a session, which is made in the store, and its cookie sent) and `:form` (a map, sent as the
  parameters of a form)."
  [{:keys [handler] :as web} method uri & {:keys [headers query session form]}]
  (let [headers (cond-> (or headers {})
                  session (assoc "cookie" (session-cookie web session))
                  form (assoc "content-type" "application/x-www-form-urlencoded"))
        request (cond-> {:request-method method
                         :uri uri
                         :headers headers}
                  query (assoc :query-string query)
                  form (assoc :body (io/input-stream (.getBytes ^String (str/join "&" (map (fn [[k v]] (str (name k) "=" v)) form)) "UTF-8"))))
        {:keys [value signals]} (t/with-signals true (handler request))
        data (bytes-of (:body value))
        text (String. data "UTF-8")]
    (assoc value
           :bytes data
           :text text
           :json (try (json/read-value text json/keyword-keys-object-mapper) (catch Exception _ nil))
           :signals signals)))

(defn header [response name]
  (get-in response [:headers name]))

(defn content-type [response]
  (some-> (header response "Content-Type") (str/split #";") first))

;; ---------------------------------------------------------------------------
;; A person's browser

(defn browser
  "A browser in front of the portal: its cookies, which it keeps and sends back."
  [web]
  {:web web :cookies (atom {})})

(defn set-cookies
  "The cookies a response sets, a vector of the texts of its `Set-Cookie` headers."
  [response]
  (let [header (header response "Set-Cookie")]
    (cond (nil? header) [] (string? header) [header] :else (vec header))))

(defn- remember!
  "Keeps the cookies the response sets, and drops those it tells the browser to expire."
  [{:keys [cookies]} response]
  (doseq [cookie (set-cookies response)
          :let [[pair & attributes] (str/split cookie #";\s*")
                [k v] (str/split pair #"=" 2)
                gone? (or (some #(re-matches #"(?i)max-age=0+" %) attributes) (str/blank? v))]]
    (if gone? (swap! cookies dissoc k) (swap! cookies assoc k v)))
  response)

(defn cookie
  "The value of the cookie `name` that the browser holds, or nil."
  [{:keys [cookies]} name]
  (get @cookies name))

(defn visit!
  "Makes a request as the browser does: it sends the cookies it has, and keeps those that come back. A request that changes
  something carries the `Origin` of the portal, as a browser sends with a form it posts, unless `:headers` say another. Options
  are those of `call`, less `:session`."
  [{:keys [web cookies] :as browser} method uri & {:keys [headers] :as options}]
  (let [unsafe? (not (#{:get :head :options} method))
        sent (cond-> (merge {} headers)
               (seq @cookies) (assoc "cookie" (str/join "; " (map (fn [[k v]] (str k "=" v)) @cookies)))
               (and unsafe? (not-any? #(= "origin" (str/lower-case %)) (keys headers))) (assoc "origin" base-url))]
    (remember! browser (apply call web method uri (mapcat identity (assoc options :headers sent))))))

(defn follow!
  "Goes where `address` says, an address of the portal (absolute, as the identity provider writes it, or a path), as the browser
  does after a redirect."
  [browser address]
  (let [uri (URI. address)]
    (visit! browser :get (.getRawPath uri) :query (.getRawQuery uri))))

(defn at-the-provider
  "What the identity provider answers when the browser is sent to `address`: `{:status :location :body}`, where `:location` is
  where it sends the browser back to, with the code and the `state`."
  [address]
  (oidc-fake/visit address))

(defn sign-in!
  "A browser signs in the way a person does: it asks for `/sign-in` (with the place to come back to, if one is given), is sent to
  the identity provider, which signs in whoever `pulse.platform.test.oidc/sign-in-as!` said, and comes back with the code.
  Returns `{:begin response :provider answer :callback response}`: the three answers on the way, the last one the portal's."
  [browser & {:keys [return-to]}]
  (let [begin (visit! browser :get "/sign-in" :query (when return-to (str "return-to=" (java.net.URLEncoder/encode ^String return-to "UTF-8"))))
        provider (at-the-provider (header begin "Location"))
        callback (follow! browser (:location provider))]
    {:begin begin :provider provider :callback callback}))
