(ns pulse.web.system
  "The portal's components: how each key of the system map (`pulse/web/config.edn`) is started and stopped. Integrant
  starts them in dependency order and stops them in the reverse order, so the server stops taking requests before its
  client of the api closes, and the key cache and the logging go last."
  (:require [integrant.core :as ig]
            [pulse.platform.system :as platform]
            [pulse.web.handler :as handler]))

;; Logging, the key cache, the server and the client of the api start and stop as in every Pulse service: the methods
;; are in pulse.platform.system, and these keys are those components.
(derive :pulse.web/logging :pulse.platform/logging)
(derive :pulse.web/jwks :pulse.platform/jwks)
(derive :pulse.web/api :pulse.platform/service-client)
(derive :pulse.web/server :pulse.platform/server)

(defmethod ig/init-key :pulse.web/handler [_ options]
  (handler/create options))

(defn start
  "Starts the system described by `config` (see `pulse.web.config/load-config`) and returns it."
  [config]
  (ig/init config))

(defn stop
  "Stops a system returned by `start`."
  [system]
  (ig/halt! system))

(defn port
  "The port the server of `system` listens on (useful when the configured port is 0)."
  [system]
  (platform/port (:pulse.web/server system)))
