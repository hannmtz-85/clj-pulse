(ns pulse.platform.log
  "Structured logging on Telemere (ADR-0010). Application code emits events as data with
  `taoensso.telemere/event!`; this namespace decides where they go and how they look. It is the
  only place that touches Telemere's handlers.

  Output is one console handler, formatted as `:text` (readable, for development) or `:json`
  (one object per line, for containers). Libraries that log through SLF4J, Jetty among them,
  arrive as signals of kind `:slf4j` and are formatted the same way."
  (:require [clojure.set :as set]
            [jsonista.core :as json]
            [taoensso.telemere :as t]
            [taoensso.telemere.utils :as tu]))

(def levels
  "Minimum levels, from the most to the least verbose."
  [:trace :debug :info :warn :error :fatal])

(def ^:private json-members [:inst :level :kind :id :ns :msg :data :ctx :error])

(def ^:private clean-signal (tu/clean-signal-fn))

(defn- json-signal
  "The members of a signal that go into a JSON line. Telemere's cleaning has already removed
  the empty ones, turned the delayed message into text and the exception into a cause chain
  without stack trace."
  [signal]
  (-> (clean-signal signal)
      (set/rename-keys {:msg_ :msg})
      (select-keys json-members)))

(defn- write-json
  "A JSON line must never be lost because one value cannot be serialised (a function, a
  record): fall back to a line that carries the signal as EDN text."
  [signal]
  (try
    (json/write-value-as-string signal)
    (catch Exception _
      (let [edn (pr-str signal)]
        (json/write-value-as-string
         {:level "error"
          :id "pulse.platform.log/unserializable-signal"
          :edn (if (> (count edn) 2000) (str (subs edn 0 2000) "...") edn)})))))

(defn output-fn
  "The function that turns a signal into the string a handler writes, for `format` `:json`
  or `:text`."
  [format]
  (case format
    :json (t/pr-signal-fn {:pr-fn write-json :clean-fn json-signal})
    :text (t/format-signal-fn)))

(defn start!
  "Sends every signal to a single console handler. Options:

  `:level`      minimum level, default `:info`
  `:format`     `:text` (default) or `:json`
  `:ns-levels`  a map of namespace pattern to level that overrides `:level` for matching
                namespaces, such as {\"org.eclipse.jetty.*\" :warn}. SLF4J signals take the
                logger name as their namespace

  The handler is asynchronous and blocks the caller when its buffer is full instead of
  dropping, because audit events must not be lost. Calling `start!` again replaces the
  previous setup."
  [{:keys [level format ns-levels] :or {level :info format :text}}]
  (t/remove-handler! :default/console)
  (t/set-min-level! (conj (vec ns-levels) ["*" level]))
  (t/add-handler! ::console
                  (t/handler:console {:output-fn (output-fn format)})
                  {:async {:mode :blocking :buffer-size 1024 :n-threads 1}})
  nil)

(defn stop!
  "Flushes and removes the handler made by `start!`."
  []
  (t/remove-handler! ::console)
  (t/set-min-level! :info)
  nil)
