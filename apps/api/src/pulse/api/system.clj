(ns pulse.api.system
  "The api's components: how each key of the system map (`pulse/api/config.edn`) is started and
  stopped. Integrant starts them in dependency order and stops them in the reverse order, so
  the server stops taking requests before the key cache and the logging go away."
  (:require [integrant.core :as ig]
            [pulse.api.handler :as handler]
            [pulse.fhir.client.http :as fhir-http]
            [pulse.fhir.client.memory :as fhir-memory]
            [pulse.fhir.synthetic :as synthetic]
            [pulse.platform.jwt :as jwt]
            [pulse.platform.log :as log]
            [ring.adapter.jetty :as jetty])
  (:import (org.eclipse.jetty.server Server ServerConnector)))

(defmethod ig/init-key :pulse.api/logging [_ options]
  (log/start! options)
  options)

(defmethod ig/halt-key! :pulse.api/logging [_ _]
  (log/stop!))

(defmethod ig/init-key :pulse.api/jwks [_ options]
  (jwt/jwks-source options))

(defmethod ig/halt-key! :pulse.api/jwks [_ source]
  (jwt/close! source))

(defmethod ig/init-key :pulse.api/verifier [_ {:keys [jwks] :as options}]
  (jwt/verifier (-> options (dissoc :jwks) (assoc :jwks-source jwks))))

(defmethod ig/init-key :pulse.api/fhir [_ {:keys [kind] :as options}]
  (case kind
    :http (fhir-http/client (dissoc options :kind))
    ;; The "lite" backend: a small FHIR server in memory, holding the demo cohort.
    :memory (fhir-memory/memory-client (synthetic/generate (synthetic/cohort)))))

(defmethod ig/init-key :pulse.api/handler [_ options]
  (handler/create options))

(defmethod ig/init-key :pulse.api/server [_ {:keys [handler host port]}]
  (jetty/run-jetty handler
                   {:host host
                    :port port
                    :join? false
                    :send-server-version? false
                    ;; On stop, let requests in flight finish before closing.
                    :configurator (fn [^Server server] (.setStopTimeout server 5000))}))

(defmethod ig/halt-key! :pulse.api/server [_ ^Server server]
  (.stop server))

(defn start
  "Starts the system described by `config` (see `pulse.api.config/load-config`) and returns it."
  [config]
  (ig/init config))

(defn stop
  "Stops a system returned by `start`."
  [system]
  (ig/halt! system))

(defn port
  "The port the server of `system` listens on (useful when the configured port is 0)."
  [system]
  (.getLocalPort ^ServerConnector (first (.getConnectors ^Server (:pulse.api/server system)))))
