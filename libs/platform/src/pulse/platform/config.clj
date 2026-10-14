(ns pulse.platform.config
  "Configuration as data (ADR-0003): an EDN resource on the classpath, read with Aero and
  checked with a malli schema before anything starts, so a bad configuration fails at once
  and says what is wrong.

  The file is the Integrant system map itself. Aero's `#env`, `#or`, `#profile` and `#long`
  work as usual, and `#ig/ref` is added here so components can point at each other. Reading
  from the classpath rather than a file path keeps the apps startable from any directory."
  (:require [aero.core :as aero]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [integrant.core :as ig]
            [malli.core :as m]
            [malli.error :as me]))

(defmethod aero/reader 'ig/ref [_ _ value]
  (ig/ref value))

(def ref-schema
  "For a slot that holds an Integrant `#ig/ref` in the file and the started component later."
  [:fn {:error/message "should be an #ig/ref"} ig/ref?])

(defn- problem-lines
  "One line per problem of a humanized malli result (nested maps and vectors), as
  `path: message`. The offending values are never printed: they may be secrets."
  ([problems] (problem-lines [] problems))
  ([path problems]
   (let [line (fn [message] (if (seq path) (str (str/join " " path) ": " message) (str message)))]
     (cond
       (map? problems) (mapcat (fn [[k v]] (problem-lines (conj path k) v)) problems)
       (sequential? problems) (mapcat (fn [p] (if (or (map? p) (sequential? p))
                                                (problem-lines path p)
                                                [(line p)]))
                                      problems)
       :else [(line problems)]))))

(defn validate!
  "Returns `config` when it satisfies `schema`, otherwise throws an ex-info with
  `:type ::invalid-config` whose message lists every problem."
  [schema config]
  (if-let [explanation (m/explain schema config)]
    (let [lines (problem-lines (me/humanize explanation))]
      (throw (ex-info (str "Invalid configuration:\n  " (str/join "\n  " lines))
                      {:type ::invalid-config :problems lines})))
    config))

(defn read-config
  "Reads the classpath `resource` with Aero for `profile` and validates it against `schema`.
  Aero resolves `#include` and the like relative to the resource."
  [resource {:keys [profile schema]}]
  (let [url (or (io/resource resource)
                (throw (ex-info (str "Configuration resource not found on the classpath: " resource)
                                {:type ::missing-resource :resource resource})))]
    (validate! schema (aero/read-config url {:profile profile}))))

(defn invalid-config?
  "True for the exception thrown by `validate!`."
  [e]
  (= ::invalid-config (:type (ex-data e))))
