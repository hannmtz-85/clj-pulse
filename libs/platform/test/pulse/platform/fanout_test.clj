(ns pulse.platform.fanout-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [pulse.platform.fanout :as fanout]
            [taoensso.telemere :as t]))

(def ^:dynamic *marker* nil)

(defn- elapsed-ms [f]
  (let [started (System/nanoTime)
        result (f)]
    [result (/ (- (System/nanoTime) started) 1e6)]))

(deftest every-task-reports-its-outcome
  (is (= {:a {:ok 1} :b {:ok "two"} [:c :d] {:ok [3]} :nothing {:ok nil}}
         (fanout/run {:a (constantly 1) :b (constantly "two") [:c :d] (constantly [3]) :nothing (constantly nil)}
                     {:timeout-ms 1000})))
  (is (= {} (fanout/run {} {:timeout-ms 1000})) "no tasks, no waiting"))

(deftest a-failing-task-costs-only-itself
  (let [boom (ex-info "boom" {:k 1})
        {:keys [a b]} (fanout/run {:a (fn [] (throw boom)) :b (constantly :fine)} {:timeout-ms 1000})]
    (is (identical? boom (:error a)) "the exception itself, not a wrapper")
    (is (= {:ok :fine} b))))

(deftest tasks-run-at-once
  (let [[results ms] (elapsed-ms
                      #(fanout/run (into {} (for [i (range 20)] [i (fn [] (Thread/sleep 100) i)]))
                                   {:timeout-ms 5000}))]
    (is (every? (fn [[i outcome]] (= {:ok i} outcome)) results))
    (is (< ms 1200) (str "twenty tasks of 100 ms took " (long ms) " ms; one after another it would be 2000"))))

(deftest a-slow-task-is-cut-off-at-the-deadline-and-does-not-hold-up-the-rest
  (let [interrupted (promise)
        [results ms] (elapsed-ms
                      #(fanout/run {:fast (constantly :done)
                                    :slow (fn [] (try (Thread/sleep 5000) :too-late
                                                      (catch InterruptedException e
                                                        (deliver interrupted true)
                                                        (throw e))))}
                                   {:timeout-ms 200}))]
    (is (= {:fast {:ok :done} :slow {:timeout true}} results))
    (is (< ms 1500) (str "took " (long ms) " ms"))
    (testing "the slow task was told to stop"
      (is (true? (deref interrupted 2000 false))))))

(deftest the-deadline-is-one-for-all-not-one-each
  (let [[results ms] (elapsed-ms
                      #(fanout/run {:a (fn [] (Thread/sleep 2000)) :b (fn [] (Thread/sleep 2000)) :c (fn [] (Thread/sleep 2000))}
                                   {:timeout-ms 250}))]
    (is (every? #{{:timeout true}} (vals results)))
    (is (< ms 1000) (str "took " (long ms) " ms; three waits of 250 ms would be 750"))))

(deftest tasks-done-by-the-deadline-are-kept
  (let [results (fanout/run {:quick (constantly 1) :quicker (constantly 2)} {:timeout-ms 5000})]
    (is (= {:quick {:ok 1} :quicker {:ok 2}} results))))

(deftest the-callers-bindings-reach-the-tasks
  (binding [*marker* :in-the-caller]
    (t/with-ctx+ {:request-id "req-7"}
      (is (= {:marker {:ok :in-the-caller} :ctx {:ok {:request-id "req-7"}}}
             (fanout/run {:marker (fn [] *marker*) :ctx (fn [] t/*ctx*)} {:timeout-ms 1000}))
          "so what a task logs carries the request id"))))

(defspec the-result-always-has-the-keys-of-the-tasks-and-each-kind-of-outcome 100
  (prop/for-all [kinds (gen/map gen/keyword (gen/elements [:ok :error]) {:max-elements 12})]
    (let [tasks (update-vals kinds (fn [kind] (if (= :ok kind)
                                                (constantly :value)
                                                #(throw (ex-info "x" {})))))
          results (fanout/run tasks {:timeout-ms 5000})]
      (and (= (set (keys kinds)) (set (keys results)))
           (every? (fn [[k outcome]] (if (= :ok (kinds k))
                                       (= {:ok :value} outcome)
                                       (instance? clojure.lang.ExceptionInfo (:error outcome))))
                   results)))))
