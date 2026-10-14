(ns pulse.api.config
  "The api's configuration: the system map in `pulse/api/config.edn` and the schema it must
  satisfy. Maps are closed, so a misspelt key is an error instead of a setting that silently
  does nothing."
  (:require [pulse.platform.config :as config :refer [ref-schema]]
            [pulse.platform.http.cors :as cors]
            [pulse.platform.log :as log]))

(def ^:private http-url
  [:re {:error/message "should be an http(s) URL"} #"^https?://\S+$"])

(def ^:private origin
  [:fn {:error/message "should be an exact origin such as http://localhost:3000: no wildcard, no path, no trailing slash"}
   cors/origin?])

(def ^:private level (into [:enum] log/levels))

(def schema
  [:map {:closed true}
   [:pulse.api/logging
    [:map {:closed true}
     [:level level]
     [:format [:enum :text :json]]
     [:ns-levels [:map-of :string level]]]]
   [:pulse.api/jwks
    [:map {:closed true}
     [:uri http-url]
     [:ttl-ms {:optional true} pos-int?]
     [:min-refresh-interval-ms {:optional true} pos-int?]
     [:outage-ttl-ms {:optional true} pos-int?]]]
   [:pulse.api/verifier
    [:map {:closed true}
     [:jwks ref-schema]
     [:issuer http-url]
     [:audience [:string {:min 1}]]
     [:algorithms [:vector {:min 1} :string]]
     [:leeway-s [:int {:min 0 :max 300}]]
     [:token-type {:optional true} [:string {:min 1}]]]]
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
   [:pulse.api/server
    [:map {:closed true}
     [:handler ref-schema]
     [:host [:string {:min 1}]]
     [:port [:int {:min 0 :max 65535}]]]]])

(defn load-config
  "The validated system map for `profile` (default `:dev`). Throws an exception that
  `pulse.platform.config/invalid-config?` recognises when the configuration is wrong."
  ([] (load-config {}))
  ([{:keys [profile] :or {profile :dev}}]
   (config/read-config "pulse/api/config.edn" {:profile profile :schema schema})))
