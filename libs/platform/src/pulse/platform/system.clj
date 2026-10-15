(ns pulse.platform.system
  "The Integrant components that every Pulse service starts the same way: logging, the cache of
  the identity provider's signing keys, the verifier of access tokens built on it, and the Jetty
  server that takes the service's Ring handler. With them, the schemas of what their
  configuration may say.

  A service names its own keys in its configuration (`:pulse.api/jwks`, `:pulse.scheduler/jwks`)
  and derives each from the one here, which is all it takes for these methods to start and stop
  it:

    (derive :pulse.api/logging :pulse.platform/logging)

  Integrant starts components in dependency order and stops them in the reverse, so the server
  stops taking requests before the key cache and the logging go away."
  (:require [integrant.core :as ig]
            [pulse.platform.config :refer [ref-schema]]
            [pulse.platform.jwt :as jwt]
            [pulse.platform.log :as log]
            [ring.adapter.jetty :as jetty])
  (:import (org.eclipse.jetty.server Server ServerConnector)))

;; ---------------------------------------------------------------------------
;; Components

(defmethod ig/init-key :pulse.platform/logging [_ options]
  (log/start! options)
  options)

(defmethod ig/halt-key! :pulse.platform/logging [_ _]
  (log/stop!))

(defmethod ig/init-key :pulse.platform/jwks [_ options]
  (jwt/jwks-source options))

(defmethod ig/halt-key! :pulse.platform/jwks [_ source]
  (jwt/close! source))

(defmethod ig/init-key :pulse.platform/verifier [_ {:keys [jwks] :as options}]
  (jwt/verifier (-> options (dissoc :jwks) (assoc :jwks-source jwks))))

(defmethod ig/init-key :pulse.platform/server [_ {:keys [handler host port]}]
  (jetty/run-jetty handler
                   {:host host
                    :port port
                    :join? false
                    :send-server-version? false
                    ;; On stop, let requests in flight finish before closing.
                    :configurator (fn [^Server server] (.setStopTimeout server 5000))}))

(defmethod ig/halt-key! :pulse.platform/server [_ ^Server server]
  (.stop server))

(defn port
  "The port `server`, a started `:pulse.platform/server`, listens on (useful when the configured
  port is 0)."
  [^Server server]
  (.getLocalPort ^ServerConnector (first (.getConnectors server))))

;; ---------------------------------------------------------------------------
;; What their configuration may say. Maps are closed, so a misspelt key is an error.

(def http-url
  [:re {:error/message "should be an http(s) URL"} #"^https?://\S+$"])

(def logging-schema
  (let [level (into [:enum] log/levels)]
    [:map {:closed true}
     [:level level]
     [:format [:enum :text :json]]
     [:ns-levels [:map-of :string level]]]))

(def jwks-schema
  [:map {:closed true}
   [:uri http-url]
   [:ttl-ms {:optional true} pos-int?]
   [:min-refresh-interval-ms {:optional true} pos-int?]
   [:outage-ttl-ms {:optional true} pos-int?]])

(def verifier-schema
  [:map {:closed true}
   [:jwks ref-schema]
   [:issuer http-url]
   [:audience [:string {:min 1}]]
   [:algorithms [:vector {:min 1} :string]]
   [:leeway-s [:int {:min 0 :max 300}]]
   [:token-type {:optional true} [:string {:min 1}]]])

(def server-schema
  [:map {:closed true}
   [:handler ref-schema]
   [:host [:string {:min 1}]]
   [:port [:int {:min 0 :max 65535}]]])
