(ns spa-output
  "What the build of the clinical workspace (`spa`, run by `bb spa:build` and `bb spa:test`) reads of its surroundings and of
  its own output, as pure functions (ADR-0029, point 9). They use nothing that only Babashka has, so the tests run them on the JVM."
  (:require [clojure.string :as str]))

(def windows-powershell
  "Where Windows PowerShell is. The Clojure CLI of this machine (deps.clj) starts it, and shadow-cljs starts the Clojure CLI; a
  shell that does not have the directory on its PATH, such as Git Bash in some setups, makes the build fail with
  \"Executable 'powershell' not found\"."
  "C:\\Windows\\System32\\WindowsPowerShell\\v1.0")

(defn environment
  "The variables to add to the environment of the build, as `{name value}`: on Windows, when the directory of Windows PowerShell
  exists (`powershell-exists?`) and is not on the `path` yet, the `PATH` with it in front. Nothing otherwise."
  [{:keys [windows? powershell-exists? path]}]
  (let [path (str path)]
    (if (and windows? powershell-exists?
             (not (str/includes? (str/lower-case path) (str/lower-case windows-powershell))))
      {"PATH" (str windows-powershell ";" path)}
      {})))

(defn warnings-in
  "The number of warnings that the output of a build of shadow-cljs reports, from its summary, `Build completed. (131 files, 130
  compiled, 0 warnings, 36.90s)`; nil when there is no such line (a build that failed, or one whose output is not what this
  expects: either way it must not pass). The last summary counts, since a run may build more than once."
  [output]
  (some->> (re-seq #"Build completed\. \([^)]*?(\d+) warnings?," (str output))
           last
           second
           parse-long))
