(ns pulse.web.refusal
  "What the api refuses, as words beside the fields of a form (ADR-0028). A form of the portal does not repeat the rules of the api: it
  sends what was typed, and when the api says no (a 400 for the shape, a 422 for what cannot be right) it writes the api's own
  message beside the field that it is about, as a sentence of its own. A rule that the portal wrote would be one more place to
  change. Pure functions of data in the shapes of the api, free of Ring and of the network.

  The forms that use it are the one that records a reading (`pulse.web.recording`) and the ones that ask for a visit and cancel an
  appointment (`pulse.web.requesting`)."
  (:require [clojure.string :as str]))

(def ^:private max-message-length 200)

(defn- sentence
  "A message of the api, written to follow the name of a field (`must be between 20 and 200 mmHg`), as a sentence of its own."
  [message]
  (let [message (str/trim message)
        message (str/trimr (subs message 0 (min max-message-length (count message))))
        message (str (str/upper-case (subs message 0 1)) (subs message 1))]
    (if (re-find #"[.!?]$" message) message (str message "."))))

(defn join-and
  "`a`, `a and b`, `a, b and c`: the names of the fields that are wrong, as a list in a sentence."
  [items]
  (if (= 1 (count items))
    (first items)
    (str (str/join ", " (butlast items)) " and " (last items))))

(defn place
  "The words beside the fields of a form for what the api refused: `errors` is the `errors` of its problem, a map from a field of the
  api (a keyword) to its messages, and `known` is a map from the fields of the api that the form has to the fields of the form
  that they are written beside. The result is a map from a field of the form to a sentence: the first message of the field that
  says anything, capitalised and ended with a stop. What the api says of a field the form has not got (a unit, which the form never
  sends) is left out. Empty when there is nothing to place."
  [known errors]
  (if (map? errors)
    (into {}
          (keep (fn [[field messages]]
                  (let [message (first (filter #(and (string? %) (not (str/blank? %))) (if (sequential? messages) messages [messages])))]
                    (when (and message (contains? known field))
                      [(get known field) (sentence message)]))))
          errors)
    {}))
