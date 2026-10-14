(ns pulse.platform.http.health
  "Readiness checks: is every dependency of the service reachable right now? Liveness is
  trivial and lives with the service's routes; readiness asks the dependencies.

  The checks run concurrently under one deadline, so a dependency that hangs makes the probe
  answer `down` for it instead of hanging the probe."
  (:require [taoensso.telemere :as t]))

(defn run-checks
  "Runs `checks`, a map of name to a function of no arguments, concurrently. A check is `up`
  when it returns truthy and `down` when it returns falsey, throws, or has not answered by the
  deadline (`:timeout-ms`, default 2000, for the lot). Returns a map of name to `:up` or
  `:down`. The reason a check failed is logged, not returned."
  [checks {:keys [timeout-ms] :or {timeout-ms 2000}}]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)
        started (update-vals checks
                             (fn [check]
                               (future (try (check)
                                            (catch Exception e
                                              (t/event! :pulse.health/check-failed
                                                        {:level :warn :error e})
                                              false)))))]
    (update-vals started
                 (fn [result]
                   (let [remaining (max 0 (- deadline (System/currentTimeMillis)))
                         value (deref result remaining ::timeout)]
                     (when (= ::timeout value)
                       (future-cancel result))
                     (if (and (not= ::timeout value) value) :up :down))))))

(defn readiness-response
  "The Ring response for the `results` of `run-checks`: 200 when everything is up, 503 when
  anything is down. The body is a map of strings for the service's content negotiation to
  encode."
  [results]
  (let [ready? (every? #{:up} (vals results))]
    {:status (if ready? 200 503)
     :body {:status (if ready? "ready" "unready")
            :checks (-> results (update-keys name) (update-vals name))}}))
