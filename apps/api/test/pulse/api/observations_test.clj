(ns pulse.api.observations-test
  "GET and POST /api/patients/{id}/observations against the demo cohort (ADR-0014): who may read
  and record, what a series contains and how it is bounded, what a reading must be to be recorded,
  what is stored, and what is audited. Dr. Rivera's panel is Ana, Liam, Sofía and Omar; Dr.
  Okafor's is Maya and Noah, who has no readings at all and is where new ones can be recorded
  without any of the cohort's in the way."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [jsonista.core :as json]
            [pulse.api.observations :as observations]
            [pulse.api.test.harness :as h :refer [audit-events call with-api]]
            [pulse.fhir.client :as fc]
            [pulse.fhir.client.memory :as mem]
            [pulse.fhir.measurement :as measurement]
            [pulse.fhir.observation :as obs]
            [pulse.fhir.ranges :as ranges]
            [pulse.fhir.schema :as schema]
            [pulse.fhir.terminology :as t]
            [pulse.fhir.time :as time]
            [pulse.fhir.units :as units])
  (:import (java.net URLEncoder)
           (java.time Clock Duration Instant ZoneOffset)
           (java.time.format DateTimeFormatter)
           (java.time.temporal ChronoUnit)))

(def ^:private now
  "The time the tests say it is: a moment after the demo cohort was generated, so that none of its
  readings is as new as one recorded now."
  (do (h/demo-cohort)
      (.plusSeconds (.truncatedTo (Instant/now) ChronoUnit/SECONDS) 1)))

(def ^:private clock (Clock/fixed now ZoneOffset/UTC))

(defn- ago [^Duration d] (str (.minus ^Instant now d)))
(defn- ahead [^Duration d] (str (.plus ^Instant now d)))

(defn- readings
  "The Observations of `patient-id` with catalogue concept `concept` that `client` holds, newest
  first, found by looking through everything it holds: a way to the answer that does not go
  through the search the endpoint makes."
  [client patient-id concept]
  (->> (mem/contents client)
       (filter #(and (= "Observation" (:resourceType %))
                     (= (str "Patient/" patient-id) (get-in % [:subject :reference]))
                     (obs/concept? % concept)))
       (sort-by obs/effective-millis >)
       vec))

(defn- series [api who id query]
  (call api :get (str "/api/patients/" id "/observations") :token (h/token-for who) :query query))

(defn- record
  "POST of `body`, a map (or the text to send as it is), for patient `id` by `who`."
  [api who id body]
  (call api :post (str "/api/patients/" id "/observations")
        :token (h/token-for who)
        :body (if (string? body) body (json/write-value-as-string body))))

(defn- items [response] (get-in response [:json :items]))
(defn- ids [response] (mapv :id (items response)))

(defn- errored-fields
  "The fields the problem names; none for one that does not, such as a body that is not JSON."
  [response]
  (let [errors (get-in response [:json :errors])]
    (if (map? errors) (set (keys errors)) #{})))

(defn- enc
  "`s` for a query string. A `+` in one means a space, so an offset such as +02:00 must be encoded."
  [^String s]
  (URLEncoder/encode s "UTF-8"))

;; ---------------------------------------------------------------------------
;; Who may read a series

(deftest who-may-read-whose-series
  (with-api {}
    (fn [api]
      (doseq [[who id expected]
              [[:ana "pt-ana" 200] [:ana "pt-liam" 403] [:ana "pt-maya" 403]
               [:liam "pt-liam" 200] [:liam "pt-ana" 403]
               [:rivera "pt-ana" 200] [:rivera "pt-liam" 200] [:rivera "pt-sofia" 200] [:rivera "pt-omar" 200]
               [:rivera "pt-maya" 403] [:rivera "pt-noah" 403]
               [:okafor "pt-maya" 200] [:okafor "pt-noah" 200] [:okafor "pt-ana" 403]
               [:admin "pt-ana" 200] [:admin "pt-noah" 200]
               [:scheduler "pt-ana" 200] [:scheduler "pt-noah" 200]]]
        (testing (str who " -> " id)
          (is (= expected (:status (series api who id "measurement=heart-rate")))))))))

(deftest a-series-without-credentials-or-with-another-method
  (with-api {}
    (fn [api]
      (is (= 401 (:status (call api :get "/api/patients/pt-ana/observations" :query "measurement=heart-rate"))))
      (is (= 401 (:status (call api :post "/api/patients/pt-ana/observations" :body "{}"))))
      (let [r (call api :delete "/api/patients/pt-ana/observations" :token (h/token-for :admin))]
        (is (= 405 (:status r)))
        (is (= "GET, POST" (h/header r "Allow")))))))

;; ---------------------------------------------------------------------------
;; What a series contains

(deftest a-series-is-what-the-patient-measured
  (let [client (h/demo-cohort)]
    (with-api {:fhir client}
      (fn [api]
        (testing "heart rate: every reading, newest first, in the unit it is stored in"
          (let [expected (readings client "pt-ana" :heart-rate)
                r (series api :ana "pt-ana" "measurement=heart-rate&limit=500")
                shown (items r)]
            (is (= 200 (:status r)))
            (is (= "application/json" (h/content-type r)))
            (is (>= (count expected) 58) "a month of two readings a day")
            (is (= (mapv :id expected) (ids r)))
            (is (= false (get-in r [:json :truncated])))
            (is (= (mapv #(get-in % [:valueQuantity :value]) expected) (mapv :value shown)))
            (is (= (mapv :effectiveDateTime expected) (mapv :at shown)))
            (is (every? #(= "/min" (:unit %)) shown))
            (is (every? #(= "heart-rate" (:measurement %)) shown))
            (is (every? #(= "Patient/pt-ana" (:by %)) shown) "home readings are by the patient")
            (is (every? #(= (name (ranges/classify :heart-rate (:value %))) (:band %)) shown))
            (is (every? #(= #{:id :measurement :at :value :unit :band :by} (set (keys %))) shown))))
        (testing "blood pressure: two numbers, and the band of the worse of them"
          (let [expected (readings client "pt-ana" :bp-panel)
                r (series api :ana "pt-ana" "measurement=blood-pressure&limit=500")
                shown (items r)]
            (is (= (mapv :id expected) (ids r)))
            (is (= (mapv obs/blood-pressure expected) (mapv #(select-keys % [:systolic :diastolic]) shown)))
            (is (every? #(= "mm[Hg]" (:unit %)) shown))
            (is (every? #(not (contains? % :value)) shown))
            (is (every? #(= (name (ranges/worst [(ranges/classify :systolic (:systolic %))
                                                 (ranges/classify :diastolic (:diastolic %))]))
                            (:band %))
                        shown))
            (is (= "very-high" (:band (first shown))) "the scenario: a pressure that climbed past 160")))
        (testing "weight has no reference band"
          (let [shown (items (series api :ana "pt-ana" "measurement=weight&limit=500"))]
            (is (seq shown))
            (is (every? #(and (= "kg" (:unit %)) (nil? (:band %)) (number? (:value %))) shown))))
        (testing "glucose: the low and the highs of the scenario"
          (let [shown (items (series api :liam "pt-liam" "measurement=glucose&limit=500"))]
            (is (every? #(= "mg/dL" (:unit %)) shown))
            (is (some #{"low"} (map :band shown)))
            (is (some #{"very-high"} (map :band shown)))))
        (testing "a measurement nobody took is an empty series, not an error"
          (let [r (series api :ana "pt-ana" "measurement=temperature")]
            (is (= 200 (:status r)))
            (is (= {:items [] :truncated false} (:json r)))))))))

(deftest what-is-in-a-series-is-what-the-summary-shows-as-latest
  (let [client (h/demo-cohort)]
    (with-api {:fhir client}
      (fn [api]
        (doseq [[who patient kind] [[:rivera "pt-ana" :blood-pressure] [:rivera "pt-ana" :heart-rate]
                                    [:rivera "pt-ana" :weight] [:rivera "pt-liam" :glucose]
                                    [:rivera "pt-sofia" :spo2]]]
          (testing (str patient " " kind)
            (let [first-reading (first (items (series api who patient (str "measurement=" (name kind) "&limit=1"))))
                  latest (get-in (call api :get (str "/api/patients/" patient "/summary") :token (h/token-for who))
                                 [:json :vitals kind])]
              (is (some? first-reading))
              (is (= (dissoc first-reading :measurement :by) latest)))))))))

;; ---------------------------------------------------------------------------
;; Bounds, order and limit

(def ^:private offset-format DateTimeFormatter/ISO_OFFSET_DATE_TIME)

(defn- in-zone-plus-2 [instant-string]
  (.format ^DateTimeFormatter offset-format (.atOffset (Instant/parse instant-string) (ZoneOffset/ofHours 2))))

(deftest from-is-inclusive-and-to-is-exclusive
  (let [client (h/demo-cohort)
        expected (readings client "pt-ana" :heart-rate)
        at (fn [i] (:effectiveDateTime (nth expected i)))]
    (with-api {:fhir client}
      (fn [api]
        (let [get-ids (fn [query] (ids (series api :ana "pt-ana" (str "measurement=heart-rate&limit=500&" query))))]
          (testing "both ends: the readings from the 20th newest up to, and not including, the 10th"
            (is (= (mapv :id (subvec expected 10 20))
                   (get-ids (str "from=" (at 19) "&to=" (at 9))))))
          (testing "only one end"
            (is (= (mapv :id (subvec expected 0 10)) (get-ids (str "from=" (at 9)))))
            (is (= (mapv :id (subvec expected 10)) (get-ids (str "to=" (at 9))))))
          (testing "an instant in another offset is the same instant"
            (is (= (get-ids (str "from=" (at 19) "&to=" (at 9)))
                   (get-ids (str "from=" (enc (in-zone-plus-2 (at 19))) "&to=" (enc (in-zone-plus-2 (at 9)))))))
            (is (seq (get-ids (str "from=" (enc (in-zone-plus-2 (at 19))) "&to=" (enc (in-zone-plus-2 (at 9))))))))
          (testing "a date is midnight UTC"
            (let [date (subs (at 8) 0 10)
                  midnight (time/->millis date)]
              (is (= (mapv :id (filter #(>= (obs/effective-millis %) midnight) expected)) (get-ids (str "from=" date))))
              (is (= (mapv :id (filter #(< (obs/effective-millis %) midnight) expected)) (get-ids (str "to=" date))))))
          (testing "a period with nothing in it"
            (is (= [] (get-ids "from=2001-01-01&to=2001-02-01")))))))))

(deftest a-period-that-ends-before-it-starts-is-a-400
  (with-api {}
    (fn [api]
      (doseq [query ["from=2026-10-10&to=2026-10-01" "from=2026-10-10&to=2026-10-10"]]
        (testing query
          (let [r (series api :ana "pt-ana" (str "measurement=heart-rate&" query))]
            (is (= 400 (:status r)))
            (is (= #{:to} (errored-fields r)))))))))

(deftest a-series-is-cut-at-the-limit-and-says-so
  (let [client (h/demo-cohort)
        expected (readings client "pt-ana" :heart-rate)
        n (count expected)]
    (with-api {:fhir client}
      (fn [api]
        (let [get-series (fn [query] (:json (series api :ana "pt-ana" (str "measurement=heart-rate&" query))))]
          (testing "the newest ones are kept, and the answer says it was cut"
            (let [body (get-series "limit=5")]
              (is (= (mapv :id (take 5 expected)) (mapv :id (:items body))))
              (is (= true (:truncated body)))))
          (testing "exactly as many as there are is not cut"
            (is (= false (:truncated (get-series (str "limit=" n)))))
            (is (= true (:truncated (get-series (str "limit=" (dec n))))))
            (is (= n (count (:items (get-series (str "limit=" (inc n))))))))
          (testing "the default is a hundred"
            (is (= n (count (:items (get-series ""))))))
          (testing "oldest first, when asked"
            (is (= (mapv :id (reverse expected)) (mapv :id (:items (get-series "sort=date")))))
            (is (= (mapv :id (take 3 (reverse expected))) (mapv :id (:items (get-series "sort=date&limit=3"))))))
          (testing "newest first is the default and can be asked for"
            (is (= (get-series "") (get-series "sort=-date")))))))))

(deftest a-series-can-be-read-page-by-page-going-back-with-to
  (let [client (h/demo-cohort)
        expected (readings client "pt-ana" :heart-rate)]
    (with-api {:fhir client}
      (fn [api]
        (let [pages (loop [to nil, pages []]
                      (let [body (:json (series api :ana "pt-ana" (str "measurement=heart-rate&limit=7"
                                                                       (when to (str "&to=" to)))))
                            pages (conj pages body)]
                        (if (:truncated body)
                          (recur (:at (peek (:items body))) pages)
                          pages)))]
          (is (> (count pages) 1))
          (is (every? #(= 7 (count (:items %))) (butlast pages)))
          (is (= (mapv :id expected) (mapv :id (mapcat :items pages)))
              "every reading once, in order: nothing skipped and nothing repeated"))))))

(deftest a-patient-without-readings-and-one-that-does-not-exist
  (with-api {}
    (fn [api]
      (testing "Noah has measured nothing: an empty series"
        (let [r (series api :okafor "pt-noah" "measurement=heart-rate")]
          (is (= 200 (:status r)))
          (is (= {:items [] :truncated false} (:json r)))))
      (testing "a patient that does not exist: 404 for whoever may look"
        (is (= 404 (:status (series api :admin "pt-ghost" "measurement=heart-rate"))))
        (is (= 404 (:status (series api :rivera "pt-ghost" "measurement=heart-rate"))))
        (is (= 404 (:status (series api :scheduler "pt-ghost" "measurement=heart-rate")))))
      (testing "and for a patient it is the 403 of someone else's record: no way to probe"
        (is (= 403 (:status (series api :ana "pt-ghost" "measurement=heart-rate"))))))))

(deftest bad-query-parameters-are-a-400-that-names-the-parameter
  (with-api {}
    (fn [api]
      (doseq [[query parameter]
              [["" :measurement]
               ["measurement=nope" :measurement]
               ["measurement=Heart-Rate" :measurement]
               ["measurement=heart-rate&limit=0" :limit]
               ["measurement=heart-rate&limit=501" :limit]
               ["measurement=heart-rate&limit=many" :limit]
               ["measurement=heart-rate&sort=value" :sort]
               ["measurement=heart-rate&from=yesterday" :from]
               ["measurement=heart-rate&from=" :from]
               ["measurement=heart-rate&from=2026-02-30" :from]
               ["measurement=heart-rate&from=2026-10-13T08:00:00" :from]
               ["measurement=heart-rate&from=2026-10-13T23:59:60Z" :from]
               ["measurement=heart-rate&to=2026-10-13T08:00Z" :to]
               ["measurement=heart-rate&from=1800-01-01" :from]
               ["measurement=heart-rate&to=2200-01-01" :to]
               ["measurement=heart-rate&to=+999999999-01-01T00:00:00Z" :to]]]
        (testing query
          (let [r (series api :admin "pt-ana" query)]
            (is (= 400 (:status r)))
            (is (contains? (get-in r [:json :errors]) parameter))))))))

;; ---------------------------------------------------------------------------
;; What a series asks of FHIR

(defn- with-spied-api
  "Like `with-api`, with the FHIR client wrapped in a spy: `(f api calls)`."
  [f]
  (let [calls (atom [])]
    (with-api {:fhir (h/spy (h/demo-cohort) calls) :clock clock}
      (fn [api] (f api calls)))))

(def ^:private hr "http://loinc.org|8867-4")

(deftest the-series-asks-for-exactly-what-it-needs
  (with-spied-api
    (fn [api calls]
      (testing "a patient reading their own: the one search, a page of the limit and one more"
        (series api :ana "pt-ana" "measurement=heart-rate")
        (is (= [[:search "Observation" {:patient "Patient/pt-ana" :code hr :_sort "-date" :_count 101}]] @calls)))
      (reset! calls [])
      (testing "a practitioner: the patient is read first, to know the panel"
        (series api :rivera "pt-ana" "measurement=heart-rate&limit=10&sort=date")
        (is (= [[:read "Patient" "pt-ana"]
                [:search "Observation" {:patient "Patient/pt-ana" :code hr :_sort "date" :_count 11}]]
               @calls)))
      (reset! calls [])
      (testing "a period goes to the server as UTC instants, in the form FHIR takes"
        (series api :ana "pt-ana" (str "measurement=heart-rate&from=2026-10-01&to=" (enc "2026-10-14T10:00:00+02:00")))
        (is (= [{:patient "Patient/pt-ana" :code hr :_sort "-date" :_count 101
                 :date ["ge2026-10-01T00:00:00Z" "lt2026-10-14T08:00:00Z"]}]
               (map #(nth % 2) (filter #(= :search (first %)) @calls)))))
      (reset! calls [])
      (testing "pages are of at most 200, and the other measurements have their own code"
        (series api :ana "pt-ana" "measurement=blood-pressure&limit=500")
        (is (= [{:patient "Patient/pt-ana" :code "http://loinc.org|85354-9" :_sort "-date" :_count 200}]
               (map #(nth % 2) (filter #(= :search (first %)) @calls)))))
      (reset! calls [])
      (testing "the patient is looked for again only when there is nothing to show"
        (series api :okafor "pt-noah" "measurement=heart-rate")
        (is (= [[:read "Patient" "pt-noah"]
                [:search "Observation" {:patient "Patient/pt-noah" :code hr :_sort "-date" :_count 101}]
                [:read "Patient" "pt-noah"]]
               @calls)))
      (reset! calls [])
      (testing "someone who may not read it, and an id that cannot be one: nothing at all"
        (series api :ana "pt-liam" "measurement=heart-rate")
        (series api :admin "a%20b" "measurement=heart-rate")
        (series api :admin "pt-ana%2F.." "measurement=heart-rate")
        (is (empty? @calls))))))

(deftest a-series-when-the-fhir-server-is-down
  (with-api {:fhir h/unreachable-fhir}
    (fn [api]
      (doseq [who [:ana :rivera :admin :scheduler]]
        (let [r (series api who "pt-ana" "measurement=heart-rate")]
          (is (= 502 (:status r)) (str who))
          (is (not (str/includes? (:text r) "secret-host"))))))))

;; ---------------------------------------------------------------------------
;; Readings that are not what they should be

(deftest one-odd-reading-does-not-fail-the-series
  (let [client (h/demo-cohort)
        put! (fn [resource]
               (fc/put! client (merge {:resourceType "Observation" :status "final"
                                       :subject {:reference "Patient/pt-noah"}}
                                      resource)))]
    ;; other systems write to the same FHIR server: whatever they write has to be shown or left out
    (put! {:id "odd-text" :code (t/codeable-concept :heart-rate) :effectiveDateTime "2026-10-01T08:00:00Z"
           :valueString "high"})
    (put! {:id "odd-unit" :code (t/codeable-concept :heart-rate) :effectiveDateTime "2026-10-02T08:00:00Z"
           :valueQuantity {:value 80 :unit "furlongs" :code "furlong"}})
    (put! {:id "odd-integer" :code (t/codeable-concept :heart-rate) :effectiveDateTime "2026-10-03T08:00:00Z"
           :valueInteger 70})
    (put! {:id "odd-panel" :code (t/codeable-concept :bp-panel) :effectiveDateTime "2026-10-03T08:00:00Z"
           :component [{:code (t/codeable-concept :systolic) :valueQuantity (units/quantity 150 "mm[Hg]")}]})
    (with-api {:fhir client}
      (fn [api]
        (let [r (series api :okafor "pt-noah" "measurement=heart-rate")]
          (is (= 200 (:status r)))
          (is (= [{:id "odd-integer" :at "2026-10-03T08:00:00Z" :value 70 :unit "/min" :band "normal"}
                  {:id "odd-unit" :at "2026-10-02T08:00:00Z" :value 80 :unit "furlong" :band nil}
                  {:id "odd-text" :at "2026-10-01T08:00:00Z" :value nil :unit "/min" :band nil}]
                 (map #(select-keys % [:id :at :value :unit :band]) (items r)))))
        (let [r (series api :okafor "pt-noah" "measurement=blood-pressure")]
          (is (= [{:id "odd-panel" :systolic 150 :diastolic nil :unit "mm[Hg]" :band "high"}]
                 (map #(select-keys % [:id :systolic :diastolic :unit :band]) (items r)))))))))

(deftest a-reading-is-classified-in-the-unit-it-was-written-in
  (let [bp-in-kpa {:resourceType "Observation" :id "x" :effectiveDateTime "2026-10-01T08:00:00Z"
                   :component [{:code (t/codeable-concept :systolic) :valueQuantity (units/quantity 20 "kPa")}
                               {:code (t/codeable-concept :diastolic) :valueQuantity (units/quantity 12 "kPa")}]}]
    (is (= {:id "x" :at "2026-10-01T08:00:00Z" :systolic 20 :diastolic 12 :unit "kPa" :band "high"}
           (observations/reading-view :blood-pressure bp-in-kpa))
        "150 over 90 mmHg, as the unit it was written in says")))

;; ---------------------------------------------------------------------------
;; Who may record

(deftest who-may-record-for-whom
  (with-api {:clock clock}
    (fn [api]
      (doseq [[who id expected]
              [[:ana "pt-ana" 201] [:ana "pt-liam" 403] [:ana "pt-maya" 403]
               [:liam "pt-liam" 201] [:liam "pt-ana" 403]
               [:rivera "pt-ana" 201] [:rivera "pt-sofia" 201] [:rivera "pt-maya" 403] [:rivera "pt-noah" 403]
               [:okafor "pt-maya" 201] [:okafor "pt-noah" 201] [:okafor "pt-ana" 403]
               [:admin "pt-ana" 403] [:admin "pt-noah" 403]
               [:scheduler "pt-ana" 201] [:scheduler "pt-noah" 201]]]
        (testing (str who " -> " id)
          (is (= expected (:status (record api who id {:measurement "heart-rate" :value 70})))))))))

;; ---------------------------------------------------------------------------
;; What is recorded

(deftest a-recorded-reading-is-an-observation-in-fhir
  (let [client (h/demo-cohort)]
    (with-api {:fhir client :clock clock}
      (fn [api]
        (let [r (record api :ana "pt-ana" {:measurement "heart-rate" :value 71.6})
              id (get-in r [:json :id])
              stored (fc/read-resource client "Observation" id)]
          (is (= 201 (:status r)))
          (is (= "application/json" (h/content-type r)))
          (is (re-matches #"obs-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}" id))
          (is (= {:id id :measurement "heart-rate" :at (str now) :value 72 :unit "/min" :band "normal"
                  :by "Patient/pt-ana"}
                 (:json r))
              "what was stored, not what was sent: converted, rounded, and dated by the server")
          (testing "the Observation"
            (is (some? stored))
            (is (schema/valid? stored))
            (is (= "final" (:status stored)))
            (is (= "Patient/pt-ana" (get-in stored [:subject :reference])))
            (is (obs/concept? stored :heart-rate))
            (is (= {:kind :quantity :value 72 :unit "/min"} (obs/value stored)))
            (is (= (str now) (:effectiveDateTime stored)))
            (is (= [{:reference "Patient/pt-ana"}] (:performer stored)))
            (is (= "vital-signs" (get-in stored [:category 0 :coding 0 :code])))))))))

(deftest who-took-the-reading
  (let [client (h/demo-cohort)]
    (with-api {:fhir client :clock clock}
      (fn [api]
        (let [by (fn [who patient]
                   (let [r (record api who patient {:measurement "weight" :value 80})
                         stored (fc/read-resource client "Observation" (get-in r [:json :id]))]
                     [(get-in r [:json :by]) (get-in stored [:performer 0 :reference]) (get-in stored [:subject :reference])]))]
          (testing "a patient: themselves"
            (is (= ["Patient/pt-ana" "Patient/pt-ana" "Patient/pt-ana"] (by :ana "pt-ana"))))
          (testing "a clinician: named as the performer, and the subject is still the patient"
            (is (= ["Practitioner/pr-rivera" "Practitioner/pr-rivera" "Patient/pt-ana"] (by :rivera "pt-ana")))
            (is (= ["Practitioner/pr-okafor" "Practitioner/pr-okafor" "Patient/pt-noah"] (by :okafor "pt-noah"))))
          (testing "the rules engine is not a person, and so there is nobody to name"
            (is (= [nil nil "Patient/pt-ana"] (by :scheduler "pt-ana")))
            (let [r (record api :scheduler "pt-ana" {:measurement "weight" :value 80})]
              (is (schema/valid? (fc/read-resource client "Observation" (get-in r [:json :id])))))))))))

(deftest units-are-converted-to-the-one-each-measurement-is-stored-in
  (with-api {:clock clock}
    (fn [api]
      (doseq [[label body expected]
              [["glucose in mmol/L" {:measurement "glucose" :value 6.1 :unit "mmol/L"}
                {:value 110 :unit "mg/dL" :band "normal"}]
               ["weight in pounds" {:measurement "weight" :value 160 :unit "[lb_av]"}
                {:value 72.6 :unit "kg" :band nil}]
               ["temperature in Fahrenheit" {:measurement "temperature" :value 98.6 :unit "[degF]"}
                {:value 37.0 :unit "Cel" :band "normal"}]
               ["a pressure in kPa" {:measurement "blood-pressure" :systolic 20 :diastolic 12 :unit "kPa"}
                {:systolic 150 :diastolic 90 :unit "mm[Hg]" :band "high"}]
               ["a heart rate with decimals" {:measurement "heart-rate" :value 72.4}
                {:value 72 :unit "/min" :band "normal"}]
               ["oxygen saturation" {:measurement "spo2" :value 91 :unit "%"}
                {:value 91 :unit "%" :band "low"}]]]
        (testing label
          (let [r (record api :ana "pt-ana" body)]
            (is (= 201 (:status r)))
            (is (= expected (dissoc (:json r) :id :at :measurement :by)))))))))

(deftest the-server-decides-what-the-client-must-not
  (let [client (h/demo-cohort)]
    (with-api {:fhir client :clock clock}
      (fn [api]
        (let [r (record api :ana "pt-ana"
                        {:measurement "heart-rate" :value 70 :id "mine" :status "entered-in-error"
                         :subject {:reference "Patient/pt-liam"} :performer {:reference "Practitioner/pr-okafor"}
                         :category "laboratory" :resourceType "Patient"})
              stored (fc/read-resource client "Observation" (get-in r [:json :id]))]
          (is (= 201 (:status r)))
          (is (nil? (fc/read-resource client "Observation" "mine")))
          (is (= "final" (:status stored)))
          (is (= "Patient/pt-ana" (get-in stored [:subject :reference])))
          (is (= [{:reference "Patient/pt-ana"}] (:performer stored)))
          (is (= "Observation" (:resourceType stored))))))))

(deftest every-reading-gets-an-id-of-its-own-and-is-written-once
  (let [calls (atom [])]
    (with-api {:fhir (h/spy (h/demo-cohort) calls) :clock clock}
      (fn [api]
        (let [a (record api :ana "pt-ana" {:measurement "heart-rate" :value 70})
              b (record api :ana "pt-ana" {:measurement "heart-rate" :value 70})]
          (is (not= (get-in a [:json :id]) (get-in b [:json :id])))
          (is (= [[:put "Observation" (get-in a [:json :id])] [:put "Observation" (get-in b [:json :id])]]
                 (filter #(= :put (first %)) @calls))))))))

;; ---------------------------------------------------------------------------
;; When a reading was taken

(deftest the-time-defaults-to-now-and-is-kept-in-utc
  (with-api {:clock (Clock/fixed (Instant/parse "2026-10-14T12:00:00.987Z") ZoneOffset/UTC)}
    (fn [api]
      (let [at (fn [body] (get-in (record api :ana "pt-ana" (merge {:measurement "spo2" :value 97} body)) [:json :at]))]
        (is (= "2026-10-14T12:00:00Z" (at {})) "the clock's now, to the second")
        (is (= "2026-10-14T08:00:00Z" (at {:at "2026-10-14T10:00:00+02:00"})) "any offset, stored as UTC")
        (is (= "2026-10-14T08:00:00Z" (at {:at "2026-10-14T08:00:00Z"})))
        (is (= "2026-10-14T10:00:00.123456Z" (at {:at "2026-10-14T10:00:00.123456Z"})) "fractions are kept")))))

(deftest a-reading-cannot-claim-a-time-that-cannot-be-right
  (with-api {:clock clock}
    (fn [api]
      (let [status-at (fn [at] (:status (record api :ana "pt-ana" {:measurement "heart-rate" :value 70 :at at})))]
        (testing "ahead of the server: five minutes for clocks that differ, not a second more"
          (is (= 201 (status-at (ahead (Duration/ofMinutes 5)))))
          (is (= 422 (status-at (ahead (.plusSeconds (Duration/ofMinutes 5) 1)))))
          (is (= 422 (status-at "2099-01-01T00:00:00Z"))))
        (testing "back: thirty days, not a second more"
          (is (= 201 (status-at (ago (Duration/ofDays 30)))))
          (is (= 422 (status-at (ago (.plusSeconds (Duration/ofDays 30) 1)))))
          (is (= 422 (status-at "2020-01-01T00:00:00Z"))))
        (testing "and the answer is about the time"
          (let [r (record api :ana "pt-ana" {:measurement "heart-rate" :value 70 :at (ahead (Duration/ofHours 1))})]
            (is (= {:at ["must not be in the future"]} (get-in r [:json :errors])))))))))

;; ---------------------------------------------------------------------------
;; What cannot be recorded

(def ^:private rejected
  "Bodies that do not get in: what they are, the body, the status and the fields the answer names."
  [;; the shape is wrong: 400, from the schema
   ["no measurement" {:value 72} 400 #{:measurement}]
   ["a measurement that does not exist" {:measurement "nope" :value 1} 400 #{:measurement}]
   ["the name in another case" {:measurement "Heart-Rate" :value 72} 400 #{:measurement}]
   ["no number" {:measurement "heart-rate"} 400 #{:value}]
   ["a number as text" {:measurement "heart-rate" :value "72"} 400 #{:value}]
   ["a null number" {:measurement "heart-rate" :value nil} 400 #{:value}]
   ["a boolean for a number" {:measurement "heart-rate" :value true} 400 #{:value}]
   ["a blood pressure with no diastolic" {:measurement "blood-pressure" :systolic 120} 400 #{:diastolic}]
   ["a blood pressure that is one number" {:measurement "blood-pressure" :value 120} 400 #{:systolic :diastolic}]
   ["a unit that is not text" {:measurement "heart-rate" :value 72 :unit 5} 400 #{:unit}]
   ["an empty unit" {:measurement "heart-rate" :value 72 :unit ""} 400 #{:unit}]
   ["a unit that is far too long" {:measurement "heart-rate" :value 72 :unit (apply str (repeat 17 "x"))} 400 #{:unit}]
   ["a time that is not one" {:measurement "heart-rate" :value 72 :at "yesterday"} 400 #{:at}]
   ["a time with no offset" {:measurement "heart-rate" :value 72 :at "2026-10-14T08:00:00"} 400 #{:at}]
   ["a date, which is not a time" {:measurement "heart-rate" :value 72 :at "2026-10-14"} 400 #{:at}]
   ["a leap second" {:measurement "heart-rate" :value 72 :at "2026-10-14T23:59:60Z"} 400 #{:at}]
   ["a year with more than four digits" {:measurement "heart-rate" :value 72 :at "+999999999-01-01T00:00:00Z"} 400 #{:at}]
   ["a body that is not an object" "[1, 2, 3]" 400 #{}]
   ["a body that is not JSON" "{not json" 400 #{}]
   ;; the shape is right and it cannot be a reading: 422, from the catalogue
   ["a heart rate of 400" {:measurement "heart-rate" :value 400} 422 #{:value}]
   ["a heart rate of 5" {:measurement "heart-rate" :value 5} 422 #{:value}]
   ["oxygen above 100 %" {:measurement "spo2" :value 101} 422 #{:value}]
   ["a weight of 700 kg" {:measurement "weight" :value 700} 422 #{:value}]
   ["a weight in pounds that is too much once converted" {:measurement "weight" :value 2000 :unit "[lb_av]"} 422 #{:value}]
   ["a Fahrenheit temperature labelled as Celsius" {:measurement "temperature" :value 98.6 :unit "Cel"} 422 #{:value}]
   ["a unit the measurement cannot have" {:measurement "heart-rate" :value 72 :unit "kg"} 422 #{:unit}]
   ["a unit that is not UCUM" {:measurement "weight" :value 160 :unit "lb"} 422 #{:unit}]
   ["a diastolic above the systolic" {:measurement "blood-pressure" :systolic 80 :diastolic 120} 422 #{:diastolic}]
   ["both pressures out of range" {:measurement "blood-pressure" :systolic 20 :diastolic 250} 422 #{:systolic :diastolic}]
   ["a number too large to be one" "{\"measurement\":\"heart-rate\",\"value\":1e999}" 422 #{:value}]
   ["a reading from the future" {:measurement "heart-rate" :value 70 :at (ahead (Duration/ofHours 1))} 422 #{:at}]
   ["a reading from last year" {:measurement "heart-rate" :value 70 :at "2025-10-14T08:00:00Z"} 422 #{:at}]
   ["everything wrong at once" {:measurement "blood-pressure" :systolic 20 :diastolic 250
                                :at (ahead (Duration/ofHours 1))}
    422 #{:systolic :diastolic :at}]])

(deftest what-cannot-be-recorded-is-refused-says-what-is-wrong-and-writes-nothing
  (let [calls (atom [])]
    (with-api {:fhir (h/spy (h/demo-cohort) calls) :clock clock}
      (fn [api]
        (doseq [[label body status fields] rejected]
          (testing label
            (let [r (record api :ana "pt-ana" body)]
              (is (= status (:status r)))
              (is (= "application/problem+json" (h/content-type r)))
              (is (= fields (errored-fields r)))
              (is (empty? (h/audit-writes r)))))
          (is (empty? @calls) "a reading that cannot be recorded costs no call to FHIR, not even the patient's"))))))

(deftest a-refusal-does-not-repeat-what-was-sent
  (with-api {:clock clock}
    (fn [api]
      (doseq [body [{:measurement "heart-rate" :value 72 :unit "SECRET-UNIT"}
                    {:measurement "heart-rate" :value 72 :at "SECRET-TIME"}
                    {:measurement "SECRET-NAME" :value 72}]]
        (is (not (str/includes? (:text (record api :ana "pt-ana" body)) "SECRET")) (pr-str body))))))

;; ---------------------------------------------------------------------------
;; What recording asks of FHIR

(deftest recording-asks-of-fhir-only-what-it-needs
  (with-spied-api
    (fn [api calls]
      (testing "a patient for themselves: the patient is read, and then the reading is written"
        (let [id (get-in (record api :ana "pt-ana" {:measurement "heart-rate" :value 70}) [:json :id])]
          (is (= [[:read "Patient" "pt-ana"] [:put "Observation" id]] @calls))))
      (reset! calls [])
      (testing "a practitioner: the authorization step read the patient, and the endpoint reads it again"
        (let [id (get-in (record api :rivera "pt-ana" {:measurement "heart-rate" :value 70}) [:json :id])]
          (is (= [[:read "Patient" "pt-ana"] [:read "Patient" "pt-ana"] [:put "Observation" id]] @calls))))
      (reset! calls [])
      (testing "a reading that cannot be recorded: only what the authorization step needed"
        (record api :rivera "pt-ana" {:measurement "weight" :value 700})
        (is (= [[:read "Patient" "pt-ana"]] @calls)))
      (reset! calls [])
      (testing "someone who may not record, and an id that cannot be a patient's: nothing"
        (record api :ana "pt-liam" {:measurement "heart-rate" :value 70})
        (record api :admin "pt-ana" {:measurement "heart-rate" :value 70})
        (record api :scheduler "a%20b" {:measurement "heart-rate" :value 70})
        (is (empty? @calls)))
      (testing "a patient that does not exist: found out, and nothing written"
        (let [r (record api :scheduler "pt-ghost" {:measurement "heart-rate" :value 70})]
          (is (= 404 (:status r)))
          (is (= [[:read "Patient" "pt-ghost"]] @calls))
          (is (empty? (h/audit-writes r))))))))

(deftest when-the-fhir-server-fails-the-reading-is-not-recorded-and-says-so
  (testing "the server is down"
    (with-api {:fhir h/unreachable-fhir :clock clock}
      (fn [api]
        (let [r (record api :ana "pt-ana" {:measurement "heart-rate" :value 70})]
          (is (= 502 (:status r)))
          (is (not (str/includes? (:text r) "secret-host")))
          (is (empty? (h/audit-writes r)))))))
  (testing "it answers reads and refuses the write"
    (let [client (h/demo-cohort)]
      (with-api {:fhir (h/misbehaving client {"Observation" :fail}) :clock clock}
        (fn [api]
          (let [before (count (mem/contents client))
                r (record api :ana "pt-ana" {:measurement "heart-rate" :value 70})]
            (is (= 502 (:status r)))
            (is (= "The FHIR server could not be reached." (get-in r [:json :detail])))
            (is (empty? (h/audit-writes r)) "it is not recorded as written when it was not")
            (is (= before (count (mem/contents client)))))))))
  (testing "it cannot even find out whether the patient exists"
    (with-api {:fhir (h/misbehaving (h/demo-cohort) {"Patient" :fail}) :clock clock}
      (fn [api]
        (is (= 502 (:status (record api :ana "pt-ana" {:measurement "heart-rate" :value 70}))))))))

;; ---------------------------------------------------------------------------
;; The audit trail

(deftest recording-is-audited-by-reference-and-never-with-the-reading
  (with-api {:clock clock}
    (fn [api]
      (let [r (record api :okafor "pt-noah" {:measurement "glucose" :value 6.1 :unit "mmol/L"})
            id (get-in r [:json :id])
            actor {:subject "u-okafor" :username "dr.okafor@pulse.test" :roles ["practitioner"] :client "pulse-spa"}
            path "/api/patients/pt-noah/observations"]
        (is (= 201 (:status r)))
        (is (= [{:level :info
                 :data {:actor actor :action :observation/record :outcome :allowed :scope :panel
                        :patient "Patient/pt-noah" :method "POST" :path path}}]
               (map #(select-keys % [:level :data]) (audit-events r)))
            "the decision, as for every action")
        (is (= [{:level :info
                 :data {:actor actor :action :observation/record :outcome :created
                        :patient "Patient/pt-noah" :resource (str "Observation/" id)
                        :method "POST" :path path}}]
               (map #(select-keys % [:level :data]) (h/audit-writes r)))
            "and the write, once FHIR has it")
        (is (every? #(string? (get-in % [:ctx :request-id])) (concat (audit-events r) (h/audit-writes r))))
        (testing "nothing of the reading is logged"
          (let [logged (pr-str (:signals r))]
            (doseq [secret ["glucose" "mmol" "mg/dL" "laboratory"]]
              (is (not (str/includes? logged secret)) secret))))))))

(deftest a-refusal-is-audited-as-a-decision-and-never-as-a-write
  (with-api {:clock clock}
    (fn [api]
      (let [denied (record api :ana "pt-liam" {:measurement "heart-rate" :value 70})
            invalid (record api :ana "pt-ana" {:measurement "heart-rate" :value 700})]
        (is (= [:denied] (map #(get-in % [:data :outcome]) (audit-events denied))))
        (is (= [:allowed] (map #(get-in % [:data :outcome]) (audit-events invalid))))
        (is (empty? (concat (h/audit-writes denied) (h/audit-writes invalid))))))))

;; ---------------------------------------------------------------------------
;; Recorded and read back

(deftest a-recorded-reading-is-in-the-series-and-in-the-summary
  (let [client (h/demo-cohort)]
    (with-api {:fhir client :clock clock}
      (fn [api]
        (testing "Noah has nothing, and then he has what was recorded, newest first"
          (is (= [] (ids (series api :okafor "pt-noah" "measurement=heart-rate"))))
          (let [first-id (get-in (record api :okafor "pt-noah" {:measurement "heart-rate" :value 64
                                                                :at (ago (Duration/ofHours 3))})
                                 [:json :id])
                last-id (get-in (record api :okafor "pt-noah" {:measurement "heart-rate" :value 66
                                                               :at (ago (Duration/ofHours 1))})
                                [:json :id])
                r (series api :okafor "pt-noah" "measurement=heart-rate")]
            (is (= [last-id first-id] (ids r)))
            (is (= [66 64] (mapv :value (items r))))
            (is (= ["Practitioner/pr-okafor" "Practitioner/pr-okafor"] (mapv :by (items r))))
            (testing "from and to apply to what was recorded"
              (is (= [last-id] (ids (series api :okafor "pt-noah"
                                            (str "measurement=heart-rate&from=" (ago (Duration/ofHours 2)))))))
              (is (= [first-id] (ids (series api :okafor "pt-noah"
                                             (str "measurement=heart-rate&to=" (ago (Duration/ofHours 2))))))))
            (testing "the summary's latest reading is the newest of them"
              (let [summary (:json (call api :get "/api/patients/pt-noah/summary" :token (h/token-for :okafor)))]
                (is (= last-id (get-in summary [:vitals :heart-rate :id])))
                (is (= 66 (get-in summary [:vitals :heart-rate :value])))
                (is (= [] (:unavailable summary)))))))
        (testing "a blood pressure is read back as the two numbers it was"
          (let [r (record api :okafor "pt-noah" {:measurement "blood-pressure" :systolic 162 :diastolic 101})
                summary (:json (call api :get "/api/patients/pt-noah/summary" :token (h/token-for :okafor)))]
            (is (= {:id (get-in r [:json :id]) :systolic 162 :diastolic 101 :unit "mm[Hg]" :band "very-high"}
                   (select-keys (get-in summary [:vitals :blood-pressure]) [:id :systolic :diastolic :unit :band])))))))))

;; ---------------------------------------------------------------------------
;; Whatever is sent

(def ^:private gen-junk
  (gen/one-of [gen/small-integer
               (gen/double* {:NaN? false :infinite? false})
               gen/string-alphanumeric
               gen/boolean
               (gen/return nil)
               (gen/elements ["heart-rate" "blood-pressure" "weight" "glucose" "spo2" "temperature"
                              "mmol/L" "kg" "/min" "[degF]" "kPa" "mm[Hg]" "%" "Cel" "[lb_av]" "g" "K"
                              "2026-10-14T11:00:00Z" "2026-10-14T23:59:60Z" "2026-10-14" "" "T"
                              "+999999999-01-01T00:00:00Z" "0000-01-01T00:00:00Z"])
               (gen/vector gen/small-integer 0 3)
               (gen/map gen/keyword gen/small-integer {:max-elements 2})]))

(def ^:private gen-nearly-right-body
  "A body that could be a reading: a measurement, numbers around its plausible limits, a unit
  and a time that are usually fine and sometimes are not."
  (gen/bind
   (gen/elements (vec (keys measurement/measurements)))
   (fn [kind]
     (let [{stored :unit :keys [channels]} (get measurement/measurements kind)
           others (filter #(and (not= stored %) (units/compatible? % stored)) (keys units/definitions))]
       (gen/let [numbers (apply gen/tuple
                                (for [[_ {[lower upper] :plausible}] channels
                                      :let [margin (quot (- upper lower) 5)]]
                                  (gen/fmap (fn [[whole tenth]] (+ whole (/ tenth 10.0)))
                                            (gen/tuple (gen/choose (- lower margin) (+ upper margin))
                                                       (gen/choose 0 9)))))
                 unit (gen/frequency [[6 (gen/return nil)]
                                      [2 (gen/return stored)]
                                      [2 (if (seq others) (gen/elements others) (gen/return nil))]
                                      [1 (gen/elements ["kg" "nope"])]])
                 at (gen/frequency [[6 (gen/return nil)]
                                    [2 (gen/return (ago (Duration/ofHours 2)))]
                                    [1 (gen/return (ahead (Duration/ofHours 2)))]
                                    [1 (gen/return (ago (Duration/ofDays 40)))]])]
         (cond-> (into {"measurement" (name kind)}
                       (map (fn [[channel _] n] [(name channel) n]) channels numbers))
           unit (assoc "unit" unit)
           at (assoc "at" at)))))))

(def ^:private gen-mutation
  "A change to a body: none, a field gone, or a field set to whatever."
  (let [fields ["measurement" "value" "systolic" "diastolic" "unit" "at" "subject" "id" "status"]]
    (gen/frequency [[4 (gen/return identity)]
                    [1 (gen/fmap (fn [field] #(dissoc % field)) (gen/elements fields))]
                    [2 (gen/fmap (fn [[field v]] #(assoc % field v)) (gen/tuple (gen/elements fields) gen-junk))]])))

(def ^:private gen-body
  (gen/let [body gen-nearly-right-body
            mutations (gen/vector gen-mutation 0 2)]
    (reduce (fn [body mutate] (mutate body)) body mutations)))

(deftest whatever-is-sent-the-answer-is-a-recorded-reading-or-a-problem-and-never-a-failure
  (let [client (h/demo-cohort)]
    (with-api {:fhir client :clock clock}
      (fn [api]
        (let [token (h/token-for :ana)
              result (tc/quick-check
                      300
                      (prop/for-all [body gen-body]
                        (let [r (call api :post "/api/patients/pt-ana/observations" :token token
                                      :body (json/write-value-as-string body))]
                          (case (:status r)
                            201 (let [stored (fc/read-resource client "Observation" (get-in r [:json :id]))]
                                  (and (schema/valid? stored)
                                       (= "Patient/pt-ana" (get-in stored [:subject :reference]))
                                       (= "final" (:status stored))))
                            (400 422) (= "application/problem+json" (h/content-type r))
                            false))))]
          (is (:pass? result) (pr-str (select-keys result [:fail :shrunk :seed]))))))))

(deftest whatever-is-asked-the-answer-is-a-series-or-a-problem-and-never-a-failure
  (with-api {}
    (fn [api]
      (let [gen-query (gen/let [measurement (gen/elements ["heart-rate" "blood-pressure" "weight" "nope" ""])
                                from (gen/elements [nil "2026-10-01" "2026-10-01T08:00:00Z" "2026-13-01" "x" "1800-01-01" "2026-10-01T08:00:00+14:00"])
                                to (gen/elements [nil "2026-10-14" "2026-10-14T08:00:00Z" "2026-02-30" "" "2200-01-01"])
                                limit (gen/elements [nil "1" "5" "500" "0" "501" "x"])
                                order (gen/elements [nil "date" "-date" "value"])]
                        (str/join "&" (keep (fn [[k v]] (when v (str k "=" v)))
                                            [["measurement" measurement] ["from" from] ["to" to]
                                             ["limit" limit] ["sort" order]])))
            result (tc/quick-check
                    300
                    (prop/for-all [query gen-query]
                      (let [r (series api :rivera "pt-ana" query)]
                        (case (:status r)
                          200 (and (vector? (items r)) (boolean? (get-in r [:json :truncated])))
                          400 (= "application/problem+json" (h/content-type r))
                          false))))]
        (is (:pass? result) (pr-str (select-keys result [:fail :shrunk :seed])))))))
