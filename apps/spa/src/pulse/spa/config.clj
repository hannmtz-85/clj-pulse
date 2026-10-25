(ns pulse.spa.config
  "The configuration of the server of the workspace's release: the system map in `pulse/spa/config.edn` and the schema it must
  satisfy. Maps are closed, so a misspelt key is an error instead of a setting that silently does nothing."
  (:require [pulse.platform.config :as config :refer [ref-schema]]
            [pulse.platform.system :as system :refer [http-url]]))

(def origin
  "An address that is an origin and no more: a scheme, a host and perhaps a port."
  [:re {:error/message "should be an http(s) origin with no path, such as http://localhost:3000"} #"^https?://[^/?#\s]+$"])

(def schema
  [:map {:closed true}
   [:pulse.spa/logging system/logging-schema]
   [:pulse.spa/handler
    [:map {:closed true}
     [:logging ref-schema]
     [:root [:string {:min 1}]]
     [:base-url origin]
     [:api-base-url http-url]
     [:issuer http-url]
     [:quiet-paths [:set :string]]]]
   [:pulse.spa/server system/server-schema]])

(defn load-config
  "The validated system map for `profile` (default `:dev`). Throws an exception that
  `pulse.platform.config/invalid-config?` recognises when the configuration is wrong."
  ([] (load-config {}))
  ([{:keys [profile] :or {profile :dev}}]
   (config/read-config "pulse/spa/config.edn" {:profile profile :schema schema})))
