(ns pulse.spa.system
  "The components of the server of the workspace's release: how each key of the system map (`pulse/spa/config.edn`) is started and
  stopped, in dependency order and the reverse."
  (:require [integrant.core :as ig]
            [pulse.platform.system :as platform]
            [pulse.spa.handler :as handler]))

;; Logging and the server start and stop as in every Pulse service: the methods are in pulse.platform.system
(derive :pulse.spa/logging :pulse.platform/logging)
(derive :pulse.spa/server :pulse.platform/server)

(defmethod ig/init-key :pulse.spa/handler [_ options]
  (handler/create options))

(defn start
  "Starts the system described by `config` (see `pulse.spa.config/load-config`) and returns it."
  [config]
  (ig/init config))

(defn stop
  "Stops a system returned by `start`."
  [system]
  (ig/halt! system))

(defn port
  "The port the server of `system` listens on (useful when the configured port is 0)."
  [system]
  (platform/port (:pulse.spa/server system)))
