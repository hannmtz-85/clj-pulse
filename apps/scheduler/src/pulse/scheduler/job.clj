(ns pulse.scheduler.job
  "Jobs as data (ADR-0019): what runs and when, and the check that a catalogue of them is sound.

  A job is `{:id :evaluate-rules :description \"...\" :schedule {...} :handler (fn [...])}`. The
  schedule is data (see `pulse.scheduler.schedule`); the handler is a function that closes over
  what it needs, so a catalogue is built by a function from the configuration and the job holds
  no state of its own."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [pulse.scheduler.schedule :as schedule]))

(defn- id?
  "A keyword with no namespace and a name that can sit in a URL path and a log line: lower case
  letters, digits and hyphens, starting with a letter."
  [x]
  (and (keyword? x)
       (nil? (namespace x))
       (boolean (re-matches #"[a-z][a-z0-9-]*" (name x)))))

(def schema
  [:map {:closed true}
   [:id [:fn {:error/message "should be a keyword such as :evaluate-rules (lower case, digits and hyphens, no namespace)"}
         id?]]
   [:description [:string {:min 1}]]
   [:schedule schedule/schema]
   [:handler [:fn {:error/message "should be a function"}
              (some-fn fn? var?)]]])

(defn- problems
  "One line per problem of `jobs`: each job against `schema`, and ids used twice."
  [jobs]
  (concat
   (for [[position job] (map-indexed vector jobs)
         :let [explanation (m/explain schema job)]
         :when explanation]
     (str "job " (if-let [id (when (map? job) (:id job))] (pr-str id) (str "#" position))
          ": " (pr-str (me/humanize explanation))))
   (for [[id n] (frequencies (keep #(when (map? %) (:id %)) jobs))
         :when (> n 1)]
     (str "job " (pr-str id) " is defined " n " times"))))

(defn check!
  "Returns `jobs` as a vector, in the order given, when each is a sound job and no two share an
  id. Otherwise throws an ex-info with `:type ::invalid-jobs` and `:problems`, one line for
  each thing wrong, so that the scheduler fails when it starts and not when the job is due."
  [jobs]
  (let [jobs (vec jobs)]
    (if-let [lines (seq (problems jobs))]
      (throw (ex-info (str "Invalid jobs:\n  " (str/join "\n  " lines))
                      {:type ::invalid-jobs :problems (vec lines)}))
      jobs)))
