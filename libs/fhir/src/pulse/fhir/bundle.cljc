(ns pulse.fhir.bundle
  "Building and reading FHIR Bundles."
  (:require [clojure.string :as str]))

(defn put-entry
  "A transaction entry that creates or replaces `resource` at its own type and id."
  [{:keys [resourceType id] :as resource}]
  {:resource resource
   :request {:method "PUT" :url (str resourceType "/" id)}})

(defn transaction
  "A transaction Bundle that PUTs every resource: idempotent, because ids are chosen by us."
  [resources]
  {:resourceType "Bundle"
   :type "transaction"
   :entry (mapv put-entry resources)})

(defn transactions
  "Splits `resources` (in dependency order) into transaction Bundles of at most `n` entries."
  [resources n]
  (map transaction (partition-all n resources)))

(defn searchset
  "A searchset Bundle of `resources`; `total` and `next-url` are optional."
  [resources {:keys [total next-url]}]
  (cond-> {:resourceType "Bundle"
           :type "searchset"
           :entry (mapv (fn [r] {:resource r}) resources)}
    total (assoc :total total)
    next-url (assoc :link [{:relation "next" :url next-url}])))

(defn resources
  "The resources contained in the entries of `bundle`."
  [bundle]
  (keep :resource (:entry bundle)))

(defn next-url
  "The URL of the next page of a searchset Bundle, or nil on the last page."
  [bundle]
  (some (fn [{:keys [relation url]}] (when (= "next" relation) url))
        (:link bundle)))

(defn response-statuses
  "The HTTP status strings of the entries of a transaction-response Bundle."
  [bundle]
  (keep (comp :status :response) (:entry bundle)))

(defn all-succeeded?
  "True when every entry of a transaction-response Bundle has a 2xx status."
  [bundle]
  (let [statuses (response-statuses bundle)]
    (and (seq statuses)
         (every? #(str/starts-with? % "2") statuses))))
