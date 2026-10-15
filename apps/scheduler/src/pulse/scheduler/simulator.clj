(ns pulse.scheduler.simulator
  "The device simulator (ADR-0023): the readings that the devices of the demo cohort would send, as
  Observations, and their way to FHIR through `pulse.scheduler.pipeline`.

  What is due at a tick is a pure function (`plan`) of the scenario, the number of the tick and its
  instant. A device is a signal of a patient in the cohort; it is due every `:cadence-ticks` ticks, and
  its reading is the baseline of the signal at that hour of the day with a noise that is cut, so a
  normal reading cannot cross a threshold of Annex C (`rules-test` checks it against the rules).
  What crosses one is an episode, a run of readings that the settings write down.

  `tick!` makes the Observations of a plan and sends them in a transaction of `PUT`s: their ids are
  made of the patient, the signal and the second they were taken, so sending one twice is sending it
  once."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [pulse.fhir.bundle :as bundle]
            [pulse.fhir.client :as c]
            [pulse.fhir.measurement :as measurement]
            [pulse.scheduler.pipeline :as pipeline])
  (:import (java.time Instant ZoneOffset)
           (java.time.format DateTimeFormatter)
           (java.util Random)))

;; ---------------------------------------------------------------------------
;; The scenario: the devices of the cohort and the settings

(def ^:private episode-schema
  [:map {:closed true}
   [:patient [:string {:min 1}]]
   [:signal (into [:enum] (keys measurement/measurements))]
   [:at-tick nat-int?]
   [:readings [:vector {:min 1} [:map-of :keyword number?]]]])

(def settings-schema
  "What the settings of a simulation (`simulation.edn`) must satisfy."
  [:map {:closed true}
   [:cycle-ticks pos-int?]
   [:cadence-ticks pos-int?]
   [:noise-limit [:and number? [:fn {:error/message "should be from 0.5 to 6 standard deviations"}
                                #(<= 0.5 % 6.0)]]]
   [:episodes [:vector episode-schema]]])

(defn settings
  "The settings of the simulation, read from `pulse/scheduler/simulation.edn` on the classpath."
  []
  (with-open [rdr (java.io.PushbackReader. (io/reader (io/resource "pulse/scheduler/simulation.edn")))]
    (edn/read rdr)))

(defn- channels-of
  "The numeric channels of the signal `spec` of the cohort, as the simulator uses them: a baseline, a
  noise and the offsets of the hours of the day. The ramps and events of the cohort tell its 30 days
  and are left out."
  [{:keys [signal systolic diastolic channel]}]
  (let [keep-baseline #(select-keys % [:baseline :sd :by-slot])]
    (if (= :blood-pressure signal)
      {:systolic (keep-baseline systolic) :diastolic (keep-baseline diastolic)}
      {:value (keep-baseline channel)})))

(defn devices
  "The devices of `cohort`: a vector of `{:patient id :kind measurement :channels {...}}`, one for each
  signal of each patient."
  [cohort]
  (vec (for [{:keys [id signals]} (:patients cohort)
             spec signals]
         {:patient id :kind (:signal spec) :channels (channels-of spec)})))

(defn- problems
  "A line for each thing that is wrong with `settings` for the `devices`: the shape, an episode of a
  device there is not, one that does not fit in the cycle, one that overlaps another, a reading that
  cannot be one."
  [devices {:keys [cycle-ticks episodes] :as settings}]
  (if-let [explanation (m/explain settings-schema settings)]
    [(str "settings: " (pr-str (me/humanize explanation)))]
    (let [known (set (map (juxt :patient :kind) devices))]
      (concat
       (for [{:keys [patient signal]} episodes
             :when (not (known [patient signal]))]
         (str "episode of " patient " " signal ": there is no such device in the cohort"))
       (for [{:keys [patient signal at-tick readings]} episodes
             :when (> (+ at-tick (count readings)) cycle-ticks)]
         (str "episode of " patient " " signal ": it does not end within the cycle of " cycle-ticks " ticks"))
       (for [[[patient signal] those] (group-by (juxt :patient :signal) episodes)
             [a b] (partition 2 1 (sort-by :at-tick those))
             :when (> (+ (:at-tick a) (count (:readings a))) (:at-tick b))]
         (str "episodes of " patient " " signal " overlap"))
       (for [{:keys [patient signal readings]} episodes
             reading readings
             :let [{:keys [errors]} (measurement/normalize (assoc reading :measurement signal))]
             :when errors]
         (str "episode of " patient " " signal ": " (pr-str reading) " is not a reading: " (pr-str errors)))))))

(defn scenario
  "The scenario for `cohort` (as `pulse.fhir.synthetic/cohort` reads it) and `settings` (by default
  those of `settings`): `{:devices [...] :cycle-ticks n :cadence-ticks n :noise-limit sd :episodes [...]}`.
  Throws an ex-info of `:type` `::invalid-scenario` with the `:problems`, one line each, when the
  settings are wrong, so that the scheduler fails when it starts and not when a tick is due."
  ([cohort] (scenario cohort (settings)))
  ([cohort settings]
   (let [devices (devices cohort)]
     (if-let [lines (seq (problems devices settings))]
       (throw (ex-info (str "Invalid simulation:\n  " (str/join "\n  " lines))
                       {:type ::invalid-scenario :problems (vec lines)}))
       (assoc settings :devices devices)))))

;; ---------------------------------------------------------------------------
;; What is due at a tick

(defn- seed-of
  "A stable 64-bit seed from a string (explicit, so that it never depends on `hash`)."
  [^String s]
  (reduce (fn [h c] (unchecked-add (unchecked-multiply 31 h) (long (int c)))) 7 s))

(defn- noise
  "A sample of a normal distribution with mean 0 and standard deviation 1 (the sum of 12 uniform
  samples less 6) that is not further than `limit` from the mean: one that is is drawn again."
  [^Random rng limit]
  (loop []
    (let [z (- (reduce + (repeatedly 12 #(.nextDouble rng))) 6.0)]
      (if (<= (Math/abs z) limit) z (recur)))))

(defn- channel-order [kind]
  (if (= :blood-pressure kind) [:systolic :diastolic] [:value]))

(defn- normal-reading
  "The numbers of the reading of `device` at tick `n`, at `hour` of the day. The noise comes from a
  generator seeded with the patient, the signal and the tick, in the order of the channels."
  [{:keys [patient kind channels]} hour limit n]
  (let [rng (Random. (seed-of (str patient "/" (name kind) "/" n)))]
    (into {}
          (map (fn [channel]
                 (let [{:keys [baseline sd by-slot] :or {sd 0}} (get channels channel)]
                   [channel (+ baseline (get by-slot hour 0) (* sd (noise rng limit)))])))
          (channel-order kind))))

(defn- episode-reading
  "The reading that an episode of the signal of the patient has at `step` of the cycle, or nil."
  [episodes patient kind step]
  (some (fn [{p :patient signal :signal :keys [at-tick readings]}]
          (when (and (= patient p) (= kind signal) (<= at-tick step (+ at-tick (count readings) -1)))
            (nth readings (- step at-tick))))
        episodes))

(defn plan
  "The readings due at tick `n` (0, 1, 2, ...) of `scenario`, taken at `at`: a vector of
  `{:patient :kind :reading {...numbers} :at instant :abnormal? boolean}`. A device is due every
  `:cadence-ticks` ticks, each at its own turn so that the load is even; one in an episode is due on
  every tick of it, with the reading the episode says. Pure: the same tick gives the same plan."
  [{:keys [devices cycle-ticks cadence-ticks noise-limit episodes]} n ^Instant at]
  (let [step (mod n cycle-ticks)
        hour (.getHour (.atOffset at ZoneOffset/UTC))]
    (into []
          (keep-indexed
           (fn [i {:keys [patient kind] :as device}]
             (if-let [reading (episode-reading episodes patient kind step)]
               {:patient patient :kind kind :reading reading :at at :abnormal? true}
               (when (zero? (mod (+ n i) cadence-ticks))
                 {:patient patient :kind kind :reading (normal-reading device hour noise-limit n) :at at
                  :abnormal? false}))))
          devices)))

;; ---------------------------------------------------------------------------
;; The Observation

(def tag
  "What marks a resource as simulated, in its `meta`."
  {:system "https://pulse.example/tag" :code "simulated" :display "Simulated device reading"})

(def device-display "Pulse device simulator")

(def ^:private stamp-format
  (.withZone (DateTimeFormatter/ofPattern "yyyyMMddHHmmss") ZoneOffset/UTC))

(defn observation-id
  "The id of the Observation of the signal `kind` of `patient` taken at `at`: unique to the second."
  [patient kind ^Instant at]
  (str "obs-sim-" (str/replace patient #"^pt-" "") "-" (name kind) "-" (.format ^DateTimeFormatter stamp-format at)))

(defn observation
  "The Observation of a planned reading (an element of a `plan`), or nil when it is not a reading
  (`pulse.fhir.measurement/normalize` is what the api asks of one). It has no performer, says what
  device it is and carries the `tag`."
  [{:keys [patient kind reading at]}]
  (when-let [ok (:ok (measurement/normalize (assoc reading :measurement kind)))]
    (-> (measurement/observation ok {:id (observation-id patient kind at)
                                     :patient patient
                                     :at (str at)
                                     :performer nil})
        (assoc :meta {:tag [tag]}
               :device {:display device-display}))))

;; ---------------------------------------------------------------------------
;; A tick, into FHIR

(defn tick!
  "Runs tick `n` of `scenario` at `at`: the plan, as Observations, in batches, in one transaction of
  `PUT`s each, to `fhir`. `options` are those of `pulse.scheduler.pipeline/run!` (`:batch-size`...).
  Returns the numbers of that (`:read`, `:written`, `:batches`, `:failed`, `:failed-batches`,
  `:errors`) with `:planned` and `:abnormal`, how many of the readings belong to an episode."
  ([fhir scenario n at] (tick! fhir scenario n at {}))
  ([fhir scenario n at options]
   (let [planned (plan scenario n at)]
     (assoc (pipeline/run! planned (keep observation) #(c/transact! fhir (bundle/transaction %)) options)
            :planned (count planned)
            :abnormal (count (filter :abnormal? planned))))))
