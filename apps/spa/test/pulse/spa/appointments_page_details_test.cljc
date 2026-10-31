(ns pulse.spa.appointments-page-details-test
  "What a mutation analysis of `pulse.spa.pages.appointments` and of the buttons of a row found that the first tests did not look at: the
  line under the name of an appointment when it has only a patient or only a time, the cards that reach the edges of their rows, the
  words of the notice for a list that could not be updated, the note of a list that is the first of more, what the dialog says of an
  appointment that has no description or was cancelled, the field of the reason, and that a row with nothing to do has no buttons. Pure
  hiccup, so it runs on the JVM and on Node over the same data."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [pulse.spa.appointment-command :as command]
            [pulse.spa.appointments :as appointments]
            [pulse.spa.pages.appointments :as page]
            [pulse.spa.pages.parts :as parts]
            [pulse.spa.test.appointments-fixtures :as fx]
            [pulse.ui.test.hiccup :as hx]))

(def ^:private links
  {:panel "/" :patient (fn [id] (str "/patients/" id)) :dialog (fn [id action] (str "/appointments/" id "/" (name action))) :close "/appointments"})

(def ^:private handlers {:retry (fn []) :edit (fn [_ _]) :submit (fn [_]) :key-down (fn [_])})

(defn- content [state] (page/content state {:links links :handlers handlers}))

(defn- words [tree] (hx/words tree))

(defn- first-row
  "The first row of the list of booked appointments, as the model makes it."
  []
  (first (get-in (appointments/model (fx/settled) {} false) [:booked :items])))

(defn- lines [row-tree] (map words (hx/by-tag row-tree :p)))

(defn- subline
  "The line under the description of a row made from `row`: what the row says of whose it is and when."
  [row]
  (second (lines (page/appointment-row row {:links links}))))

(deftest the-line-under-the-name-has-the-patient-and-the-time-with-a-dot-between-only-when-it-has-both
  (let [row (first-row)]
    (is (= "Ana García · Wed 14 Oct, 10:00–10:30" (subline row)))
    (is (= "Ana García" (subline (assoc row :when nil))) "a patient and no time: no dot")
    (is (= "Wed 14 Oct, 10:00–10:30" (subline (assoc row :name nil))) "a time and no patient: no dot either")
    (is (= "Requested for 16 Oct – 22 Oct" (subline (assoc row :name nil :when nil :asked "Requested for 16 Oct – 22 Oct"))) "a request is told by what it asked")
    (is (not (str/includes? (str (lines (page/appointment-row (assoc row :name nil :when nil :asked nil) {:links links}))) "·")) "and with neither there is no line")))

(deftest the-lists-reach-the-edges-of-their-cards
  (doseq [state [(appointments/start fx/now) (fx/settled)]
          :let [tree (content state)]]
    (is (= 3 (count (hx/select tree #(hx/has-class? % "mt-3")))) "three cards: the body of each has no padding of its own")))

(defn- visible-paragraphs
  "The paragraphs of a page that a person sees: the one for a screen reader says the same words, and a test of the whole text would be
  satisfied by it."
  [tree]
  (map words (remove #(hx/has-class? % "sr-only") (hx/by-tag tree :p))))

(deftest a-list-that-could-not-be-updated-says-so-in-its-own-words
  (let [tree (content (assoc (fx/settled) :latest {:requests fx/failed}))]
    (is (some #{"We could not update the list"} (visible-paragraphs tree)) "the title of the message")
    (is (some #(str/starts-with? % "What you see may be out of date.") (map words (hx/select tree #(hx/has-class? % "text-sm"))))
        "and what it says besides")))

(deftest a-list-that-is-the-first-of-more-says-so
  (let [tree (content (assoc (fx/settled) :requests (fx/loaded (fx/page [fx/sofia-request fx/ana-request] 130))))]
    (is (str/includes? (words tree) "Showing the first 2 of 130 requests."))
    (is (not (str/includes? (words tree) "of 130 booked")))))

(deftest the-dialog-says-what-an-appointment-with-no-description-is
  (let [state (-> (assoc (fx/settled) :requests (fx/loaded (fx/page [(assoc fx/sofia-request :description nil)])))
                  (appointments/sync-dialog {:id "appt-sofia-1" :action :confirm}))
        dialog (first (hx/by-tag (content state) :dialog))]
    (is (str/includes? (words dialog) "Appointment"))
    (is (some #(= "Appointment" %) (map words (hx/by-tag dialog :p))))))

(deftest the-dialog-of-a-cancelled-appointment-says-why-it-was-cancelled
  (let [state (appointments/sync-dialog (fx/settled) {:id "appt-ana-0" :action :confirm})
        dialog (first (hx/by-tag (content state) :dialog))]
    (is (str/includes? (words dialog) "Reason: I had to travel that week."))))

(deftest the-field-for-the-reason-does-not-offer-what-was-typed-before
  (let [state (appointments/sync-dialog (fx/settled) {:id "appt-ana-1" :action :cancel})
        field (first (hx/select (content state) #(= "why" (:id (hx/attributes %)))))]
    (is (some? field))
    (is (= "off" (:autocomplete (hx/attributes field))))))

(deftest a-row-with-nothing-to-do-has-no-buttons
  (is (nil? (parts/appointment-buttons {:id "appt-ana-0" :label "Blood pressure check" :actions #{}} {:links links})))
  (is (nil? (parts/appointment-buttons {:id "appt-ana-0" :label "Blood pressure check" :actions nil} {:links links})))
  (is (nil? (parts/appointment-buttons {:id "appt-ana-1" :label "x" :actions #{:cancel}} {:links {}})) "and none without a place to lead to"))

(deftest what-the-api-said-in-a-notice-and-the-lifecycle-in-a-blocked-dialog-breaks-wherever-it-must
  (let [long-word (apply str (repeat 8 "Appointment-appt-sofia-1-"))
        notice (-> (appointments/sync-dialog (fx/settled) {:id "appt-sofia-1" :action :confirm})
                   (update :dialog command/refused {} {:kind :conflict :text long-word}))
        dialog (first (hx/by-tag (content notice) :dialog))
        wrapped (fn [tree text] (some #(= text (words %)) (hx/select tree #(hx/has-class? % "wrap-anywhere"))))
        blocked (first (hx/by-tag (content (appointments/sync-dialog (fx/settled) {:id "appt-ana-0" :action :confirm})) :dialog))]
    (is (wrapped dialog long-word) "the api's sentence in the notice")
    (is (wrapped blocked "Only a proposed appointment can be confirmed; this one is cancelled.") "the lifecycle's sentence in the message")))
