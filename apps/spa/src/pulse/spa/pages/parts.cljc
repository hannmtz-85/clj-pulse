(ns pulse.spa.pages.parts
  "The pieces that more than one page of the clinical workspace is made of (ADR-0029): text written by someone else in a box that
  breaks it where it must, the message that stands where a part could not be loaded, and the buttons of an alert. Pure hiccup of the
  components of `libs/ui`, with no Reagent.

  **A control that has nothing to lead to is not there**: the button that acknowledges an alert is there only when the page can do it
  (`:acknowledge` among the handlers, a function of the id of the alert and the words that name it, or among the links, a function of
  the id that gives the address the mock-up leads to), the one that opens a chart only when the patient is named and `:patient` is among
  the links, and the one that tries again only when `:retry` is among the handlers."
  (:require [clojure.string :as str]
            [pulse.spa.ack :as ack]
            [pulse.ui.component :as c]))

(defn wrapped
  "`text`, written by someone else, in a box that breaks it wherever it must: a word with no spaces in it does not push the page wide."
  [text]
  [:span {:class "wrap-anywhere"} text])

(defn retry-button
  "The button that tries again what failed, or nil when the page has nothing to do it with."
  ([handlers] (retry-button handlers :lg))
  ([handlers size]
   (when-let [retry (:retry handlers)]
     (c/button {:variant :outline :size size :icon :refresh-cw :on-click retry} "Try again"))))

(defn not-loaded
  "The message that stands where a part of the page should be when it could not be loaded, as the panel and the portal say it: a
  `title`, what else is up to date (`rest-of-page`, a sentence, or nil) and the button to try again."
  [title rest-of-page handlers]
  (c/alert {:tone :high :title title :actions (retry-button handlers)}
           (str (when rest-of-page (str rest-of-page " ")) "The problem is on our side, not yours.")))

(defn acknowledge-button
  "The button that acknowledges the alert `id`, which the page names `label` (such as `BP stage 2 · Ana García`; a screen reader is
  told it, so that several buttons of one page do not all say the same). It is busy from the click until the page has been read again
  (`ack`, the state of the acknowledgement of this alert, or nil), so that a second click is not a second command. Nil when the page
  cannot acknowledge."
  [id label ack {:keys [links handlers]}]
  (let [on-click (:acknowledge handlers)
        href (:acknowledge links)]
    (when (or on-click href)
      (c/button (cond-> {:id (str "ack-" id)
                         :size :sm
                         :icon :check
                         :aria-label (str "Acknowledge " label)}
                  on-click (assoc :on-click (fn [_] (on-click id label)))
                  (and (not on-click) href) (assoc :href (href id))
                  (and on-click (contains? ack/busy-states (:state ack))) (assoc :loading? true))
                "Acknowledge"))))

(defn failure-message
  "What the person is told when an alert was not acknowledged, in the alert's own card, or nil when there is nothing to tell."
  [ack]
  (when-let [message (:message ack)]
    [:p {:class "mt-2 font-medium wrap-anywhere"} message]))

(defn actions
  "The buttons of an alert, in a list, or nil when there are none: an alert whose actions are all missing has no place for them."
  [& buttons]
  (when-let [present (seq (remove nil? buttons))]
    present))

(defn readings-lines
  "The readings behind an alert as a list of lines of text under a heading, or the sentence that says why there are none."
  [{:keys [state lines]}]
  (case state
    :loaded (when (seq lines)
              (list [:p {:class "mt-2 font-medium"} "The readings behind it"]
                    (into [:ul {:class "list-disc pl-5"}] (for [line lines] [:li {:class "wrap-anywhere"} line]))))
    :loading [:div {:class "mt-2"} (c/skeleton {:class "h-4 w-56"})]
    :failed [:p {:class "mt-2"} "The readings behind it could not be loaded."]
    nil))

(defn raised-line
  "`Raised yesterday, 22:30. The rule in its words.`, each only when it is known, or nil when neither is."
  [since-phrase description]
  (let [pieces (keep identity [(when since-phrase (str "Raised " since-phrase ".")) description])]
    (when (seq pieces)
      [:p {:class "wrap-anywhere"} (str/join " " pieces)])))
