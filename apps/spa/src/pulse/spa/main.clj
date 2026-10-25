(ns pulse.spa.main
  "Serves the release of the workspace: `clojure -M:dev -m pulse.spa.main [profile]`. The profile is the argument, else the
  SPA_PROFILE environment variable, else `dev`."
  (:require [pulse.platform.config :as platform-config]
            [pulse.spa.config :as config]
            [pulse.spa.system :as system]
            [taoensso.telemere :as t]))

(defn- profile [args]
  (keyword (or (first args) (System/getenv "SPA_PROFILE") "dev")))

(defn- load-config-or-exit
  "A wrong configuration is reported in plain words and ends the process; anything else is a bug and keeps its stack trace."
  [profile]
  (try
    (config/load-config {:profile profile})
    (catch clojure.lang.ExceptionInfo e
      (if (platform-config/invalid-config? e)
        (do (binding [*out* *err*]
              (println (str "The server of the workspace cannot start with profile " profile ".\n" (ex-message e))))
            (System/exit 1))
        (throw e)))))

(defn -main [& args]
  (let [profile (profile args)
        config (load-config-or-exit profile)
        system (system/start config)]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable (fn []
                                           (t/event! :pulse.spa/shutting-down {:level :info})
                                           (system/stop system))
                               "spa-shutdown"))
    (t/event! :pulse.spa/listening
              {:level :info
               :data {:profile profile
                      :root (get-in config [:pulse.spa/handler :root])
                      :url (str "http://" (get-in config [:pulse.spa/server :host])
                                ":" (system/port system))}})
    @(promise)))
