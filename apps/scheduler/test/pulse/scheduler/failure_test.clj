(ns pulse.scheduler.failure-test
  "Which failures end a run of a job: the infrastructure being out of reach or refusing the job, and
  an interruption. Everything else is about one item, and the run goes on."
  (:require [clojure.test :refer [deftest is testing]]
            [pulse.fhir.client :as c]
            [pulse.platform.http.client :as http]
            [pulse.platform.token :as token]
            [pulse.scheduler.failure :as failure])
  (:import (java.io IOException)
           (java.net ConnectException)
           (java.net.http HttpConnectTimeoutException HttpTimeoutException)))

(deftest the-failures-that-end-a-run
  (doseq [[label e] {"no token" (ex-info "no token" {:type ::token/unavailable :reason :rejected})
                     "the api unreachable" (ex-info "down" {:type ::http/unreachable :reason :connect})
                     "the api refusing the token" (ex-info "refused" {:type ::http/failed :status 401})
                     "the api forbidding the job" (ex-info "forbidden" {:type ::http/failed :status 403})
                     "the api failing, at the first status of a failure" (ex-info "broken" {:type ::http/failed :status 500})
                     "the api failing" (ex-info "broken" {:type ::http/failed :status 502})
                     "FHIR failing, at the first status of a failure" (ex-info "broken" {:type ::c/fhir-error :status 500})
                     "FHIR failing" (ex-info "broken" {:type ::c/fhir-error :status 503})
                     "FHIR with no answer at all" (ex-info "no answer" {:type ::c/fhir-error})
                     "a connection that is refused" (ConnectException. "refused")
                     "a request that takes too long" (HttpTimeoutException. "late")
                     "a connection that takes too long" (HttpConnectTimeoutException. "late")
                     "any other input or output that fails" (IOException. "broken pipe")
                     "an interruption" (InterruptedException. "stop")
                     "an interruption that a client wrapped" (ex-info "wrapped" {} (InterruptedException. "stop"))
                     "an interruption wrapped twice" (RuntimeException. "outer" (ex-info "wrapped" {} (InterruptedException. "stop")))}]
    (testing label
      (is (true? (failure/fatal? e))))))

(deftest the-failures-that-do-not
  (doseq [[label e] {"the api saying that something is not found" (ex-info "no such patient" {:type ::http/failed :status 404})
                     "the api saying that something conflicts" (ex-info "conflict" {:type ::http/failed :status 409})
                     "the api saying that something is unprocessable" (ex-info "unprocessable" {:type ::http/failed :status 422})
                     "the last status that is not a failure of the api" (ex-info "bad" {:type ::http/failed :status 499})
                     "the first status that is not a refusal of the token" (ex-info "bad" {:type ::http/failed :status 402})
                     "the last status below those that are" (ex-info "bad" {:type ::http/failed :status 400})
                     "an answer of the api that says no more" (ex-info "bad" {:type ::http/failed})
                     "FHIR saying that the resource is not valid" (ex-info "bad" {:type ::c/fhir-error :status 422})
                     "FHIR saying that the condition matches several" (ex-info "bad" {:type ::c/fhir-error :status 412})
                     "the last status that is not a failure of FHIR" (ex-info "bad" {:type ::c/fhir-error :status 499})
                     "a bug" (IllegalStateException. "a bug")
                     "an exception that says nothing of what it is" (ex-info "just this" {})
                     "an exception caused by a bug" (ex-info "wrapped" {} (IllegalStateException. "a bug"))
                     "an exception caused by an exception caused by a bug" (RuntimeException. "outer" (ex-info "wrapped" {} (IllegalStateException. "a bug")))}]
    (testing label
      (is (false? (failure/fatal? e))))))
