(ns pulse.spa.handler-details-test
  "What the mutation analysis of the handler found that `handler-test` did not pin (Phase 8, step 3): the probe is logged quietly, since
  it is polled all day, and every other request is logged."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [pulse.spa.handler :as handler]
            [taoensso.telemere :as t])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- release-directory []
  (let [root (.toFile (Files/createTempDirectory "spa-handler-details-" (make-array FileAttribute 0)))]
    (spit (io/file root "index.html") "<!DOCTYPE html><title>Pulse</title>")
    (str root)))

(defn- logged
  "The events that handling a GET of `uri` logs, as `[level id path]`."
  [uri]
  (let [app (handler/create {:root (release-directory) :base-url "http://localhost:3000" :quiet-paths #{"/healthz"}
                             :api-base-url "http://localhost:4000" :issuer "http://localhost:8080/realms/pulse"})
        {:keys [signals]} (t/with-min-level :debug
                            (t/with-signals true (app {:request-method :get :uri uri :headers {}})))]
    (mapv (juxt :level :id (comp :path :data)) signals)))

(deftest the-probe-is-logged-at-debug-and-a-page-at-info
  (is (= [[:debug :pulse.http/request "/healthz"]] (logged "/healthz")))
  (is (= [[:info :pulse.http/request "/"]] (logged "/")))
  (is (= [[:info :pulse.http/request "/patients/pt-ana"]] (logged "/patients/pt-ana")))
  (is (= [[:info :pulse.http/request "/missing.js"]] (logged "/missing.js")) "a file that is not there is a client's mistake, not a warning"))
