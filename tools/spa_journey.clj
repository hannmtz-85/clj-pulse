(ns spa-journey
  "The acceptance of the clinical workspace in a real browser, run by `bb spa:journey` (ADR-0029): it makes the release, starts the api
  (`bb dev lite`, with the simulator off and the rules every five seconds) and the server of the release, runs
  `apps/spa/journey.mjs` against them and stops what it started, whatever happens. It needs the stack (`bb up`) for Keycloak, and
  nothing else running on the ports it uses: it must never look at somebody else's api, because it acknowledges an alert and confirms a
  request, and the in-memory FHIR server of `bb dev lite` is what it leaves clean by being stopped."
  (:refer-clojure :exclude [run!])
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [spa]
            [spa-journey-args :as args]))

(def ^:private log-dir "apps/spa/target/journey")

(defn- status
  "The status of a GET to `url`, or 0 when nothing answers."
  [url]
  (try (:status (http/get url {:throw false :timeout 3000}))
       (catch Exception _ 0)))

(defn- port-free? [port]
  (try (with-open [_ (java.net.ServerSocket. port 1 (java.net.InetAddress/getByName "127.0.0.1"))] true)
       (catch java.io.IOException _ false)))

(defn- fail! [& lines]
  (doseq [line lines] (println line))
  (System/exit 1))

(defn- preflight!
  "Stops with a word on what is missing before anything is built or started."
  [{:keys [keycloak]}]
  (when-not (fs/exists? ".env")
    (fail! "There is no .env: run `bb up` (or `bb env:init`) first."))
  (when-not (fs/exists? (fs/path "apps/spa/node_modules/axe-core"))
    (fail! "The npm packages of the workspace are not installed (axe-core is one of them): run `npm ci` in apps/spa."))
  (when-not (= 200 (status (str keycloak "/realms/pulse/.well-known/openid-configuration")))
    (fail! (str "Keycloak does not answer at " keycloak ": start the stack with `bb up`.")))
  (when-let [busy (seq (args/busy port-free?))]
    (fail! (str "These ports are in use: " (str/join ", " busy) ".")
           "Stop `bb dev`, `bb web`, `bb scheduler` and `bb spa:serve`: the journey starts its own api and server, and must not look at anybody else's.")))

(defn- start!
  "Starts `command` with `env` added to the environment, its output in a log of its own: `{:name :log :process}`."
  [what env command]
  (let [log (io/file log-dir (str what ".log"))]
    (io/make-parents log)
    {:name what
     :log log
     :process (apply p/process {:extra-env env :out :write :out-file log :err :out} command)}))

(defn- wait-until-ready
  "Waits until `url` answers 200, for at most `ms`; says so, with the end of the log, when the process stopped or the time ran out."
  [{:keys [name log process]} url ms]
  (let [end (+ (System/currentTimeMillis) ms)]
    (loop []
      (cond
        (= 200 (status url)) true
        (not (p/alive? process)) (do (println (str "The " name " stopped before it was ready. The end of " log ":"))
                                     (println (args/tail (slurp log) 30))
                                     false)
        (> (System/currentTimeMillis) end) (do (println (str "The " name " was not ready at " url " in " (quot ms 1000) " seconds. The end of " log ":"))
                                               (println (args/tail (slurp log) 30))
                                               false)
        :else (do (Thread/sleep 1500) (recur))))))

(defn run!
  "Runs the journey: see the namespace. Exits with the journey's own code, or with 1 when something it needs did not start."
  [arguments]
  (let [{:keys [error build?] :as options} (args/parse arguments)]
    (when error (fail! error))
    (preflight! options)
    (when build? (spa/release!))
    (let [started (atom [])
          stop-all! (fn [] (doseq [{:keys [process]} (reverse @started)] (p/destroy-tree process)))]
      (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable stop-all!))
      (let [exit (try
                   (println "Starting the api (bb dev lite) and the server of the release...")
                   (swap! started conj (start! "api" args/api-environment ["clojure" "-M:dev" "-m" "pulse.dev" "lite"]))
                   (swap! started conj (start! "workspace" {} ["clojure" "-M:dev" "-m" "pulse.spa.main"]))
                   (if (and (wait-until-ready (first @started) (str "http://localhost:" (:api args/ports) "/readyz") 240000)
                            (wait-until-ready (second @started) (str "http://localhost:" (:workspace args/ports) "/healthz") 120000))
                     (do (println "Running the journey.")
                         (:exit @(apply p/process {:inherit true} (args/node-command options))))
                     1)
                   (finally (stop-all!)))]
        (println (if (zero? exit) "The journey passed." (str "The journey did not pass (exit " exit "). The logs of what it started are in " log-dir ".")))
        (System/exit exit)))))
