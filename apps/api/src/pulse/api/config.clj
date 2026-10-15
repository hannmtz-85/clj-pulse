(ns pulse.api.config
  "The api's configuration: the system map in `pulse/api/config.edn` and the schema it must
  satisfy. Maps are closed, so a misspelt key is an error instead of a setting that silently
  does nothing. The components it shares with the other services have their schemas in
  `pulse.platform.system`."
  (:require [pulse.platform.config :as config :refer [ref-schema]]
            [pulse.platform.http.cors :as cors]
            [pulse.platform.system :as system :refer [http-url]]))

(def ^:private origin
  [:fn {:error/message "should be an exact origin such as http://localhost:3000: no wildcard, no path, no trailing slash"}
   cors/origin?])

(def schema
  [:map {:closed true}
   [:pulse.api/logging system/logging-schema]
   [:pulse.api/jwks system/jwks-schema]
   [:pulse.api/verifier system/verifier-schema]
   [:pulse.api/fhir
    [:multi {:dispatch :kind}
     [:http [:map {:closed true}
             [:kind [:= :http]]
             [:base-url http-url]
             [:timeout-ms {:optional true} [:int {:min 100 :max 120000}]]
             [:connect-timeout-ms {:optional true} [:int {:min 100 :max 60000}]]]]
     [:memory [:map {:closed true} [:kind [:= :memory]]]]]]
   [:pulse.api/handler
    [:map {:closed true}
     [:logging ref-schema]
     [:verifier ref-schema]
     [:fhir ref-schema]
     [:realm [:string {:min 1}]]
     [:issuer http-url]
     [:cors [:map {:closed true}
             [:allowed-origins [:set origin]]]]
     [:quiet-paths [:set :string]]
     [:summary-timeout-ms [:int {:min 100 :max 60000}]]]]
   [:pulse.api/server system/server-schema]])

(defn load-config
  "The validated system map for `profile` (default `:dev`). Throws an exception that
  `pulse.platform.config/invalid-config?` recognises when the configuration is wrong."
  ([] (load-config {}))
  ([{:keys [profile] :or {profile :dev}}]
   (config/read-config "pulse/api/config.edn" {:profile profile :schema schema})))
