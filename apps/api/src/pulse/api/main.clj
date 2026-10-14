(ns pulse.api.main
  "Starts the api: `clojure -M:dev -m pulse.api.main [profile]`, or `bb dev [profile]`.
  The profile is the argument, else the API_PROFILE environment variable, else `dev`."
  (:require [pulse.api.config :as config]
            [pulse.api.system :as system]
            [pulse.platform.config :as platform-config]
            [taoensso.telemere :as t]))

(defn- profile [args]
  (keyword (or (first args) (System/getenv "API_PROFILE") "dev")))

(defn- load-config-or-exit
  "A wrong configuration is reported in plain words and ends the process; anything else is a
  bug and keeps its stack trace."
  [profile]
  (try
    (config/load-config {:profile profile})
    (catch clojure.lang.ExceptionInfo e
      (if (platform-config/invalid-config? e)
        (do (binding [*out* *err*]
              (println (str "The api cannot start with profile " profile ".\n" (ex-message e))))
            (System/exit 1))
        (throw e)))))

(defn -main [& args]
  (let [profile (profile args)
        config (load-config-or-exit profile)
        system (system/start config)]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable (fn []
                                           (t/event! :pulse.api/stopping {:level :info})
                                           (system/stop system))
                               "api-shutdown"))
    (t/event! :pulse.api/started
              {:level :info
               :data {:profile profile
                      :url (str "http://" (get-in config [:pulse.api/server :host])
                                ":" (system/port system))}})
    @(promise)))
