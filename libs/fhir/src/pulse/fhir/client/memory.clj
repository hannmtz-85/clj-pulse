(ns pulse.fhir.client.memory
  "An in-memory FhirClient: the test double for everything that talks to FHIR, and the
  backend of the \"lite\" profile (the `api` without HAPI, for machines with little RAM).

  It is a small FHIR server, not a stub: it stores resources by type and id, validates the
  ones it knows with the malli schemas, executes transactions atomically, and answers
  searches with filters, sorting and `next`-link paging, so code written against it
  exercises the same paths as against HAPI. Only the search parameters listed in
  `search-params` are supported; any other is rejected, like a real server would."
  (:require [clojure.string :as str]
            [pulse.fhir.bundle :as bundle]
            [pulse.fhir.client :as c]
            [pulse.fhir.outcome :as outcome]
            [pulse.fhir.schema :as schema]
            [pulse.fhir.time :as time])
  (:import (java.net URLDecoder)
           (java.text Normalizer Normalizer$Form)))

;; ---------------------------------------------------------------------------
;; Search parameters: type -> name -> [kind path]

(def search-params
  {"Patient" {"_id" [:id [:id]]
              "family" [:string [:name :family]]
              "general-practitioner" [:reference [:generalPractitioner]]
              "identifier" [:identifier [:identifier]]}
   "Practitioner" {"_id" [:id [:id]]}
   "Organization" {"_id" [:id [:id]]}
   "Observation" {"_id" [:id [:id]]
                  "patient" [:reference [:subject]]
                  "subject" [:reference [:subject]]
                  "code" [:token [:code]]
                  "date" [:date [:effectiveDateTime]]
                  "status" [:code [:status]]}
   "Condition" {"patient" [:reference [:subject]]
                "subject" [:reference [:subject]]
                "code" [:token [:code]]
                "clinical-status" [:token [:clinicalStatus]]}
   "MedicationRequest" {"patient" [:reference [:subject]]
                        "subject" [:reference [:subject]]
                        "status" [:code [:status]]}
   "AllergyIntolerance" {"patient" [:reference [:patient]]}
   "Appointment" {"_id" [:id [:id]]
                  "patient" [:reference [:participant :actor]]
                  "actor" [:reference [:participant :actor]]
                  "status" [:code [:status]]
                  "date" [:date [:start]]}
   ;; R4 gives Flag no `status` search parameter (HAPI answers HAPI-0524): an alert is
   ;; filtered on its status after the search
   "Flag" {"_id" [:id [:id]]
           "patient" [:reference [:subject]]
           "subject" [:reference [:subject]]
           "date" [:date [:period :start]]
           "identifier" [:identifier [:identifier]]}
   "Communication" {"patient" [:reference [:subject]]
                    "subject" [:reference [:subject]]
                    "status" [:code [:status]]}})

(defn- values-at
  "Every value reachable from `x` by following `path`, flattening vectors along the way."
  [x path]
  (reduce (fn [xs k]
            (mapcat (fn [x] (let [v (get x k)]
                              (cond (sequential? v) v
                                    (some? v) [v])))
                    xs))
          [x]
          path))

(defmulti ^:private matches?
  "Does any of `values` match the search value `v`? Dispatches on the parameter kind."
  (fn [kind _values _v] kind))

(defmethod matches? :id [_ values v] (boolean (some #(= v %) values)))

(defmethod matches? :code [_ values v]
  (let [wanted (set (str/split v #","))]
    (boolean (some wanted values))))

(defn- fold
  "Lower case, without accents: how a FHIR server compares strings in a search, HAPI included
  (\"Garcia\" finds \"García\")."
  [^String s]
  (-> (Normalizer/normalize (str/lower-case s) Normalizer$Form/NFD)
      (str/replace #"\p{M}+" "")))

(defmethod matches? :string [_ values v]
  (let [wanted (fold v)]
    (boolean (some #(str/starts-with? (fold %) wanted) values))))

(defmethod matches? :reference [_ values v]
  ;; A comma is OR, as in FHIR: patient=Patient/a,Patient/b finds the resources of either.
  (let [wanted (str/split v #",")]
    (boolean (some (fn [{:keys [reference]}]
                     (some (fn [w] (or (= reference w) (str/ends-with? (str reference) (str "/" w)))) wanted))
                   values))))

(defn- system+value [v]
  (if (str/includes? v "|")
    (str/split v #"\|" 2)
    [nil v]))

(defmethod matches? :token [_ values v]
  (let [[system code] (system+value v)]
    (boolean (some (fn [cc]
                     (some (fn [c] (and (= code (:code c))
                                        (or (str/blank? system) (= system (:system c)))))
                           (:coding cc)))
                   values))))

(defmethod matches? :identifier [_ values v]
  (let [[system value] (system+value v)]
    (boolean (some (fn [i] (and (= value (:value i))
                                (or (str/blank? system) (= system (:system i)))))
                   values))))

(defmethod matches? :date [_ values v]
  (let [[_ prefix instant] (re-matches #"(eq|ne|gt|lt|ge|le)?(.+)" v)
        wanted (time/->millis instant)
        compare-with (case prefix
                       "gt" >, "lt" <, "ge" >=, "le" <=, "ne" not=, =)]
    (boolean (some #(compare-with (time/->millis %) wanted) values))))

(defn- matches-param? [resource-type resource [k v]]
  (if-let [[kind path] (get-in search-params [resource-type k])]
    (matches? kind (values-at resource path) v)
    (throw (c/fhir-error (str "Unsupported search parameter " k " for " resource-type) 400 nil))))

;; ---------------------------------------------------------------------------
;; Search

(defn- decode [^String s] (URLDecoder/decode s "UTF-8"))

(defn- parse-url
  "{:type \"Observation\" :pairs [[\"patient\" \"pt-ana\"] ...]} from a relative search URL."
  [url]
  (let [[path query] (str/split url #"\?" 2)]
    {:type path
     :pairs (->> (str/split (or query "") #"&")
                 (remove str/blank?)
                 (map #(str/split % #"=" 2))
                 (mapv (fn [[k v]] [(decode k) (decode (or v ""))])))}))

(def ^:private control-params #{"_count" "_offset" "_sort" "_summary"})

(defn- sort-resources
  "Sorts by the date parameter named in `_sort` (\"date\" ascending, \"-date\" descending),
  otherwise by id so the order is stable."
  [resource-type sort-spec resources]
  (if-let [path (some-> (get-in search-params [resource-type "date"]) second)]
    (case sort-spec
      "date" (sort-by #(time/->millis (first (values-at % path))) resources)
      "-date" (sort-by #(- (time/->millis (first (values-at % path)))) resources)
      (sort-by :id resources))
    (sort-by :id resources)))

(defn- matching-resources
  "The resources of `resource-type` in `state` that satisfy every one of `filters`, pairs of a
  search parameter and its value."
  [state resource-type filters]
  (->> state
       (keep (fn [[[t _] r]] (when (= t resource-type) r)))
       (filter (fn [r] (every? #(matches-param? resource-type r %) filters)))))

(defn- search-page
  "The searchset Bundle found at relative `url`."
  [state default-page-size url]
  (let [{resource-type :type pairs :pairs} (parse-url url)
        control (into {} (filter (comp control-params first)) pairs)
        filters (remove (comp control-params first) pairs)
        matching (->> (matching-resources @state resource-type filters)
                      (sort-resources resource-type (get control "_sort")))
        total (count matching)]
    (if (= "count" (get control "_summary"))
      {:resourceType "Bundle" :type "searchset" :total total}
      (let [size (min 200 (or (some-> (get control "_count") parse-long) default-page-size))
            offset (or (some-> (get control "_offset") parse-long) 0)
            next-offset (+ offset size)]
        (bundle/searchset
         (take size (drop offset matching))
         {:total total
          :next-url (when (< next-offset total)
                      (str resource-type "?"
                           (c/query-string (concat filters
                                                   (when-let [s (get control "_sort")] [["_sort" s]])
                                                   [["_count" size] ["_offset" next-offset]]))))})))))

;; ---------------------------------------------------------------------------
;; Writes

(defn- check-valid! [resource]
  (when (contains? schema/resource-schemas (:resourceType resource))
    (when-let [problems (schema/validate resource)]
      (throw (c/fhir-error (str "Invalid " (:resourceType resource)) 422 problems)))))

(defn- store
  "Returns [new-state http-status] after putting `resource`."
  [state resource]
  (let [k [(:resourceType resource) (:id resource)]]
    [(assoc state k resource) (if (contains? state k) "200 OK" "201 Created")]))

(defn- location [resource]
  (str (:resourceType resource) "/" (:id resource) "/_history/1"))

(defn- create
  "Returns [new-state response-entry] after creating `resource` under a new id."
  [state resource]
  (let [resource (assoc resource :id (str (random-uuid)))]
    (check-valid! resource)
    [(first (store state resource))
     {:response {:status "201 Created" :location (location resource)}}]))

(defn- create-unless-matching
  "A conditional create, as HAPI does it: a resource that matches `if-none-exist` (a search query
  string) is left as it is and answered with a 200 and its location, whatever its status, and
  none matching is a create. More than one match is a 412, since the condition does not say which."
  [state resource if-none-exist]
  (let [{:keys [pairs]} (parse-url (str (:resourceType resource) "?" if-none-exist))
        found (vec (matching-resources state (:resourceType resource) pairs))]
    (case (count found)
      0 (create state resource)
      1 [state {:response {:status "200 OK" :location (location (first found))}}]
      (throw (c/fhir-error (str "Multiple resources match the condition of a create of "
                                (:resourceType resource))
                           412 nil)))))

(defn- apply-entry [[state responses] {:keys [resource request]}]
  (case (:method request)
    "PUT" (do (check-valid! resource)
              (let [[state' status] (store state resource)]
                [state' (conj responses {:response {:status status}})]))
    "POST" (let [[state' response] (if-let [if-none-exist (:ifNoneExist request)]
                                     (create-unless-matching state resource if-none-exist)
                                     (create state resource))]
             [state' (conj responses response)])
    "DELETE" (let [[type id] (str/split (:url request) #"/" 2)]
               [(dissoc state [type id]) (conj responses {:response {:status "200 OK"}})])
    (throw (c/fhir-error (str "Unsupported method " (:method request)) 400 nil))))

(defrecord MemoryClient [state page-size]
  c/FhirClient
  (read-resource [_ resource-type id]
    (get @state [resource-type id]))

  (search [_ resource-type params]
    (c/paged #(search-page state page-size %)
             (str resource-type "?" (c/query-string params))))

  (put! [_ resource]
    (check-valid! resource)
    (swap! state (fn [s] (first (store s resource))))
    resource)

  (delete! [_ resource-type id]
    (swap! state dissoc [resource-type id])
    nil)

  (transact! [_ tx]
    ;; Atomic: entries are applied to a copy and the state is replaced only if all succeed.
    (let [[state' responses] (reduce apply-entry [@state []] (:entry tx))]
      (reset! state state')
      {:resourceType "Bundle" :type "transaction-response" :entry responses}))

  (validate [_ resource]
    (or (schema/validate resource)
        (outcome/operation-outcome [(outcome/issue "information" "informational" "All OK")]))))

(defn memory-client
  "A fresh in-memory server, optionally pre-loaded with `resources`. Options:
  :page-size (default 50) is the search page size when a request gives no _count."
  ([] (memory-client [] {}))
  ([resources] (memory-client resources {}))
  ([resources {:keys [page-size] :or {page-size 50}}]
   (->MemoryClient (atom (into {} (map (fn [r] [[(:resourceType r) (:id r)] r])) resources))
                   page-size)))

(defn contents
  "Every stored resource, for assertions in tests."
  [client]
  (vals @(:state client)))
