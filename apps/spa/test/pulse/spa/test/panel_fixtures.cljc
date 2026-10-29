(ns pulse.spa.test.panel-fixtures
  "The panel of the workspace as the tests need it, in the shapes the api answers (ADR-0029): five patients at 14:00 on Tuesday 13 October
  2026 in Madrid, a clinic a lot like the demo's. Ana has a very high blood pressure and an active alert, Sofía a low oxygen saturation
  and one too, Liam a glucose that is elevated, Omar nothing wrong, and Noah, who is new, no reading at all. They come in the order the api
  lists them, by family name: Chen, García, Haddad, Kim, Rossi. It is not a namespace of tests (its name does not end in `-test`): the
  tests of the model, of the page, of the events and of the rendering take their data from here, so that they say the same of the same
  clinic. It is `.cljc`, so that the JVM and Node tests alike use it.")

(def now "2026-10-13T12:00:00Z")

(def patients
  "The list the api answers `GET /api/patients`."
  {:total 5 :offset 0 :limit 100
   :items [{:id "pt-liam" :name "Liam Chen" :family "Chen" :given ["Liam"] :birthDate "1980-05-02" :gender "male" :identifier "PULSE-0002"}
           {:id "pt-ana" :name "Ana García" :family "García" :given ["Ana"] :birthDate "1968-03-14" :gender "female" :identifier "PULSE-0001"}
           {:id "pt-omar" :name "Omar Haddad" :family "Haddad" :given ["Omar"] :birthDate "1991-07-30" :gender "male" :identifier "PULSE-0004"}
           {:id "pt-noah" :name "Noah Kim" :family "Kim" :given ["Noah"] :birthDate "2001-01-09" :gender "male" :identifier "PULSE-0005"}
           {:id "pt-sofia" :name "Sofía Rossi" :family "Rossi" :given ["Sofía"] :birthDate "1959-11-21" :gender "female" :identifier "PULSE-0003"}]})

(def ids (mapv :id (:items patients)))

(defn reading
  "A reading as the api shows it: `{:id :at :unit :band}` and its numbers."
  [id at band unit & {:as numbers}]
  (merge {:id id :at at :unit unit :band band} numbers))

(def ana-pressure (reading "o-ana-bp" "2026-10-13T08:00:00Z" "very-high" "mm[Hg]" :systolic 177 :diastolic 100))

(def vitals-none
  {:blood-pressure nil :heart-rate nil :glucose nil :spo2 nil :weight nil :temperature nil})

(defn summary
  "The summary of a patient as the api answers it: the record is not what the panel reads, so it is left empty."
  [id name & {:as parts}]
  (merge {:patient (some #(when (= id (:id %)) %) (:items patients))
          :conditions [] :medications [] :allergies []
          :vitals vitals-none
          :appointments []
          :alerts []
          :unavailable []}
         parts
         {:patient (assoc (some #(when (= id (:id %)) %) (:items patients)) :name name)}))

(def summaries
  {"pt-liam" (summary "pt-liam" "Liam Chen"
                      :vitals (assoc vitals-none
                                     :glucose (reading "o-liam-glu" "2026-10-13T07:00:00Z" "elevated" "mg/dL" :value 168)
                                     :weight (reading "o-liam-w" "2026-10-12T07:00:00Z" "normal" "kg" :value 81.2))
                      :appointments [{:id "appt-liam-1" :status "booked" :description "Diabetes review"
                                      :start "2026-10-16T12:00:00Z" :end "2026-10-16T12:30:00Z" :practitioner "Practitioner/pr-rivera"}])
   "pt-ana" (summary "pt-ana" "Ana García"
                     :vitals (assoc vitals-none
                                    :blood-pressure ana-pressure
                                    :heart-rate (reading "o-ana-hr" "2026-10-13T08:00:00Z" "normal" "/min" :value 74))
                     :alerts [{:id "flag-pt-ana-R1" :text "BP stage 2" :status "active" :since "2026-10-12T20:30:00Z"}]
                     :appointments [{:id "appt-ana-1" :status "booked" :description "Hypertension follow-up"
                                     :start "2026-10-14T08:00:00Z" :end "2026-10-14T08:30:00Z" :practitioner "Practitioner/pr-rivera"}
                                    {:id "appt-ana-0" :status "cancelled" :description "Blood pressure check"
                                     :start "2026-09-29T07:00:00Z" :end "2026-09-29T07:30:00Z" :practitioner "Practitioner/pr-rivera"}])
   "pt-omar" (summary "pt-omar" "Omar Haddad"
                      :vitals (assoc vitals-none
                                     :blood-pressure (reading "o-omar-bp" "2026-10-13T08:00:00Z" "normal" "mm[Hg]" :systolic 118 :diastolic 76)
                                     :heart-rate (reading "o-omar-hr" "2026-10-13T08:00:00Z" "normal" "/min" :value 62)))
   "pt-noah" (summary "pt-noah" "Noah Kim"
                      :appointments [{:id "appt-noah-1" :status "proposed" :description "First visit"
                                      :requestedStart "2026-10-20T00:00:00Z" :requestedEnd "2026-10-27T00:00:00Z" :practitioner "Practitioner/pr-okafor"}])
   "pt-sofia" (summary "pt-sofia" "Sofía Rossi"
                       :vitals (assoc vitals-none
                                      :spo2 (reading "o-sofia-spo2" "2026-10-13T08:00:00Z" "low" "%" :value 90)
                                      :heart-rate (reading "o-sofia-hr" "2026-10-13T08:00:00Z" "normal" "/min" :value 70))
                       :alerts [{:id "flag-pt-sofia-R2" :text "Low SpO₂" :status "active" :since "2026-10-12T08:30:00Z"}])})

(defn series
  "The series the api answers for a patient and a measurement: `values` are the numbers of readings one a day, the last one the newest,
  as `{:items :truncated}` with the newest first. A blood pressure is given as pairs."
  [kind values & {:keys [band truncated] :or {band "normal" truncated false}}]
  (let [n (count values)
        items (for [[i v] (map-indexed vector values)
                    :let [day (- 13 (- n i 1))
                          at (str "2026-10-" (if (< day 10) (str "0" day) day) "T08:00:00Z")]]
                (cond-> {:id (str "o-" (name kind) "-" i) :at at :unit "x" :band band :measurement (name kind)}
                  (= :blood-pressure kind) (assoc :systolic (first v) :diastolic (second v))
                  (not= :blood-pressure kind) (assoc :value v)))]
    {:items (vec (reverse items)) :truncated truncated}))

(def series-by-patient
  "The series of the headline measurement of each patient that has one."
  {"pt-liam" (series :glucose [150 160 158 170 168] :band "elevated")
   "pt-ana" (series :blood-pressure [[150 92] [158 96] [165 98] [172 100] [177 100]] :band "very-high")
   "pt-omar" (series :blood-pressure [[120 78] [118 76] [119 77] [118 76]])
   "pt-sofia" (series :spo2 [95 94 93 92 90] :band "low")})

(def active-alerts
  {:total 2 :offset 0 :limit 1
   :items [{:id "flag-pt-ana-R1" :patient "Patient/pt-ana" :text "BP stage 2" :status "active" :since "2026-10-12T20:30:00Z" :until nil}]})

(def acknowledged-alerts
  {:total 1 :offset 0 :limit 100
   :items [{:id "flag-pt-liam-R3" :patient "Patient/pt-liam" :text "Glucose out of range" :status "inactive"
            :since "2026-10-11T20:30:00Z" :until "2026-10-13T07:40:00Z"}]})

(def requests
  {:total 2 :offset 0 :limit 1
   :items [{:id "appt-noah-1" :status "proposed" :description "First visit" :patient "Patient/pt-noah"}]})

(defn loaded [data]
  {:state :loaded :data data})

(def failed {:state :failed :kind :server})

(def loading {:state :loading})

(defn rows
  "The rows of a loaded panel: the summary of each patient and, for those with a headline reading, the series."
  []
  (into {}
        (for [id ids
              :let [kind (cond (= "pt-ana" id) :blood-pressure (= "pt-omar" id) :blood-pressure (= "pt-sofia" id) :spo2
                               (= "pt-liam" id) :glucose)]]
          [id (cond-> {:summary (loaded (get summaries id))}
                kind (assoc :series (loaded (get series-by-patient id))))])))

(defn settled
  "A panel that has loaded all of its parts: the state the page shows when everything went well. `now` is the moment of the fixtures."
  []
  {:now now
   :list (loaded patients)
   :rows (rows)
   :inbox {:active (loaded active-alerts) :acknowledged (loaded acknowledged-alerts) :requests (loaded requests)}
   :queue []
   :inflight #{}
   :order ["pt-ana" "pt-sofia" "pt-liam" "pt-omar" "pt-noah"]})
