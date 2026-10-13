(ns stack
  "Helpers for the local infrastructure stack."
  (:require [babashka.http-client :as http]))

(defn wait-http!
  "Polls `url` until it answers 200 or `timeout-s` seconds have passed (then exits 1).
  Used for HAPI FHIR: its distroless image has no shell, curl or wget, and no JDK compiler,
  so it cannot carry a container healthcheck."
  [label url timeout-s]
  (let [deadline (+ (System/currentTimeMillis) (* 1000 timeout-s))]
    (loop []
      (let [ok? (try (= 200 (:status (http/get url {:throw false :timeout 5000})))
                     (catch Exception _ false))]
        (cond
          ok? (println label "is ready")
          (> (System/currentTimeMillis) deadline) (do (println label "did not become ready in" timeout-s "s")
                                                      (System/exit 1))
          :else (do (Thread/sleep 3000) (recur)))))))
