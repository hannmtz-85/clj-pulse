(ns pulse.platform.config
  "Configuration as data (ADR-0003): an EDN resource on the classpath, read with Aero and
  checked with a malli schema before anything starts, so a bad configuration fails at once
  and says what is wrong.

  The file is the Integrant system map itself. Aero's `#env`, `#or`, `#profile`, `#long` and
  `#ref` work as usual, and two tags are added here: `#ig/ref`, so components can point at each
  other, and `#csv`, which turns a comma separated string (an environment variable, say) into a
  set of its trimmed, non-empty parts. Reading from the classpath rather than a file path keeps
  the apps startable from any directory.

  A secret in the file is written `#secret #env SOME_VARIABLE`: it is read as a `Secret`, which
  prints as `#secret[REDACTED]` wherever it ends up, in a log or in the dump that Integrant makes of
  a component that failed to start, and is opened with `reveal` only where it is used (ADR-0021)."
  (:require [aero.core :as aero]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [integrant.core :as ig]
            [malli.core :as m]
            [malli.error :as me])
  (:import (java.io Writer)))

(defmethod aero/reader 'ig/ref [_ _ value]
  (ig/ref value))

(def ^:dynamic *environment*
  "Variables that `#env` reads before the environment of the process, a map of name to string (or
  to nil, which makes a variable that is set count as not set). It is empty, and it is for tests: a
  service's configuration comes from the environment it runs in, and a test of a profile that needs a
  variable can say what the variable is without it being set on every machine that runs the tests."
  {})

(defmethod aero/reader 'env [_ _ value]
  (let [variable (str value)]
    (if (contains? *environment* variable)
      (get *environment* variable)
      (System/getenv variable))))

;; ---------------------------------------------------------------------------
;; Secrets

(deftype Secret [value]
  Object
  (toString [_] "#secret[REDACTED]")
  ;; Two secrets with the same value are equal, as two configurations that read the same file are
  (equals [_ other] (and (instance? Secret other) (= value (.-value ^Secret other))))
  (hashCode [_] (hash value)))

(defmethod print-method Secret [_ ^Writer w]
  (.write w "#secret[REDACTED]"))

(defn secret
  "`value` as a `Secret`, or nil when there is no value (a variable that is not set)."
  [value]
  (when (some? value)
    (->Secret value)))

(defn secret? [x]
  (instance? Secret x))

(defn reveal
  "What a `Secret` holds. Anything else is returned as it is, so that code that takes a secret
  also takes a plain string, as a test gives it."
  [x]
  (if (secret? x) (.-value ^Secret x) x))

(defmethod aero/reader 'secret [_ _ value]
  (secret value))

(defn secret-schema
  "For a slot that holds a non-empty `Secret`. `hint` goes into the message when it does not, and
  says where the secret comes from, such as \"set SCHEDULER_CLIENT_SECRET (see .env)\"."
  ([] (secret-schema nil))
  ([hint]
   [:fn {:error/message (str "should be a secret that is not empty" (when hint (str ": " hint)))}
    (fn [x] (and (secret? x)
                 (string? (reveal x))
                 (not (str/blank? (reveal x)))))]))

(defmethod aero/reader 'csv [_ _ value]
  (into #{} (comp (map str/trim) (remove str/blank?)) (str/split (str value) #",")))

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
