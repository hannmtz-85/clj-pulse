(ns smoke
  "`bb smoke` (ADR-0030): the four applications run in their containers (`bb up`, `bb seed` and `bb apps`), and this asks them what only
  the containers can answer. The checks that the images could not make when they were built: that the containers are healthy and
  were not killed, that a token asked at the back channel (`keycloak:8080`) says it was issued where a browser reaches Keycloak
  (`localhost:8080`) so that the services accept each other's tokens, that nginx answers the table of requests as the Ring server does
  (`spa-requests`), that the scheduler works through the api and HAPI, and, in a real browser, that a patient and a practitioner
  can sign in and see their pages.

  It prints a line for each check and exits with 1 when one fails. The pure parts, the verdicts, are `smoke-checks`."
  (:refer-clojure :exclude [run!])
  (:require [babashka.http-client :as http]
            [babashka.process :as p]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [env]
            [kc]
            [pulse.spa.security :as security]
            [smoke-checks :as checks]
            [spa-requests]))

(def api "http://localhost:4000")
(def portal "http://localhost:3300")
(def spa "http://localhost:3000")

(def ^:private results (atom []))

(defn- check!
  "Records and prints a check."
  [label ok? & [detail]]
  (swap! results conj (boolean ok?))
  (println (str (if ok? "PASS" "FAIL") "  " label (when (and (not ok?) detail) (str "  -- " detail))))
  (boolean ok?))

(defn- check-problems! [label problems]
  (check! label (empty? problems) (str/join "; " problems)))

(defn- heading [title]
  (println)
  (println (str "== " title)))

;; ---------------------------------------------------------------------------
;; Asking

(def ^:private client (http/client {:follow-redirects :never}))

(defn- request
  "The answer to `method` `url` as `{:status :headers :body}`, or `{:error message}` when nothing answered. `:token` is a bearer token."
  [method url & [{:keys [token]}]]
  (try
    (-> (http/request {:client client :method method :uri url :throw false :timeout 10000
                       :headers (cond-> {} token (assoc "Authorization" (str "Bearer " token)))})
        (select-keys [:status :headers :body]))
    (catch Exception e {:error (ex-message e)})))

(defn- body-json [response]
  (try (json/read-str (str (:body response)) :key-fn keyword) (catch Exception _ nil)))

(defn- sh [& args]
  (apply p/shell {:out :string :err :string :continue true} args))

(defn- compose [& args]
  (apply sh "docker" "compose" "--profile" "apps" args))

(defn- exec-in
  "Runs `script` with `sh` in the container of `service`; the script goes in through the standard input, so that nothing in it is a
  matter of how a shell quotes an argument. Returns `{:exit :out :err}`."
  [service script]
  (p/shell {:in script :out :string :err :string :continue true} "docker" "compose" "--profile" "apps" "exec" "-T" service "sh"))

(defn- poll
  "Calls `f` every five seconds until it gives something, for `timeout-seconds` at most: that, or nil."
  [timeout-seconds f]
  (let [deadline (+ (System/currentTimeMillis) (* 1000 timeout-seconds))]
    (loop []
      (or (try (f) (catch Exception _ nil))
          (when (< (System/currentTimeMillis) deadline)
            (Thread/sleep 5000)
            (recur))))))

(defn- password [] (env/get-var "DEMO_USER_PASSWORD"))

(defn- token-of
  "An access token of the demo user `username`, as the clinical workspace gets it."
  [username]
  (:access_token (kc/login {:username username :password (password)})))

;; ---------------------------------------------------------------------------
;; The checks

(defn- containers! []
  (heading "The containers")
  (let [ps (checks/parse-ps (:out (compose "ps" "--all" "--format" "json")))]
    (check-problems! (str "the " (count checks/services) " applications are running and healthy") (checks/container-problems checks/services ps)))
  (doseq [service checks/services
          :let [id (str/trim (:out (compose "ps" "-q" service)))
                [oom restarts] (when-not (str/blank? id)
                                 (str/split (str/trim (:out (sh "docker" "inspect" "--format" "{{.State.OOMKilled}} {{.RestartCount}}" id))) #"\s+"))]]
    (check-problems! (str service " was not killed for lack of memory or restarted")
                     (checks/restart-problems service (= "true" oom) (some-> restarts parse-long)))))

(defn- up? [response & names]
  (and (= 200 (:status response))
       (let [found (:checks (body-json response))]
         (every? #(= "up" (get found %)) names))))

(defn- readiness! []
  (heading "Readiness")
  (let [response (request :get (str api "/readyz"))]
    (check! "the api is ready: it has Keycloak's keys and HAPI answers" (up? response :keycloak :fhir) (pr-str (or (:error response) [(:status response) (:body response)]))))
  (let [response (request :get (str portal "/readyz"))]
    (check! "the portal is ready: it has Keycloak's keys and the api answers" (up? response :keycloak :api) (pr-str (or (:error response) [(:status response) (:body response)]))))
  (let [response (request :get (str spa "/healthz"))]
    (check! "the workspace is served" (= 200 (:status response)) (pr-str (or (:error response) (:status response)))))
  (let [{:keys [exit out err]} (exec-in "scheduler" "wget -q -O- http://127.0.0.1:5000/readyz\n")
        response {:status (if (zero? exit) 200 503) :body out}]
    (check! "the scheduler is ready: it has Keycloak's keys and its own token, the api and FHIR answer, and the executor runs"
            (up? response :keycloak :executor :token :api :fhir) (pr-str [out err])))
  (check! "the scheduler is not reachable from the machine: the administrator reaches it through the api"
          (try (with-open [socket (java.net.Socket.)]
                 (.connect socket (java.net.InetSocketAddress. "127.0.0.1" 5000) 1500)
                 false)
               (catch java.io.IOException _ true))))

(defn- issuer! []
  (heading "The issuer and the back channel")
  (let [discovery (exec-in "api" "wget -q -O- http://keycloak:8080/realms/pulse/.well-known/openid-configuration\n")]
    (check-problems! "asked at keycloak:8080, the realm says it is issued at localhost:8080 and gives the endpoints a service calls at keycloak:8080"
                     (if (zero? (:exit discovery))
                       (checks/discovery-problems (try (json/read-str (:out discovery) :key-fn keyword) (catch Exception _ {})))
                       [(str "the api could not ask keycloak:8080: " (str/trim (:err discovery)))])))
  (doseq [[service client secret] [["api" "pulse-api" "API_CLIENT_SECRET"] ["scheduler" "pulse-scheduler" "SCHEDULER_CLIENT_SECRET"]]]
    (let [{:keys [exit out err]} (exec-in service (str "wget -q -O- --post-data \"grant_type=client_credentials&client_id=" client "&client_secret=$" secret
                                                       "\" http://keycloak:8080/realms/pulse/protocol/openid-connect/token\n"))
          claims (when (zero? exit)
                   (try (some-> (json/read-str out :key-fn keyword) :access_token kc/decode-jwt) (catch Exception _ nil)))]
      (check-problems! (str "the token of " service ", asked by its container at keycloak:8080, says it was issued at localhost:8080")
                       (if claims
                         (checks/issuer-problems (str "the " service) claims)
                         [(str "no token: " (str/trim (str err out)))])))))

(defn- tokens! []
  (heading "Tokens of people, checked by the containers")
  (let [try-token (fn [username] (try (token-of username) (catch Exception e (println "     no token for" username ":" (ex-message e)) nil)))
        ana (try-token "ana.garcia@pulse.test")
        rivera (try-token "dr.rivera@pulse.test")
        admin (try-token "admin@pulse.test")]
    (check! "a call with no token is a 401" (= 401 (:status (request :get (str api "/api/me")))))
    (let [response (request :get (str api "/api/me") {:token ana})]
      (check! "Ana's token is accepted by the api: it fetched Keycloak's keys at keycloak:8080 and the issuer is the one in the token"
              (= 200 (:status response)) (pr-str (select-keys response [:status :error]))))
    (check! "Ana is refused the jobs of the scheduler" (= 403 (:status (request :get (str api "/api/admin/jobs") {:token ana}))))
    (check! "Dr. Rivera sees patients"
            (let [response (request :get (str api "/api/patients") {:token rivera})]
              (and (= 200 (:status response)) (pos? (count (:items (body-json response)))))))
    (let [response (request :get (str api "/api/admin/jobs") {:token admin})
          body (body-json response)]
      (check! "the administrator sees the three jobs of the scheduler: the api asked it with a token of its own, got at keycloak:8080"
              (and (= 200 (:status response)) (= 3 (:total body))) (pr-str (select-keys response [:status :error]))))))

(defn- workspace! []
  (heading "The workspace: nginx answers as the Ring server does")
  (let [index (request :get (str spa "/index.html"))
        script (checks/script-name (:body index))]
    (when (check! "the page loads a script with the hash of its content in its name" (some? script) (pr-str (select-keys index [:status :error])))
      (let [context {:headers (dissoc (:headers (security/header-options {:api-base-url api :issuer checks/issuer})) "Cache-Control")
                     :page (:body index)}
            rows (spa-requests/requests script)
            failures (doall
                      (for [row rows
                            :let [response (request (:method row) (str spa (:path row)))
                                  problems (if (:error response)
                                             [(:error response)]
                                             (spa-requests/problems row response context))]
                            :when (seq problems)]
                        [row problems]))]
        (check! (str (- (count rows) (count failures)) " of " (count rows) " requests are answered as the table says")
                (empty? failures))
        (doseq [[row problems] failures]
          (println (str "      " (str/upper-case (name (:method row))) " " (:path row) ": " (str/join "; " problems))))))))

(defn- scheduler! [{:keys [timeout-seconds]}]
  (heading "The scheduler works through the api, Keycloak and HAPI")
  (let [jobs (poll timeout-seconds #(let [body (body-json (request :get (str api "/api/admin/jobs") {:token (token-of "admin@pulse.test")}))]
                                      (when (checks/jobs-settled? 3 body) body)))]
    (when (check! (str "the three jobs have run within " timeout-seconds " s") (some? jobs))
      (check-problems! "and none of them has failed" (checks/jobs-problems jobs))))
  (let [total (poll timeout-seconds #(let [body (body-json (request :get (str api "/api/alerts?status=active&limit=1") {:token (token-of "dr.rivera@pulse.test")}))]
                                       (when (pos? (or (:total body) 0)) (:total body))))]
    (check! "the rules engine has raised an alert that the practitioner's inbox shows: readings written to HAPI, read through the api" (some? total))))

(defn- memory!
  "What each container uses, for the record. Not a check: the limit is, and a container that went over it was killed (above)."
  []
  (heading "Memory (for the record)")
  (println (:out (sh "docker" "stats" "--no-stream" "--format" "{{.Name}}\t{{.MemUsage}}\t{{.MemPerc}}"))))

(defn- browser! []
  (heading "In a real browser")
  (let [{:keys [exit]} (p/shell {:continue true} "node" "tools/smoke_browser.mjs")]
    (check! "a patient signs in at the portal and a practitioner at the workspace" (zero? exit))))

(defn run!
  "`bb smoke [--timeout-seconds N] [--no-browser]`."
  [args]
  (let [options (try (checks/parse-args args)
                     (catch clojure.lang.ExceptionInfo e
                       (println (ex-message e))
                       (System/exit 2)))]
    (reset! results [])
    (containers!)
    (readiness!)
    (issuer!)
    (tokens!)
    (workspace!)
    (scheduler! options)
    (when (:browser? options) (browser!))
    (memory!)
    (let [failed (count (remove true? @results))]
      (println)
      (println (str (- (count @results) failed) " of " (count @results) " checks passed"))
      (System/exit (if (zero? failed) 0 1)))))
