(ns doctor
  "`bb doctor`: verifies that the machine can build and run Pulse.
  Required checks make the task exit 1; advisory checks only warn."
  (:require [babashka.fs :as fs]
            [babashka.process :refer [shell]]
            [clojure.string :as str])
  (:import (java.net InetAddress ServerSocket)))

(defn- run-out
  "Runs a command and returns its combined stdout+stderr, or nil if it cannot run."
  [& args]
  (try
    (let [{:keys [out err exit]} (apply shell {:out :string :err :string :continue true} args)]
      (when (zero? exit) (str/trim (str out "\n" err))))
    (catch Exception _ nil)))

(defn- first-line [s] (some-> s str/split-lines first str/trim))

(defn- major-java []
  (when-let [out (run-out "java" "-version")]
    (some->> (re-find #"version \"(\d+)" out) second parse-long)))

(defn- port-free? [port]
  (try
    (with-open [_ (ServerSocket. port 1 (InetAddress/getByName "127.0.0.1"))] true)
    (catch Exception _ false)))

(defn- free-ram-gb
  "Free physical memory in GB, or nil when it cannot be determined.
  Babashka cannot use ManagementFactory, so ask the operating system."
  []
  (try
    (let [os (str/lower-case (System/getProperty "os.name"))
          kb (cond
               (str/includes? os "windows")
               (some (fn [ps]
                       (some-> (run-out ps "-NoProfile" "-Command"
                                        "(Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory")
                               first-line parse-long))
                     ["pwsh" "powershell"])

               (fs/exists? "/proc/meminfo")
               (some->> (slurp "/proc/meminfo") (re-find #"MemAvailable:\s+(\d+)") second parse-long))]
      (when kb (/ (double kb) 1024 1024)))
    (catch Throwable _ nil)))

(def ports
  {3000 "spa" 3300 "web" 4000 "api" 5000 "scheduler" 5432 "postgres"
   8080 "keycloak" 8090 "hapi" 9000 "keycloak management"})

(defn- checks []
  (let [java (major-java)
        ram  (free-ram-gb)
        busy (remove (comp port-free? key) ports)]
    [{:name "JDK >= 21"        :required true  :ok (and java (>= java 21)) :detail (str "java " (or java "not found"))}
     {:name "JAVA_HOME"        :required false :ok (some? (System/getenv "JAVA_HOME")) :detail (or (System/getenv "JAVA_HOME") "unset")}
     {:name "Clojure CLI"      :required true  :ok (some? (run-out "clojure" "--version")) :detail (or (first-line (run-out "clojure" "--version")) "not found")}
     {:name "Babashka"         :required true  :ok true :detail (str "bb " (System/getProperty "babashka.version"))}
     {:name "Node >= 22"       :required true  :ok (let [v (run-out "node" "--version")] (boolean (some-> v (subs 1) (str/split #"\.") first parse-long (>= 22)))) :detail (or (run-out "node" "--version") "not found")}
     {:name "npm"              :required true  :ok (some? (run-out "npm" "--version")) :detail (str "npm " (or (run-out "npm" "--version") "not found"))}
     {:name "git"              :required true  :ok (some? (run-out "git" "--version")) :detail (or (run-out "git" "--version") "not found")}
     {:name "Docker engine"    :required false :ok (some? (run-out "docker" "info" "--format" "{{.ServerVersion}}")) :detail (if (run-out "docker" "info" "--format" "{{.ServerVersion}}") "running" "not running or not installed")}
     {:name "Free RAM >= 3 GB" :required false :ok (boolean (and ram (>= ram 3))) :detail (if ram (format "%.1f GB free" ram) "unknown")}
     {:name "Ports free"       :required false :ok (empty? busy) :detail (if (empty? busy) "all free" (str "in use: " (str/join ", " (map (fn [[p n]] (str p " (" n ")")) busy))))}
     {:name ".env present"     :required false :ok (fs/exists? ".env") :detail (if (fs/exists? ".env") ".env found" "missing (copy .env.example)")}]))

(defn -main [& _]
  (let [results (checks)]
    (doseq [{:keys [name required ok detail]} results]
      (println (format "%-4s %-18s %s" (cond ok "OK" required "FAIL" :else "WARN") name detail)))
    (let [failed (filter #(and (:required %) (not (:ok %))) results)]
      (println)
      (if (seq failed)
        (do (println (count failed) "required check(s) failed.") (System/exit 1))
        (println "All required checks passed.")))))
