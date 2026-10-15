(ns pulse.scheduler.simulator-test
  "The device simulator (ADR-0023): the devices of the cohort, what is due at a tick, that a normal
  reading cannot cross a threshold of Annex C and that each episode makes its rule fire for long
  enough to be seen, the Observation and a tick into FHIR."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.fhir.client :as c]
            [pulse.fhir.client.memory :as mem]
            [pulse.fhir.schema :as schema]
            [pulse.fhir.synthetic :as synthetic]
            [pulse.scheduler.rules :as rules]
            [pulse.scheduler.simulator :as simulator]
            [taoensso.telemere :as t])
  (:import (java.time Duration Instant)))

(def ^:private cohort (synthetic/cohort))

(def ^:private scenario (simulator/scenario cohort))

(def ^:private without-episodes (assoc scenario :episodes []))

(def ^:private ^Instant start (Instant/parse "2026-10-15T10:00:00Z"))

(defn- at-tick
  "The instant of tick `n`: every 15 seconds from `start`."
  ^Instant [n]
  (.plus start (Duration/ofSeconds (* 15 n))))

(defn- plan-of [scenario n] (simulator/plan scenario n (at-tick n)))

(defn- of [planned patient kind]
  (first (filter #(and (= patient (:patient %)) (= kind (:kind %))) planned)))

;; ---------------------------------------------------------------------------
;; The devices of the cohort

(deftest the-devices-are-the-signals-of-the-patients-of-the-cohort
  (let [devices (simulator/devices cohort)]
    (is (= 14 (count devices)))
    (is (= {"pt-ana" 3 "pt-liam" 3 "pt-sofia" 2 "pt-omar" 3 "pt-maya" 3} (frequencies (map :patient devices)))
        "Noah has no signals: the empty state of the interface")
    (is (= #{:blood-pressure :heart-rate :spo2 :glucose :weight} (set (map :kind devices))))
    (testing "a pressure has two channels and the others one"
      (is (= #{:systolic :diastolic} (set (keys (:channels (of devices "pt-ana" :blood-pressure))))))
      (is (= #{:value} (set (keys (:channels (of devices "pt-sofia" :spo2)))))))
    (testing "the baseline and the noise of the signal, and the hours of the day; no ramp, no events"
      (is (= {:value {:baseline 122 :sd 7 :by-slot {20 58}}} (:channels (of devices "pt-liam" :glucose))))
      (is (= {:systolic {:baseline 138 :sd 3} :diastolic {:baseline 86 :sd 2.5}}
             (:channels (of devices "pt-ana" :blood-pressure))))
      (is (= {:value {:baseline 71.8 :sd 0.2}} (:channels (of devices "pt-maya" :weight)))))))

;; ---------------------------------------------------------------------------
;; The settings

(defn- problems-with
  "The lines of `ex-data` of the scenario made with the settings changed by `f`, or nil if it is made."
  [f]
  (try (simulator/scenario cohort (f (simulator/settings)))
       nil
       (catch clojure.lang.ExceptionInfo e
         (is (= ::simulator/invalid-scenario (:type (ex-data e))))
         (:problems (ex-data e)))))

(defn- mentions? [lines & fragments]
  (boolean (some (fn [line] (every? #(str/includes? line %) fragments)) lines)))

(deftest the-settings-of-the-simulation-are-sound
  (is (nil? (problems-with identity)))
  (let [s (simulator/settings)]
    (is (= 32 (:cycle-ticks s)))
    (is (= 2 (:cadence-ticks s)))
    (is (= 4 (count (:episodes s))) "one for each rule of Annex C")))

(deftest wrong-settings-are-reported-with-what-is-wrong
  (testing "a shape that is not the settings'"
    (is (mentions? (problems-with #(assoc % :cycle-ticks 0)) "settings" ":cycle-ticks"))
    (is (mentions? (problems-with #(assoc % :cadence-ticks -1)) "settings" ":cadence-ticks"))
    (is (mentions? (problems-with #(assoc % :noise-limit 0.1)) "settings" ":noise-limit" "0.5 to 6"))
    (is (mentions? (problems-with #(assoc % :noise-limit 7)) "settings" ":noise-limit"))
    (is (mentions? (problems-with #(assoc % :cycles 3)) "settings" ":cycles"))
    (is (mentions? (problems-with #(assoc-in % [:episodes 0 :signal] :lung-capacity)) "settings" ":signal")))
  (testing "an episode of a device the cohort does not have"
    (is (mentions? (problems-with #(assoc-in % [:episodes 0 :patient] "pt-nobody")) "pt-nobody" "no such device"))
    (is (mentions? (problems-with #(assoc-in % [:episodes 0 :signal] :glucose)) "pt-sofia" ":glucose" "no such device"))
    (is (mentions? (problems-with #(assoc-in % [:episodes 0 :patient] "pt-noah")) "pt-noah" "no such device")))
  (testing "an episode that does not end within the cycle"
    (is (mentions? (problems-with #(assoc-in % [:episodes 3 :at-tick] 30)) "pt-maya" ":weight" "within the cycle of 32"))
    (is (nil? (problems-with #(assoc-in % [:episodes 3 :at-tick] 28))) "the last tick of the cycle is fine"))
  (testing "episodes of one device that overlap"
    (is (mentions? (problems-with #(update % :episodes conj {:patient "pt-sofia" :signal :spo2 :at-tick 2 :readings [{:value 90}]}))
                   "pt-sofia" ":spo2" "overlap"))
    (is (nil? (problems-with #(update % :episodes conj {:patient "pt-sofia" :signal :spo2 :at-tick 3 :readings [{:value 90}]})))
        "one after the other is not an overlap"))
  (testing "a reading that cannot be one"
    (is (mentions? (problems-with #(assoc-in % [:episodes 0 :readings 0] {:value 20})) "pt-sofia" ":spo2" "not a reading"))
    (is (mentions? (problems-with #(assoc-in % [:episodes 1 :readings 0] {:systolic 100 :diastolic 120})) "pt-ana" "not a reading"))
    (is (mentions? (problems-with #(assoc-in % [:episodes 1 :readings 0] {:systolic 172})) "pt-ana" "not a reading")))
  (testing "all the problems at once"
    (is (= 2 (count (problems-with #(-> %
                                        (assoc-in [:episodes 0 :patient] "pt-nobody")
                                        (assoc-in [:episodes 2 :at-tick] 31))))))))

;; ---------------------------------------------------------------------------
;; What is due at a tick

(deftest a-plan-is-a-pure-function-of-the-tick
  (is (= (plan-of scenario 5) (plan-of scenario 5)))
  (is (not= (map :reading (plan-of without-episodes 4)) (map :reading (plan-of without-episodes 6)))
      "another tick, another noise")
  (is (= (plan-of scenario 5) (simulator/plan scenario 5 (at-tick 5))) "and nothing else is asked"))

(deftest every-device-is-due-once-in-the-cadence-and-the-load-is-even
  (let [a (plan-of without-episodes 40)
        b (plan-of without-episodes 41)]
    (is (= 7 (count a)))
    (is (= 7 (count b)))
    (is (= 14 (count (distinct (map (juxt :patient :kind) (concat a b))))) "every device, once in the two ticks")
    (is (empty? (filter :abnormal? (concat a b))))
    (is (= #{(at-tick 40)} (set (map :at a))) "taken at the instant of the tick")))

(deftest the-baseline-follows-the-hour-of-the-day
  (let [glucose-at (fn [hour]
                     (let [at (Instant/parse (format "2026-10-15T%02d:00:00Z" hour))
                           values (for [n (range 200)
                                        :let [r (of (simulator/plan without-episodes n at) "pt-liam" :glucose)]
                                        :when r]
                                    (get-in r [:reading :value]))]
                       (/ (reduce + values) (count values))))]
    (is (< 120 (glucose-at 8) 125) "122 in the morning")
    (is (< 178 (glucose-at 20) 182) "and 58 more in the evening: the cohort has it too")))

(deftest an-episode-takes-over-its-signal-for-the-ticks-it-lasts
  (testing "the saturation of Sofía, from tick 0"
    (is (= [[88 true] [89 true] [88 true]]
           (for [n [0 1 2]
                 :let [r (of (plan-of scenario n) "pt-sofia" :spo2)]]
             [(get-in r [:reading :value]) (:abnormal? r)]))
        "one on every tick, whatever the cadence")
    (is (false? (:abnormal? (of (plan-of scenario 4) "pt-sofia" :spo2))) "a normal one after")
    (is (nil? (of (plan-of scenario 3) "pt-sofia" :spo2)) "or none, when it is not its turn"))
  (testing "the pressure of Ana, from tick 8"
    (is (= [{:systolic 172 :diastolic 104} {:systolic 176 :diastolic 107} {:systolic 170 :diastolic 103}]
           (map #(:reading (of (plan-of scenario %) "pt-ana" :blood-pressure)) [8 9 10]))))
  (testing "the glucose of Liam, from tick 16"
    (is (= [268 274] (map #(get-in (of (plan-of scenario %) "pt-liam" :glucose) [:reading :value]) [16 17]))))
  (testing "the weight of Maya, from tick 24"
    (is (= [74.6 74.8 74.7 74.9] (map #(get-in (of (plan-of scenario %) "pt-maya" :weight) [:reading :value]) [24 25 26 27]))))
  (testing "the other devices go on at their cadence while one is in an episode"
    (is (= 7 (count (remove :abnormal? (plan-of scenario 9)))))
    (is (= (map (juxt :patient :kind) (remove (comp #{["pt-ana" :blood-pressure]} (juxt :patient :kind)) (plan-of without-episodes 9)))
           (map (juxt :patient :kind) (remove :abnormal? (plan-of scenario 9)))))))

(deftest the-cycle-repeats
  (is (= (map :reading (filter :abnormal? (plan-of scenario 1)))
         (map :reading (filter :abnormal? (plan-of scenario 33)))))
  (is (= (map :reading (filter :abnormal? (plan-of scenario 26)))
         (map :reading (filter :abnormal? (plan-of scenario 58)))))
  (is (= 1 (count (filter :abnormal? (plan-of scenario 0)))))
  (is (empty? (filter :abnormal? (plan-of scenario 5))) "between episodes there is nothing abnormal")
  (is (= 4 (count (distinct (mapcat (fn [n] (map (juxt :patient :kind) (filter :abnormal? (plan-of scenario n)))) (range 32)))))
      "the four episodes, in a cycle"))

;; ---------------------------------------------------------------------------
;; Normal is normal: no threshold of Annex C is crossed except by an episode

(def ^:private one-reading-rules
  "The rules whose condition is on a single reading, by the measurement they are about."
  (into {} (map (juxt :measurement identity)) [rules/bp-stage-2 rules/low-spo2 rules/glucose-out-of-range]))

(deftest a-normal-reading-never-satisfies-the-condition-of-a-rule
  ;; 10,000 ticks are 42 hours, every hour of the day twice, and 70,000 readings
  (let [planned (mapcat #(plan-of without-episodes %) (range 10000))
        weights (volatile! {})]
    (is (= 70000 (count planned)))
    (doseq [{:keys [patient kind reading]} planned]
      (when-let [rule (one-reading-rules kind)]
        (when ((:test rule) reading)
          (is false (str (:title rule) " would fire for " patient " " (pr-str reading)))))
      (when (= :weight kind)
        (vswap! weights update patient (fnil conj []) (:value reading))))
    (testing "and the weight of nobody rises by 2 kg between the lowest reading and the highest"
      (doseq [[patient values] @weights]
        (is (< (- (reduce max values) (reduce min values)) 2.0) patient)))))

(deftest every-normal-reading-is-a-reading
  (let [planned (mapcat #(plan-of without-episodes %) (range 400))]
    (is (every? simulator/observation planned) "within the plausible limits and the precision of the api")))

(defn- series
  "The readings of `kind` of `patient` over ticks `ns` of `scenario`, as the rules take them."
  [scenario patient kind ns]
  (for [n ns
        :let [r (of (plan-of scenario n) patient kind)]
        :when r]
    (assoc (:reading r) :at (at-tick n))))

(deftest each-episode-makes-its-rule-fire-for-long-enough-to-be-seen
  ;; the rules engine runs every 30 seconds, that is every second tick: an episode must be visible at
  ;; two ticks in a row at least
  (doseq [[rule patient kind first-tick]
          [[rules/low-spo2 "pt-sofia" :spo2 0]
           [rules/bp-stage-2 "pt-ana" :blood-pressure 8]
           [rules/glucose-out-of-range "pt-liam" :glucose 16]
           [rules/rapid-weight-gain "pt-maya" :weight 24]]]
    (testing (str (:title rule) " for " patient)
      (let [readings (series scenario patient kind (range 0 (+ first-tick 10)))
            fires? (fn [n] (some? (rules/evaluate rule (filter #(not (.isAfter ^Instant (:at %) (at-tick n))) readings) (at-tick n))))
            firing (filter fires? (range first-tick (+ first-tick 10)))]
        (is (not-any? fires? (range 0 first-tick)) "not before the episode")
        (is (seq firing) "during it")
        (is (>= (count firing) 2) "for at least two ticks, that is 30 seconds")
        (is (= firing (range (first firing) (inc (last firing)))) "without a gap")))))

(deftest nothing-else-fires-in-a-whole-cycle-and-a-half
  ;; the four rules over every patient with the readings of 48 ticks, evaluated at each tick
  (let [fired (for [n (range 48)
                    :let [now (at-tick n)
                          by-device (group-by (juxt :patient :kind)
                                              (for [m (range (inc n))
                                                    r (plan-of scenario m)]
                                                (assoc (:reading r) :at (:at r) :patient (:patient r) :kind (:kind r))))]
                    rule rules/catalogue
                    [[patient kind] readings] by-device
                    :when (and (= (:measurement rule) kind)
                               (rules/evaluate rule (map #(select-keys % [:at :value :systolic :diastolic]) readings) now))]
                [(:id rule) patient])]
    (is (= #{["R2" "pt-sofia"] ["R1" "pt-ana"] ["R3" "pt-liam"] ["R4" "pt-maya"]} (set fired)))))

;; ---------------------------------------------------------------------------
;; The Observation

(deftest an-observation-is-a-reading-of-a-device
  (let [at (Instant/parse "2026-10-15T10:00:15Z")
        planned {:patient "pt-sofia" :kind :spo2 :reading {:value 93.4} :at at :abnormal? false}
        o (simulator/observation planned)]
    (is (schema/valid? o))
    (is (= "Observation" (:resourceType o)))
    (is (= "obs-sim-sofia-spo2-20261015100015" (:id o)))
    (is (= "Patient/pt-sofia" (get-in o [:subject :reference])))
    (is (= "2026-10-15T10:00:15Z" (:effectiveDateTime o)))
    (is (= 93 (get-in o [:valueQuantity :value])) "rounded as the api rounds it")
    (is (= "%" (get-in o [:valueQuantity :unit])))
    (is (not (contains? o :performer)) "nobody took it")
    (is (= {:display "Pulse device simulator"} (:device o)))
    (is (= {:tag [{:system "https://pulse.example/tag" :code "simulated" :display "Simulated device reading"}]} (:meta o)))))

(deftest a-pressure-is-an-observation-with-two-components
  (let [o (simulator/observation {:patient "pt-ana" :kind :blood-pressure :reading {:systolic 172 :diastolic 104}
                                  :at (Instant/parse "2026-10-15T10:00:00Z") :abnormal? true})]
    (is (schema/valid? o))
    (is (= "obs-sim-ana-blood-pressure-20261015100000" (:id o)))
    (is (= [172 104] (map #(get-in % [:valueQuantity :value]) (:component o))))))

(deftest an-observation-has-an-id-that-is-unique-to-the-second
  (let [id (fn [patient kind at] (simulator/observation-id patient kind (Instant/parse at)))]
    (is (= (id "pt-ana" :weight "2026-10-15T10:00:00Z") (id "pt-ana" :weight "2026-10-15T10:00:00.900Z"))
        "the second, so writing a reading twice is writing it once")
    (is (not= (id "pt-ana" :weight "2026-10-15T10:00:00Z") (id "pt-ana" :weight "2026-10-15T10:00:01Z")))
    (is (not= (id "pt-ana" :weight "2026-10-15T10:00:00Z") (id "pt-liam" :weight "2026-10-15T10:00:00Z")))
    (is (not= (id "pt-ana" :weight "2026-10-15T10:00:00Z") (id "pt-ana" :heart-rate "2026-10-15T10:00:00Z")))
    (testing "and a valid FHIR id for every device"
      (doseq [{:keys [patient kind]} (simulator/devices cohort)]
        (is (re-matches #"[A-Za-z0-9\-.]{1,64}" (id patient kind "2026-10-15T10:00:00Z")))))))

(deftest what-is-not-a-reading-is-not-an-observation
  (let [planned {:patient "pt-sofia" :kind :spo2 :at (Instant/parse "2026-10-15T10:00:00Z")}]
    (is (nil? (simulator/observation (assoc planned :reading {:value 20}))))
    (is (nil? (simulator/observation (assoc planned :reading {}))))
    (is (nil? (simulator/observation (assoc planned :kind :lung-capacity :reading {:value 90}))))))

;; ---------------------------------------------------------------------------
;; A tick, into FHIR

(defn- observations-of [client patient]
  (vec (c/search client "Observation" {:patient (str "Patient/" patient)})))

(deftest a-tick-writes-the-observations-of-its-plan
  (let [client (mem/memory-client)
        stats (simulator/tick! client scenario 0 (at-tick 0))]
    (is (= {:planned 7 :abnormal 1 :read 7 :written 7 :batches 1 :failed 0 :failed-batches 0 :errors 0} stats))
    (is (= 7 (count (c/search client "Observation" {}))))
    (is (= [88] (map #(get-in % [:valueQuantity :value]) (observations-of client "pt-sofia"))))
    (is (every? schema/valid? (c/search client "Observation" {})))
    (is (every? #(= "simulated" (get-in % [:meta :tag 0 :code])) (c/search client "Observation" {})))))

(deftest a-tick-is-sent-in-batches
  (let [client (mem/memory-client)
        transactions (atom [])
        spy (reify c/FhirClient
              (read-resource [_ type id] (c/read-resource client type id))
              (search [_ type params] (c/search client type params))
              (put! [_ resource] (c/put! client resource))
              (delete! [_ type id] (c/delete! client type id))
              (transact! [_ bundle] (swap! transactions conj bundle) (c/transact! client bundle))
              (validate [_ resource] (c/validate client resource)))
        stats (simulator/tick! spy without-episodes 4 (at-tick 4) {:batch-size 3})]
    (is (= {:planned 7 :abnormal 0 :read 7 :written 7 :batches 3} (select-keys stats [:planned :abnormal :read :written :batches])))
    (is (= [3 3 1] (map (comp count :entry) @transactions)) "three, three and what is left")
    (is (every? #(= "transaction" (:type %)) @transactions))
    (is (every? #(= "PUT" (get-in % [:request :method])) (mapcat :entry @transactions)) "idempotent: the ids are ours")))

(deftest the-same-tick-twice-is-the-same-observations
  (let [client (mem/memory-client)]
    (simulator/tick! client scenario 6 (at-tick 6))
    (let [once (set (map :id (c/search client "Observation" {})))]
      (simulator/tick! client scenario 6 (at-tick 6))
      (is (= once (set (map :id (c/search client "Observation" {})))) "no duplicates")
      (is (= 7 (count once))))))

(deftest ticks-add-up
  (let [client (mem/memory-client)]
    (doseq [n (range 10)] (simulator/tick! client scenario n (at-tick n)))
    (is (< 60 (count (c/search client "Observation" {})) 75) "about seven a tick, more in an episode")))

(deftest a-fhir-server-that-fails-is-counted-not-thrown
  (let [refusing (reify c/FhirClient
                   (read-resource [_ _ _] nil)
                   (search [_ _ _] [])
                   (put! [_ r] r)
                   (delete! [_ _ _] nil)
                   (transact! [_ _] (throw (ex-info "connection refused" {})))
                   (validate [_ _] nil))
        {:keys [value signals]} (t/with-signals true (simulator/tick! refusing scenario 0 (at-tick 0)))]
    (is (= {:planned 7 :read 7 :written 0 :failed 7 :failed-batches 1} (select-keys value [:planned :read :written :failed :failed-batches])))
    (is (= 1 (count (filter #(= :pulse.pipeline/batch-failed (:id %)) signals))))))
