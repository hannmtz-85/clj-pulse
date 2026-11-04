(ns smoke-checks
  "What `bb smoke` (ADR-0030) decides from what it reads of the containers and the services, as pure functions: the verdicts, so that they
  are tested on the JVM, and `smoke` does the asking. They use nothing that only Babashka has."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]))

(def issuer
  "The issuer of the realm, which is the `iss` of every token whatever address the token was asked at: the one a browser reaches."
  "http://localhost:8080/realms/pulse")

(def back-channel
  "The identity provider as the containers reach it."
  "http://keycloak:8080")

(def services
  "The applications of the profile `apps`."
  ["api" "scheduler" "web" "spa"])

(defn parse-args
  "The options of `bb smoke`: `--timeout-seconds` is how long to wait for the jobs of the scheduler to have run (they run every 15, 30
  and 60 seconds), and `--no-browser` leaves out the part that needs Chrome or Edge. An argument that is none of them is an exception."
  [args]
  (loop [args (seq args)
         options {:timeout-seconds 150 :browser? true}]
    (if-not args
      options
      (let [[argument & more] args]
        (cond
          (= "--no-browser" argument) (recur more (assoc options :browser? false))
          (and (= "--timeout-seconds" argument) (some-> (first more) parse-long pos?))
          (recur (next more) (assoc options :timeout-seconds (parse-long (first more))))
          :else (throw (ex-info (str "I do not know what to do with " (pr-str argument) ". The options are --timeout-seconds N and --no-browser.")
                                {:argument argument})))))))

(defn parse-ps
  "The containers `docker compose ps --format json` lists, as `{:service :state :health}`, whether the output is one JSON document for
  each container (Compose 2.21 and later) or one array (earlier). `:health` is nil for a container with no health check."
  [output]
  (let [text (str/trim (str output))
        parsed (cond
                 (str/blank? text) []
                 (str/starts-with? text "[") (json/read-str text :key-fn keyword)
                 :else (map #(json/read-str % :key-fn keyword) (remove str/blank? (str/split-lines text))))]
    (vec (for [{:keys [Service State Health]} parsed]
           {:service Service :state State :health (when-not (str/blank? Health) Health)}))))

(defn container-problems
  "What is wrong with the containers `ps` lists, as sentences, for the `wanted` services: one that is missing, one that is not running,
  and one that is not healthy."
  [wanted ps]
  (vec (mapcat (fn [service]
                 (let [{:keys [state health] :as found} (first (filter #(= service (:service %)) ps))]
                   (cond
                     (nil? found) [(str service " has no container")]
                     (not= "running" state) [(str service " is " (or state "in no state") ", not running")]
                     (not= "healthy" health) [(str service " is " (or health "without a health check") ", not healthy")])))
               wanted)))

(defn restart-problems
  "What is wrong with a container that was killed for lack of memory or restarted, from `docker inspect`: `oom-killed` and `restarts`."
  [service oom-killed restarts]
  (cond-> []
    (true? oom-killed) (conj (str service " was killed for lack of memory"))
    (and (number? restarts) (pos? restarts)) (conj (str service " was restarted " restarts " time" (when (> restarts 1) "s")))))

(defn issuer-problems
  "What is wrong with the `iss` of a token asked at the back channel (the claims `claims` of `who`): it must be the issuer of the realm,
  the address a browser reaches, and not the address the token was asked at."
  [who claims]
  (cond
    (nil? (:iss claims)) [(str "the token of " who " has no issuer")]
    (not= issuer (:iss claims)) [(str "the token of " who " says it was issued by " (:iss claims) ", not " issuer)]
    :else []))

(defn discovery-problems
  "What is wrong with the discovery document of the realm as a container reads it at the back channel: the issuer is the one a browser
  reaches, the endpoints that a service calls are at the back channel, and the one that a browser follows is where a browser reaches it
  (`hostname-backchannel-dynamic`, which `compose.yaml` sets)."
  [document]
  (cond-> []
    (not= issuer (:issuer document)) (conj (str "the issuer is " (pr-str (:issuer document))))
    (not (str/starts-with? (str (:token_endpoint document)) (str back-channel "/"))) (conj (str "the token endpoint is " (pr-str (:token_endpoint document)) ", not at " back-channel))
    (not (str/starts-with? (str (:jwks_uri document)) (str back-channel "/"))) (conj (str "the keys are at " (pr-str (:jwks_uri document)) ", not at " back-channel))
    (not (str/starts-with? (str (:authorization_endpoint document)) "http://localhost:8080/")) (conj (str "the authorization endpoint is " (pr-str (:authorization_endpoint document)) ", which a browser does not reach"))))

(defn jobs-settled?
  "Whether every job of `body` (the answer of `GET /api/admin/jobs`) has run at least once, and there are `expected` of them."
  [expected body]
  (let [items (:items body)]
    (and (= expected (count items))
         (every? #(some? (:lastRun %)) items))))

(defn jobs-problems
  "What is wrong with the jobs of `body` once they have run: a job whose last run was not ok, and a job that has failed or been
  interrupted at all."
  [body]
  (vec (mapcat (fn [{:keys [id lastRun counts]}]
                 (cond-> []
                   (not= "ok" (:outcome lastRun)) (conj (str id ": the last run was " (pr-str (:outcome lastRun))))
                   (pos? (+ (or (:failed counts) 0) (or (:interrupted counts) 0)))
                   (conj (str id ": " (:failed counts) " failed and " (:interrupted counts) " interrupted"))))
               (:items body))))

(defn script-name
  "The name of the script of the workspace's page, `main.<hash>.js`, from the text of `index.html`; nil when it loads none."
  [html]
  (second (re-find #"/js/(main\.[0-9A-Fa-f]{8,}\.js)" (str html))))
