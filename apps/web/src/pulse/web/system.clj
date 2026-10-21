(ns pulse.web.system
  "The portal's components: how each key of the system map (`pulse/web/config.edn`) is started and stopped. Integrant
  starts them in dependency order and stops them in the reverse order, so the server stops taking requests before its
  client of the api closes, and the key cache and the logging go last."
  (:require [integrant.core :as ig]
            [pulse.platform.system :as platform]
            [pulse.web.handler :as handler]
            [pulse.web.oidc :as oidc]
            [pulse.web.session :as session]))

;; Logging, the key cache, the verifiers of the two tokens of a sign-in, the server and the client of the api start and stop
;; as in every Pulse service: the methods are in pulse.platform.system, and these keys are those components.
(derive :pulse.web/logging :pulse.platform/logging)
(derive :pulse.web/jwks :pulse.platform/jwks)
(derive :pulse.web/access-verifier :pulse.platform/verifier)
(derive :pulse.web/id-verifier :pulse.platform/verifier)
(derive :pulse.web/api :pulse.platform/service-client)
(derive :pulse.web/server :pulse.platform/server)

;; The portal as a client of the identity provider. Nothing is asked of it by starting, so a portal can start before Keycloak
;; does, and there is nothing to stop.
(defmethod ig/init-key :pulse.web/oidc [_ options]
  (oidc/client options))

;; The sessions are in memory: no thread, and nothing to stop. A restart ends them all.
(defmethod ig/init-key :pulse.web/sessions [_ options]
  (session/memory-store options))

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
