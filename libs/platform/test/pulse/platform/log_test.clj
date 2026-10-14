(ns pulse.platform.log-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [pulse.platform.log :as log]
            [taoensso.telemere :as t]))

(defn- parse [line]
  (json/read-value line json/keyword-keys-object-mapper))

(deftest json-lines-carry-the-members-a-log-reader-needs
  (let [signal (t/with-signal
                 (t/with-ctx+ {:request-id "req-1"}
                   (t/event! :pulse.test/something {:level :info :data {:actor "u1" :n 3} :msg "hello"})))
        line ((log/output-fn :json) signal)
        entry (parse line)]
    (is (str/ends-with? line "\n") "one object per line")
    (is (= 1 (count (str/split-lines line))))
    (is (= {:level "info" :kind "event" :id "pulse.test/something" :msg "hello"
            :data {:actor "u1" :n 3} :ctx {:request-id "req-1"}}
           (select-keys entry [:level :kind :id :msg :data :ctx])))
    (is (string? (:inst entry)))
    (testing "only the members of a line, not Telemere's internals"
      (is (every? #{:inst :level :kind :id :ns :msg :data :ctx :error} (keys entry))))))

(deftest an-error-becomes-a-cause-chain-without-a-stack-trace
  (let [signal (t/with-signal
                 (t/event! :pulse.test/boom {:level :error :error (ex-info "bad" {:k 1})}))
        line ((log/output-fn :json) signal)
        entry (parse line)]
    (is (= [{:type "clojure.lang.ExceptionInfo" :msg "bad" :data {:k 1}}] (:error entry)))
    (is (not (str/includes? line "\\tat ")))))

(deftest a-value-that-cannot-be-serialised-does-not-lose-the-line
  (let [signal (t/with-signal
                 (t/event! :pulse.test/odd {:level :info :data {:thing (Object.)}}))
        entry (parse ((log/output-fn :json) signal))]
    (is (= "pulse.platform.log/unserializable-signal" (:id entry)))
    (is (str/includes? (:edn entry) "pulse.test/odd"))))

(deftest text-is-for-people
  (let [signal (t/with-signal (t/event! :pulse.test/hello {:level :info :data {:a 1}}))
        line ((log/output-fn :text) signal)]
    (is (string? line))
    (is (str/includes? line ":pulse.test/hello"))
    (is (not (str/starts-with? line "{")))))

(defn- signal-created?
  "Whether an event at `level` passes the filters now. The signal is trapped so that nothing
  is written."
  [level]
  (some? (t/with-signal true (t/event! :pulse.test/probe {:level level}))))

(deftest the-minimum-level-applies-and-stop-puts-things-back
  (try
    (log/start! {:level :warn :format :text :ns-levels {}})
    (is (not (signal-created? :info)) "below the minimum: no signal")
    (is (signal-created? :warn))
    (log/start! {:level :debug :format :json :ns-levels {}})
    (is (signal-created? :debug) "starting again replaces the setup")
    (finally
      (log/stop!)))
  (is (not (signal-created? :debug)) "stop! restores :info")
  (is (signal-created? :info)))
