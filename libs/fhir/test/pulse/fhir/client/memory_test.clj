(ns pulse.fhir.client.memory-test
  (:require [clojure.test :refer [deftest is testing]]
            [pulse.fhir.alert :as alert]
            [pulse.fhir.bundle :as bundle]
            [pulse.fhir.client :as c]
            [pulse.fhir.client.http :as http]
            [pulse.fhir.client.memory :as mem]
            [pulse.fhir.observation :as obs]
            [pulse.fhir.resource :as r]))

(defn- heart-rate [i patient at value]
  (obs/quantity-observation {:id (str "o-" i) :patient patient :concept :heart-rate
                             :value value :unit "/min" :at at}))

(defn- ana-readings []
  (map-indexed (fn [i day] (heart-rate i "pt-ana" (str "2026-10-0" day "T08:00:00Z") (+ 60 i)))
               (range 1 8)))

(def ^:private clinic (r/organization {:id "org-1" :name "Clinic"}))

(deftest read-write-delete
  (let [client (mem/memory-client)
        org clinic]
    (is (nil? (c/read-resource client "Organization" "org-1")))
    (is (= org (c/put! client org)))
    (is (= org (c/read-resource client "Organization" "org-1")))
    (is (nil? (c/delete! client "Organization" "org-1")))
    (is (nil? (c/read-resource client "Organization" "org-1")))))

(deftest invalid-resources-are-rejected-with-the-outcome
  (let [client (mem/memory-client)
        e (try (c/put! client (assoc clinic :name 42))
               (catch clojure.lang.ExceptionInfo e e))]
    (is (= ::c/fhir-error (:type (ex-data e))))
    (is (= 422 (:status (ex-data e))))
    (is (= "OperationOutcome" (-> e ex-data :outcome :resourceType)))
    (is (nil? (c/read-resource client "Organization" "org-1")) "nothing was stored")))

(deftest search-filters
  (let [client (mem/memory-client (concat (ana-readings)
                                          [(heart-rate 99 "pt-liam" "2026-10-01T08:00:00Z" 70)]))
        ids (fn [params] (set (map :id (c/search client "Observation" params))))]
    (testing "by patient, as a full reference or a bare id"
      (is (= 7 (count (ids {:patient "Patient/pt-ana"}))))
      (is (= 7 (count (ids {:patient "pt-ana"}))))
      (is (= #{"o-99"} (ids {:patient "pt-liam"}))))
    (testing "by several patients at once: a comma is OR, as in FHIR"
      (is (= 8 (count (ids {:patient "Patient/pt-ana,Patient/pt-liam"}))))
      (is (= #{"o-99"} (ids {:patient "Patient/pt-liam,Patient/pt-nobody"})))
      (is (= 7 (count (ids {:patient "Patient/pt-nobody,Patient/pt-ana"}))))
      (is (empty? (ids {:patient "Patient/pt-nobody,Patient/pt-ghost"}))))
    (testing "by code, with and without the system"
      (is (= 8 (count (ids {:code "http://loinc.org|8867-4"}))))
      (is (= 8 (count (ids {:code "8867-4"}))))
      (is (empty? (ids {:code "8480-6"}))))
    (testing "by date with prefixes, and several parameters are ANDed"
      (is (= #{"o-4" "o-5" "o-6"} (ids {:patient "pt-ana" :date "ge2026-10-05T00:00:00Z"})))
      (is (= #{"o-0" "o-1"} (ids {:patient "pt-ana" :date "le2026-10-02T08:00:00Z"})))
      (is (= #{"o-2" "o-3"} (ids {:patient "pt-ana" :date ["gt2026-10-02T08:00:00Z" "lt2026-10-05T08:00:00Z"]}))))
    (testing "unsupported parameters are an error, like on a real server"
      (is (thrown? clojure.lang.ExceptionInfo (doall (c/search client "Observation" {:nope "x"})))))))

(deftest string-search-ignores-case-and-accents
  ;; As HAPI does: someone who types "Garcia" must find "García".
  (let [client (mem/memory-client [(r/patient {:id "pt-ana" :given "Ana" :family "García" :gender "female"
                                               :birth-date "1968-03-14"})
                                   (r/patient {:id "pt-liam" :given "Liam" :family "Chen" :gender "male"
                                               :birth-date "1980-07-22"})])
        ids (fn [family] (set (map :id (c/search client "Patient" {:family family}))))]
    (is (= #{"pt-ana"} (ids "Garcia")))
    (is (= #{"pt-ana"} (ids "garcía")))
    (is (= #{"pt-ana"} (ids "GARC")))
    (is (= #{"pt-liam"} (ids "ch")))
    (is (= #{} (ids "arcia")) "a prefix, not a substring")))

(deftest search-parameters-are-the-ones-r4-defines
  ;; Flag has no `status` search parameter in R4, and HAPI answers HAPI-0524 when asked for one.
  (let [client (mem/memory-client [(r/flag {:id "f-1" :patient "pt-ana" :text "BP" :start "2026-10-10T08:00:00Z"})
                                   (r/flag {:id "f-2" :patient "pt-ana" :text "BP again" :start "2026-10-13T08:00:00Z"})])]
    (is (thrown? clojure.lang.ExceptionInfo (doall (c/search client "Flag" {:patient "pt-ana" :status "active"}))))
    (testing "what R4 does give Flag: the patient, and the date of the period to sort by"
      (is (= ["f-2" "f-1"] (mapv :id (c/search client "Flag" {:patient "pt-ana" :_sort "-date"}))))
      (is (= ["f-1" "f-2"] (mapv :id (c/search client "Flag" {:patient "pt-ana" :_sort "date"})))))))

(deftest search-sorting
  (let [client (mem/memory-client (ana-readings))
        dates (fn [sort] (mapv :effectiveDateTime (c/search client "Observation" {:_sort sort})))]
    (is (= (sort (dates "date")) (dates "date")))
    (is (= (reverse (dates "date")) (dates "-date")))))

(deftest search-follows-next-links-page-by-page
  (let [client (mem/memory-client (ana-readings) {:page-size 3})]
    (testing "all results come back, in order, across several pages"
      (is (= 7 (count (c/search client "Observation" {:_sort "date"}))))
      (is (= (map :id (c/search client "Observation" {:_sort "date" :_count 100}))
             (map :id (c/search client "Observation" {:_sort "date" :_count 2})))
          "the page size does not change the result"))
    (testing "find-one"
      (is (= "o-6" (:id (c/find-one client "Observation" {:_sort "-date"}))))
      (is (nil? (c/find-one client "Observation" {:patient "pt-nobody"}))))))

(deftest paged-is-lazy
  (let [pages {"p0" (bundle/searchset [{:id 1} {:id 2}] {:next-url "p1"})
               "p1" (bundle/searchset [{:id 3}] {:next-url "p2"})
               "p2" (bundle/searchset [{:id 4}] {})}
        fetched (atom [])
        fetch (fn [url] (swap! fetched conj url) (pages url))
        results (c/paged fetch "p0")]
    (is (= [] @fetched) "nothing is fetched until the sequence is consumed")
    (is (= {:id 1} (first results)))
    (is (= ["p0"] @fetched) "taking the first element fetches one page only")
    (is (= [1 2 3 4] (map :id results)))
    (is (= ["p0" "p1" "p2"] @fetched) "the remaining pages are fetched in order, once")))

(deftest transactions
  (let [client (mem/memory-client)
        tx (bundle/transaction [clinic (r/organization {:id "org-2" :name "Other"})])]
    (testing "first execution creates, second replaces"
      (is (= ["201 Created" "201 Created"]
             (bundle/response-statuses (c/transact! client tx))))
      (is (= ["200 OK" "200 OK"]
             (bundle/response-statuses (c/transact! client tx)))))
    (testing "a failing entry rolls back the whole transaction"
      (let [bad (bundle/transaction [(r/organization {:id "org-3" :name "Third"})
                                     (assoc clinic :id "org-4" :name 42)])]
        (is (thrown? clojure.lang.ExceptionInfo (c/transact! client bad)))
        (is (nil? (c/read-resource client "Organization" "org-3")) "org-3 was not stored")))))

;; ---------------------------------------------------------------------------
;; Conditional create (what the rules engine does to raise an alert once)

(def ^:private alert-system "https://pulse.example/identifier/alert")

(defn- an-alert [key & {:as overrides}]
  (merge (r/flag {:id "unused" :patient "pt-ana" :text "BP stage 2" :start "2026-10-13T08:00:00Z"
                  :identifier {:system alert-system :value key}})
         overrides))

(defn- raise!
  "A transaction that raises `flag` unless there is one with its identifier: the response entry."
  [client flag key]
  (-> (c/transact! client (bundle/transaction-of [(bundle/conditional-create-entry flag (str "identifier=" alert-system "|" key))]))
      :entry first :response))

(defn- flags [client] (vec (c/search client "Flag" {:patient "pt-ana"})))

(deftest a-conditional-create-creates-once
  (let [client (mem/memory-client)
        key "R1-pt-ana-2026-10-13T08:00:00Z"
        first-time (raise! client (an-alert key) key)
        again (raise! client (an-alert key) key)]
    (is (= "201 Created" (:status first-time)))
    (is (= "200 OK" (:status again)))
    (is (= 1 (count (flags client))) "one Flag, however many times it was raised")
    (testing "the second answer points at the Flag the first one made"
      (is (= (bundle/location-id (:location first-time)) (bundle/location-id (:location again))))
      (is (= (second (bundle/location-id (:location first-time))) (:id (first (flags client))))))
    (testing "in the form HAPI gives, with the version"
      (is (= (str "Flag/" (:id (first (flags client))) "/_history/1") (:location first-time)))
      (is (= (:location first-time) (:location again))))
    (testing "the server named it, whatever the resource said"
      (is (not= "unused" (:id (first (flags client))))))))

(deftest a-conditional-create-does-not-write-over-what-is-there
  ;; the point of not using a PUT: an alert that was acknowledged stays acknowledged
  (let [client (mem/memory-client)
        key "R1-pt-ana-2026-10-13T08:00:00Z"]
    (raise! client (an-alert key) key)
    (let [created (first (flags client))
          acknowledged (:ok (alert/acknowledge created "2026-10-14T09:00:00Z"))]
      (c/put! client acknowledged)
      (is (= "200 OK" (:status (raise! client (an-alert key) key))))
      (is (= [acknowledged] (flags client)) "nothing changed, and it is still inactive")
      (is (= "inactive" (:status (first (flags client))))))))

(deftest a-conditional-create-looks-at-the-condition-and-the-type-only
  (let [client (mem/memory-client [(r/organization {:id "org-1" :name "Clinic"})])
        key "R1-pt-ana-2026-10-13T08:00:00Z"]
    (raise! client (an-alert key) key)
    (testing "another identifier is another alert"
      (is (= "201 Created" (:status (raise! client (an-alert "R1-pt-ana-2026-10-13T20:00:00Z") "R1-pt-ana-2026-10-13T20:00:00Z"))))
      (is (= 2 (count (flags client)))))
    (testing "the same value in another system is not a match: the condition names the system"
      (let [other (an-alert key :identifier [{:system "https://other.example/id" :value key}])
            response (-> (c/transact! client (bundle/transaction-of
                                              [(bundle/conditional-create-entry
                                                other (str "identifier=https://other.example/id|" key))]))
                         :entry first :response)]
        (is (= "201 Created" (:status response)))))
    (testing "a resource of another type with the same value is no match"
      (is (= 1 (count (c/search client "Organization" {})))))))

(deftest a-conditional-create-is-checked-like-any-other-write
  (let [client (mem/memory-client)
        key "R1-pt-ana-2026-10-13T08:00:00Z"
        e (try (raise! client (assoc (an-alert key) :status "sleeping") key)
               (catch clojure.lang.ExceptionInfo e e))]
    (is (= 422 (:status (ex-data e))))
    (is (empty? (flags client)) "nothing invalid is stored")))

(deftest a-condition-that-matches-several-is-an-error-and-nothing-is-created
  (let [key "R1-pt-ana-2026-10-13T08:00:00Z"
        client (mem/memory-client [(assoc (an-alert key) :id "f-1") (assoc (an-alert key) :id "f-2")])
        e (try (raise! client (an-alert key) key)
               (catch clojure.lang.ExceptionInfo e e))]
    (is (= 412 (:status (ex-data e))))
    (is (= ["f-1" "f-2"] (mapv :id (flags client))))))

(deftest a-transaction-with-a-conditional-create-is-still-atomic
  (let [client (mem/memory-client)
        key "R1-pt-ana-2026-10-13T08:00:00Z"
        tx (bundle/transaction-of [(bundle/conditional-create-entry (an-alert key) (str "identifier=" alert-system "|" key))
                                   (bundle/put-entry (assoc clinic :id "org-9" :name 42))])]
    (is (thrown? clojure.lang.ExceptionInfo (c/transact! client tx)))
    (is (empty? (flags client)) "the create was rolled back with the rest")))

;; ---------------------------------------------------------------------------
;; create-once!: the conditional create as a function (alerts and reminders)

(def ^:private reminder-system "https://pulse.example/identifier/reminder")

(defn- a-reminder [key & {:as overrides}]
  (merge (r/communication {:id "unused" :patient "pt-ana" :appointment "appt-ana-1"
                           :sent "2026-10-13T08:00:00Z" :text "Reminder: your appointment is tomorrow."
                           :identifier {:system reminder-system :value key}})
         overrides))

(defn- communications [client] (vec (c/search client "Communication" {:patient "pt-ana"})))

(deftest create-once-creates-a-resource-and-then-finds-it
  (let [client (mem/memory-client)
        key "appt-ana-1/2026-10-14T04:00:00Z"
        created (c/create-once! client (a-reminder key))
        again (c/create-once! client (a-reminder key))]
    (is (= :created (:outcome created)))
    (is (= :existing (:outcome again)))
    (is (string? (:id created)))
    (is (= (:id created) (:id again)) "the second answer is the resource of the first")
    (is (= [(:id created)] (mapv :id (communications client))) "one, however many times it was asked")
    (testing "found by its identifier, which has a system"
      (is (= [(:id created)] (mapv :id (c/search client "Communication" {:identifier (str reminder-system "|" key)}))))
      (is (empty? (c/search client "Communication" {:identifier (str "https://other.example/id|" key)})))
      (is (empty? (c/search client "Communication" {:identifier (str reminder-system "|appt-ana-1/2026-10-14T05:00:00Z")}))))))

(deftest create-once-goes-by-the-identifier-and-by-nothing-else
  (let [client (mem/memory-client)
        first-key "appt-ana-1/2026-10-14T04:00:00Z"
        second-key "appt-ana-1/2026-10-14T06:00:00Z"]
    (c/create-once! client (a-reminder first-key))
    (testing "another identifier is another resource, whatever else the two have in common"
      (is (= :created (:outcome (c/create-once! client (a-reminder second-key)))))
      (is (= 2 (count (communications client)))))
    (testing "the same identifier is the same resource, whatever else differs"
      (is (= :existing (:outcome (c/create-once! client (a-reminder first-key :sent "2026-10-13T09:00:00Z"
                                                                    :payload [{:contentString "Another text"}])))))
      (is (= 2 (count (communications client)))))
    (testing "and the same value in another system is not the same"
      (let [other (a-reminder first-key :identifier [{:system "https://other.example/id" :value first-key}])]
        (is (= :created (:outcome (c/create-once! client other))))
        (is (= 3 (count (communications client))))))))

(deftest create-once-needs-an-identifier-to-go-by
  (let [client (mem/memory-client)]
    (doseq [[label resource] {"none" (dissoc (a-reminder "k") :identifier)
                              "no system" (assoc (a-reminder "k") :identifier [{:value "k"}])
                              "an empty system" (assoc (a-reminder "k") :identifier [{:system "" :value "k"}])
                              "an empty value" (assoc (a-reminder "k") :identifier [{:system reminder-system :value ""}])}
            :let [e (try (c/create-once! client resource) (catch clojure.lang.ExceptionInfo e e))]]
      (testing label
        (is (= ::c/no-identifier (:type (ex-data e))))
        (is (= "Communication" (:resource-type (ex-data e))) "and says what it was asked to create")))
    (is (empty? (communications client)) "without it, every ask would have made another one")))

(deftest create-once-leaves-what-is-there-as-it-is
  (let [client (mem/memory-client)
        key "appt-ana-1/2026-10-14T04:00:00Z"
        {:keys [id]} (c/create-once! client (a-reminder key))
        changed (assoc (c/read-resource client "Communication" id) :status "entered-in-error")]
    (c/put! client changed)
    (is (= {:outcome :existing :id id} (c/create-once! client (a-reminder key))))
    (is (= [changed] (communications client)) "not written over, whatever has become of it")))

(deftest create-once-reports-what-the-server-says-when-it-cannot-decide-or-create
  (testing "an identifier that two resources have"
    (let [key "appt-ana-1/2026-10-14T04:00:00Z"
          client (mem/memory-client [(assoc (a-reminder key) :id "co-1") (assoc (a-reminder key) :id "co-2")])
          e (try (c/create-once! client (a-reminder key)) (catch clojure.lang.ExceptionInfo e e))]
      (is (= 412 (:status (ex-data e))))
      (is (= ["co-1" "co-2"] (mapv :id (communications client))))))
  (testing "a resource that is not valid"
    (let [client (mem/memory-client)
          e (try (c/create-once! client (a-reminder "k" :status "sleeping")) (catch clojure.lang.ExceptionInfo e e))]
      (is (= 422 (:status (ex-data e))))
      (is (empty? (communications client))))))

(deftest server-side-validation
  (let [client (mem/memory-client)]
    (is (not-any? #{"error"} (map :severity (:issue (c/validate client clinic)))))
    (is (some #{"error"} (map :severity (:issue (c/validate client (assoc clinic :name 42))))))))

(deftest http-helpers
  (testing "query strings encode values and repeat sequential ones"
    (is (= "patient=Patient%2Fpt-ana&date=ge2026-10-01T00%3A00%3A00%2B02%3A00&date=lt2026-11-01"
           (c/query-string [[:patient "Patient/pt-ana"]
                            [:date ["ge2026-10-01T00:00:00+02:00" "lt2026-11-01"]]]))))
  (testing "next links are re-pointed at the address we actually use"
    (is (= "http://localhost:8090/fhir?_getpages=abc&_count=50"
           (#'http/rebase "http://localhost:8090/fhir" "http://hapi.internal:8080/fhir?_getpages=abc&_count=50")))))
