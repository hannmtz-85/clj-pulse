(ns spa-journey-args-test
  "What `bb spa:journey` reads of its command line and decides about its surroundings (ADR-0029): the options it takes and refuses, what it
  tells the journey, the environment of the api it starts and the ports it must find free."
  (:require [clojure.test :refer [deftest is testing]]
            [spa-journey-args :as args]))

(deftest with-no-options-it-builds-and-runs-everything-against-the-local-stack
  (is (= {:build? true :keycloak "http://localhost:8080"} (args/parse [])))
  (is (= {:build? true :keycloak "http://localhost:8080"} (args/parse nil))))

(deftest each-option-is-read
  (is (= false (:build? (args/parse ["--no-build"]))))
  (is (= "target/pictures" (:shots (args/parse ["--shots" "target/pictures"]))))
  (is (= "states,policy" (:only (args/parse ["--only" "states,policy"]))))
  (is (= "http://kc.test:9090" (:keycloak (args/parse ["--keycloak" "http://kc.test:9090"]))))
  (testing "together, in any order"
    (is (= {:build? false :shots "pics" :only "patient" :keycloak "http://localhost:8080"}
           (args/parse ["--only" "patient" "--no-build" "--shots" "pics"])))))

(deftest what-it-does-not-know-or-cannot-use-is-an-error-that-says-so
  (testing "an option it does not have"
    (is (re-find #"Unknown option \"--fast\"" (:error (args/parse ["--fast"]))))
    (is (re-find #"--no-build, --shots <dir>, --only <sections> and --keycloak <url>" (:error (args/parse ["--fast"])))))
  (testing "something that is not an option"
    (is (re-find #"Unknown option \"states\"" (:error (args/parse ["states"])))))
  (testing "an option that lacks its value"
    (is (= "The option --shots needs a value." (:error (args/parse ["--shots"]))))
    (is (= "The option --only needs a value." (:error (args/parse ["--only" "--no-build"])))) "the next option is not a value"
    (is (= "The option --keycloak needs a value." (:error (args/parse ["--no-build" "--keycloak"])))))
  (testing "and nothing else is read after the mistake"
    (is (nil? (:build? (args/parse ["--fast" "--no-build"]))))))

(deftest the-journey-is-told-where-everything-is
  (is (= ["--spa" "http://localhost:3000" "--api" "http://localhost:4000" "--keycloak" "http://localhost:8080" "--portal" "http://localhost:3300"]
         (args/node-arguments (args/parse []))))
  (testing "with the pictures and the sections that were asked for"
    (is (= ["--shots" "pics" "--only" "states"]
           (drop 8 (args/node-arguments (args/parse ["--shots" "pics" "--only" "states"]))))))
  (testing "and the identity provider it was told of"
    (is (= "http://kc.test:9090" (second (drop-while #(not= "--keycloak" %) (args/node-arguments (args/parse ["--keycloak" "http://kc.test:9090"]))))))))

(deftest the-api-of-the-journey-keeps-the-cohort-still-and-raises-the-alerts-quickly
  (is (= {"SCHEDULER_SIMULATOR" "false" "SCHEDULER_EVALUATE_EVERY_MS" "5000"} args/api-environment)))

(deftest the-ports-are-those-the-dev-system-and-the-release-use
  (is (= {:workspace 3000 :portal 3300 :api 4000 :scheduler 5000} args/ports)))

(deftest a-port-in-use-is-named-with-what-should-be-there
  (is (= [] (args/busy (constantly true))) "every port free: nothing is said")
  (is (= ["workspace (3000)" "portal (3300)" "api (4000)" "scheduler (5000)"] (args/busy (constantly false))) "in the order of the ports")
  (is (= ["api (4000)"] (args/busy #(not= 4000 %)))))

(deftest the-end-of-a-log-is-what-is-shown-of-it
  (is (= "c\nd" (args/tail "a\nb\nc\nd" 2)))
  (is (= "a\nb" (args/tail "a\nb" 5)) "a short log is all of it")
  (is (= "" (args/tail nil 3)))
  (is (= "" (args/tail "" 3))))

(deftest the-command-is-the-script-and-flat-strings
  (let [command (args/node-command (args/parse ["--shots" "pics" "--only" "states"]))]
    (is (= ["node" "apps/spa/journey.mjs"] (take 2 command)))
    (is (every? string? command) "a process takes each element as one argument: a vector in it would be one argument that is its text")
    (is (= (concat ["node" "apps/spa/journey.mjs"] (args/node-arguments (args/parse ["--shots" "pics" "--only" "states"]))) command))
    (is (= ["--shots" "pics" "--only" "states"] (drop 10 command)))))