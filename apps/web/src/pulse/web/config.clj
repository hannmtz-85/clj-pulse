(ns pulse.web.config
  "The portal's configuration: the system map in `pulse/web/config.edn` and the schema it must satisfy. Maps are
  closed, so a misspelt key is an error instead of a setting that silently does nothing. The components it shares with
  the other services have their schemas in `pulse.platform.system`."
  (:require [pulse.platform.config :as config :refer [ref-schema]]
            [pulse.platform.system :as system :refer [http-url]]))

(def schema
  [:map {:closed true}
   [:pulse.web/logging system/logging-schema]
   [:pulse.web/jwks system/jwks-schema]
   [:pulse.web/api system/client-schema]
   [:pulse.web/handler
    [:map {:closed true}
     [:logging ref-schema]
     [:jwks ref-schema]
     [:api ref-schema]
     [:issuer http-url]
     [:quiet-paths [:set :string]]]]
   [:pulse.web/server system/server-schema]])

(defn load-config
  "The validated system map for `profile` (default `:dev`). Throws an exception that
  `pulse.platform.config/invalid-config?` recognises when the configuration is wrong."
  ([] (load-config {}))
  ([{:keys [profile] :or {profile :dev}}]
   (config/read-config "pulse/web/config.edn" {:profile profile :schema schema})))
