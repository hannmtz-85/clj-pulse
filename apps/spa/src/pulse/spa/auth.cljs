(ns pulse.spa.auth
  "The identity provider, as an effect of re-frame (ADR-0029, point 1). keycloak-js does the authorization-code flow with PKCE:
  a visitor is sent to the sign-in page of Keycloak and comes back to the page they asked for with a code, which the library
  exchanges for the tokens.

  The tokens stay inside the `Keycloak` object of this namespace. Nothing here puts one in the state of the application, in an
  event, in the console or in a storage; what leaves is the claims of the access token, as data, which is what `pulse.spa.person`
  reads."
  (:require ["keycloak-js" :default Keycloak]
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
