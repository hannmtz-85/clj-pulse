(ns build
  "The jars of the JVM apps (ADR-0030), made with tools.build: `clojure -T:build uber :app :api`, or `bb build [app ...]`.

  A jar holds the app, the libraries of the monorepo it depends on (their sources and resources) and everything it loads from Maven,
  and it is compiled ahead of time from its main namespace: a jar that is not compiled took 33 to 43 s to answer its first request,
  one that is, 6 to 10 s. It starts with `java -jar target/<app>.jar -m pulse.<app>.main`; the configuration is inside, and the
  values come from the environment as always. `build-plan` says what each app needs and what the jar must hold, and the build
  looks at the jar it made before it says it is done."
  (:require [build-plan :as plan]
            [clojure.string :as str]
            [clojure.tools.build.api :as b])
  (:import (java.util.zip ZipEntry ZipFile)))

(defn- jar-contents
  "The names of the entries of the jar at `path` and the text of its manifest."
  [path]
  (with-open [zip (ZipFile. (str path))]
    {:entries (set (map #(.getName ^ZipEntry %) (enumeration-seq (.entries zip))))
     :manifest (when-let [entry (.getEntry zip "META-INF/MANIFEST.MF")]
                 (slurp (.getInputStream zip entry)))}))

(defn check!
  "Throws when the jar of `app` is not what `build-plan` says it must be: a manifest that does not start `clojure.main`, an entry
  that is missing, or the class of the main namespace not being there (the jar was not compiled)."
  [app]
  (let [{:keys [entries manifest]} (jar-contents (plan/jar-file app))
        problems (into (plan/manifest-problems manifest)
                       (map #(str "The jar has no " % ".") (plan/missing-entries app entries)))]
    (when (seq problems)
      (throw (ex-info (str "The jar of " app " is not what it should be:\n  " (str/join "\n  " problems))
                      {:app app :problems problems})))))

(defn uber
  "Makes `target/<app>.jar` for the `:app` (`api`, `scheduler` or `web`, as a string, a symbol or a keyword): compiles the main
  namespace of the app with everything it loads and puts the result with the libraries in one jar. Run from the root of the
  repository."
  [{:keys [app]}]
  (let [app (plan/app-name app)
        started (System/nanoTime)
        basis (b/create-basis {:project (plan/project app)})
        jar (plan/jar-file app)]
    (b/delete {:path (plan/work-dir app)})
    (b/delete {:path jar})
    (b/compile-clj {:basis basis
                    :class-dir (plan/class-dir app)
                    :ns-compile [(plan/main app)]
                    :compile-opts plan/compile-options
                    :src-dirs []})
    (b/uber {:basis basis
             :class-dir (plan/class-dir app)
             :uber-file jar
             :main 'clojure.main})
    (check! app)
    (println (format "Built %s (%.1f MB) in %.0f s: java -jar %s -m %s"
                     jar
                     (/ (.length (java.io.File. ^String jar)) 1048576.0)
                     (/ (- (System/nanoTime) started) 1e9)
                     jar
                     (plan/main app)))))
