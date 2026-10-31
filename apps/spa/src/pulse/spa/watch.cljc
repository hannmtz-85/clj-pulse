(ns pulse.spa.watch
  "The beat of the clinical workspace, as far as it can be decided without a browser (ADR-0029, point 6): every 30 seconds, while the tab
  is seen, the workspace asks the api how many alerts need a decision and shows the number in the navigation of every page, and the page
  the person is on may look again at what it shows (`pulse.spa.route`, `:refresh`). It is the only thing in the workspace that polls: the
  rules run at that pace, so a faster beat would only ask the same question twice. Pure and `.cljc`.

  **A number that could not be read is not a number of zero.** A badge that disappears says \"no alerts\", which is the one thing a
  clinician must not be told by mistake, so a count that could not be read is `:unknown` and the navigation shows it as such.")

(def beat-ms
  "How often the workspace looks again, while the tab is seen."
  30000)

(defn count-request
  "The call that asks how many alerts need a decision: the api answers the page of one alert, with the `total` of the caller's scope,
  which is all that is read. `epoch` says which run of the beat asks, so that an answer to one that is over is not heard."
  [epoch]
  {:method :get
   :path ["api" "alerts"]
   :query {:status "active" :limit 1}
   :id [:watch :count]
   :on-success [:pulse.spa.watch/counted epoch]
   :on-failure [:pulse.spa.watch/count-failed epoch]})

(defn counted
  "The part that the answer `body` of the api is: `{:state :loaded :total n}`, or a failure of kind `:unexpected` when it is not what the
  api answers (another service could be on that address)."
  [body]
  (if (and (map? body) (integer? (:total body)) (not (neg? (:total body))))
    {:state :loaded :total (:total body)}
    {:state :failed :kind :unexpected}))

(defn nav-count
  "What the navigation shows for the alerts, from the state of the beat `watch`: the number that need a decision, `:unknown` when it
  could not be read, and nil while it is not known yet."
  [watch]
  (let [{:keys [state total]} (:count watch)]
    (case state
      :loaded total
      :failed :unknown
      nil)))
