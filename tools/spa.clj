(ns spa
  "The build of the clinical workspace (ADR-0029, point 9), run by `bb spa`, `bb spa:build` and `bb spa:test`: shadow-cljs
  with the settings that make it work on this machine, and the checks that a build must pass.

  A release that has a warning is not a release. The ones that matter most are the inference warnings of the compiler: a property
  of JavaScript that it cannot tell the type of is renamed by Closure in an optimized build, and the workspace then reads `nil`
  where it read the name of the person (found in the spike of the phase). So the output of shadow-cljs is read, and any warning
  fails the build."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [pulse.design.spa :as design-spa]
            [spa-output]))

(def spa-dir "apps/spa")

(defn env
  "The environment the build runs in: the current one, with the directory of Windows PowerShell on the PATH when it is needed."
  []
  (spa-output/environment {:windows? (fs/windows?)
                           :powershell-exists? (fs/exists? spa-output/windows-powershell)
                           :path (or (System/getenv "PATH") (System/getenv "Path"))}))

(defn- installed? []
  (fs/exists? (fs/path spa-dir "node_modules" "shadow-cljs")))

(defn- require-install! []
  (when-not (installed?)
    (println "The npm packages of the workspace are not installed. Run `npm ci` in apps/spa.")
    (System/exit 1)))

(defn- shadow!
  "Runs `npx shadow-cljs args` in the directory of the workspace, with its output shown as it comes and kept: returns
  `{:exit :output}`."
  [& args]
  (let [proc (apply p/process {:dir spa-dir :extra-env (env) :out :stream :err :out}
                    "npx" "shadow-cljs" args)
        lines (with-open [reader (io/reader (:out proc))]
                (doall (for [line (line-seq reader)]
                         (do (println line) line))))]
    {:exit (:exit @proc) :output (str/join "\n" lines)}))

(defn- build!
  "Builds `build-id` with `mode` (`compile` or `release`) and fails, exiting with 1, when the build failed or reported any warning."
  [mode build-id]
  (require-install!)
  (let [{:keys [exit output]} (shadow! mode build-id)
        warnings (spa-output/warnings-in output)]
    (cond
      (not (zero? exit)) (do (println "The build failed.") (System/exit 1))
      (nil? warnings) (do (println "The build did not say how many warnings it had: it cannot be told that it has none.") (System/exit 1))
      (pos? warnings) (do (println (str "The build has " warnings " warning" (when (> warnings 1) "s") ": a build with a warning is not a release.")) (System/exit 1)))
    output))

(defn dev!
  "Watches and serves the workspace on 3000, with hot reload (`npx shadow-cljs watch app`)."
  []
  (require-install!)
  (let [proc (p/process {:dir spa-dir :extra-env (env) :inherit true} "npx" "shadow-cljs" "watch" "app")]
    (System/exit (:exit @proc))))

(defn release!
  "Makes the release in apps/spa/target/release: the optimized build with the hash of its content in its name, and the page, the
  stylesheet, the icon and the font beside it. Fails on any warning."
  []
  (design-spa/clean-release!)
  (build! "release" "app")
  (let [script (design-spa/release!)]
    (println (str "The release is in " design-spa/release-dir " (" script "). Serve it with `bb spa:serve`."))))

(defn test!
  "Runs the tests of the workspace on Node: compiles them, which fails on any warning, and runs them."
  []
  (build! "compile" "test")
  (let [proc (p/process {:dir spa-dir :extra-env (env) :inherit true} "node" "target/node-tests.js")]
    (System/exit (:exit @proc))))
