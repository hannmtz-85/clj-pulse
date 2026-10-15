(ns pulse.scheduler.system
  "The scheduler's components: how each key of the system map (`pulse/scheduler/config.edn`)
  is started and stopped. Integrant starts them in dependency order and stops them in the reverse
  order, so the server stops taking requests before the executor stops the jobs, and the key
  cache and the logging go last."
  (:require [integrant.core :as ig]
            [pulse.fhir.client.http :as fhir-http]
            [pulse.fhir.client.memory :as fhir-memory]
            [pulse.platform.system :as platform]
            [pulse.scheduler.executor :as executor]
            [pulse.scheduler.handler :as handler]
            [pulse.scheduler.job :as job]
            [pulse.scheduler.jobs :as jobs]))

;; Logging, the key cache, the verifier, the server, the keeper of the service's own token and its
;; client of the api start and stop as in every Pulse service: the methods are in
;; pulse.platform.system, and these keys are those components.
(derive :pulse.scheduler/logging :pulse.platform/logging)
(derive :pulse.scheduler/jwks :pulse.platform/jwks)
(derive :pulse.scheduler/verifier :pulse.platform/verifier)
(derive :pulse.scheduler/server :pulse.platform/server)
(derive :pulse.scheduler/token :pulse.platform/token-source)
(derive :pulse.scheduler/api :pulse.platform/service-client)

(defmethod ig/init-key :pulse.scheduler/fhir [_ {:keys [kind] :as options}]
  (case kind
    :http (fhir-http/client (dissoc options :kind))
    ;; Empty, for the tests, which put in it what they need. The api's "lite" backend is a server
    ;; in memory too, but it is the api's: alerts written to another one would never be seen.
    :memory (fhir-memory/memory-client [])))

(defmethod ig/init-key :pulse.scheduler/jobs [_ options]
  (job/check! (jobs/catalogue options)))

(defmethod ig/init-key :pulse.scheduler/executor [_ {:keys [jobs grace-ms]}]
  (executor/start jobs {:grace-ms grace-ms}))

(defmethod ig/halt-key! :pulse.scheduler/executor [_ running]
  (executor/stop! running))

(defmethod ig/init-key :pulse.scheduler/handler [_ options]
  (handler/create options))

(defn start
  "Starts the system described by `config` (see `pulse.scheduler.config/load-config`) and
  returns it."
  [config]
  (ig/init config))

(defn stop
  "Stops a system returned by `start`."
  [system]
  (ig/halt! system))

(defn port
  "The port the server of `system` listens on (useful when the configured port is 0)."
  [system]
  (platform/port (:pulse.scheduler/server system)))
