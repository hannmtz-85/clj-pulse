(ns build-plan
  "What the build of the JVM apps (`bb build`, ADR-0030) knows of each app, as data and pure functions. They use nothing that only
  Babashka or tools.build has, so the tests run them on the JVM and `bb` loads them for the task.

  The clinical workspace is not here: its image is nginx with the files of the release (`spa.Dockerfile`), not a jar."
  (:require [clojure.string :as str]))

(def names
  "The JVM apps that are packaged, in the order `bb build` makes them."
  ["api" "scheduler" "web"])

(def apps
  "What the build needs of each app: the namespace its jar starts (`-m`) and the files that must be inside the jar for the app to
  work. A jar that lacks one starts and then fails when it reads its configuration, or serves a page with no stylesheet, so the
  build looks for them (`missing-entries`) before it says it is done. The class of the main namespace is added to them: a jar
  without it was not compiled, and starts in 40 s instead of 8."
  {"api" {:main 'pulse.api.main
          :entries ["pulse/api/config.edn"
                    "pulse/api/swagger-initializer.js"
                    "pulse/seed/cohort.edn"
                    "swagger-ui/oauth2-redirect.js"]}
   "scheduler" {:main 'pulse.scheduler.main
                :entries ["pulse/scheduler/config.edn"
                          "pulse/scheduler/simulation.edn"
                          "pulse/seed/cohort.edn"]}
   "web" {:main 'pulse.web.main
          :entries ["pulse/web/config.edn"
                    "public/favicon.svg"
                    "public/fonts/inter-latin-wght-normal.woff2"
                    "public/htmx.min.js"
                    "public/pulse.css"]}})

(def compile-options
  "The options of the Clojure compiler for the jars: calls to a var become direct calls, which is what the start-up times of
  ADR-0030 were measured with. Nothing in the apps redefines a var at run time (the tests do, and they do not run from a jar)."
  {:direct-linking true})

(defn- unknown-app [app]
  (ex-info (str "There is no app called " (pr-str (str app)) " to build. The apps are " (str/join ", " names) ".")
           {:type ::unknown-app :app app :known names}))

(defn app-name
  "The name of the app that `app` (a string, a symbol or a keyword) names, or an exception that says which apps there are."
  [app]
  (let [text (when (or (string? app) (symbol? app) (keyword? app)) (name app))]
    (if (contains? apps text)
      text
      (throw (unknown-app app)))))

(defn parse-apps
  "The apps that `bb build` is asked for, as a vector of names in the order they were given and without repeats: all of them when
  none is named. An app that does not exist is an exception."
  [args]
  (if (empty? args)
    names
    (vec (distinct (map app-name args)))))

(defn main
  "The namespace that the jar of `app` starts."
  [app]
  (:main (get apps (app-name app))))

(defn project
  "The project that tools.build makes the basis of: the app by its directory, as a local dependency, and nothing else of its own, so
  that the dependencies are those of `apps/<app>/deps.edn` and of the libraries it names. The path is relative to the root of the
  repository, where the build runs."
  [app]
  (let [app (app-name app)]
    {:paths []
     :deps {(symbol "pulse" app) {:local/root (str "apps/" app)}}}))

(defn work-dir
  "Where the build of `app` keeps what it compiles."
  [app]
  (str "target/" (app-name app)))

(defn class-dir
  "Where the classes of `app` are compiled to, and the jar is made from."
  [app]
  (str (work-dir app) "/classes"))

(defn jar-file
  "The jar of `app`."
  [app]
  (str "target/" (app-name app) ".jar"))

(defn main-class-entry
  "The entry of the jar that the compiler writes for the main namespace of `app`: `pulse/api/main__init.class`."
  [app]
  (str (str/replace (munge (str (main app))) "." "/") "__init.class"))

(defn expected-entries
  "The entries a jar of `app` must have: its files and the class of its main namespace."
  [app]
  (conj (:entries (get apps (app-name app))) (main-class-entry app)))

(defn missing-entries
  "The entries a jar of `app` should have and `present` (the names of the entries it has) lacks, in the order they are expected."
  [app present]
  (let [present (set present)]
    (vec (remove present (expected-entries app)))))

(defn manifest-problems
  "What is wrong with the text of the manifest of a jar, as a vector of sentences (empty when nothing is): it must start
  `clojure.main`, which then runs the namespace that `-m` names, and say `Multi-Release: true`, or the versions of a library's
  classes for the JVM it runs on would not be seen."
  [manifest]
  (let [lines (set (map str/trim (str/split-lines (str manifest))))]
    (cond-> []
      (not (contains? lines "Main-Class: clojure.main")) (conj "The manifest does not say Main-Class: clojure.main.")
      (not (contains? lines "Multi-Release: true")) (conj "The manifest does not say Multi-Release: true."))))
