(ns pulse.scheduler.main
  "Starts the scheduler: `clojure -M:dev -m pulse.scheduler.main [profile]`. The profile is the
  argument, else the SCHEDULER_PROFILE environment variable, else `dev`."
  (:require [pulse.platform.config :as platform-config]
            [pulse.scheduler.config :as config]
            [pulse.scheduler.system :as system]
            [taoensso.telemere :as t]))

(defn- profile [args]
  (keyword (or (first args) (System/getenv "SCHEDULER_PROFILE") "dev")))

(defn- load-config-or-exit
  "A wrong configuration is reported in plain words and ends the process; anything else is a
  bug and keeps its stack trace."
  [profile]
  (try
    (config/load-config {:profile profile})
    (catch clojure.lang.ExceptionInfo e
      (if (platform-config/invalid-config? e)
        (do (binding [*out* *err*]
              (println (str "The scheduler cannot start with profile " profile ".\n" (ex-message e))))
            (System/exit 1))
        (throw e)))))

(defn -main [& args]
  (let [profile (profile args)
        config (load-config-or-exit profile)
        system (system/start config)]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable (fn []
                                           (t/event! :pulse.scheduler/shutting-down {:level :info})
                                           (system/stop system))
                               "scheduler-shutdown"))
    (t/event! :pulse.scheduler/listening
              {:level :info
               :data {:profile profile
                      :url (str "http://" (get-in config [:pulse.scheduler/server :host])
                                ":" (system/port system))}})
    @(promise)))
