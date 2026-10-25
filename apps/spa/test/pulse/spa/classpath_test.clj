(ns pulse.spa.classpath-test
  "What the workspace puts on a classpath that it shares with the other applications (the tests, `bb dev`). The portal serves its
  own `public/pulse.css`, `public/favicon.svg` and fonts from the classpath: if the workspace had files of those names under
  `public` as well, which of the two a request got would depend on the order of the classpath, and the portal could be served
  the stylesheet of the workspace, which has no rule for the classes of its pages."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- found
  "Where on the classpath there is a resource called `name`, as strings."
  [name]
  (mapv str (enumeration-seq (.getResources (.getContextClassLoader (Thread/currentThread)) name))))

(deftest the-workspace-has-no-public-directory-on-the-classpath
  (doseq [name ["public/pulse.css" "public/favicon.svg" "public/index.html" "public/fonts/OFL.txt"
                "public/fonts/inter-latin-wght-normal.woff2" "public/js/main.js"]]
    (testing name
      (is (not-any? #(str/includes? % "apps/spa") (found name))
          "the static files of the workspace are in apps/spa/public, which is not on any classpath"))))

(deftest the-portals-files-are-found-once-and-are-the-portals
  (doseq [name ["public/pulse.css" "public/favicon.svg" "public/htmx.min.js"]]
    (let [places (found name)]
      (is (= 1 (count places)) (str name " is in one place: " (pr-str places)))
      (is (str/includes? (first places) "apps/web") name))))

(deftest what-the-workspace-does-have-on-the-classpath-is-its-configuration
  (is (= 1 (count (found "pulse/spa/config.edn"))))
  (is (str/includes? (first (found "pulse/spa/config.edn")) "apps/spa")))
