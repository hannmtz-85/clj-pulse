(ns pulse.web.main
  "Starts the portal: `clojure -M:dev -m pulse.web.main [profile]`. The profile is the argument, else the WEB_PROFILE
  environment variable, else `dev`."
  (:require [pulse.platform.config :as platform-config]
            [pulse.web.config :as config]
            [pulse.web.system :as system]
            [taoensso.telemere :as t]))

(defn- profile [args]
  (keyword (or (first args) (System/getenv "WEB_PROFILE") "dev")))

(defn- load-config-or-exit
  "A wrong configuration is reported in plain words and ends the process; anything else is a bug and keeps its stack
  trace."
  [profile]
  (try
    (config/load-config {:profile profile})
    (catch clojure.lang.ExceptionInfo e
      (if (platform-config/invalid-config? e)
        (do (binding [*out* *err*]
              (println (str "The portal cannot start with profile " profile ".\n" (ex-message e))))
            (System/exit 1))
        (throw e)))))

(defn -main [& args]
  (let [profile (profile args)
        config (load-config-or-exit profile)
        system (system/start config)]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable (fn []
                                           (t/event! :pulse.web/shutting-down {:level :info})
                                           (system/stop system))
                               "web-shutdown"))
    (t/event! :pulse.web/listening
              {:level :info
               :data {:profile profile
                      :url (str "http://" (get-in config [:pulse.web/server :host])
                                ":" (system/port system))}})
    @(promise)))
