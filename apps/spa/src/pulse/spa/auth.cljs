(ns pulse.spa.auth
  "The identity provider, as an effect of re-frame (ADR-0029, point 1). keycloak-js does the authorization-code flow with PKCE:
  a visitor is sent to the sign-in page of Keycloak and comes back to the page they asked for with a code, which the library
  exchanges for the tokens.

  The tokens stay inside the `Keycloak` object of this namespace. Nothing here puts one in the state of the application, in an
  event, in the console or in a storage; what leaves is the claims of the access token, as data, which is what `pulse.spa.person`
  reads, and the access token itself, **only** to `pulse.spa.fetch`, as a promise, at the moment of a call. Nothing runs by itself:
  the token is renewed when a call needs it (there is no timer, which a module that starts one when it loads makes a nuisance of
  every test and every reload)."
  (:require ["keycloak-js" :default Keycloak]
            [pulse.spa.route :as route]
            [pulse.spa.settings :as settings]
            [re-frame.core :as rf]))

(defonce ^:private ^js keycloak
  (Keycloak. #js {:url settings/keycloak-url
                  :realm settings/keycloak-realm
                  :clientId settings/keycloak-client-id}))

(defn claims
  "The claims of the access token as a map with string keys, or nil when there is no token. The claims are read as data and
  never as the properties of a JavaScript object: a build with advanced optimizations renames those that it does not know
  (`preferred_username`, `realm_access`)."
  []
  (some-> (.-tokenParsed keycloak) (js->clj)))

(defn token!
  "A promise of an access token that is good for at least another 30 seconds: the one the library has, or a new one, got with the
  refresh token, when it is about to expire. It is rejected when there is no way to have one, which is the sign-in having ended
  (the session of the identity provider is over, or the person was signed out elsewhere)."
  []
  (.then (.updateToken keycloak 30) (fn [_] (.-token keycloak))))

(defn renew!
  "A promise of a new access token, whether or not the one the library has is still good: for when the api has said it does not
  accept it (the clock of the browser and the api's can differ by more than the 30 seconds that `token!` allows for). Rejected as
  `token!` is."
  []
  (.then (.updateToken keycloak 99999) (fn [_] (.-token keycloak))))

(defn login!
  "Sends the browser to the sign-in page of the identity provider, which brings it back to this page: for when `token!` cannot
  give a token, and the person has to sign in again."
  []
  (.login keycloak))

(rf/reg-fx
 ::init
 (fn [{:keys [on-success on-failure]}]
   ;; `login-required` sends a visitor to the sign-in page, and a person who has signed in comes back here with the code in the
   ;; address. S256 is what the realm requires of this client. There is no hidden frame to watch the session (`checkLoginIframe`):
   ;; a browser that blocks third-party storage would make it fail, and the session ends at the next renewal anyway.
   (-> (.init keycloak #js {:onLoad "login-required" :pkceMethod "S256" :checkLoginIframe false})
       (.then (fn [authenticated]
                (rf/dispatch (conj on-success (when authenticated (claims))))))
       (.catch (fn [_] (rf/dispatch on-failure))))))

(rf/reg-fx
 ::logout
 (fn [_]
   ;; Keycloak ends the session, with the id token as the hint so that it does not ask, and sends the browser back to the page
   ;; of someone who has signed out, which does not sign them in again. The address is registered in the realm.
   (.logout keycloak #js {:redirectUri (str (.-origin js/location) route/signed-out-path)})))
