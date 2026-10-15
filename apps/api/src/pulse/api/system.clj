(ns pulse.api.system
  "The api's components: how each key of the system map (`pulse/api/config.edn`) is started and
  stopped. Integrant starts them in dependency order and stops them in the reverse order, so
  the server stops taking requests before the key cache and the logging go away."
  (:require [integrant.core :as ig]
            [pulse.api.handler :as handler]
            [pulse.fhir.client.http :as fhir-http]
            [pulse.fhir.client.memory :as fhir-memory]
            [pulse.fhir.synthetic :as synthetic]
            [pulse.platform.system :as platform]))

;; Logging, the key cache, the verifier and the server start and stop as in every Pulse service:
;; the methods are in pulse.platform.system, and these keys are those components.
(derive :pulse.api/logging :pulse.platform/logging)
(derive :pulse.api/jwks :pulse.platform/jwks)
(derive :pulse.api/verifier :pulse.platform/verifier)
(derive :pulse.api/server :pulse.platform/server)

(defmethod ig/init-key :pulse.api/fhir [_ {:keys [kind] :as options}]
  (case kind
    :http (fhir-http/client (dissoc options :kind))
    ;; The "lite" backend: a small FHIR server in memory, holding the demo cohort.
    :memory (fhir-memory/memory-client (synthetic/generate (synthetic/cohort)))))

(defmethod ig/init-key :pulse.api/handler [_ options]
  (handler/create options))

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
  (platform/port (:pulse.api/server system)))
