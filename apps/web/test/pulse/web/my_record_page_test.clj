(ns pulse.web.my-record-page-test
  "The record of the portal as a function of the data in the shapes of the api (ADR-0028): the medication, the conditions and the
  allergies of a patient, what it shows for one with nothing on file, for each list that the api could not give and for data that other
  systems wrote oddly; and that no code is shown, since a code is for the clinic."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [pulse.design.portal :as portal]
            [pulse.ui.test.a11y :as a11y]
            [pulse.ui.test.hiccup :as hx]
            [pulse.ui.test.html :as parsed]
            [pulse.web.html :as html]
            [pulse.web.pages.record :as record]))

(def ^:private ana (portal/record-model "pt-ana"))

(def ^:private sofia (portal/record-model "pt-sofia"))

(def ^:private noah (portal/record-model "pt-noah"))

(def ^:private links {:retry "/record"})

(defn- read-back
  "The page as it is served and read back, so that what is checked is the HTML."
  [model & [the-links]]
  (parsed/document (html/render [:main (record/content model (or the-links links))])))

(defn- words [tree] (hx/words tree))

(defn- list-named [tree label]
  (first (hx/select tree #(and (= :ul (hx/tag %)) (= label (:aria-label (hx/attributes %)))))))

(defn- rows [tree label] (hx/by-tag (list-named tree label) :li))

(defn- lines [row] (map words (hx/by-tag row :p)))

(defn- cards [tree]
  (hx/select tree #(and (= :section (hx/tag %)) (hx/has-class? % "shadow-card"))))

(defn- alerts [tree]
  (hx/select tree #(and (= :div (hx/tag %)) (hx/has-class? % "bg-tone-high-soft"))))

(defn- lost
  "The summary of `model` with the lists in `names` not loaded: the api says null for each and names it."
  [model & names]
  (-> model
      (update :summary (fn [summary]
                         (-> (reduce #(assoc %1 (keyword %2) nil) summary names)
                             (assoc :unavailable (vec names)))))))

;; ---------------------------------------------------------------------------
;; The record of a patient

(deftest the-record-of-a-patient-has-these-parts-in-this-order
  (let [tree (read-back ana)
        main (first (hx/by-tag tree :main))
        page (first (hx/by-tag main :div))]
    (is (= ["My record"] (map words (hx/by-tag tree :h1))))
    (is (= ["Medication" "Conditions" "Allergies"] (map words (hx/by-tag tree :h2))))
    (is (= [:header :section :section :section] (map hx/tag (filter hx/element? (hx/child-nodes page)))))
    (is (= "What your clinic has on file. Tell them if something is wrong." (words (first (hx/by-tag tree :p)))))
    (is (= [] (a11y/problems tree)) "a heading for each part, a label for each list")))

(deftest the-medication-is-the-name-the-dose-and-since-when
  (let [tree (read-back ana)]
    (is (= [["Amlodipine" "5 mg once daily"] ["Lisinopril" "10 mg once daily"]] (map lines (rows tree "Medication")))
        "in the order the api gave them: by name")
    (is (every? #(str/includes? (words %) "Since Nov 2025") (rows tree "Medication")))
    (is (every? #(= 1 (count (hx/select % (fn [el] (hx/has-class? el "badge"))))) (rows tree "Medication")) "one chip for each")))

(deftest the-conditions-are-the-name-the-state-and-since-when
  (let [tree (read-back ana)]
    (is (= [["Essential (primary) hypertension" "Active"]] (map lines (rows tree "Conditions"))))
    (is (str/includes? (words (first (rows tree "Conditions"))) "Since May 2019"))))

(deftest a-code-is-for-the-clinic
  (let [text (words (read-back ana))]
    (doseq [code ["I10" "29046" "17767" "ICD" "RxNorm"]]
      (is (not (str/includes? text code)) code))))

(deftest an-allergy-is-the-name-and-the-reaction
  (let [tree (read-back sofia)]
    (is (= [["Penicillin G" "Skin rash"]] (map lines (rows tree "Allergies")))
        "with a capital to begin: the clinic wrote penicillin G")
    (is (not (str/includes? (words (list-named tree "Allergies")) "Since")) "an allergy has no month to say")))

(deftest a-patient-with-nothing-on-file-is-told-so-in-each-card
  (let [tree (read-back noah)
        text (words tree)]
    (is (= ["Medication" "Conditions" "Allergies"] (map words (hx/by-tag tree :h2))))
    (is (empty? (hx/by-tag tree :li)) "no row")
    (is (empty? (alerts tree)) "nothing is lost: there is just nothing")
    (is (str/includes? text "No medication is prescribed."))
    (is (str/includes? text "No conditions are recorded."))
    (is (str/includes? text "No allergies are recorded. If you have one, tell your clinic."))
    (is (= [] (a11y/problems tree)))))

(deftest a-card-with-something-in-it-does-not-say-it-has-nothing
  (let [text (words (read-back ana))]
    (is (not (str/includes? text "No medication is prescribed.")))
    (is (not (str/includes? text "No conditions are recorded.")))
    (is (str/includes? text "No allergies are recorded. If you have one, tell your clinic.") "Ana has none")))

;; ---------------------------------------------------------------------------
;; What the api could not give

(deftest a-list-that-could-not-be-loaded-is-a-message-in-its-place-and-never-an-empty-list
  (doseq [[name title lost-title] [["medications" "Medication" "Your medication could not be loaded"]
                                   ["conditions" "Conditions" "Your conditions could not be loaded"]
                                   ["allergies" "Allergies" "Your allergies could not be loaded"]]
          :let [tree (read-back (lost ana name))
                text (words tree)]]
    (testing name
      (is (= [lost-title] (map #(words (first (hx/by-tag % :p))) (alerts tree))))
      (is (str/includes? (words (first (alerts tree))) "The rest of the page is up to date. The problem is on our side, not yours."))
      (is (= ["Medication" "Conditions" "Allergies"] (map words (hx/by-tag tree :h2))) "the heading stays")
      (is (nil? (list-named tree title)) "no list of the one that was lost")
      (is (not (str/includes? text "No medication is prescribed.")))
      (is (= "/record" (:href (hx/attributes (first (filter #(= "Try again" (words %)) (hx/by-tag tree :a)))))))
      (testing "and the others are as they were"
        (let [whole (read-back ana)]
          (doseq [other (remove #{title} ["Medication" "Conditions"])]
            (is (= (words (list-named whole other)) (words (list-named tree other))))))
        (is (= [] (a11y/problems tree)))))))

(deftest every-list-lost-says-so-and-does-not-speak-of-a-rest-of-the-page
  (let [tree (read-back (lost ana "medications" "conditions" "allergies"))]
    (is (= 3 (count (alerts tree))))
    (is (not (str/includes? (words tree) "The rest of the page")))
    (is (str/includes? (words tree) "The problem is on our side, not yours."))
    (is (= [] (a11y/problems tree)))))

(deftest a-list-the-summary-does-not-have-is-lost-too
  (testing "the api said nothing of it, and not that it could not be loaded"
    (doseq [summary [(dissoc (:summary ana) :medications)
                     (assoc (:summary ana) :medications nil)
                     (assoc (:summary ana) :medications "no list")
                     (assoc (:summary ana) :medications {:id "x"})]
            :let [tree (read-back {:summary summary})]]
      (is (= ["Your medication could not be loaded"] (map #(words (first (hx/by-tag % :p))) (alerts tree))))
      (is (not (str/includes? (words tree) "No medication is prescribed."))))))

(deftest no-summary-is-a-page-that-says-so
  (let [tree (read-back {:summary nil})]
    (is (= ["My record"] (map words (hx/by-tag tree :h1))))
    (is (= ["Your record could not be loaded"] (map #(words (first (hx/by-tag % :p))) (alerts tree))))
    (is (not (str/includes? (words tree) "The rest of the page")) "there is no rest")
    (is (empty? (cards tree)) "no card that looks as if there were a record")
    (is (empty? (hx/by-tag tree :li)))
    (is (= [] (a11y/problems tree)))))

(deftest the-way-to-try-again-is-there-only-when-the-page-has-one
  (let [tree (read-back (lost ana "allergies") {})]
    (is (= 1 (count (alerts tree))))
    (is (empty? (hx/by-tag tree :a)) "no link, and no button that leads nowhere")))

;; ---------------------------------------------------------------------------
;; Data that other systems wrote

(defn- with-medication [item]
  {:summary (assoc (:summary ana) :medications [item] :unavailable [])})

(deftest a-medication-with-no-name-is-still-in-the-list
  (doseq [display [nil "" "   " 42 {:text "x"} ["lisinopril"]]]
    (let [tree (read-back (with-medication {:id "m" :display display :dosage "1 tablet" :authoredOn "2025-11-04T09:00:00Z"}))]
      (is (= [["Not named" "1 tablet"]] (map lines (rows tree "Medication"))) (pr-str display)))))

(deftest a-date-that-cannot-be-read-is-no-date
  (doseq [authored [nil "" "yesterday" "2025-13-45" "2025-11" "04/11/2025" 20251104 {:a 1}]]
    (let [tree (read-back (with-medication {:id "m" :display "lisinopril" :dosage "10 mg" :authoredOn authored}))]
      (is (= [["Lisinopril" "10 mg"]] (map lines (rows tree "Medication"))) (pr-str authored))
      (is (not (str/includes? (words (list-named tree "Medication")) "Since")) (pr-str authored))))
  (testing "a plain date and an instant are both dates"
    (doseq [authored ["2025-11-04" "2025-11-04T09:00:00Z" "2025-11-04T09:00:00+01:00"]]
      (is (str/includes? (words (list-named (read-back (with-medication {:id "m" :display "x" :authoredOn authored})) "Medication")) "Since Nov 2025")))))

(deftest what-is-not-text-is-left-out-and-never-throws
  (doseq [item [{} {:id "m"} {:display "x" :dosage nil} {:display "x" :dosage 5} {:display "x" :dosage {:a 1}} {:display "x" :dosage "  "}
                {:display "x" :unknown "key"}]]
    (let [tree (read-back (with-medication item))]
      (is (= 1 (count (rows tree "Medication"))) (pr-str item))
      (is (= 1 (count (hx/by-tag (first (rows tree "Medication")) :p))) "only the name")))
  (testing "a condition with no state and an allergy with no reaction have one line"
    (let [tree (read-back {:summary (assoc (:summary ana)
                                           :conditions [{:display "Asthma" :status nil :onset "2020-01-02"}]
                                           :allergies [{:display "latex" :reaction nil}]
                                           :unavailable [])})]
      (is (= [["Asthma"]] (map lines (rows tree "Conditions"))))
      (is (= [["Latex"]] (map lines (rows tree "Allergies")))))))

(deftest what-the-clinic-wrote-is-text-whatever-it-holds
  (let [hostile "<script>alert(1)</script>"
        html-text (html/render [:main (record/content {:summary (assoc (:summary ana)
                                                                       :medications [{:display hostile :dosage hostile :authoredOn "2025-11-04"}]
                                                                       :conditions [{:display hostile :status hostile}]
                                                                       :allergies [{:display hostile :reaction hostile}]
                                                                       :unavailable [])}
                                                      links)])]
    (is (not (str/includes? html-text "<script>alert")))
    (is (= 6 (count (re-seq #"&lt;script&gt;alert\(1\)&lt;/script&gt;" html-text))) "the name, the dose, the state and the reaction, as text")))

;; ---------------------------------------------------------------------------
;; The words of a row

(deftest the-name-of-a-medication-begins-with-a-capital-and-is-otherwise-as-the-clinic-wrote-it
  (is (= "Lisinopril" (record/name-of "lisinopril")))
  (is (= "Penicillin G" (record/name-of "penicillin G")))
  (is (= "Essential (primary) hypertension" (record/name-of "Essential (primary) hypertension")))
  (is (= "Éclair" (record/name-of "éclair")))
  (is (= "A" (record/name-of "a")))
  (is (= "Metformin HCl 500 MG" (record/name-of "metformin HCl 500 MG")) "nothing else is changed")
  (doseq [odd [nil "" " " "\n" 5 :kw [] {}]]
    (is (= "Not named" (record/name-of odd)) (pr-str odd))))

(deftest since-is-a-month-of-the-clinic
  (is (= "Since Nov 2025" (record/since "2025-11-04T09:00:00Z")))
  (is (= "Since Nov 2025" (record/since "2025-11-04")))
  (is (= "Since Nov 2025" (record/since "2025-10-31T23:30:00Z")) "23:30 UTC on 31 October is 00:30 on 1 November in Madrid")
  (doseq [odd [nil "" "2025" "2025-11" "soon" 5 :kw {} []]]
    (is (nil? (record/since odd)) (pr-str odd))))

;; ---------------------------------------------------------------------------
;; Properties

(def ^:private odd-value
  (gen/one-of [(gen/return nil) gen/string gen/small-integer (gen/return "2025-11-04") (gen/return "2025-11-04T09:00:00Z")
               (gen/return "<b>x</b>") (gen/return []) (gen/return {})]))

(def ^:private odd-item
  (gen/let [display odd-value dosage odd-value date odd-value status odd-value]
    {:display display :dosage dosage :authoredOn date :onset date :status status :reaction dosage}))

(defn- count-rows [tree label]
  (if-let [l (list-named tree label)] (count (hx/by-tag l :li)) 0))

(deftest any-list-of-anything-is-a-page-with-one-row-for-each-item
  (let [result (tc/quick-check
                200
                (prop/for-all [medications (gen/vector odd-item 0 6)
                               conditions (gen/vector odd-item 0 6)
                               allergies (gen/vector odd-item 0 6)]
                  (let [tree (read-back {:summary {:medications medications :conditions conditions :allergies allergies
                                                   :unavailable []}})]
                    (and (= [(count medications) (count conditions) (count allergies)]
                            (mapv #(count-rows tree %) ["Medication" "Conditions" "Allergies"]))
                         (empty? (alerts tree))
                         (= [] (a11y/problems tree))))))]
    (is (:pass? result) (pr-str (select-keys result [:shrunk :fail])))))
