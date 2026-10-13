(ns seed
  "`bb seed` and `bb seed:validate`: load the demo cohort into a FHIR server and check it.

  Both reuse the library code of the apps (pulse.fhir.*) running in Babashka: the same
  generator, the same malli schemas and the same HTTP FhirClient."
  (:require [clojure.string :as str]
            [env]
            [pulse.fhir.bundle :as bundle]
            [pulse.fhir.client :as c]
            [pulse.fhir.client.http :as http]
            [pulse.fhir.outcome :as outcome]
            [pulse.fhir.schema :as schema]
            [pulse.fhir.synthetic :as synthetic]))

(def default-url "http://localhost:8090/fhir")

(defn- fhir-url [] (or (env/get-var "FHIR_BASE_URL") default-url))

(defn- fhir-client [] (http/client {:base-url (fhir-url)}))

(defn- generate
  "The cohort as resources, checked against the local schemas first so a bug in the
  generator fails here with a precise message instead of at the server."
  []
  (let [resources (synthetic/generate (synthetic/cohort) {})]
    (doseq [resource resources]
      (when-let [problems (schema/validate resource)]
        (throw (ex-info (str "Generated an invalid " (:resourceType resource) "/" (:id resource)
                             ": " (outcome/summary problems))
                        {:resource resource}))))
    resources))

(defn- server-count [client resource-type]
  (count (c/search client resource-type {:_count 200})))

(defn- report
  "Prints, per resource type, how many resources were generated and how many the server
  holds. Exits 1 when the server holds fewer than were generated."
  [client resources]
  (let [expected (synthetic/counts resources)
        rows (for [[resource-type n] (sort expected)]
               [resource-type n (server-count client resource-type)])]
    (println (format "%-20s %9s %9s" "Resource" "generated" "on server"))
    (doseq [[resource-type n on-server] rows]
      (println (format "%-20s %9d %9d%s" resource-type n on-server (if (< on-server n) "  <-- MISSING" ""))))
    (when (some (fn [[_ n on-server]] (< on-server n)) rows)
      (System/exit 1))))

(defn seed!
  "Creates or replaces the whole cohort. Idempotent: every resource has a fixed id and is
  written with PUT."
  []
  (let [client (fhir-client)
        resources (generate)
        transactions (bundle/transactions resources 150)]
    (println "Seeding" (count resources) "resources into" (fhir-url)
             "in" (count transactions) "transactions")
    (doseq [tx transactions]
      (c/transact! client tx)
      (print ".")
      (flush))
    (println)
    (report client resources)))

(defn- signature
  "What makes a resource different to the validator: its type and its main code or status."
  [resource]
  [(:resourceType resource)
   (or (some-> resource :code :coding first :code)
       (some-> resource :medicationCodeableConcept :coding first :code)
       (:status resource))])

(defn- representatives
  "One resource per signature (about 23 for the demo cohort). Every resource of a kind is
  produced by the same builder, so one of each exercises the same validation rules."
  [resources]
  (->> (group-by signature resources)
       (sort-by (comp pr-str key))
       (map (comp first val))))

(defn- truncate [s n] (let [s (str/replace (str s) #"\s+" " ")] (subs s 0 (min n (count s)))))

(defn validate!
  "Asks the server to `$validate` a representative sample of the generated resources, or
  every one with `--all`. Exits 1 on any error.

  The calls are sequential on purpose: HAPI builds a validator per request and, with the
  768 MB heap of this stack, ten concurrent validations ran it out of memory."
  [& args]
  (let [all? (some #{"--all"} args)
        client (fhir-client)
        resources (generate)
        targets (if all? resources (representatives resources))
        _ (println "Validating" (count targets) (if all? "resources" "representative resources")
                   "against" (fhir-url) "(sequentially) ...")
        results (doall (map-indexed (fn [i resource]
                                      (when (and (pos? i) (zero? (mod i 25))) (print ".") (flush))
                                      [resource (c/validate client resource)])
                                    targets))
        issues (mapcat (fn [[resource oc]]
                         (map #(assoc % :resource (str (:resourceType resource) "/" (:id resource)))
                              (:issue oc)))
                       results)
        errors (filter #(#{"error" "fatal"} (:severity %)) issues)
        others (remove #(#{"error" "fatal"} (:severity %)) issues)]
    (println)
    (println "Issues by severity:" (let [m (frequencies (map :severity issues))] (if (seq m) (pr-str m) "none")))
    (doseq [[message n] (take 5 (sort-by (comp - val) (frequencies (map #(str (:severity %) ": " (truncate (:diagnostics %) 110)) others))))]
      (println (format "  %4d x %s" n message)))
    (doseq [{:keys [resource diagnostics]} (take 10 errors)]
      (println " ERROR" resource "-" (truncate diagnostics 200)))
    (when (> (count errors) 10)
      (println " ... and" (- (count errors) 10) "more errors"))
    (if (seq errors)
      (System/exit 1)
      (println "OK: the server reports no errors."))))
