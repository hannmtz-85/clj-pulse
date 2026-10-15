(ns pulse.scheduler.end-to-end-test
  "The rules engine against the real api, over HTTP (ADR-0022): it reads the patients and their
  readings with the scheduler's own token, raises the alerts of Annex C in FHIR, the practitioners
  find them in the inbox of the api, and what a practitioner acknowledges is not raised again.

  The api is the api's own handler, authorization included, served by Jetty on a free port. The
  identity provider is a fake, and FHIR an in-memory server that the api and the engine share, as
  they share HAPI."
  (:require [clojure.test :refer [deftest is testing]]
            [pulse.api.test.harness :as h]
            [pulse.fhir.client :as c]
            [pulse.fhir.client.memory :as mem]
            [pulse.fhir.synthetic :as synthetic]
            [pulse.platform.http.client :as http]
            [pulse.platform.log :as log]
            [pulse.platform.system :as platform]
            [pulse.platform.token :as token]
            [pulse.scheduler.engine :as engine]
            [pulse.scheduler.jobs :as jobs]
            [pulse.scheduler.source :as source]
            [ring.adapter.jetty :as jetty])
  (:import (java.time Instant)
           (org.eclipse.jetty.server Server)))

(def ^:private end "2026-10-13T20:00:00Z")

(defn- with-world
  "Calls `(f world)` with the demo cohort, seeded to end at `end`, in an in-memory FHIR server; the
  api's handler on that server, served by Jetty; and the rules engine, as a function `:run!` of the
  time (an ISO instant) whose source is a client of that api with the token of `:who` (the scheduler
  by default) and whose alerts go to the same FHIR server. The world is the api's harness (`:handler`
  for `call`) with `:fhir` and `:run!` added."
  [{:keys [who] :or {who :scheduler}} f]
  (let [fhir (mem/memory-client (synthetic/generate (synthetic/cohort) {:end-instant (Instant/parse end)}))]
    (log/start! {:level :warn})
    (try
      (h/with-api {:fhir fhir}
        (fn [api]
          (let [^Server server (jetty/run-jetty (:handler api) {:host "127.0.0.1" :port 0 :join? false})
                own-token (token/source {:fetch (fn [] {:access-token (h/token-for who) :expires-in 300})})
                client (http/client {:base-url (str "http://127.0.0.1:" (platform/port server)) :token own-token})]
            (try
              (f (assoc api
                        :fhir fhir
                        :run! (fn [now]
                                (engine/run! {:data (source/api-source client fhir)
                                              :fhir fhir
                                              :actor jobs/service-actor}
                                             (Instant/parse now)))))
              (finally
                (.stop server)
                (http/close! client))))))
      (finally (log/stop!)))))

(defn- inbox
  "The alerts that `who` sees in the inbox of the api, `status` (active by default) being what they
  are in."
  [world who & [status]]
  (:json (h/call world :get "/api/alerts" :token (h/token-for who)
                 :query (str "limit=100&status=" (or status "active")))))

(defn- about
  "What the items of an inbox are about, as pairs of patient and text."
  [{:keys [items]}]
  (set (map (juxt :patient :text) items)))

;; ---------------------------------------------------------------------------

(deftest the-engine-raises-the-alerts-annex-c-expects-and-the-practitioners-find-them
  (with-world {}
    (fn [world]
      (is (= {:patients 6 :raised 4 :already-raised 0 :still-active 0 :not-applicable 0 :failed 0}
             ((:run! world) end))
          "every patient read through the api, every reading with its unit and its time")
      (testing "Dr. Rivera finds the alerts of her patients"
        (is (= #{["Patient/pt-ana" "BP stage 2"]
                 ["Patient/pt-liam" "Glucose out of range"]
                 ["Patient/pt-sofia" "Low SpO₂"]}
               (about (inbox world :rivera)))))
      (testing "and Dr. Okafor those of his"
        (is (= #{["Patient/pt-maya" "Rapid weight gain"]} (about (inbox world :okafor)))))
      (testing "the administrator finds them all, raised when the engine ran"
        (is (= 4 (:total (inbox world :admin))))
        (is (= #{end} (set (map :since (:items (inbox world :admin))))))))))

(deftest an-alert-is-raised-once-however-often-the-engine-runs
  (with-world {}
    (fn [world]
      ((:run! world) end)
      (doseq [now ["2026-10-13T20:05:00Z" "2026-10-13T20:10:00Z"]]
        (is (= {:raised 0 :already-raised 0 :still-active 4 :failed 0}
               (select-keys ((:run! world) now) [:raised :already-raised :still-active :failed]))
            now))
      (is (= 4 (:total (inbox world :admin))) "no copies")
      (is (= 4 (count (c/search (:fhir world) "Flag" {})))))))

(deftest what-a-practitioner-acknowledges-is-not-raised-again-for-the-same-readings
  (with-world {}
    (fn [world]
      ((:run! world) end)
      (let [ana (first (filter #(= "Patient/pt-ana" (:patient %)) (:items (inbox world :rivera))))
            ack (h/call world :post (str "/api/alerts/" (:id ana) "/ack") :token (h/token-for :rivera))]
        (is (= 200 (:status ack)))
        (is (= "inactive" (get-in ack [:json :status])))
        (let [again ((:run! world) "2026-10-13T20:30:00Z")]
          (is (= {:raised 0 :already-raised 1 :still-active 3 :failed 0}
                 (select-keys again [:raised :already-raised :still-active :failed]))
              "the server found the one that was acknowledged and left it as it was"))
        (is (= 3 (:total (inbox world :admin))))
        (is (= #{["Patient/pt-ana" "BP stage 2"]} (about (inbox world :admin "inactive"))) "still acknowledged")))))

(deftest only-the-role-of-the-engine-reads-as-the-engine
  (testing "a patient's token is not enough: the api refuses it, and the run fails as a whole"
    (with-world {:who :ana}
      (fn [world]
        (let [e (try ((:run! world) end) (catch clojure.lang.ExceptionInfo e e))]
          (is (= {:type ::http/failed :status 403 :path "/api/patients"}
                 (select-keys (ex-data e) [:type :status :path])))
          (is (empty? (c/search (:fhir world) "Flag" {})) "and nothing was written"))))))
