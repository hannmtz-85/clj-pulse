(ns pulse.api.fhir-test
  (:require [clojure.test :refer [deftest is testing]]
            [pulse.api.fhir :as fhir]
            [pulse.fhir.client :as fc])
  (:import (java.net.http HttpTimeoutException)))

(defn- client-that
  "A FHIR client whose reads and searches do `f`."
  [f]
  (reify fc/FhirClient
    (read-resource [_ _ _] (f))
    (search [_ _ _] (f))
    (put! [_ _] (f))
    (delete! [_ _ _] (f))
    (transact! [_ _] (f))
    (validate [_ _] (f))))

(defn- searching
  "A FHIR client whose searches call `f`, and which does nothing else."
  [f]
  (reify fc/FhirClient
    (read-resource [_ _ _] (throw (UnsupportedOperationException.)))
    (search [_ _ params] (f params))
    (put! [_ _] (throw (UnsupportedOperationException.)))
    (delete! [_ _ _] (throw (UnsupportedOperationException.)))
    (transact! [_ _] (throw (UnsupportedOperationException.)))
    (validate [_ _] (throw (UnsupportedOperationException.)))))

(defn- problem-status
  "The status of the problem raised by `thunk`, or nil when nothing is."
  [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e (:status (ex-data e)))))

(deftest a-server-that-cannot-be-reached-is-a-502
  (let [client (client-that #(throw (java.net.ConnectException. "refused")))]
    (is (= 502 (problem-status #(fhir/read-resource client "Patient" "pt-ana"))))
    (is (= 502 (problem-status #(fhir/search client "Patient" {}))))
    (is (= 502 (problem-status #(fhir/find-one client "Patient" {}))))))

(deftest any-other-failure-is-a-502-too
  (is (= 502 (problem-status #(fhir/read-resource (client-that (fn [] (throw (ex-info "HTTP 500" {:status 500})))) "Patient" "x"))))
  (is (= 502 (problem-status #(fhir/search (client-that (fn [] (throw (NullPointerException.)))) "Patient" {})))))

(deftest a-server-that-is-too-slow-is-a-504
  (let [client (client-that #(throw (HttpTimeoutException. "request timed out")))]
    (is (= 504 (problem-status #(fhir/read-resource client "Patient" "pt-ana"))))
    (is (= 504 (problem-status #(fhir/search client "Patient" {}))))
    (is (= 504 (problem-status #(fhir/find-one client "Patient" {}))))))

(deftest the-cause-is-kept-for-the-log-and-the-message-is-generic
  (let [cause (java.net.ConnectException. "connection refused: secret-host:8090")
        e (try (fhir/read-resource (client-that #(throw cause)) "Patient" "x")
               (catch clojure.lang.ExceptionInfo e e))]
    (is (identical? cause (ex-cause e)))
    (is (= "The FHIR server could not be reached." (ex-message e)))))

(deftest a-cancelled-call-is-not-a-failure-of-the-server
  ;; Run on a thread of its own: the interrupt flag it leaves set must not reach the thread
  ;; that runs the tests, which would trip over it.
  (let [client (client-that #(throw (InterruptedException. "cancelled")))
        outcome (promise)
        thread (Thread. ^Runnable (fn []
                                    (try (fhir/read-resource client "Patient" "x")
                                         (deliver outcome :returned)
                                         (catch InterruptedException _
                                           (deliver outcome {:interrupted-again? (.isInterrupted (Thread/currentThread))}))
                                         (catch Throwable t
                                           (deliver outcome t)))))]
    (.start thread)
    (.join thread 5000)
    (is (= {:interrupted-again? true} (deref outcome 1000 :no-answer))
        "it is not turned into a problem, and the flag is put back for whoever cancelled")))

(deftest a-search-is-realised-inside-the-guard
  ;; A lazy sequence that fails only when its second element is wanted.
  (let [client (searching (fn [_] (lazy-seq (cons {:id "a"} (lazy-seq (throw (java.net.ConnectException. "boom")))))))]
    (is (= 502 (problem-status #(fhir/search client "Patient" {}))))))

(deftest a-search-stops-at-its-limit
  (let [fetched (atom 0)
        endless (fn [] (map (fn [i] (swap! fetched inc) {:id (str i)}) (range)))
        client (searching (fn [_] (endless)))]
    (is (= 3 (count (fhir/search client "Patient" {} {:limit 3}))))
    (is (<= @fetched 40) "it did not go on for ever (a chunk may be read ahead)")
    (testing "the default limit is a thousand"
      (is (= 1000 (count (fhir/search client "Patient" {})))))))

(deftest find-one-fetches-only-the-first-page
  (let [pages (atom 0)
        client (searching (fn [_]
                            (swap! pages inc)
                            (cons {:id "first"} (lazy-seq (swap! pages inc) [{:id "second"}]))))]
    (is (= {:id "first"} (fhir/find-one client "Observation" {})))
    (is (= 1 @pages) "the rest of the pages are not asked for")))
