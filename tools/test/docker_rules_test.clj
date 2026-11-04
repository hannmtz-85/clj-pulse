(ns docker-rules-test
  "The rules that the images and the services of the profile `apps` must keep (ADR-0030, point 7), as tests that read `compose.yaml`, the
  Dockerfiles and `.dockerignore`: nothing runs here, so a rule that is broken is found in `bb check` and not when someone starts the
  containers. The containers themselves are checked by `bb smoke`."
  (:require [build-plan :as plan]
            [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.spa.nginx :as nginx])
  (:import (java.util.regex Pattern)))

;; ---------------------------------------------------------------------------
;; Reading the files

(defn- compose [] (yaml/parse-string (slurp "compose.yaml")))

(defn- services [] (:services (compose)))

(defn- app-services
  "The services of the profile `apps`, as `{name service}` with the names as strings."
  []
  (into {} (for [[service-name service] (services)
                 :when (some #{"apps"} (:profiles service))]
             [(name service-name) service])))

(def ^:private jvm-apps (set plan/names))

(defn- instructions
  "The instructions of a Dockerfile, as `[INSTRUCTION arguments]` with the lines that continue one joined to it and the comments
  left out."
  [file]
  (let [logical (->> (str/split-lines (slurp file))
                     (remove #(str/starts-with? (str/trim %) "#"))
                     (reduce (fn [{:keys [lines open] :as state} line]
                               (let [continued? (str/ends-with? (str/trimr line) "\\")
                                     text (str/trimr (if continued? (subs (str/trimr line) 0 (dec (count (str/trimr line)))) line))]
                                 (cond
                                   open (let [joined (str open " " (str/triml text))]
                                          (if continued? (assoc state :open joined) {:lines (conj lines joined) :open nil}))
                                   continued? (assoc state :open text)
                                   :else (assoc state :lines (conj lines text)))))
                             {:lines [] :open nil})
                     :lines)]
    (vec (for [line logical
               :when (not (str/blank? line))
               :let [[_ instruction arguments] (re-matches #"(?s)\s*(\S+)\s*(.*)" line)]]
           [(str/upper-case instruction) arguments]))))

(defn- stages
  "The stages of a Dockerfile, each as `{:from :name :instructions}`."
  [file]
  (reduce (fn [stages [instruction arguments]]
            (if (= "FROM" instruction)
              (let [[_ image as] (re-matches #"(\S+)(?:\s+[Aa][Ss]\s+(\S+))?" (str/trim arguments))]
                (conj stages {:from image :name as :instructions []}))
              (update-in stages [(dec (count stages)) :instructions] conj [instruction arguments])))
          []
          (instructions file)))

(def ^:private jvm-dockerfile "infra/docker/jvm.Dockerfile")
(def ^:private spa-dockerfile "infra/docker/spa.Dockerfile")

;; ---------------------------------------------------------------------------
;; .dockerignore

(defn- glob->regex
  "A pattern of a `.dockerignore` as a regular expression on a path from the root of the context: `**/` is any directories,
  `*` and `?` stay within a name."
  ^Pattern [glob]
  (loop [chars (seq glob) out ""]
    (cond
      (empty? chars) (re-pattern (str "^" out "$"))
      (= "**/" (apply str (take 3 chars))) (recur (drop 3 chars) (str out "(?:.*/)?"))
      (= "**" (apply str (take 2 chars))) (recur (drop 2 chars) (str out ".*"))
      (= \* (first chars)) (recur (rest chars) (str out "[^/]*"))
      (= \? (first chars)) (recur (rest chars) (str out "[^/]"))
      :else (recur (rest chars) (str out (Pattern/quote (str (first chars))))))))

(defn- ignore-patterns [file]
  (vec (for [line (str/split-lines (slurp file))
             :let [line (str/trim line)]
             :when (and (not (str/blank? line)) (not (str/starts-with? line "#")))]
         line)))

(defn- ignored?
  "Whether the `.dockerignore` patterns leave `path` out of the context. The last pattern that matches the path, or a directory above it,
  decides, and one that begins with ! brings it back."
  [patterns path]
  (let [ancestors (let [segments (str/split path #"/")]
                    (for [n (range 1 (inc (count segments)))] (str/join "/" (take n segments))))]
    (reduce (fn [ignored pattern]
              (let [negated? (str/starts-with? pattern "!")
                    regex (glob->regex (if negated? (subs pattern 1) pattern))]
                (if (some #(re-find regex %) ancestors)
                  (not negated?)
                  ignored)))
            false
            patterns)))

(deftest the-matcher-of-dockerignore-reads-the-patterns-as-docker-does
  (let [patterns ["docs" "**/node_modules" "**/target" ".env" ".env.*" "!.env.example" "design/dist" "**/*.log"]]
    (testing "left out"
      (doseq [path ["docs" "docs/adr/0030.md" "apps/spa/node_modules/react/index.js" "node_modules" "a/b/target/x.jar" "target/api.jar"
                    ".env" ".env.local" "design/dist/index.html" "tools/x.log" "x.log"]]
        (is (ignored? patterns path) path)))
    (testing "kept"
      (doseq [path ["apps/spa/package.json" ".env.example" "design/src/pulse/design/spa.clj" "libs/ui/src/x.cljc" "infra/spa/nginx.conf"
                    "documents/x" "tools/target.clj" "apps/spa/public/pulse.css"]]
        (is (not (ignored? patterns path)) path)))
    (testing "the last pattern that matches decides"
      (is (not (ignored? ["a" "!a/b"] "a/b")) "a pattern that brings it back")
      (is (ignored? ["!a/b" "a"] "a/b")))))

;; ---------------------------------------------------------------------------
;; The services

(deftest the-profile-has-the-four-applications-and-only-them
  (is (= (conj jvm-apps "spa") (set (keys (app-services)))))
  (testing "and the infrastructure is what bb up starts, with no profile"
    (doseq [service ["postgres" "keycloak" "hapi"]]
      (is (nil? (:profiles (get (services) (keyword service)))) service))))

(deftest every-application-is-built-from-a-dockerfile-of-the-repository
  (doseq [[service-name service] (app-services)
          :let [{:keys [context dockerfile args]} (:build service)]]
    (testing service-name
      (is (= "." context) "the root of the repository: the libraries are beside the apps")
      (is (.exists (io/file dockerfile)))
      (if (contains? jvm-apps service-name)
        (do (is (= jvm-dockerfile dockerfile))
            (is (= {:APP service-name} args) "the app it builds is the one it is called"))
        (is (= spa-dockerfile dockerfile))))))

(deftest every-application-is-held-to-what-the-images-promise
  (doseq [[service-name service] (app-services)]
    (testing service-name
      (is (= true (:read_only service)) "nothing to write to but a temporary directory")
      (is (= ["/tmp"] (:tmpfs service)))
      (is (= ["ALL"] (:cap_drop service)) "no capability")
      (is (= ["no-new-privileges:true"] (:security_opt service)))
      (is (= "unless-stopped" (:restart service)))
      (is (some? (:mem_limit service)) "a limit of memory, since the machine has little")
      (is (= "CMD" (first (get-in service [:healthcheck :test]))) "a health check, so that depends_on can wait for it"))))

(deftest nothing-is-published-but-on-the-machine-itself
  (doseq [[service-name service] (app-services)
          port (:ports service)]
    (is (re-matches #"127\.0\.0\.1:\d+:\d+" port) (str service-name " " port)))
  (is (empty? (:ports (get (app-services) "scheduler"))) "the administrator reaches the scheduler through the api"))

(deftest the-ports-are-those-of-the-configuration-of-each-app-and-of-the-realm
  (letfn [(default-port [app variable]
            (some->> (slurp (str "apps/" app "/resources/pulse/" app "/config.edn"))
                     (re-find (re-pattern (str "#env " variable " (\\d+)")))
                     second
                     parse-long))]
    (testing "the api (4000), the portal (3300) and, inside the network, the scheduler (5000)"
      (doseq [[app variable] [["api" "API_PORT"] ["web" "WEB_PORT"] ["scheduler" "SCHEDULER_PORT"]]
              :let [port (default-port app variable)
                    service (get (app-services) app)]]
        (is (some? port) app)
        (is (every? #(str/ends-with? % (str ":" port)) (:ports service)) "published on the port it listens on")
        (is (= (str "http://127.0.0.1:" port "/readyz") (last (get-in service [:healthcheck :test]))) "and asked there")))
    (testing "the workspace is 3000 on the machine, where the realm registers it, and the port of nginx inside"
      (is (= ["127.0.0.1:3000:8080"] (:ports (get (app-services) "spa"))))
      (is (= nginx/port 8080))
      (is (= (str "http://127.0.0.1:" nginx/port "/healthz") (last (get-in (get (app-services) "spa") [:healthcheck :test])))))))

(deftest the-apps-listen-on-every-address-of-the-container-and-find-each-other-by-name
  (let [apps (app-services)
        env (fn [app] (:environment (get apps app)))]
    (is (= "0.0.0.0" (:API_HOST (env "api"))))
    (is (= "0.0.0.0" (:SCHEDULER_HOST (env "scheduler"))))
    (is (= "0.0.0.0" (:WEB_HOST (env "web"))))
    (is (= "http://scheduler:5000" (:SCHEDULER_BASE_URL (env "api"))))
    (is (= "http://api:4000" (:API_BASE_URL (env "scheduler"))))
    (is (= "http://api:4000" (:API_BASE_URL (env "web"))))
    (doseq [app ["api" "scheduler"]]
      (is (= "http://hapi:8080/fhir" (:FHIR_BASE_URL (env app))) app))))

(deftest the-back-channel-is-keycloak-by-name-and-the-issuer-stays-where-the-browser-sees-it
  (doseq [app jvm-apps
          :let [env (:environment (get (app-services) app))]]
    (testing app
      (is (= "http://keycloak:8080/realms/pulse/protocol/openid-connect/certs" (:KEYCLOAK_JWKS_URI env)))
      (is (= "http://keycloak:8080/realms/pulse/protocol/openid-connect/token" (:KEYCLOAK_TOKEN_URI env)))
      (doseq [browser-side [:KEYCLOAK_ISSUER :KEYCLOAK_AUTHORIZATION_URI :KEYCLOAK_END_SESSION_URI :WEB_BASE_URL :API_CORS_ORIGINS]]
        (is (not (contains? env browser-side)) (str (name browser-side) " keeps the default, which is localhost")))))
  (testing "which is what Keycloak says it is"
    (let [keycloak (:environment (get (services) :keycloak))]
      (is (= "http://localhost:8080" (:KC_HOSTNAME keycloak)))
      (is (= "true" (:KC_HOSTNAME_BACKCHANNEL_DYNAMIC keycloak))))))

(deftest every-variable-of-an-app-is-one-that-its-configuration-reads
  (doseq [app jvm-apps
          :let [config (slurp (str "apps/" app "/resources/pulse/" app "/config.edn"))
                read (set (map second (re-seq #"#env\s+(\w+)" config)))]]
    (testing app
      (doseq [variable (map name (keys (:environment (get (app-services) app))))
              :when (not= "JAVA_OPTS" variable)]
        (is (contains? read variable) (str variable " is not read by the configuration of " app " (a typing mistake is a setting that does nothing)"))))))

(deftest the-secrets-come-from-the-env-file-and-are-never-written-in-the-file
  (doseq [[service-name service] (services)
          [variable value] (:environment service)
          :when (re-find #"(?i)SECRET|PASSWORD" (name variable))]
    (is (and (string? value) (re-matches #"\$\{\w+(:\?[^}]+)?\}" value)) (str service-name " " (name variable) " must be ${NAME}, from .env")))
  (testing "and an application does not start without its own, and says why"
    (doseq [[app variable] {"api" :API_CLIENT_SECRET "scheduler" :SCHEDULER_CLIENT_SECRET "web" :WEB_CLIENT_SECRET}]
      (is (re-matches #"\$\{\w+:\?run \"bb env:init\" first\}" (get-in (app-services) [app :environment variable])) app)))
  (testing "and each application gets its own, and only its own"
    (doseq [[app variable] {"api" :API_CLIENT_SECRET "scheduler" :SCHEDULER_CLIENT_SECRET "web" :WEB_CLIENT_SECRET}]
      (is (contains? (:environment (get (app-services) app)) variable) app)
      (is (= #{variable} (set (filter #(re-find #"SECRET" (name %)) (keys (:environment (get (app-services) app))))))))
    (is (nil? (:environment (get (app-services) "spa"))) "the workspace is a public client: it has nothing to keep")))

(deftest an-application-starts-when-what-it-needs-is-ready
  (let [needs (fn [app] (into {} (for [[k v] (:depends_on (get (app-services) app))] [(name k) (:condition v)])))]
    (is (= {"keycloak" "service_healthy" "hapi" "service_started"} (needs "api")))
    (is (= {"keycloak" "service_healthy" "hapi" "service_started" "api" "service_healthy"} (needs "scheduler")))
    (is (= {"keycloak" "service_healthy" "api" "service_healthy"} (needs "web")))
    (is (= {} (needs "spa")) "the page asks the api and Keycloak from the browser, not when it starts")))

(defn- megabytes [text]
  (let [[_ n unit] (re-matches #"(\d+)([mg])" text)]
    (when n (* (parse-long n) (if (= "g" unit) 1024 1)))))

(deftest the-heap-leaves-the-container-room-for-the-rest-of-the-jvm
  (doseq [app jvm-apps
          :let [service (get (app-services) app)
                heap (some-> (re-find #"-Xmx(\d+)m" (get-in service [:environment :JAVA_OPTS])) second parse-long)
                limit (megabytes (str (:mem_limit service)))]]
    (testing app
      (is (some? heap))
      (is (some? limit))
      (is (>= limit (* 1.4 heap)) "metaspace, the code, the stacks and the buffers are not in the heap")
      (is (<= (* 0.4 limit) heap) "and the heap is not a fraction of a limit the machine has to give"))))

(deftest the-whole-profile-fits-in-the-memory-docker-has
  (let [limit (fn [service] (megabytes (str (:mem_limit service))))
        infrastructure (reduce + (map limit (filter #(nil? (:profiles %)) (vals (services)))))
        applications (reduce + (map limit (vals (app-services))))]
    (is (< (+ infrastructure applications) 4096) "Docker Desktop is capped at 6 GB and the machine uses the rest")
    (is (< applications 1500))))

;; ---------------------------------------------------------------------------
;; The Dockerfiles

(deftest every-image-is-a-tag-and-never-latest
  (doseq [file [jvm-dockerfile spa-dockerfile]
          {:keys [from]} (stages file)
          :when (not (str/starts-with? from "$"))]
    (testing (str file " " from)
      (is (re-find #":[^:/]+$" from) "a tag")
      (is (not (str/ends-with? from ":latest")))
      (is (re-find #"\d" (second (str/split from #":"))) "a tag with a version in it, which a pull of tomorrow does not change"))))

(deftest the-jvm-images-run-java-21-as-a-user-that-is-not-root
  (let [[build runtime :as all] (stages jvm-dockerfile)]
    (is (= 2 (count all)))
    (is (re-find #"^clojure:temurin-21-" (:from build)) "Java 21, which shadow-cljs 3 and the project need")
    (is (re-find #"^eclipse-temurin:21[._0-9]*-jre-alpine" (:from runtime)) "a JRE, not a JDK")
    (let [instructions (:instructions runtime)
          user (some (fn [[i a]] (when (= "USER" i) (str/trim a))) instructions)]
      (is (= "pulse" user))
      (is (some (fn [[i a]] (and (= "RUN" i) (str/includes? a "-u 10001"))) instructions) "a fixed id")
      (testing "the user is set after the files are copied, and the entry point is the exec form with exec"
        (let [position (fn [wanted] (first (keep-indexed (fn [i [ins _]] (when (= wanted ins) i)) instructions)))]
          (is (< (position "COPY") (position "USER"))))
        (let [entry (some (fn [[i a]] (when (= "ENTRYPOINT" i) a)) instructions)]
          (is (str/starts-with? entry "[\"sh\", \"-c\", \"exec java "))
          (is (str/includes? entry "-jar /app/app.jar -m $MAIN")))))))

(deftest the-jar-is-built-by-the-same-task-as-on-the-machine
  (let [[build] (stages jvm-dockerfile)
        run (some (fn [[i a]] (when (and (= "RUN" i) (str/includes? a "-T:build")) a)) (:instructions build))]
    (is (str/includes? run "clojure -T:build uber :app :${APP}"))
    (is (str/includes? run "--mount=type=cache,target=/root/.m2") "the Maven cache is not downloaded again")))

(deftest the-workspace-is-built-in-the-image-and-served-by-nginx-with-the-generated-file
  (let [[node build runtime :as all] (stages spa-dockerfile)]
    (is (= 3 (count all)))
    (is (re-find #"^node:22\." (:from node)) "Node 22, which the project asks for")
    (is (re-find #"^clojure:temurin-21-" (:from build)))
    (is (= "nginxinc/nginx-unprivileged" (first (str/split (:from runtime) #":"))) "it listens above 1024 and runs as a user that is not root")
    (let [copies (for [[i a] (:instructions runtime) :when (= "COPY" i)] a)]
      (is (some #{(str nginx/path " /etc/nginx/conf.d/default.conf")} copies) "the file that pulse.spa.nginx makes")
      (is (some #{(str "--from=build /src/apps/spa/target/release " nginx/root)} copies) "the release, where the configuration looks for it"))
    (is (some (fn [[i a]] (and (= "RUN" i) (= "nginx -t" (str/trim a)))) (:instructions runtime)) "a configuration that does not load is found when the image is made")
    (testing "a release with a warning is not a release"
      (let [run (some (fn [[i a]] (when (and (= "RUN" i) (str/includes? a "shadow-cljs release")) a)) (:instructions build))]
        (is (str/includes? run "0 warnings"))
        (is (some (fn [[i a]] (and (= "SHELL" i) (str/includes? a "pipefail"))) (:instructions build)) "or the exit code of tee would hide a failed build")))))

(deftest the-dockerfiles-copy-what-exists-and-what-dockerignore-lets-through
  (let [patterns (ignore-patterns ".dockerignore")]
    (doseq [file [jvm-dockerfile spa-dockerfile]
            stage (stages file)
            [instruction arguments] (:instructions stage)
            :when (and (= "COPY" instruction) (not (str/includes? arguments "--from=")))
            :let [words (remove #(str/starts-with? % "--") (str/split (str/trim arguments) #"\s+"))
                  sources (butlast words)]
            app (if (str/includes? arguments "${APP}") plan/names [nil])
            source sources
            :let [path (str/replace (str/replace source "${APP}" (str app)) #"/$" "")]]
      (testing (str file ": COPY " path)
        (is (.exists (io/file path)) "a path of the repository")
        (is (not (ignored? patterns path)) "which .dockerignore does not leave out of the context")))))

(deftest dockerignore-keeps-out-what-must-not-be-in-a-layer
  (let [patterns (ignore-patterns ".dockerignore")]
    (doseq [path [".env" ".env.local" ".git" ".git/config" "apps/spa/node_modules/react/index.js" "design/node_modules/tailwindcss/x.js"
                  "target/api.jar" "apps/spa/target/release/index.html" "design/dist/index.html" "apps/spa/.shadow-cljs/x" ".cpcache/x"]]
      (is (ignored? patterns path) path))
    (is (not (ignored? patterns ".env.example")))
    (testing "and nothing an image is made of"
      (doseq [path ["deps.edn" "tools/build.clj" "tools/build_plan.clj" "tools/design.clj" "libs/fhir/deps.edn" "apps/api/deps.edn"
                    "apps/spa/package-lock.json" "apps/spa/public/pulse.css" "design/tokens.edn" "design/fonts" "infra/spa/nginx.conf"
                    "infra/docker/jvm.Dockerfile"]]
        (is (not (ignored? patterns path)) path)))))

(deftest a-dockerfile-has-no-secret-in-it
  (doseq [file [jvm-dockerfile spa-dockerfile]
          [instruction arguments] (mapcat :instructions (stages file))]
    (when (contains? #{"ENV" "ARG"} instruction)
      (let [names (map second (re-seq #"(?:^|\s)([A-Za-z_][A-Za-z0-9_]*)(?:=|\s|$)" arguments))]
        (is (seq names))
        (is (not-any? #(re-find #"(?i)SECRET|PASSWORD|TOKEN" %) names) (str file " " instruction " " arguments))))
    (when (= "COPY" instruction)
      (is (not (re-find #"\.env\b" (str/replace arguments ".env.example" ""))) (str file " COPY " arguments)))))
