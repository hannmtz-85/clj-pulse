(ns pulse.web.config
  "The portal's configuration: the system map in `pulse/web/config.edn` and the schema it must satisfy. Maps are
  closed, so a misspelt key is an error instead of a setting that silently does nothing. The components it shares with
  the other services have their schemas in `pulse.platform.system`."
  (:require [pulse.platform.config :as config :refer [ref-schema]]
            [pulse.platform.system :as system :refer [http-url]]))

(def origin
  "An address that is an origin and no more: a scheme, a host and perhaps a port. The addresses the portal makes from it
  (where the identity provider sends the browser back to) and the origin it compares with the one of a request take it so."
  [:re {:error/message "should be an http(s) origin with no path, such as http://localhost:3300"} #"^https?://[^/?#\s]+$"])

(def oidc-schema
  "The portal as a client of the identity provider (see `pulse.web.oidc`). The clock is optional, and for tests."
  [:map {:closed true}
   [:client-id [:string {:min 1}]]
   [:client-secret (config/secret-schema "set WEB_CLIENT_SECRET (`bb env:init` adds it to .env)")]
   [:base-url origin]
   [:authorization-uri http-url]
   [:end-session-uri http-url]
   [:token-uri http-url]
   [:timeout-ms [:int {:min 100 :max 60000}]]
   [:connect-timeout-ms [:int {:min 100 :max 60000}]]
   [:clock {:optional true} [:fn {:error/message "should be a java.time.Clock"} #(instance? java.time.Clock %)]]])

(def sessions-schema
  "The limits of the store of sessions (see `pulse.web.session`). The clock is optional, and for tests."
  [:map {:closed true}
   [:idle-ms [:int {:min 1000}]]
   [:absolute-ms [:int {:min 1000}]]
   [:pending-ms [:int {:min 1000}]]
   [:max-sessions [:int {:min 1}]]
   [:max-pending [:int {:min 1}]]
   [:clock {:optional true} [:fn {:error/message "should be a java.time.Clock"} #(instance? java.time.Clock %)]]])

(def schema
  [:map {:closed true}
   [:pulse.web/logging system/logging-schema]
   [:pulse.web/jwks system/jwks-schema]
   [:pulse.web/access-verifier system/verifier-schema]
   [:pulse.web/id-verifier system/verifier-schema]
   [:pulse.web/api system/client-schema]
   [:pulse.web/oidc oidc-schema]
   [:pulse.web/sessions sessions-schema]
   [:pulse.web/handler
    [:map {:closed true}
     [:logging ref-schema]
     [:jwks ref-schema]
     [:api ref-schema]
     [:oidc ref-schema]
     [:sessions ref-schema]
     [:access-verifier ref-schema]
     [:id-verifier ref-schema]
     [:issuer http-url]
     [:quiet-paths [:set :string]]]]
   [:pulse.web/server system/server-schema]])

(defn load-config
  "The validated system map for `profile` (default `:dev`). Throws an exception that
  `pulse.platform.config/invalid-config?` recognises when the configuration is wrong."
  ([] (load-config {}))
  ([{:keys [profile] :or {profile :dev}}]
   (config/read-config "pulse/web/config.edn" {:profile profile :schema schema})))
