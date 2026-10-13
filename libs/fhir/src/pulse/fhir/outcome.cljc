(ns pulse.fhir.outcome
  "FHIR OperationOutcome: building one from a malli explanation and inspecting one."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]))

(defn operation-outcome
  "An OperationOutcome with the given issue maps."
  [issues]
  {:resourceType "OperationOutcome" :issue (vec issues)})

(defn issue
  "One OperationOutcome issue. `severity` is fatal|error|warning|information, `code` an
  IssueType such as \"required\", \"value\" or \"structure\"."
  ([severity code diagnostics] {:severity severity :code code :diagnostics diagnostics})
  ([severity code diagnostics expression]
   (assoc (issue severity code diagnostics) :expression [expression])))

(defn- expression
  "A FHIRPath-like location: Patient.name[0].family for resource type \"Patient\" and the
  malli path [:name 0 :family]."
  [resource-type in]
  (apply str resource-type
         (map (fn [k] (if (integer? k) (str "[" k "]") (str "." (name k)))) in)))

(defn- issue-code [{:keys [type]}]
  (case type
    ::m/missing-key "required"
    ::m/invalid-dispatch-value "not-supported"
    ::m/extra-key "structure"
    "value"))

(defn from-explanation
  "Converts a malli explanation (the result of `m/explain`) of a `resource-type` resource
  into an OperationOutcome with one error issue per malli error."
  [resource-type explanation]
  (operation-outcome
   (for [{:keys [in] :as err} (:errors explanation)]
     (issue "error"
            (issue-code err)
            (or (me/error-message err) (str "invalid: " (pr-str (:type err))))
            (expression resource-type in)))))

(defn errors
  "The issues of `outcome` with severity error or fatal."
  [outcome]
  (filter #(#{"error" "fatal"} (:severity %)) (:issue outcome)))

(defn error?
  "True when `outcome` contains at least one error or fatal issue."
  [outcome]
  (boolean (seq (errors outcome))))

(defn summary
  "A one-line text of the error issues of `outcome`, for logs and exception messages."
  [outcome]
  (str/join "; " (map (fn [{:keys [diagnostics expression]}]
                        (str (first expression) ": " diagnostics))
                      (errors outcome))))
