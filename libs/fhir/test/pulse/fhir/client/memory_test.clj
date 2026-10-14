(ns pulse.fhir.client.memory-test
  (:require [clojure.test :refer [deftest is testing]]
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
