(ns pulse.fhir.schema
  "malli schemas for the subset of FHIR R4 (4.0.1) that Pulse reads and writes.

  The schemas are open maps: elements Pulse does not model are allowed, because the server
  (HAPI) is the authority on full conformance (see `bb seed:validate`, which calls
  `$validate`). What these schemas guarantee is that everything Pulse produces or accepts
  has the shape the rest of the code relies on. They are portable (.cljc) so the SPA can
  validate forms with the same definitions."
  (:require [malli.core :as m]
            [pulse.fhir.outcome :as outcome]))

;; Regular expressions of the FHIR primitive types (from the specification). malli's :re
;; uses `re-find`, so every pattern is anchored: otherwise "14/03/1968" would match on "1968".
(def ^:private re-id #"^[A-Za-z0-9\-.]{1,64}$")
(def ^:private re-date
  #"^([0-9]([0-9]([0-9][1-9]|[1-9]0)|[1-9]00)|[1-9]000)(-(0[1-9]|1[0-2])(-(0[1-9]|[1-2][0-9]|3[0-1]))?)?$")
(def ^:private re-date-time
  #"^([0-9]([0-9]([0-9][1-9]|[1-9]0)|[1-9]00)|[1-9]000)(-(0[1-9]|1[0-2])(-(0[1-9]|[1-2][0-9]|3[0-1])(T([01][0-9]|2[0-3]):[0-5][0-9]:([0-5][0-9]|60)(\.[0-9]{1,9})?)?)?(Z|(\+|-)((0[0-9]|1[0-3]):[0-5][0-9]|14:00))?)?$")
(def ^:private re-instant
  #"^([0-9]([0-9]([0-9][1-9]|[1-9]0)|[1-9]00)|[1-9]000)-(0[1-9]|1[0-2])-(0[1-9]|[1-2][0-9]|3[0-1])T([01][0-9]|2[0-3]):[0-5][0-9]:([0-5][0-9]|60)(\.[0-9]{1,9})?(Z|(\+|-)((0[0-9]|1[0-3]):[0-5][0-9]|14:00))$")
(def ^:private re-relative-reference #"^[A-Z][A-Za-z]+/[A-Za-z0-9\-.]{1,64}$")

(defn- opt [k schema] [k {:optional true} schema])

(def registry
  "Reusable FHIR datatypes, referenced from the resource schemas by qualified keyword."
  (merge
   (m/default-schemas)
   {::id [:re re-id]
    ::date [:re re-date]
    ::date-time [:re re-date-time]
    ::instant [:re re-instant]
    ::coding [:map (opt :system :string) (opt :code :string) (opt :display :string)]
    ::codeable-concept [:map (opt :coding [:vector ::coding]) (opt :text :string)]
    ::reference [:map [:reference [:re re-relative-reference]] (opt :display :string)]
    ::quantity [:map [:value number?] (opt :unit :string) (opt :system :string) (opt :code :string)]
    ::period [:map (opt :start ::date-time) (opt :end ::date-time)]
    ::human-name [:map (opt :use :string) (opt :family :string) (opt :given [:vector :string])
                  (opt :prefix [:vector :string])]
    ::identifier [:map (opt :system :string) [:value :string]]
    ::contact-point [:map (opt :system [:enum "phone" "fax" "email" "pager" "url" "sms" "other"])
                     [:value :string] (opt :use :string)]
    ::address [:map (opt :city :string) (opt :country :string)]}))

(def ^:private observation-statuses
  [:enum "registered" "preliminary" "final" "amended" "corrected" "cancelled" "entered-in-error" "unknown"])

(def ^:private participation-statuses
  [:enum "accepted" "declined" "tentative" "needs-action"])

(defn- value-or-components? [obs]
  (or (some #(contains? obs %) [:valueQuantity :valueString :valueBoolean :valueInteger
                                :valueCodeableConcept :valueDateTime :dataAbsentReason])
      (seq (:component obs))))

(def resource-schemas
  "resourceType -> schema."
  {"Organization" [:map [:resourceType [:= "Organization"]] (opt :id ::id) (opt :active :boolean)
                   [:name :string]]

   "Practitioner" [:map [:resourceType [:= "Practitioner"]] (opt :id ::id) (opt :active :boolean)
                   [:name [:vector {:min 1} ::human-name]]
                   (opt :gender [:enum "male" "female" "other" "unknown"])
                   (opt :telecom [:vector ::contact-point])]

   "Patient" [:map [:resourceType [:= "Patient"]] (opt :id ::id) (opt :active :boolean)
              (opt :identifier [:vector ::identifier])
              [:name [:vector {:min 1} ::human-name]]
              [:gender [:enum "male" "female" "other" "unknown"]]
              [:birthDate ::date]
              (opt :telecom [:vector ::contact-point])
              (opt :address [:vector ::address])
              (opt :generalPractitioner [:vector ::reference])
              (opt :managingOrganization ::reference)]

   "Observation" [:and
                  [:map [:resourceType [:= "Observation"]] (opt :id ::id)
                   [:status observation-statuses]
                   (opt :category [:vector ::codeable-concept])
                   [:code ::codeable-concept]
                   [:subject ::reference]
                   [:effectiveDateTime ::date-time]
                   (opt :performer [:vector ::reference])
                   (opt :valueQuantity ::quantity)
                   (opt :component [:vector [:map [:code ::codeable-concept] (opt :valueQuantity ::quantity)]])]
                  [:fn {:error/message "must have a value[x] or components"} value-or-components?]]

   "Condition" [:map [:resourceType [:= "Condition"]] (opt :id ::id)
                [:clinicalStatus ::codeable-concept]
                [:verificationStatus ::codeable-concept]
                (opt :category [:vector ::codeable-concept])
                [:code ::codeable-concept]
                [:subject ::reference]
                (opt :onsetDateTime ::date-time)]

   "MedicationRequest" [:map [:resourceType [:= "MedicationRequest"]] (opt :id ::id)
                        [:status [:enum "active" "on-hold" "cancelled" "completed" "entered-in-error" "stopped" "draft" "unknown"]]
                        [:intent [:enum "proposal" "plan" "order" "original-order" "reflex-order" "filler-order" "instance-order" "option"]]
                        [:medicationCodeableConcept ::codeable-concept]
                        [:subject ::reference]
                        (opt :requester ::reference)
                        (opt :authoredOn ::date-time)
                        (opt :dosageInstruction [:vector [:map (opt :text :string)]])]

   "AllergyIntolerance" [:map [:resourceType [:= "AllergyIntolerance"]] (opt :id ::id)
                         [:clinicalStatus ::codeable-concept]
                         [:verificationStatus ::codeable-concept]
                         (opt :type [:enum "allergy" "intolerance"])
                         (opt :category [:vector [:enum "food" "medication" "environment" "biologic"]])
                         (opt :criticality [:enum "low" "high" "unable-to-assess"])
                         [:code ::codeable-concept]
                         [:patient ::reference]
                         (opt :recordedDate ::date-time)]

   "Appointment" [:map [:resourceType [:= "Appointment"]] (opt :id ::id)
                  [:status [:enum "proposed" "pending" "booked" "arrived" "fulfilled" "cancelled" "noshow" "entered-in-error" "checked-in" "waitlist"]]
                  (opt :description :string)
                  (opt :start ::instant)
                  (opt :end ::instant)
                  (opt :minutesDuration pos-int?)
                  (opt :requestedPeriod [:vector ::period])
                  [:participant [:vector {:min 1} [:map [:actor ::reference] [:status participation-statuses]]]]]

   "Flag" [:map [:resourceType [:= "Flag"]] (opt :id ::id)
           [:status [:enum "active" "inactive" "entered-in-error"]]
           (opt :identifier [:vector ::identifier])
           (opt :category [:vector ::codeable-concept])
           [:code ::codeable-concept]
           [:subject ::reference]
           (opt :period ::period)]

   "Communication" [:map [:resourceType [:= "Communication"]] (opt :id ::id)
                    (opt :identifier [:vector ::identifier])
                    [:status [:enum "preparation" "in-progress" "not-done" "on-hold" "stopped" "completed" "entered-in-error" "unknown"]]
                    (opt :category [:vector ::codeable-concept])
                    [:subject ::reference]
                    (opt :recipient [:vector ::reference])
                    (opt :about [:vector ::reference])
                    (opt :sent ::date-time)
                    (opt :payload [:vector [:map (opt :contentString :string)]])]

   "Bundle" [:map [:resourceType [:= "Bundle"]]
             [:type [:enum "document" "message" "transaction" "transaction-response" "batch"
                     "batch-response" "history" "searchset" "collection"]]
             (opt :total nat-int?)
             (opt :link [:vector [:map [:relation :string] [:url :string]]])
             (opt :entry [:vector [:map (opt :fullUrl :string) (opt :resource map?)
                                   (opt :request [:map [:method [:enum "GET" "HEAD" "POST" "PUT" "DELETE" "PATCH"]]
                                                  [:url :string]])]])]})

(def Resource
  "Schema of any supported resource, dispatching on `:resourceType`."
  (m/schema (into [:multi {:dispatch :resourceType}] resource-schemas)
            {:registry registry}))

(defn explain
  "The malli explanation of `resource`, or nil when it is valid."
  [resource]
  (m/explain Resource resource))

(defn valid?
  [resource]
  (m/validate Resource resource))

(defn validate
  "nil when `resource` is valid; otherwise an OperationOutcome describing every error."
  [resource]
  (when-let [explanation (explain resource)]
    (outcome/from-explanation (or (:resourceType resource) "Resource") explanation)))
