(ns build-plan-test
  "What the build of the JVM apps knows of each app (ADR-0030): which apps there are, what their jars start and must hold, and what
  `bb build` accepts. The build itself (tools.build, a few minutes for each jar) is not run here: the images run it, and the smoke test
  starts what they make."
  (:require [build-plan :as plan]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private manifest
  "The manifest that tools.build writes for a jar made with `:main clojure.main`, with the line ends the format asks for."
  (str/join "\r\n" ["Manifest-Version: 1.0" "Created-By: org.clojure/tools.build" "Build-Jdk-Spec: 21"
                    "Main-Class: clojure.main" "Multi-Release: true" ""]))

(defn- apps-with-a-main
  "The directories of `apps` that have a `pulse.<name>.main`."
  []
  (set (for [dir (.listFiles (io/file "apps"))
             :when (.isDirectory dir)
             :let [name (.getName dir)]
             :when (.exists (io/file dir "src" "pulse" name "main.clj"))]
         name)))

;; ---------------------------------------------------------------------------
;; The apps

(deftest the-apps-are-those-with-a-jar-and-in-the-order-they-are-built
  (is (= ["api" "scheduler" "web"] plan/names))
  (is (= (set plan/names) (set (keys plan/apps))) "the order and the data name the same apps")
  (is (= (count plan/names) (count (distinct plan/names)))))

(deftest every-app-with-a-main-is-packaged-as-a-jar-but-the-workspace
  (testing "so an app added to the repository is either built or said not to be"
    (is (= (conj (set plan/names) "spa") (apps-with-a-main))
        "the workspace's main is the server of its release, which is the image of nginx and not a jar"))
  (doseq [app plan/names]
    (testing app
      (is (.exists (io/file "apps" app "deps.edn")) "tools.build reads the dependencies from it")
      (is (.exists (io/file "apps" app "src" "pulse" app "main.clj")))
      (is (= (symbol (str "pulse." app ".main")) (plan/main app))))))

(deftest what-a-jar-must-hold-is-what-the-app-reads-when-it-starts
  (doseq [app plan/names
          entry (:entries (get plan/apps app))
          ;; the files of a library from Maven are not in the repository
          :when (not (str/starts-with? entry "swagger-ui/"))]
    (testing (str app " " entry)
      (is (some #(.exists (io/file % entry))
                (cons (io/file "apps" app "resources")
                      (map #(io/file "libs" % "resources") ["fhir" "platform" "ui" "view"])))
          "a file of the app or of a library it depends on"))))

(deftest the-configuration-of-each-app-is-in-its-jar
  (doseq [app plan/names]
    (is (some #{(str "pulse/" app "/config.edn")} (plan/expected-entries app)) app)))

(deftest the-web-jar-has-the-files-that-it-serves-itself
  (is (every? (set (plan/expected-entries "web")) ["public/pulse.css" "public/htmx.min.js" "public/favicon.svg"])
      "the portal serves them from its classpath: without them it answers pages with no stylesheet"))

;; ---------------------------------------------------------------------------
;; Asking for an app

(deftest an-app-is-a-string-a-symbol-or-a-keyword
  (is (= "api" (plan/app-name "api")))
  (is (= "scheduler" (plan/app-name 'scheduler)))
  (is (= "web" (plan/app-name :web))
      "which is how `clojure -T:build uber :app :web` gives it"))

(deftest an-app-that-does-not-exist-is-an-error-that-says-which-do
  (doseq [app ["spa" "API" "" "api " nil 4000 :nope 'design]]
    (testing (pr-str app)
      (let [e (try (plan/app-name app) (catch Exception e e))]
        (is (instance? clojure.lang.ExceptionInfo e))
        (is (= :build-plan/unknown-app (:type (ex-data e))))
        (is (= plan/names (:known (ex-data e))))
        (is (every? #(str/includes? (ex-message e) %) plan/names) "the message names the apps there are")))))

(deftest bb-build-builds-what-it-is-asked-in-the-order-it-is-asked-and-all-by-default
  (is (= plan/names (plan/parse-apps [])))
  (is (= plan/names (plan/parse-apps nil)))
  (is (= ["web"] (plan/parse-apps ["web"])))
  (is (= ["web" "api"] (plan/parse-apps ["web" "api"])))
  (testing "once, however many times it is named"
    (is (= ["api" "web"] (plan/parse-apps ["api" "web" "api" "web"]))))
  (testing "and an app that does not exist is an error even beside apps that do"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"There is no app called \"spa\"" (plan/parse-apps ["api" "spa"])))
    (is (= "There is no app called \"spa\" to build. The apps are api, scheduler, web."
           (ex-message (try (plan/parse-apps ["api" "spa"]) (catch clojure.lang.ExceptionInfo e e))))
        "in the words a person who typed it reads")))

;; ---------------------------------------------------------------------------
;; Where things are

(deftest the-project-is-the-app-by-its-directory
  (is (= {:paths [] :deps {'pulse/api {:local/root "apps/api"}}} (plan/project "api")))
  (is (= {:paths [] :deps {'pulse/scheduler {:local/root "apps/scheduler"}}} (plan/project :scheduler)))
  (testing "a relative path, so that it does not matter where the repository is (the path of this machine has a space)"
    (doseq [app plan/names]
      (is (not (.isAbsolute (io/file (get-in (plan/project app) [:deps (symbol "pulse" app) :local/root]))))))))

(deftest what-is-built-is-under-target-which-git-ignores
  (is (= "target/api.jar" (plan/jar-file "api")))
  (is (= "target/web" (plan/work-dir "web")))
  (is (= "target/scheduler/classes" (plan/class-dir "scheduler")))
  (is (some #(= "target/" %) (str/split-lines (slurp ".gitignore"))) "so nothing built is committed")
  (testing "and the jar is outside the directory the classes are compiled into, which the jar is made from"
    (doseq [app plan/names]
      (is (not (str/starts-with? (plan/jar-file app) (str (plan/class-dir app) "/")))))))

(deftest the-compiler-links-calls-directly
  (is (= {:direct-linking true} plan/compile-options)))

;; ---------------------------------------------------------------------------
;; What the jar must hold

(deftest the-class-of-the-main-namespace-is-what-says-that-a-jar-was-compiled
  (is (= "pulse/api/main__init.class" (plan/main-class-entry "api")))
  (is (= "pulse/scheduler/main__init.class" (plan/main-class-entry "scheduler")))
  (is (= "pulse/web/main__init.class" (plan/main-class-entry "web")))
  (doseq [app plan/names]
    (is (= (plan/main-class-entry app) (peek (plan/expected-entries app))) "it is expected, after the files")))

(deftest a-jar-that-has-everything-lacks-nothing
  (doseq [app plan/names]
    (is (= [] (plan/missing-entries app (plan/expected-entries app))) app)
    (is (= [] (plan/missing-entries app (concat ["META-INF/MANIFEST.MF" "clojure/core.clj"] (plan/expected-entries app))))
        "more is fine")))

(deftest what-a-jar-lacks-is-said-in-the-order-it-is-expected
  (let [all (plan/expected-entries "web")]
    (is (= ["public/pulse.css"] (plan/missing-entries "web" (remove #{"public/pulse.css"} all))))
    (is (= ["pulse/web/config.edn" "public/htmx.min.js"]
           (plan/missing-entries "web" (remove #{"pulse/web/config.edn" "public/htmx.min.js"} (reverse all)))))
    (is (= all (plan/missing-entries "web" [])) "an empty jar lacks all of it")
    (testing "a jar that was not compiled lacks the class of the main namespace and nothing else"
      (is (= ["pulse/web/main__init.class"] (plan/missing-entries "web" (remove #{"pulse/web/main__init.class"} all)))))
    (testing "one app's files are not another's"
      (is (seq (plan/missing-entries "api" (plan/expected-entries "web")))))))

(deftest a-manifest-must-start-clojure-main-and-be-multi-release
  (is (= [] (plan/manifest-problems manifest)))
  (is (= [] (plan/manifest-problems (str/replace manifest "\r\n" "\n"))) "with either line end")
  (is (= ["The manifest does not say Main-Class: clojure.main."]
         (plan/manifest-problems (str/replace manifest "Main-Class: clojure.main" "Main-Class: pulse.api.main"))))
  (is (= ["The manifest does not say Multi-Release: true."]
         (plan/manifest-problems (str/replace manifest "Multi-Release: true" "Multi-Release: false"))))
  (is (= 2 (count (plan/manifest-problems "Manifest-Version: 1.0\n"))))
  (is (= 2 (count (plan/manifest-problems nil))) "no manifest at all is two problems, not an error")
  (testing "a line that merely contains the words is not the line"
    (is (= ["The manifest does not say Main-Class: clojure.main."]
           (plan/manifest-problems "Manifest-Version: 1.0\r\nX-Note: Main-Class: clojure.main\r\nMulti-Release: true\r\n")))
    (is (= ["The manifest does not say Main-Class: clojure.main."]
           (plan/manifest-problems (str/replace manifest "Main-Class: clojure.main" "X-Main-Class: clojure.main"))))))
