(ns pulse.ui.component-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.ui.component :as c]
            [pulse.ui.test.a11y :as a11y]
            [pulse.ui.test.hiccup :as hx]))

;; ---------------------------------------------------------------------------
;; Buttons

(deftest a-button-is-a-button-element-that-does-not-submit-unless-told
  (let [b (c/button "Save")]
    (is (= :button (hx/tag b)))
    (is (= "button" (:type (hx/attributes b))))
    (is (= "Save" (hx/words b)))
    (is (every? (hx/classes b) ["btn" "btn-primary"])))
  (is (= "submit" (:type (hx/attributes (c/button {:type "submit"} "Send"))))))

(deftest the-variants-and-sizes-of-a-button-are-classes-of-daisyui
  (doseq [[variant expected] {:primary "btn-primary" :neutral "btn-neutral" :outline "btn-outline"
                              :subtle "btn-accent" :ghost "btn-ghost" :danger "btn-error" :link "btn-link"}]
    (is (hx/has-class? (c/button {:variant variant} "x") expected) (str variant)))
  (is (hx/has-class? (c/button {:variant :outline} "x") "btn-primary") "an outline button is outlined in the brand colour")
  (doseq [[size expected] {:xs "btn-xs" :sm "btn-sm" :lg "btn-lg"}]
    (is (hx/has-class? (c/button {:size size} "x") expected) (str size)))
  (testing "the default size has no class of its own"
    (is (not-any? #(str/starts-with? % "btn-x") (hx/classes (c/button "x"))))
    (is (not-any? #{"btn-sm" "btn-lg" "btn-xs"} (hx/classes (c/button "x")))))
  (is (hx/has-class? (c/button {:block? true} "x") "btn-block"))
  (testing "a variant or a size that does not exist is an error, not a button with no look"
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) #"no button variant called :shiny" (c/button {:variant :shiny} "x")))
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) #"no button size called :huge" (c/button {:size :huge} "x")))))

(deftest what-a-button-owns-is-not-sent-to-the-element-and-the-rest-is
  (let [a (hx/attributes (c/button {:variant :ghost :size :sm :icon :plus :icon-end :x :loading? false :block? true
                                    :hx-post "/appointments" :data-id "7" :class "mt-2" :aria-label "Add"}
                                   "Add"))]
    (is (not-any? #(contains? a %) [:variant :size :icon :icon-end :loading? :block?]))
    (is (= "/appointments" (:hx-post a)))
    (is (= "7" (:data-id a)))
    (is (= "Add" (:aria-label a)))
    (is (= "btn btn-ghost btn-sm btn-block mt-2" (:class a)) "the component's classes first, the caller's last")))

(deftest a-loading-button-cannot-be-pressed-and-says-it-is-busy
  (let [b (c/button {:loading? true :icon :plus} "Saving")
        a (hx/attributes b)]
    (is (true? (:disabled a)))
    (is (= "true" (:aria-busy a)))
    (is (= 1 (count (hx/select b #(hx/has-class? % "loading-spinner")))))
    (is (empty? (hx/by-tag b :svg)) "the spinner takes the place of the icon")
    (is (= "Saving" (hx/words b))))
  (let [a (hx/attributes (c/button "Save"))]
    (is (not (contains? a :disabled)))
    (is (not (contains? a :aria-busy)))))

(deftest a-button-with-an-href-is-a-link-that-looks-like-one
  (let [b (c/button {:href "/appointments" :variant :outline} "Appointments")
        a (hx/attributes b)]
    (is (= :a (hx/tag b)))
    (is (= "/appointments" (:href a)))
    (is (not (contains? a :type)))
    (is (hx/has-class? b "btn"))))

(deftest icons-go-before-and-after-the-text
  (let [b (c/button {:icon :plus :icon-end :chevron-right} "Add")
        kids (hx/child-nodes b)]
    (is (= [:svg "Add" :svg] [(hx/tag (first kids)) (second kids) (hx/tag (last kids))]))
    (is (every? #(= "true" (:aria-hidden (hx/attributes %))) (hx/by-tag b :svg)) "decorative: the text says it")))

(deftest an-icon-button-needs-a-name
  (let [b (c/icon-button {:icon :x :label "Close" :variant :ghost})
        a (hx/attributes b)]
    (is (= "Close" (:aria-label a)))
    (is (hx/has-class? b "btn-square"))
    (is (not (contains? a :icon)))
    (is (not (contains? a :label)))
    (is (empty? (a11y/problems b))))
  (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) #"needs a :label" (c/icon-button {:icon :x})))
  (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) #"needs a :label" (c/icon-button {:icon :x :label " "}))))

;; ---------------------------------------------------------------------------
;; Badges and tones

(deftest a-badge-is-a-small-label
  (let [b (c/badge {:variant :primary} "New")]
    (is (= :span (hx/tag b)))
    (is (every? (hx/classes b) ["badge" "badge-primary"]))
    (is (= "New" (hx/words b))))
  (is (hx/has-class? (c/badge "x") "badge-neutral"))
  (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) #"no badge variant" (c/badge {:variant :loud} "x"))))

(deftest a-tone-badge-has-an-icon-and-a-word-and-the-colours-of-its-tone
  (doseq [[tone-key label] {:normal "Normal" :elevated "Elevated" :high "High" :critical "Critical" :info "Information"}]
    (let [b (c/tone-badge {:tone tone-key})
          icons (hx/by-tag b :svg)]
      (is (= label (hx/words b)) (str tone-key " has its own word"))
      (is (= 1 (count icons)) (str tone-key " has an icon"))
      (is (every? (hx/classes b) [(str "bg-tone-" (name tone-key) "-soft") (str "text-tone-" (name tone-key) "-ink")
                                  (str "border-tone-" (name tone-key) "-edge")])
          (str tone-key))
      (is (hx/has-class? (first icons) (str "text-tone-" (name tone-key) "-edge")))))
  (testing "the children replace the word, and :icon the icon"
    (let [b (c/tone-badge {:tone :high :icon :bell} "Needs review")]
      (is (= "Needs review" (hx/words b)))
      (is (= (hx/child-nodes (first (hx/by-tag (c/tone-badge {:icon :bell}) :svg)))
             (hx/child-nodes (first (hx/by-tag b :svg)))))))
  (testing "two sizes, and anything else goes through"
    (is (hx/has-class? (c/tone-badge {:tone :normal :size :sm}) "text-xs"))
    (is (hx/has-class? (c/tone-badge {:tone :normal}) "text-sm"))
    (is (= "x" (:data-test (hx/attributes (c/tone-badge {:tone :normal :data-test "x"}))))))
  (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) #"no tone called :loud" (c/tone-badge {:tone :loud}))))

(deftest a-band-badge-is-the-chip-of-the-band-of-a-reading
  (let [b (c/band-badge "very-high")]
    (is (= "Very high" (hx/words b)))
    (is (hx/has-class? b "bg-tone-critical-soft")))
  (is (= "Low" (hx/words (c/band-badge :low))))
  (is (hx/has-class? (c/band-badge "low") "bg-tone-elevated-soft"))
  (is (hx/has-class? (c/band-badge "normal" {:size :sm}) "text-xs"))
  (testing "a reading with no band has no chip"
    (is (nil? (c/band-badge nil)))
    (is (nil? (c/band-badge "unheard-of")))))

;; ---------------------------------------------------------------------------
;; Cards, tiles and facts

(deftest a-card-has-a-header-only-when-it-has-something-to-put-in-it
  (let [card (c/card {:title "Heart rate" :subtitle "Last 24 hours" :actions [:a {:href "#"} "All"]} [:p "body"])]
    (is (= :section (hx/tag card)))
    (is (= ["Heart rate"] (map hx/words (hx/by-tag card :h2))))
    (is (str/includes? (hx/words card) "Last 24 hours"))
    (is (= 1 (count (hx/by-tag card :header))))
    (is (str/includes? (hx/words card) "All"))
    (is (= ["Last 24 hours" "body"] (map hx/words (hx/by-tag card :p)))))
  (is (empty? (hx/by-tag (c/card [:p "only a body"]) :header)))
  (is (= 1 (count (hx/by-tag (c/card {:actions [:b "x"]} "y") :header))) "actions alone make a header")
  (testing "the level of the title keeps the headings of a page in order"
    (is (= 1 (count (hx/by-tag (c/card {:title "T" :heading :h3} "x") :h3))))
    (is (empty? (hx/by-tag (c/card {:title "T" :heading :h3} "x") :h2))))
  (testing "a flush card has no padding in its body"
    (is (some #(hx/has-class? % "px-5") (hx/select (c/card {:title "T"} "x") #(= :div (hx/tag %)))))
    (is (not-any? #(and (hx/has-class? % "px-5") (hx/has-class? % "py-4"))
                  (hx/select (c/card {:title "T" :flush? true} "x") #(= :div (hx/tag %))))))
  (is (hx/has-class? (c/card {:class "mt-4"} "x") "mt-4") "the caller's class is not lost"))

(deftest a-vital-tile-shows-the-reading-and-marks-one-that-is-away-from-normal
  (let [tile (c/vital-tile {:label "Heart rate" :icon :heart-pulse :value "72" :unit "bpm" :band "normal" :at "Today, 08:15"})
        text (hx/words tile)]
    (is (str/includes? text "Heart rate"))
    (is (str/includes? text "72 bpm"))
    (is (str/includes? text "Today, 08:15"))
    (is (str/includes? text "Normal"))
    (is (empty? (hx/select tile #(hx/has-class? % "inset-y-0"))) "a normal reading is quiet: no bar"))
  (let [tile (c/vital-tile {:label "Systolic" :value "172" :unit "mmHg" :band "very-high"})]
    (let [bars (hx/select tile #(hx/has-class? % "inset-y-0"))]
      (is (= 1 (count bars)) "a bar on the edge")
      (is (hx/has-class? (first bars) "bg-tone-critical-edge"))
      (is (= "true" (:aria-hidden (hx/attributes (first bars)))) "the chip says it in words"))
    (is (hx/has-class? tile "overflow-hidden") "the bar is clipped to the rounded corners")
    (is (str/includes? (hx/words tile) "Very high")))
  (testing "no band, no chip; no unit or time, no empty element"
    (let [tile (c/vital-tile {:label "Weight" :value "82.4"})]
      (is (empty? (hx/select tile #(hx/has-class? % "rounded-full"))))
      (is (= 2 (count (hx/by-tag tile :p))) "the label and the value, and no line for a time")
      (is (empty? (hx/by-tag (first (hx/by-tag tile :p)) :svg)) "no icon was given")
      (is (= 1 (count (hx/by-tag (second (hx/by-tag tile :p)) :span))) "the value has no unit"))))

(deftest facts-are-terms-and-details
  (let [dl (c/facts [["Born" "14 March 1968"] ["Record" "PULSE-0001"]])]
    (is (= :dl (hx/tag dl)))
    (is (= ["Born" "Record"] (map hx/words (hx/by-tag dl :dt))))
    (is (= ["14 March 1968" "PULSE-0001"] (map hx/words (hx/by-tag dl :dd)))))
  (is (hx/has-class? (c/facts {:class "mt-3"} []) "mt-3")))

;; ---------------------------------------------------------------------------
;; Messages and states

(deftest an-alert-has-the-icon-and-the-colours-of-its-tone
  (let [a (c/alert {:tone :critical :title "Blood pressure" :actions [:a {:href "#"} "Review"]} "Stage 2 in 2 of the last 3.")]
    (is (every? (hx/classes a) ["bg-tone-critical-soft" "text-tone-critical-ink" "border-tone-critical-edge"]))
    (is (= 1 (count (hx/select a #(hx/has-class? % "bg-tone-critical-edge")))) "a bar in the colour of the tone")
    (is (= 1 (count (hx/by-tag a :svg))))
    (is (str/includes? (hx/words a) "Blood pressure"))
    (is (str/includes? (hx/words a) "Stage 2 in 2 of the last 3."))
    (is (str/includes? (hx/words a) "Review")))
  (testing "a role is the caller's to give, for an alert that appears on a page that is already open"
    (is (= "alert" (:role (hx/attributes (c/alert {:tone :high :role "alert"} "x")))))
    (is (nil? (:role (hx/attributes (c/alert {:tone :high} "x"))))))
  (is (hx/has-class? (c/alert "no tone given") "bg-tone-info-soft") "information is the default")
  (is (nil? (first (hx/by-tag (c/alert {:tone :info}) :p))) "no title, no paragraph"))

(deftest an-empty-state-says-what-is-missing-and-what-to-do
  (let [e (c/empty-state {:icon :bell-off :title "No alerts" :actions [:a {:href "/"} "Go back"]} "Nothing needs your attention.")]
    (is (str/includes? (hx/words e) "No alerts"))
    (is (str/includes? (hx/words e) "Nothing needs your attention."))
    (is (str/includes? (hx/words e) "Go back"))
    (is (= 1 (count (hx/by-tag e :svg)))))
  (is (= 1 (count (hx/by-tag (c/empty-state "x") :svg))) "an icon by default"))

(deftest the-title-of-an-empty-state-is-a-paragraph-unless-it-is-given-a-level
  (let [titles (fn [e] (hx/select e #(hx/has-class? % "font-semibold")))]
    (is (= [:p] (map hx/tag (titles (c/empty-state {:title "Nothing"})))))
    (doseq [level [:h1 :h2 :h3]]
      (let [e (c/empty-state {:title "We cannot find that page" :heading level})]
        (is (= [level] (map hx/tag (titles e))) (str level))
        (is (= "We cannot find that page" (hx/words (first (titles e)))))
        (is (not (contains? (hx/attributes e) :heading)) "the level is not an attribute of the box")))
    (testing "a page that is nothing but this has the one h1 that the checker wants"
      (is (empty? (a11y/problems [:main (c/empty-state {:title "T" :heading :h1} "text")]))))))

(deftest a-spinner-is-announced-and-a-skeleton-is-not
  (let [s (c/spinner)]
    (is (= "status" (:role (hx/attributes s))))
    (is (= "Loading" (hx/words s))))
  (is (= "Loading appointments" (hx/words (c/spinner {:label "Loading appointments"}))))
  (let [k (c/skeleton {:class "h-8 w-32"})]
    (is (= "true" (:aria-hidden (hx/attributes k))))
    (is (every? (hx/classes k) ["skeleton" "h-8" "w-32"]))))

;; ---------------------------------------------------------------------------
;; People

(deftest initials-are-the-first-letters-of-the-first-and-the-last-word
  (is (= "AG" (c/initials "Ana García")))
  (is (= "S" (c/initials "Sofía")))
  (is (= "MC" (c/initials "María José de la Cruz")))
  (is (= "AG" (c/initials "  ana   garcía  ")))
  (is (= "" (c/initials "   ")))
  (is (= "" (c/initials nil))))

(deftest an-avatar-is-decorative-because-the-name-is-beside-it
  (let [a (c/avatar {:name "Ana García" :size :lg})]
    (is (= "AG" (hx/words a)))
    (is (= "true" (:aria-hidden (hx/attributes a))))
    (is (hx/has-class? a "size-14"))
    (is (not (contains? (hx/attributes a) :name))))
  (is (hx/has-class? (c/avatar {:name "Ana García"}) "size-10"))
  (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) #"no avatar size" (c/avatar {:name "Ana García" :size :huge}))))

;; ---------------------------------------------------------------------------
;; Forms

(deftest a-text-field-ties-its-label-its-hint-and-its-error-to-the-input
  (let [f (c/text-field {:id "email" :label "Email" :type "email" :hint "We write only about your appointments."
                         :name "email" :required true :autocomplete "email"})
        input (first (hx/by-tag f :input))
        a (hx/attributes input)
        label (first (hx/by-tag f :label))]
    (is (= "email" (:for (hx/attributes label))))
    (is (= "Email" (hx/words label)))
    (is (= "email" (:id a)))
    (is (= "email" (:type a)))
    (is (= "email" (:name a)))
    (is (true? (:required a)))
    (is (= "email" (:autocomplete a)) "other attributes reach the input")
    (is (= "email-hint" (:aria-describedby a)))
    (is (= "We write only about your appointments." (hx/words (hx/by-id f "email-hint"))))
    (is (not (contains? a :aria-invalid)))
    (is (not (contains? a :label)))
    (is (not (contains? a :hint)))
    (is (every? (hx/classes input) ["input" "w-full"]))
    (is (empty? (a11y/problems f)))))

(deftest a-field-with-an-error-says-so-in-words-an-icon-and-the-attributes
  (let [f (c/text-field {:id "weight" :label "Weight" :hint "In kilograms." :error "Enter a number between 20 and 400."})
        input (first (hx/by-tag f :input))
        a (hx/attributes input)
        message (hx/by-id f "weight-error")]
    (is (= "true" (:aria-invalid a)))
    (is (= "weight-hint weight-error" (:aria-describedby a)))
    (is (hx/has-class? input "input-error"))
    (is (= "Enter a number between 20 and 400." (hx/words message)))
    (is (= 1 (count (hx/by-tag message :svg))) "not by colour alone")
    (is (hx/has-class? message "text-tone-critical-ink"))
    (is (empty? (a11y/problems f))))
  (testing "an error without a hint"
    (is (= "weight-error" (:aria-describedby (hx/attributes (first (hx/by-tag (c/text-field {:id "weight" :label "Weight" :error "x"}) :input)))))))
  (testing "optional is said in words"
    (is (str/includes? (hx/words (c/text-field {:id "n" :label "Notes" :optional? true})) "Notes (optional)"))
    (is (not (str/includes? (hx/words (c/text-field {:id "n" :label "Notes"})) "optional")))))

(deftest a-select-lists-its-options-and-marks-the-one-chosen
  (let [f (c/select-field {:id "unit" :label "Unit" :value "kg" :options [["kg" "Kilograms"] ["lb" "Pounds"]]})
        options (hx/by-tag f :option)]
    (is (= ["Kilograms" "Pounds"] (map hx/words options)))
    (is (= ["kg" "lb"] (map #(:value (hx/attributes %)) options)))
    (is (= [true nil] (map #(:selected (hx/attributes %)) options)))
    (is (hx/has-class? (first (hx/by-tag f :select)) "select"))
    (is (not (contains? (hx/attributes (first (hx/by-tag f :select))) :options)))
    (is (empty? (a11y/problems f))))
  (is (hx/has-class? (first (hx/by-tag (c/select-field {:id "u" :label "U" :error "x" :options []}) :select)) "select-error")))

(deftest a-textarea-has-rows-and-its-text-inside
  (let [f (c/textarea-field {:id "note" :label "Note" :value "Felt dizzy."})
        t (first (hx/by-tag f :textarea))]
    (is (= 4 (:rows (hx/attributes t))))
    (is (= "Felt dizzy." (hx/words t)))
    (is (hx/has-class? t "textarea"))
    (is (empty? (a11y/problems f))))
  (is (= 8 (:rows (hx/attributes (first (hx/by-tag (c/textarea-field {:id "n" :label "N" :rows 8}) :textarea)))))))

(deftest a-checkbox-and-a-switch-have-their-label-beside-them
  (let [f (c/checkbox-field {:id "remind" :name "remind" :label "Remind me" :hint "A day before." :checked true})
        input (first (hx/by-tag f :input))]
    (is (= "checkbox" (:type (hx/attributes input))))
    (is (true? (:checked (hx/attributes input))))
    (is (= "remind-hint" (:aria-describedby (hx/attributes input))))
    (is (= "Remind me" (hx/words (first (hx/by-tag f :label)))))
    (is (empty? (a11y/problems f))))
  (let [f (c/toggle-field {:id "dark" :label "Dark theme"})
        input (first (hx/by-tag f :input))]
    (is (= "switch" (:role (hx/attributes input))))
    (is (= "checkbox" (:type (hx/attributes input))))
    (is (hx/has-class? input "toggle"))
    (is (empty? (a11y/problems f)))))

(deftest a-radio-group-is-a-fieldset-with-a-legend
  (let [f (c/radio-group {:name "language" :legend "Language" :value "es" :hint "You can change it later."
                          :options [["en" "English"] ["es" "Español"]]})
        radios (hx/select f #(= "radio" (:type (hx/attributes %))))]
    (is (= :fieldset (hx/tag f)))
    (is (= "Language" (hx/words (first (hx/by-tag f :legend)))))
    (is (= ["language-en" "language-es"] (map #(:id (hx/attributes %)) radios)))
    (is (= [nil true] (map #(:checked (hx/attributes %)) radios)))
    (is (every? #(= "language" (:name (hx/attributes %))) radios))
    (is (str/includes? (hx/words f) "You can change it later."))
    (is (empty? (a11y/problems f)))))

;; ---------------------------------------------------------------------------
;; Tables and navigation

(def ^:private columns
  [{:key :when :label "When" :header? true}
   {:key :reading :label "Reading" :align :end}
   {:key :band :label "Band" :align :center}])

(def ^:private rows
  [{:when "Oct 16, 08:15" :reading "138/86" :band [:strong "Elevated"]}
   {:when "Oct 15, 20:02" :reading "140/88"}])

(deftest a-table-has-a-caption-column-headings-and-row-headings
  (let [t (c/data-table {:caption "Blood pressure readings" :columns columns :rows rows})
        table (first (hx/by-tag t :table))]
    (is (= "Blood pressure readings" (hx/words (first (hx/by-tag t :caption)))))
    (is (hx/has-class? (first (hx/by-tag t :caption)) "sr-only") "read, not shown")
    (is (= ["When" "Reading" "Band"] (map hx/words (hx/select (first (hx/by-tag t :thead)) #(= :th (hx/tag %))))))
    (is (every? #(= "col" (:scope (hx/attributes %))) (hx/select (first (hx/by-tag t :thead)) #(= :th (hx/tag %)))))
    (let [body (first (hx/by-tag t :tbody))]
      (is (= 2 (count (hx/by-tag body :tr))))
      (is (= ["Oct 16, 08:15" "Oct 15, 20:02"] (map hx/words (hx/by-tag body :th))) "the first column heads its row")
      (is (every? #(= "row" (:scope (hx/attributes %))) (hx/by-tag body :th)))
      (is (= ["138/86" "Elevated" "140/88" ""] (mapv hx/words (hx/by-tag body :td))) "hiccup in a cell; a missing one is empty"))
    (is (some? table))
    (is (empty? (a11y/problems t)))))

(deftest a-table-aligns-its-columns-and-scrolls-inside-a-region-a-keyboard-can-reach
  (let [t (c/data-table {:caption "Readings" :columns columns :rows rows :dense? true})
        outer (hx/attributes t)]
    (is (= "region" (:role outer)))
    (is (= "Readings" (:aria-label outer)))
    (is (= "0" (:tabindex outer)))
    (is (hx/has-class? t "overflow-x-auto"))
    (is (hx/has-class? (first (hx/by-tag t :table)) "table-sm"))
    (is (= [true false] (map #(hx/has-class? % "text-right") (take 2 (rest (hx/select (first (hx/by-tag t :thead)) #(= :th (hx/tag %))))))))
    (is (hx/has-class? (first (hx/select (first (hx/by-tag t :tbody)) #(= :td (hx/tag %)))) "text-right")))
  (testing "a caption can be shown"
    (is (not (hx/has-class? (first (hx/by-tag (c/data-table {:caption "C" :columns columns :rows [] :show-caption? true}) :caption)) "sr-only"))))
  (testing "an alignment that does not exist is an error"
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) #"no alignment"
                          (c/data-table {:caption "C" :columns [{:key :a :label "A" :align :middle}] :rows []})))))

(deftest tab-links-mark-the-current-one-for-a-screen-reader-too
  (let [n (c/tab-nav {:label "Patient sections"
                      :items [{:href "/p/1" :label "Overview" :current? true}
                              {:href "/p/1/vitals" :label "Vitals"}]})
        links (hx/by-tag n :a)]
    (is (= "Patient sections" (:aria-label (hx/attributes n))))
    (is (= ["Overview" "Vitals"] (map hx/words links)))
    (is (= ["page" nil] (map #(:aria-current (hx/attributes %)) links)))
    (is (hx/has-class? (first links) "border-primary"))
    (is (hx/has-class? (second links) "border-transparent"))
    (is (empty? (a11y/problems n)))))

(deftest a-page-header-has-the-one-h1
  (let [h (c/page-header {:title "Alerts" :description "What needs a decision." :actions [:a {:href "#"} "Export"]})]
    (is (= ["Alerts"] (map hx/words (hx/by-tag h :h1))))
    (is (str/includes? (hx/words h) "What needs a decision."))
    (is (str/includes? (hx/words h) "Export")))
  (is (= 1 (count (hx/by-tag (c/page-header {:title "T"}) :h1)))))

;; ---------------------------------------------------------------------------
;; All of them together

(deftest the-components-together-have-no-accessibility-problem-a-program-can-see
  (let [page [:main
              (c/page-header {:title "Ana García" :description "PULSE-0001" :actions (c/button {:variant :outline :icon :calendar-plus} "Book")})
              (c/alert {:tone :critical :title "Blood pressure"} "Stage 2 in 2 of the last 3 readings.")
              (c/vital-tile {:label "Systolic" :icon :activity :value "172" :unit "mmHg" :band "very-high" :at "Today"})
              (c/card {:title "Details" :subtitle "From the record"}
                      (c/facts [["Born" "1968"]])
                      (c/text-field {:id "a" :label "A" :hint "h" :error "e"})
                      (c/select-field {:id "b" :label "B" :options [["x" "X"]]})
                      (c/textarea-field {:id "c" :label "C"})
                      (c/checkbox-field {:id "d" :label "D"})
                      (c/toggle-field {:id "e" :label "E"})
                      (c/radio-group {:name "f" :legend "F" :options [["1" "One"]]}))
              (c/tab-nav {:label "Sections" :items [{:href "#" :label "One" :current? true}]})
              (c/data-table {:caption "Readings" :columns columns :rows rows})
              (c/empty-state {:title "Nothing" :actions (c/button {:variant :link} "Back")} "Text")
              (c/spinner)
              (c/skeleton)
              (c/avatar {:name "Ana García"})
              (c/icon-button {:icon :menu :label "Menu"})
              (c/tone-badge {:tone :high})
              (c/band-badge "high")
              (c/badge "x")]]
    (is (= [] (a11y/problems page)))))
