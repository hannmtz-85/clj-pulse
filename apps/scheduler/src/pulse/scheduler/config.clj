(ns pulse.scheduler.config
  "The scheduler's configuration: the system map in `pulse/scheduler/config.edn` and the schema it
  must satisfy. Maps are closed, so a misspelt key is an error instead of a setting that silently
  does nothing. The components it shares with the other services have their schemas in
  `pulse.platform.system`."
  (:require [pulse.platform.config :as config :refer [ref-schema]]
            [pulse.platform.system :as system]))

(def schema
  [:map {:closed true}
   [:pulse.scheduler/logging system/logging-schema]
   [:pulse.scheduler/jwks system/jwks-schema]
   [:pulse.scheduler/verifier system/verifier-schema]
   [:pulse.scheduler/token (system/token-source-schema "set SCHEDULER_CLIENT_SECRET (it is in .env)")]
   [:pulse.scheduler/jobs [:map {:closed true}]]
   [:pulse.scheduler/executor
    [:map {:closed true}
     [:jobs ref-schema]
     [:grace-ms [:int {:min 0 :max 60000}]]]]
   [:pulse.scheduler/handler
    [:map {:closed true}
     [:logging ref-schema]
     [:verifier ref-schema]
     [:executor ref-schema]
     [:token ref-schema]
     [:realm [:string {:min 1}]]
     [:quiet-paths [:set :string]]]]
   [:pulse.scheduler/server system/server-schema]])

(defn load-config
  "The validated system map for `profile` (default `:dev`). Throws an exception that
  `pulse.platform.config/invalid-config?` recognises when the configuration is wrong."
  ([] (load-config {}))
  ([{:keys [profile] :or {profile :dev}}]
   (config/read-config "pulse/scheduler/config.edn" {:profile profile :schema schema})))
