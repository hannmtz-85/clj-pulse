(ns pulse.dev
  "The apps built so far in one JVM, for development: the api and the scheduler as one Integrant system
  (`bb dev`, and `(go)` in the REPL, ADR-0023).

  The two configurations are merged, since their keys are namespaced by service. The scheduler's
  client of the api comes after the api's server, so that it starts after it and stops before it. When
  the api's FHIR is a server in memory (`lite`), the scheduler's is that one, because an alert that it
  wrote to another would never be seen by the api.

  The variables that the scheduler needs and the process does not have, such as
  SCHEDULER_CLIENT_SECRET, are read from `.env`, as `bb scheduler` does."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [integrant.core :as ig]
            [pulse.api.config :as api-config]
            [pulse.api.system :as api]
            [pulse.platform.config :as platform-config]
            [pulse.scheduler.config :as scheduler-config]
            [pulse.scheduler.system :as scheduler]
            [taoensso.telemere :as t]))

;; ---------------------------------------------------------------------------
;; .env

(defn- unquote-value [value]
  (if-let [[_ double single] (re-matches #"\"(.*)\"|'(.*)'" value)]
    (or double single)
    value))

(defn read-dotenv
  "The variables in the file `.env` at `path`, as a map of name to value; empty when there is no
  such file. A line is `NAME=value` (with `export` before the name, or quotes around the value, if
  it likes); anything else, such as a blank line or a comment, is not a variable."
  [path]
  (let [file (io/file path)]
    (if (.exists file)
      (into {}
            (keep (fn [line]
                    (when-let [[_ variable value] (re-matches #"\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*" line)]
                      [variable (unquote-value value)])))
            (str/split-lines (slurp file)))
      {})))

(defn environment
  "The variables of `.env` at `path` that the process does not have: what `#env` should read as well
  as the environment of the process, which wins. `process-env` is that environment, a map of name to
  value (the real one by default)."
  ([path] (environment path (System/getenv)))
  ([path process-env]
   (into {} (remove (fn [[variable _]] (contains? process-env variable))) (read-dotenv path))))

;; ---------------------------------------------------------------------------
;; The system

(defn config
  "The configuration of the api and the scheduler as one system. Options: `:profile` of the api
  (`:dev`, the default, `:lite` or `:test`; the scheduler has `:dev` or `:test`), `:dotenv`, the path
  of the file that gives the variables the process lacks (`.env`), and `:process-env`, the variables
  the process has (the real ones by default)."
  ([] (config {}))
  ([{:keys [profile dotenv process-env] :or {profile :dev dotenv ".env"}}]
   (binding [platform-config/*environment* (merge (if process-env (environment dotenv process-env) (environment dotenv))
                                                  platform-config/*environment*)]
     (let [api (api-config/load-config {:profile profile})
           scheduler (scheduler-config/load-config {:profile (if (= :test profile) :test :dev)})]
       (cond-> (-> (merge api scheduler)
                   (assoc-in [:pulse.scheduler/api :after] (ig/ref :pulse.api/server)))
         (= :memory (get-in api [:pulse.api/fhir :kind])) (assoc :pulse.scheduler/fhir (ig/ref :pulse.api/fhir)))))))

(defn- load-config-or-exit
  "A wrong configuration is reported in plain words and ends the process; anything else is a bug and
  keeps its stack trace."
  [profile]
  (try
    (config {:profile profile})
    (catch clojure.lang.ExceptionInfo e
      (if (platform-config/invalid-config? e)
        (do (binding [*out* *err*]
              (println (str "The apps cannot start with profile " profile ".\n" (ex-message e))))
            (System/exit 1))
        (throw e)))))

(defn -main
  "Starts the api and the scheduler: `clojure -M:dev -m pulse.dev [profile]`, or `bb dev [profile]`.
  The profile is the argument, else the API_PROFILE environment variable, else `dev`."
  [& args]
  (let [profile (keyword (or (first args) (System/getenv "API_PROFILE") "dev"))
        config (load-config-or-exit profile)
        system (ig/init config)
        url (fn [server-key port]
              (str "http://" (get-in config [server-key :host]) ":" port))]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable (fn []
                                           (t/event! :pulse.dev/stopping {:level :info})
                                           (ig/halt! system))
                               "dev-shutdown"))
    (t/event! :pulse.dev/started
              {:level :info
               :data {:profile profile
                      :api (url :pulse.api/server (api/port system))
                      :scheduler (url :pulse.scheduler/server (scheduler/port system))}})
    @(promise)))
