(ns pulse.web.reading-form-page-test
  "The form to record a reading as a function of what it holds (ADR-0028): the numbers of each measurement, the time, what was typed
  and what is wrong with it, and the message at the top; how the choice of what was measured works with and without a script; and
  that nothing a person typed is ever anything but text."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.design.data :as data]
            [pulse.design.portal :as portal]
            [pulse.fhir.measurement :as measurement]
            [pulse.ui.test.a11y :as a11y]
            [pulse.ui.test.hiccup :as hx]
            [pulse.ui.test.html :as parsed]
            [pulse.web.html :as html]
            [pulse.web.pages.reading-form :as form]
            [pulse.web.recording :as recording]
            [pulse.view.views :as views]))

(def ^:private links {:new-reading "/readings/new" :readings "/readings"})

(def ^:private now data/now-text)

(defn- model [kind & {:as more}]
  (merge {:kind kind :values {:taken "2026-10-13T14:00"} :errors {} :now now :csrf-token "tok-en_1"} more))

(defn- page [m & [page-links]]
  (parsed/document (html/render [:main (form/content m (or page-links links))])))

(defn- post-form [tree]
  (first (filter #(= "post" (:method (hx/attributes %))) (hx/by-tag tree :form))))

(defn- choose-form [tree]
  (first (filter #(= "get" (:method (hx/attributes %))) (hx/by-tag tree :form))))

(defn- field [tree id] (hx/by-id tree id))

(defn- attrs [tree id] (hx/attributes (field tree id)))

(defn- alerts [tree] (hx/select tree #(and (= :div (hx/tag %)) (hx/has-class? % "bg-tone-high-soft"))))

(defn- button-named [tree text]
  (first (filter #(= text (hx/words %)) (hx/select tree #(and (hx/has-class? % "btn") (#{:a :button} (hx/tag %)))))))

;; ---------------------------------------------------------------------------
;; The form

(deftest the-form-is-two-forms-one-to-choose-and-one-to-post
  (let [tree (page (model :heart-rate))]
    (is (= ["Record a reading"] (map hx/words (hx/by-tag tree :h1))))
    (is (str/includes? (hx/words tree) "Your care team can see it as soon as you save it."))
    (is (= 2 (count (hx/by-tag tree :form))))
    (testing "what was measured asks for this page again, with nothing that was typed or secret in its address"
      (is (= {:method "get" :action "/readings/new"} (select-keys (hx/attributes (choose-form tree)) [:method :action])))
      (is (empty? (hx/select (choose-form tree) #(= "hidden" (:type (hx/attributes %))))) "no token, no field: it is a GET"))
    (testing "the reading is posted to the same page, with the token of the session, and the browser does not judge it"
      (let [post (post-form tree)]
        (is (= {:method "post" :action "/readings/new" :novalidate true} (select-keys (hx/attributes post) [:method :action :novalidate])))
        (is (= [{:type "hidden" :name "__anti-forgery-token" :value "tok-en_1"}]
               (map hx/attributes (filter #(= "__anti-forgery-token" (:name (hx/attributes %))) (hx/by-tag post :input)))))))
    (is (= [] (a11y/problems [:main (form/content (model :heart-rate) links)])))))

(deftest what-was-measured-is-chosen-from-every-measurement-the-api-records
  (let [tree (page (model :spo2))
        select (field tree "measurement")]
    (is (= "Oxygen saturation" (hx/words (first (filter #(contains? (hx/attributes %) :selected) (hx/by-tag select :option))))))
    (is (= (map name views/kinds) (map #(:value (hx/attributes %)) (hx/by-tag select :option))))
    (is (= (set (map name (keys measurement/measurements))) (set (map #(:value (hx/attributes %)) (hx/by-tag select :option)))))
    (is (= ["Blood pressure" "Heart rate" "Oxygen saturation" "Glucose" "Weight" "Temperature"] (map hx/words (hx/by-tag select :option))))
    (is (= "What did you measure?" (hx/words (first (hx/select tree #(and (= :label (hx/tag %)) (= "measurement" (:for (hx/attributes %)))))))))
    (testing "the one being recorded is the one selected, for each"
      (doseq [kind views/kinds
              :let [select (field (page (model kind)) "measurement")]]
        (is (= [(name kind)] (map #(:value (hx/attributes %)) (filter #(contains? (hx/attributes %) :selected) (hx/by-tag select :option)))))))))

(deftest choosing-swaps-the-fields-with-htmx-and-has-a-button-for-a-browser-with-no-script
  (let [tree (page (model :heart-rate))
        select (attrs tree "measurement")]
    (testing "with htmx the page is asked for again, and only the fields of the measurement are taken from the answer"
      (is (= "/readings/new" (:hx-get select)))
      (is (= "#reading-fields" (:hx-target select)))
      (is (= "#reading-fields > *" (:hx-select select)))
      (is (= "innerHTML" (:hx-swap select)))
      (is (= "true" (:hx-push-url select)) "so that the address says what is on the page"))
    (testing "the fields are in an element of that name, which is a live region"
      (let [fields (field tree "reading-fields")]
        (is (some? fields))
        (is (= "polite" (:aria-live (hx/attributes fields))))
        (is (some? (hx/by-id fields "value")))
        (is (= [{:type "hidden" :name "measurement" :value "heart-rate"}]
               (map hx/attributes (filter #(= "hidden" (:type (hx/attributes %))) (hx/by-tag fields :input))))
            "and the measurement the reading is of, which changes with them")))
    (testing "without a script, a button asks for the page again with the measurement chosen"
      (let [noscript (first (hx/by-tag (choose-form tree) :noscript))]
        (is (some? noscript))
        (is (= "submit" (:type (hx/attributes (first (hx/by-tag noscript :button))))))
        (is (= "Show these fields" (hx/words noscript)))
        (is (hx/has-class? (first (hx/by-tag noscript :button)) "btn-lg") "as large as every other button of the portal: a finger")))
    (testing "the select is named as the form that asks for the page is"
      (is (= "measurement" (:name select)))
      (is (some? (hx/by-id (choose-form tree) "measurement"))))))

(deftest the-numbers-are-those-of-the-measurement
  (testing "a blood pressure is two"
    (let [tree (page (model :blood-pressure))]
      (is (= ["Systolic (mmHg)" "Diastolic (mmHg)"]
             (map hx/words (hx/select (field tree "reading-fields") #(= :label (hx/tag %))))))
      (is (= [{:name "systolic" :type "number" :inputmode "numeric" :step "1"} {:name "diastolic" :type "number" :inputmode "numeric" :step "1"}]
             (map #(select-keys (attrs tree %) [:name :type :inputmode :step]) ["systolic" "diastolic"])))
      (is (= ["The higher number." "The lower number."] (map #(hx/words (hx/by-id tree (str % "-hint"))) ["systolic" "diastolic"])))))
  (testing "the others are one, as fine as the api keeps it"
    (doseq [[kind label step inputmode] [[:heart-rate "Heart rate (bpm)" "1" "numeric"] [:spo2 "Oxygen saturation (%)" "1" "numeric"]
                                         [:glucose "Glucose (mg/dL)" "1" "numeric"] [:weight "Weight (kg)" "0.1" "decimal"]
                                         [:temperature "Temperature (°C)" "0.1" "decimal"]]
            :let [tree (page (model kind))]]
      (is (= [label] (map hx/words (hx/select (field tree "reading-fields") #(= :label (hx/tag %))))) (name kind))
      (is (= {:name "value" :type "number" :inputmode inputmode :step step} (select-keys (attrs tree "value") [:name :type :inputmode :step])) (name kind))
      (is (nil? (hx/by-id tree "systolic")) (name kind))))
  (testing "and none of them is judged by the browser: the api says what is wrong, in words"
    (let [tree (page (model :blood-pressure))]
      (is (every? #(and (not (contains? (attrs tree %) :min)) (not (contains? (attrs tree %) :max)) (not (contains? (attrs tree %) :required)))
                  ["systolic" "diastolic"])))))

(deftest the-time-is-a-field-for-a-date-and-a-time-that-asks-for-no-more-than-a-month-back
  (let [tree (page (model :heart-rate))]
    (is (= "Taken at" (hx/words (first (hx/select tree #(and (= :label (hx/tag %)) (= "taken" (:for (hx/attributes %)))))))))
    (is (= {:type "datetime-local" :name "taken" :value "2026-10-13T14:00" :min "2026-09-13T14:00" :max "2026-10-13T14:00"}
           (select-keys (attrs tree "taken") [:type :name :value :min :max]))
        "now on the clinic's clock, and 30 days before it, to pick from")
    (is (= "Now, or up to 30 days ago." (hx/words (hx/by-id tree "taken-hint")))))
  (testing "through a change of the clocks the bounds are still the times on the clock of the clinic"
    (let [tree (page (model :heart-rate :now "2026-10-25T10:00:00Z" :values {:taken "2026-10-25T11:00"}))]
      (is (= {:min "2026-09-25T12:00" :max "2026-10-25T11:00"} (select-keys (attrs tree "taken") [:min :max]))
          "summer time 30 days before, winter time now"))))

(deftest what-was-typed-is-kept
  (let [tree (page (model :blood-pressure :values {:systolic "132" :diastolic "88" :taken "2026-10-12T21:30"}))]
    (is (= ["132" "88" "2026-10-12T21:30"] (map #(:value (attrs tree %)) ["systolic" "diastolic" "taken"]))))
  (testing "and what was not is empty"
    (let [tree (page (model :blood-pressure))]
      (is (= [nil nil] (map #(:value (attrs tree %)) ["systolic" "diastolic"]))))))

(deftest buttons-save-and-cancel
  (let [tree (page (model :heart-rate))]
    (is (= "submit" (:type (hx/attributes (button-named tree "Save the reading")))))
    (is (= "/readings?measurement=heart-rate" (:href (hx/attributes (button-named tree "Cancel")))) "to the readings of what was being recorded")
    (is (= "/readings?measurement=weight" (:href (hx/attributes (button-named (page (model :weight)) "Cancel"))))))
  (testing "there is no way to cancel to a page that is not there"
    (let [tree (page (model :heart-rate) (dissoc links :readings))]
      (is (some? (button-named tree "Save the reading")))
      (is (nil? (button-named tree "Cancel"))))))

;; ---------------------------------------------------------------------------
;; What is wrong

(deftest nothing-is-wrong-with-a-form-that-has-not-been-sent
  (let [tree (page (model :blood-pressure))]
    (is (empty? (alerts tree)))
    (is (nil? (hx/by-id tree "systolic-error")))
    (is (every? #(nil? (:aria-invalid (attrs tree %))) ["systolic" "diastolic" "taken"]))
    (is (every? #(not (contains? (attrs tree %) :autofocus)) ["systolic" "diastolic" "taken"]))))

(deftest a-field-that-is-wrong-says-so-beside-itself-and-is-where-the-cursor-goes
  (let [tree (page (model :blood-pressure :values {:systolic "132" :diastolic "310" :taken "2026-10-13T14:00"}
                          :errors {:diastolic "Must be between 20 and 200 mmHg."} :failure :refused))]
    (is (= "Must be between 20 and 200 mmHg." (hx/words (hx/by-id tree "diastolic-error"))))
    (is (= [nil "true"] (map #(:aria-invalid (attrs tree %)) ["systolic" "diastolic"])) "only the diastolic")
    (is (str/includes? (:aria-describedby (attrs tree "diastolic")) "diastolic-error") "so that it is read with the field")
    (is (= [false true false] (map #(contains? (attrs tree %) :autofocus) ["systolic" "diastolic" "taken"])) "and the cursor is there")
    (testing "and the message at the top says which, in the words of the field"
      (is (= 1 (count (alerts tree))))
      (is (= "alert" (:role (hx/attributes (first (alerts tree))))))
      (is (= "The reading was not savedCheck the diastolic pressure and save it again." (hx/words (first (alerts tree))))))
    (is (= [] (a11y/problems [:main (form/content (model :blood-pressure :values {:systolic "132" :diastolic "310"}
                                                         :errors {:diastolic "Must be between 20 and 200 mmHg."} :failure :refused)
                                                  links)])))))

(deftest the-cursor-goes-to-the-first-field-that-is-wrong-in-the-order-of-the-form
  (let [tree (page (model :blood-pressure :errors {:taken "Must not be in the future." :diastolic "Enter a number." :systolic "Enter a number."}
                          :failure :refused))]
    (is (= [true false false] (map #(contains? (attrs tree %) :autofocus) ["systolic" "diastolic" "taken"])))
    (is (= "The reading was not savedCheck the systolic pressure, the diastolic pressure and the time it was taken and save it again."
           (hx/words (first (alerts tree))))))
  (let [tree (page (model :heart-rate :errors {:taken "Must not be in the future."} :failure :refused))]
    (is (= [false true] (map #(contains? (attrs tree %) :autofocus) ["value" "taken"])))
    (is (= "Must not be in the future." (hx/words (hx/by-id tree "taken-error"))))
    (is (= "true" (:aria-invalid (attrs tree "taken"))))
    (is (= "The reading was not savedCheck the time it was taken and save it again." (hx/words (first (alerts tree)))))))

(deftest the-message-at-the-top-names-the-fields-of-the-measurement-that-is-being-recorded
  (doseq [[kind named] [[:heart-rate "the heart rate"] [:spo2 "the oxygen saturation"] [:glucose "the glucose"] [:weight "the weight"]
                        [:temperature "the temperature"]]]
    (let [tree (page (model kind :errors {:value "Enter a number."} :failure :refused))]
      (is (= (str "The reading was not savedCheck " named " and save it again.") (hx/words (first (alerts tree)))) (name kind)))))

(deftest an-error-for-a-field-the-measurement-does-not-have-is-not-shown-beside-a-field-that-is-not-there
  (let [tree (page (model :heart-rate :errors {:systolic "Enter a number."} :failure :refused))]
    (is (nil? (hx/by-id tree "systolic-error")))
    (is (= "The reading was not savedCheck what you entered and save it again." (hx/words (first (alerts tree)))))))

(deftest a-refused-reading-with-nothing-to-point-at-says-to-check-it-all
  (let [tree (page (model :heart-rate :failure :refused))]
    (is (= 1 (count (alerts tree))))
    (is (= "The reading was not savedCheck what you entered and save it again." (hx/words (first (alerts tree)))))
    (is (every? #(not (contains? (attrs tree %) :autofocus)) ["value" "taken"]))))

(deftest when-the-api-failed-before-the-reading-was-sent-it-was-not-saved
  (let [tree (page (model :heart-rate :values {:value "72" :taken "2026-10-13T14:00"} :failure :unsaved))]
    (is (= "alert" (:role (hx/attributes (first (alerts tree))))))
    (is (= "The reading was not savedThe problem is on our side, not yours. Try again in a moment." (hx/words (first (alerts tree)))))
    (is (= "72" (:value (attrs tree "value"))) "and what was typed is still there")))

(deftest when-the-api-failed-with-the-reading-on-its-way-nobody-knows-if-it-was-saved
  (let [tree (page (model :heart-rate :values {:value "72" :taken "2026-10-13T14:00"} :failure :unconfirmed))]
    (is (= "alert" (:role (hx/attributes (first (alerts tree))))))
    (is (= (str "We could not confirm that your reading was savedThe problem is on our side, not yours. "
                "Look at your readings before you try again, so that it is not recorded twice.")
           (hx/words (first (alerts tree)))))
    (is (= "72" (:value (attrs tree "value"))))
    (is (= [] (a11y/problems [:main (form/content (model :heart-rate :failure :unconfirmed) links)])))))

(deftest a-failure-is-the-message-of-the-failure-whatever-else-is-wrong
  (let [tree (page (model :heart-rate :errors {:value "Enter a number."} :failure :unconfirmed))]
    (is (str/starts-with? (hx/words (first (alerts tree))) "We could not confirm that your reading was saved"))
    (is (= 1 (count (alerts tree))))))

;; ---------------------------------------------------------------------------
;; What was typed is text

(deftest what-a-person-typed-is-text-and-nothing-more
  (let [hostile "\"><script>alert(1)</script>"
        text (html/render [:main (form/content (model :heart-rate :values {:value hostile :taken hostile}
                                                      :errors {:value hostile :taken hostile} :failure :refused)
                                               links)])
        tree (parsed/document text)]
    (is (not (str/includes? text "<script>")))
    (is (= hostile (:value (attrs tree "value"))) "it comes back exactly as it was typed")
    (is (= hostile (hx/words (hx/by-id tree "value-error"))))
    (is (empty? (hx/by-tag tree :script)))))

;; ---------------------------------------------------------------------------
;; The mock-up is this page

(deftest the-mock-up-shows-the-form-as-the-api-refuses-a-diastolic-that-cannot-be-one
  (let [m (portal/new-reading-model)]
    (is (= :blood-pressure (:kind m)))
    (is (= {:diastolic "Must be between 20 and 200 mmHg."} (:errors m)) "what the portal writes of what the api says")
    (is (= (recording/fields :blood-pressure) (recording/fields (:kind m))))
    (let [tree (page m)]
      (is (= "The reading was not savedCheck the diastolic pressure and save it again." (hx/words (first (alerts tree)))))
      (is (= ["132" "310" "2026-10-13T14:00"] (map #(:value (attrs tree %)) ["systolic" "diastolic" "taken"]))))))

(deftest every-state-of-the-form-passes-the-checks-of-accessibility
  (doseq [[what m page-links]
          [["empty" (model :blood-pressure) links]
           ["with no way to cancel" (model :blood-pressure) (dissoc links :readings)]
           ["refused" (portal/new-reading-model) links]
           ["unsaved" (model :weight :failure :unsaved) links]
           ["unconfirmed" (model :temperature :failure :unconfirmed) links]
           ["wrong time" (model :glucose :errors {:taken "Must not be in the future."} :failure :refused) links]
           ["every measurement" (model :spo2 :values {:value "97"}) links]]]
    (testing what
      (is (= [] (a11y/problems [:main (form/content m page-links)]))))))
