(ns pulse.spa.ack
  "Acknowledging an alert, as far as it can be decided without a browser (ADR-0029, points 3 and 8): the call, what each answer of the
  api means, and what the workspace keeps and says while it is done. Pure and `.cljc`, so that the JVM and Node run the same tests
  over it.

  **The workspace never decides that an alert is acknowledged.** It sends `POST /api/alerts/{id}/ack` and the api answers, and then
  the page that holds the alert is read again (no optimistic updates): the alert moves to the acknowledged ones because the api's list
  says so. What this namespace keeps meanwhile is, for each alert, one of three things (`acks`, a map with `:alerts` and `:said`):
  `{:state :sending}` from the click until the api has answered, `{:state :settling}` from the answer until the page has been read
  again (in both the button is busy, so that a second click is not a second command), and `{:state :failed :message ...}`, what
  went wrong and what the person is told, with the button free again. `:said` is the last thing a screen reader was told.

  What an answer means, in `verdict`: a 200 and a 409 (someone else got there first) both leave the alert acknowledged, a 404 leaves it
  gone, a 403 is a refusal that changes nothing, and a call that did not come back (the time ran out, no answer, a failure of the
  server) leaves it unknown, so the page is read again to find out."
  (:require [clojure.string :as str]))

(def busy-states
  "The states in which the button of an alert is busy."
  #{:sending :settling})

(defn request
  "The call that acknowledges the alert `id`, as the `:http` effect takes it. A call for an alert stops the one before it for the same
  alert (they share an id), and the answer is told to the events of `pulse.spa.ack`."
  [id]
  {:method :post
   :path ["api" "alerts" id "ack"]
   :id [:ack id]
   :on-success [:pulse.spa.ack/answered id]
   :on-refusal [:pulse.spa.ack/refused id]
   :on-failure [:pulse.spa.ack/failed id]})

(defn busy?
  "Whether the alert `id` is being acknowledged: the api has not answered yet, or the page has not been read again."
  [acks id]
  (contains? busy-states (get-in acks [:alerts id :state])))

(defn label-of
  "The words that name the alert `id` in what is said of it, as the page gave them when the person acted on it."
  [acks id]
  (get-in acks [:alerts id :label]))

(defn- said-about [label text]
  (if (str/blank? label) text (str label ": " text)))

(defn begin
  "`acks` with the acknowledgement of `id` on its way. `label` is how the page names the alert, such as `BP stage 2 · Ana García`."
  [acks id label]
  (-> acks
      (assoc-in [:alerts id] {:state :sending :label label})
      (dissoc :said)))

(defn verdict
  "What the answer of the api to the acknowledgement means, from the failure the effect tells (`{:kind :status}`; a refusal has the kind
  `:refused`): `{:result r :message text}` where `r` is
    `:already` (409: the alert is not active any more, someone got there first), `:gone` (404), the two of them to be read again,
    `:forbidden` (403: the api does not let this person), `:refused` (400 or 422, which an acknowledgement should not meet),
    `:unconfirmed` (the call did not come back: the alert may or may not have been acknowledged, and the page is read again to find
    out), and `:ignore` for a call that was stopped or a sign-in the api did not accept, which the page for it answers.
  `:message` is what the person is told, for the results that leave the button free."
  [{:keys [kind status]}]
  (case kind
    :refused (if (= 409 status)
               {:result :already}
               {:result :refused :message "The alert was not acknowledged."})
    :not-found {:result :gone}
    :forbidden {:result :forbidden :message "You are not allowed to acknowledge this alert."}
    (:aborted :unauthorized) {:result :ignore}
    {:result :unconfirmed
     :message "We could not confirm that the alert was acknowledged. Check the list before you try again."}))

(defn announcement
  "What a screen reader is told when the acknowledgement of the alert named `label` ends in `result`: `:done` (the api said 200), or
  one of the results of `verdict`."
  [result label]
  (case result
    :done (said-about label "Alert acknowledged.")
    :already (said-about label "That alert was already acknowledged.")
    :gone (said-about label "That alert could not be found.")
    :forbidden (said-about label "You are not allowed to acknowledge this alert.")
    :refused (said-about label "The alert was not acknowledged.")
    :unconfirmed (said-about label "We could not confirm that the alert was acknowledged.")))

(defn settling
  "`acks` once the api has answered for `id` and the alert is to be read again: the button stays busy until the page has it, and a
  screen reader is told `said`."
  [acks id said]
  (-> acks
      (assoc-in [:alerts id :state] :settling)
      (assoc :said said)))

(defn failed
  "`acks` once the acknowledgement of `id` has not been done or not been confirmed: the button is free again and `message` is shown with
  the alert, and told to a screen reader."
  [acks id message]
  (-> acks
      (assoc-in [:alerts id] {:state :failed :label (label-of acks id) :message message})
      (assoc :said (said-about (label-of acks id) message))))

(defn forget
  "`acks` without the alert `id`, with `said` told to a screen reader (nothing, when it is nil): the page has nothing to show for it."
  [acks id said]
  (-> acks
      (update :alerts dissoc id)
      (assoc :said said)))

(defn first-failed
  "The first of the alerts `ids` (in the order the page shows them) whose acknowledgement was not done or not confirmed, or nil: it is
  still on the page with its message and a button that is free again, and it is where the focus belongs, since the button the person
  pressed lost it when it went busy."
  [acks ids]
  (first (filter #(= :failed (get-in acks [:alerts % :state])) ids)))

(defn clear-settling
  "`acks` without the alerts the page was being read again for: it has been read, or it is not going to be."
  [acks]
  (update acks :alerts (fn [alerts] (into {} (remove (fn [[_ entry]] (= :settling (:state entry)))) alerts))))

(defn hush
  "`acks` with what was last said to a screen reader taken away, for the page that says its own things again."
  [acks]
  (dissoc acks :said))

(def said-for-ms
  "How long what a screen reader was told of an acknowledgement stays on the page once the page has been read again. A page that is
  read again at once (the api is next door, as on a fast machine) would otherwise give way to its own words before the words of the
  command were drawn, and nobody would be told: found by the journey on a runner of GitHub, where the summary was back within a frame."
  1500)

(defn hush-said
  "`acks` without `said`, when that is still what was last said to a screen reader: something said since is not taken away."
  [acks said]
  (if (and (some? said) (= said (:said acks)))
    (dissoc acks :said)
    acks))

(defn hush-later
  "The effect that takes away, after `said-for-ms`, what `acks` last said to a screen reader, or nil when nothing was said."
  [acks]
  (when-let [said (:said acks)]
    [:dispatch-later {:ms said-for-ms :dispatch [:pulse.spa.ack/hush said]}]))

(defn entries
  "The state of each alert in `acks`, by id: what the page shows with the alert."
  [acks]
  (:alerts acks))
