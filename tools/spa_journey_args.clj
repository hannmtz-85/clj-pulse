(ns spa-journey-args
  "What `bb spa:journey` reads of its command line and decides about its surroundings, as pure functions (ADR-0029): which options it
  takes, what it tells the journey, and what the api it starts is told. They use nothing that only Babashka has, so the tests run them
  on the JVM."
  (:require [clojure.string :as str]))

(def api-environment
  "The environment of the api the journey starts (`bb dev lite`): the simulator is off, so that the readings are the cohort's and do not
  move under a check, and the rules run every five seconds, so that the alerts are there in seconds and not in a minute."
  {"SCHEDULER_SIMULATOR" "false"
   "SCHEDULER_EVALUATE_EVERY_MS" "5000"})

(def ports
  "The ports the journey needs for itself, by what listens there: `bb dev lite` has the api, the scheduler and the portal, and the
  release of the workspace is served on 3000. Each must be free when the journey starts, so that it never looks at somebody else's."
  {:workspace 3000 :portal 3300 :api 4000 :scheduler 5000})

(def keycloak-default "http://localhost:8080")

(defn parse
  "The options of `bb spa:journey` from its arguments: `{:build? :shots :only :keycloak}`, or `{:error text}` for an argument it does
  not know or an option that lacks its value. `--no-build` uses the release that is there; `--shots <dir>` writes the pictures;
  `--only a,b` runs those sections of the journey; `--keycloak <url>` says where the identity provider is."
  [args]
  (loop [[arg & more :as all] (seq args)
         options {:build? true :keycloak keycloak-default}]
    (cond
      (empty? all) options
      (= "--no-build" arg) (recur more (assoc options :build? false))
      (contains? #{"--shots" "--only" "--keycloak"} arg)
      (if (and (seq more) (not (str/starts-with? (first more) "--")))
        (recur (rest more) (assoc options (keyword (subs arg 2)) (first more)))
        {:error (str "The option " arg " needs a value.")})
      :else {:error (str "Unknown option " (pr-str arg) ". The options are --no-build, --shots <dir>, --only <sections> and --keycloak <url>.")})))

(defn node-arguments
  "The arguments of `apps/spa/journey.mjs` for the options, the addresses of what the journey started: the workspace, the api, the
  identity provider and the portal."
  [{:keys [shots only keycloak]}]
  (cond-> ["--spa" (str "http://localhost:" (:workspace ports))
           "--api" (str "http://localhost:" (:api ports))
           "--keycloak" (or keycloak keycloak-default)
           "--portal" (str "http://localhost:" (:portal ports))]
    shots (into ["--shots" shots])
    only (into ["--only" only])))

(defn node-command
  "The command that runs the journey, as the strings of a process: `node`, the script and the arguments of `node-arguments`, all flat (a process
  takes each element as one argument, so a vector inside the vector would be one argument that is the text of a vector)."
  [options]
  (into ["node" "apps/spa/journey.mjs"] (node-arguments options)))

(defn busy
  "The names of the ports that are not free, from `free?`, a function from a port to whether it is free, in the order of `ports`."
  [free?]
  (vec (for [[what port] (sort-by val ports) :when (not (free? port))] (str (name what) " (" port ")"))))

(defn tail
  "The last `n` lines of `text`, for what to show of a log when something did not start."
  [text n]
  (str/join "\n" (take-last n (str/split-lines (str text)))))
