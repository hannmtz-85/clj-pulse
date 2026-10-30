(ns pulse.ui.component-interface-test
  "The components that the interfaces need beyond the style tile (ADR-0026): figures, people, lists and dates,
  navigation and search, dialogs and the mark, and what changed in the older components when the mock-ups of the
  pages were made."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.ui.component :as c]
            [pulse.ui.test.a11y :as a11y]
            [pulse.ui.test.hiccup :as hx]))

;; ---------------------------------------------------------------------------
;; Figures, people, lists and dates

(deftest a-stat-is-a-figure-with-its-label-its-hint-and-the-tone-it-asks-for
  (let [s (c/stat {:label "Active alerts" :value "2" :hint "1 acknowledged today" :icon :bell-ring :tone :critical})]
    (is (= ["Active alerts" "2" "1 acknowledged today"] (map hx/words (hx/by-tag s :p))))
    (is (every? (hx/classes s) ["rounded-box" "border" "bg-base-100" "shadow-card"]))
    (is (hx/has-class? (second (hx/by-tag s :p)) "text-tone-critical-ink") "the figure takes the tone")
    (is (hx/has-class? (first (hx/by-tag s :svg)) "text-tone-critical-edge") "and so does the icon")
    (is (empty? (a11y/problems s))))
  (testing "with no tone, hint or icon there is only the label and the figure"
    (let [s (c/stat {:label "Patients" :value "4"})]
      (is (= ["Patients" "4"] (map hx/words (hx/by-tag s :p))))
      (is (empty? (hx/by-tag s :svg)))
      (is (not-any? #(str/starts-with? % "text-tone") (hx/classes (second (hx/by-tag s :p))))))))

(deftest what-a-stat-owns-is-not-sent-to-the-element
  (let [a (hx/attributes (c/stat {:label "L" :value "1" :hint "h" :icon :plus :tone :high :id "patients" :class "mt-2"}))]
    (is (not-any? #(contains? a %) [:label :value :hint :icon :tone]))
    (is (= "patients" (:id a)))
    (is (str/ends-with? (:class a) " mt-2"))))

(deftest a-person-is-an-avatar-a-name-and-a-line-under-it
  (let [p (c/person {:name "Dr. Elena Rivera" :detail "Internal medicine" :size :lg})]
    (is (= ["Dr. Elena Rivera" "Internal medicine"] (map hx/words (hx/by-tag p :p))))
    (is (hx/has-class? (first (hx/select p #(= "true" (:aria-hidden (hx/attributes %))))) "size-14") "the size is the avatar's")
    (is (= "DR" (hx/words (first (hx/select p #(hx/has-class? % "rounded-full"))))))
    (is (hx/has-class? (first (hx/by-tag p :p)) "truncate") "a long name does not push the rest away")
    (is (empty? (a11y/problems p))))
  (is (= 1 (count (hx/by-tag (c/person {:name "Ana García"}) :p))) "no detail, no second line")
  (is (hx/has-class? (first (hx/select (c/person {:name "Ana García"}) #(= "true" (:aria-hidden (hx/attributes %))))) "size-10")))

(deftest a-list-has-a-line-between-its-rows
  (let [l (c/list-group {:aria-label "Appointments"} (c/list-row "One") (c/list-row "Two"))]
    (is (= :ul (hx/tag l)))
    (is (= "Appointments" (:aria-label (hx/attributes l))))
    (is (hx/has-class? l "divide-y"))
    (is (= ["One" "Two"] (map hx/words (hx/by-tag l :li)))))
  (is (= 2 (count (hx/by-tag (c/list-group (for [x ["a" "b"]] (c/list-row x))) :li))) "the rows of a for"))

(deftest a-row-has-something-at-the-start-its-text-and-something-at-the-end
  (let [r (c/list-row {:leading (c/avatar {:name "Ana García"}) :trailing (c/badge "Confirmed")}
                      [:p "Hypertension follow-up"] [:p "Wed 14 Oct"])
        [lead text trail] (hx/child-nodes r)]
    (is (= :li (hx/tag r)))
    (is (= "AG" (hx/words lead)))
    (is (= ["Hypertension follow-up" "Wed 14 Oct"] (map hx/words (hx/by-tag text :p))))
    (is (every? (hx/classes text) ["min-w-0" "flex-1"]) "the text gives way")
    (is (every? (hx/classes trail) ["shrink-0" "flex-wrap" "max-w-full"]) "the end does not, and wraps inside the row")
    (is (hx/has-class? r "flex-wrap") "and a row that is too narrow wraps"))
  (testing "a row with only text has only text"
    (let [r (c/list-row [:p "Only"])]
      (is (= 1 (count (hx/child-nodes r))))
      (is (= "Only" (hx/words r))))))

(deftest the-end-of-a-row-goes-under-the-text-only-when-it-is-asked-to
  (let [wraps (c/list-row {:trailing (c/button "Cancel") :trailing-wraps? true} [:p "x"])
        plain (c/list-row {:trailing (c/button "Cancel")} [:p "x"])
        text-of (fn [r] (first (hx/select r #(hx/has-class? % "min-w-0"))))]
    (is (hx/has-class? (text-of wraps) "basis-44"))
    (is (not (hx/has-class? (text-of plain) "basis-44")))
    (is (not (contains? (hx/attributes wraps) :trailing-wraps?)) "it is not an attribute of the element")
    (is (not-any? #(contains? (hx/attributes wraps) %) [:leading :trailing]))))

(deftest a-date-block-is-a-small-calendar-page-hidden-from-a-screen-reader
  (let [d (c/date-block {:month "Oct" :day "14"})]
    (is (= ["Oct" "14"] (map hx/words (hx/by-tag d :span))))
    (is (every? (hx/classes d) ["bg-accent" "text-accent-content" "w-14" "min-h-14" "rounded-field"]))
    (is (= "true" (:aria-hidden (hx/attributes d))) "the date is written beside it")
    (is (empty? (hx/by-tag d :svg))))
  (testing "for what has no day yet it shows an icon on a paler page"
    (let [d (c/date-block {:icon :clock})]
      (is (= 1 (count (hx/by-tag d :svg))))
      (is (= "" (hx/words d)))
      (is (every? (hx/classes d) ["bg-base-200" "text-muted" "w-14" "min-h-14"]))
      (is (not (hx/has-class? d "bg-accent")))
      (is (= "true" (:aria-hidden (hx/attributes d)))))))

(deftest an-icon-disc-is-a-round-icon-for-the-start-of-a-row
  (let [d (c/icon-disc {:icon :pill})]
    (is (every? (hx/classes d) ["rounded-full" "size-10" "bg-accent" "text-accent-content" "inline-grid" "shrink-0"]))
    (is (= "true" (:aria-hidden (hx/attributes d))))
    (is (= 1 (count (hx/by-tag d :svg))))
    (is (= 20 (:width (hx/attributes (first (hx/by-tag d :svg)))))))
  (is (hx/has-class? (c/icon-disc {:icon :pill :size :lg}) "size-12"))
  (is (every? (hx/classes (c/icon-disc {:icon :pill :variant :muted})) ["bg-base-200" "text-muted"]))
  (testing "what it owns is not sent to the element"
    (is (not-any? #(contains? (hx/attributes (c/icon-disc {:icon :pill :size :lg :variant :muted :id "x"})) %) [:icon :size :variant])))
  (testing "a size or a variant that does not exist is an error"
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) #"no icon disc size called :huge"
                          (c/icon-disc {:icon :pill :size :huge})))
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) #"no icon disc variant called :loud"
                          (c/icon-disc {:icon :pill :variant :loud})))))

;; ---------------------------------------------------------------------------
;; Navigation and search

(deftest pills-filter-a-list-and-mark-the-current-one-for-a-screen-reader-too
  (let [n (c/pill-nav {:label "Filter the panel"
                       :items [{:href "?all" :label "All" :count 4 :current? true}
                               {:href "?alerts" :label "With active alerts" :count 2}
                               {:href "?none" :label "Normal"}]})
        links (hx/by-tag n :a)]
    (is (= "Filter the panel" (:aria-label (hx/attributes n))))
    (is (= ["All4" "With active alerts2" "Normal"] (map hx/words links)))
    (is (= ["?all" "?alerts" "?none"] (map #(:href (hx/attributes %)) links)))
    (is (= ["page" nil nil] (map #(:aria-current (hx/attributes %)) links)))
    (is (hx/has-class? (first links) "bg-accent"))
    (is (hx/has-class? (second links) "bg-base-100"))
    (is (= ["4" "2"] (map hx/words (hx/select n #(hx/has-class? % "tabular-nums")))) "a count only when there is one")
    (is (empty? (a11y/problems n)))))

(deftest a-pill-is-as-high-as-its-size-says
  (let [pill (fn [size] (first (hx/by-tag (c/pill-nav {:label "L" :size size :items [{:href "#" :label "A"}]}) :a)))]
    (is (every? (hx/classes (pill :md)) ["px-3.5" "py-1.5" "text-sm"]))
    (is (every? (hx/classes (pill :lg)) ["min-h-11" "px-4" "text-base"]) "44 px, for a finger"))
  (is (every? (hx/classes (first (hx/by-tag (c/pill-nav {:label "L" :items [{:href "#" :label "A"}]}) :a))) ["px-3.5" "py-1.5"])
      "a desk size by default")
  (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) #"no pill size called :huge"
                        (c/pill-nav {:label "L" :size :huge :items [{:href "#" :label "A"}]}))))

(deftest breadcrumbs-end-in-the-page-itself-which-has-no-link
  (let [b (c/breadcrumbs {:items [{:href "/panel" :label "Panel"} {:href "/patients" :label "Patients"} {:label "Ana García"}]})
        items (hx/by-tag b :li)]
    (is (= "Breadcrumb" (:aria-label (hx/attributes b))))
    (is (= 1 (count (hx/by-tag b :ol))))
    (is (= ["Panel" "Patients" "Ana García"] (map hx/words items)))
    (is (= ["/panel" "/patients"] (map #(:href (hx/attributes %)) (hx/by-tag b :a))))
    (is (= [nil nil "page"] (map #(:aria-current (hx/attributes %)) items)))
    (is (= [1 1 0] (map #(count (hx/by-tag % :svg)) items)) "a chevron after each link and none after the page")
    (is (every? #(hx/has-class? % "min-h-6") (hx/by-tag b :a)) "a link is a target of 24 px at least, and a line of small type is not")
    (is (hx/has-class? (last items) "font-medium"))
    (is (empty? (a11y/problems b))))
  (testing "a path of one is the page"
    (let [b (c/breadcrumbs {:items [{:label "Panel"}]})]
      (is (empty? (hx/by-tag b :a)))
      (is (= ["page"] (map #(:aria-current (hx/attributes %)) (hx/by-tag b :li)))))))

(deftest a-search-field-is-a-labelled-input-with-a-magnifying-glass
  (let [s (c/search-field {:id "search" :name "q" :label "Search patients" :placeholder "Search patients" :value "ana"})
        input (first (hx/by-tag s :input))
        a (hx/attributes input)]
    (is (= :label (hx/tag s)))
    (is (= "search" (:type a)))
    (is (= "Search patients" (:aria-label a)) "there is no visible label")
    (is (= ["search" "q" "Search patients" "ana"] ((juxt :id :name :placeholder :value) a)))
    (is (not (contains? a :label)))
    (is (hx/has-class? input "grow"))
    (is (= 1 (count (hx/by-tag s :svg))))
    (is (empty? (a11y/problems s)))))

(deftest the-menu-of-a-person-opens-without-a-script
  (let [m (c/user-menu {:name "Ana García" :items [{:href "/profile" :label "Profile" :icon :user} {:href "/logout" :label "Sign out"}]})
        summary (first (hx/by-tag m :summary))]
    (is (= :details (hx/tag m)))
    (is (every? (hx/classes m) ["dropdown" "dropdown-end"]))
    (is (= "Account menu of Ana García" (:aria-label (hx/attributes summary))))
    (is (= "AG" (hx/words (first (hx/select summary #(hx/has-class? % "rounded-full"))))))
    (is (= "Ana García" (hx/words (first (hx/select summary #(hx/has-class? % "sm:inline"))))))
    (is (= ["Profile" "Sign out"] (map hx/words (hx/by-tag m :a))))
    (is (= ["/profile" "/logout"] (map #(:href (hx/attributes %)) (hx/by-tag m :a))))
    (is (= [1 0] (map #(count (hx/by-tag % :svg)) (hx/by-tag m :a))) "an icon when it has one")
    (is (not-any? #(contains? (hx/attributes m) %) [:name :items :name-class]))
    (is (empty? (a11y/problems m)))))

(def ^:private menu-with-a-form
  (c/user-menu {:name "Ana García"
                :items [{:href "/profile" :label "Profile" :icon :user}
                        {:id "sign-out" :label "Sign out" :icon :log-out :action "/sign-out"
                         :fields {"__anti-forgery-token" "tok-en_1" "other" "a\"b"}}]}))

(deftest an-item-of-the-menu-that-changes-something-is-a-button-and-not-a-link
  (let [ul (first (hx/by-tag menu-with-a-form :ul))
        [profile sign-out] (hx/by-tag ul :li)
        button (first (hx/by-tag sign-out :button))]
    (testing "it is an item of the list, as a link is, with its icon and its words"
      (is (= 2 (count (hx/by-tag ul :li))))
      (is (= :a (hx/tag (first (hx/child-nodes profile)))))
      (is (= :button (hx/tag (first (hx/child-nodes sign-out)))))
      (is (= "Sign out" (hx/words button)))
      (is (= 1 (count (hx/by-tag button :svg)))))
    (testing "it submits its form, which is told apart by the id of the item"
      (is (= {:type "submit" :form "account-sign-out"} (hx/attributes button))))
    (testing "and no address that a page could send a person to leads to it"
      (is (= ["/profile"] (map #(:href (hx/attributes %)) (hx/by-tag menu-with-a-form :a)))))))

(deftest the-form-of-an-item-is-a-post-beside-the-list-with-its-hidden-fields
  (let [forms (hx/by-tag menu-with-a-form :form)
        form (first forms)]
    (is (= 1 (count forms)) "one for the item that posts, none for a link")
    (is (empty? (hx/by-tag (first (hx/by-tag menu-with-a-form :ul)) :form)) "not in the list, where it would take the place of an item")
    (is (= {:id "account-sign-out" :method "post" :action "/sign-out"} (hx/attributes form)))
    (is (= #{{:type "hidden" :name "__anti-forgery-token" :value "tok-en_1"} {:type "hidden" :name "other" :value "a\"b"}}
           (set (map hx/attributes (hx/by-tag form :input)))))
    (is (empty? (a11y/problems menu-with-a-form)) "every id is there once, and what points at one finds it")))

(deftest a-menu-with-no-item-that-posts-has-no-form
  (is (empty? (hx/by-tag (c/user-menu {:name "A B" :items [{:href "/x" :label "X"}]}) :form)))
  (is (empty? (hx/by-tag (c/user-menu {:name "A B" :items []}) :form))))

(deftest an-item-that-posts-needs-an-id-to-be-tied-to-its-form
  (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) #"needs an :id: \"Sign out\""
                        (c/user-menu {:name "A B" :items [{:label "Sign out" :action "/sign-out"}]})))
  (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) #"needs an :id"
                        (c/user-menu {:name "A B" :items [{:id "" :label "Sign out" :action "/sign-out"}]}))))

(deftest an-item-of-the-menu-can-be-a-button-that-calls-a-function
  (let [sign-out (fn [])
        m (c/user-menu {:name "Elena Rivera" :items [{:href "/profile" :label "Profile" :icon :user}
                                                     {:label "Sign out" :icon :log-out :on-click sign-out}]})
        [profile out] (hx/by-tag (first (hx/by-tag m :ul)) :li)
        button (first (hx/by-tag out :button))]
    (testing "it is an item of the list, as a link is, with its icon and its words"
      (is (= 2 (count (hx/by-tag (first (hx/by-tag m :ul)) :li))))
      (is (= :a (hx/tag (first (hx/child-nodes profile)))))
      (is (= :button (hx/tag (first (hx/child-nodes out)))))
      (is (= "Sign out" (hx/words button)))
      (is (= 1 (count (hx/by-tag button :svg)))))
    (testing "it is a button of its own, which submits no form, and it is the function it is given"
      (is (= {:type "button" :on-click sign-out} (hx/attributes button))))
    (testing "no address leads to it, and no form is made for it"
      (is (= ["/profile"] (map #(:href (hx/attributes %)) (hx/by-tag m :a))))
      (is (empty? (hx/by-tag m :form))))
    (is (empty? (a11y/problems m)))))

(deftest an-item-of-the-menu-is-a-link-a-form-or-a-button-never-two
  (doseq [item [{:label "Out" :href "/out" :on-click (fn [])}
                {:id "out" :label "Out" :action "/out" :on-click (fn [])}]]
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) #"a link, a form or a button, not two of them: \"Out\""
                          (c/user-menu {:name "A B" :items [{:href "/profile" :label "Profile"} item]}))
        (pr-str (keys item)))))

(deftest a-screen-that-shows-the-person-elsewhere-hides-the-name-of-the-menu
  (let [name-of (fn [m] (first (hx/select m #(hx/has-class? % "sm:inline"))))]
    (is (not (hx/has-class? (name-of (c/user-menu {:name "A B" :items []})) "lg:hidden")))
    (is (hx/has-class? (name-of (c/user-menu {:name "A B" :items [] :name-class "lg:hidden"})) "lg:hidden"))
    (is (true? (:open (hx/attributes (c/user-menu {:name "A B" :items [] :open true})))) "an attribute of the element goes through")))

;; ---------------------------------------------------------------------------
;; Dialogs and the mark

(deftest a-dialog-asks-for-a-decision-and-is-named-by-its-title
  (let [d (c/dialog {:id "cancel" :title "Cancel this appointment?" :actions (list (c/button "Keep") (c/button {:variant :danger} "Cancel"))}
                    [:p "Hypertension follow-up"])
        a (hx/attributes d)]
    (is (= :dialog (hx/tag d)))
    (is (= "cancel" (:id a)))
    (is (= "cancel-title" (:aria-labelledby a)))
    (is (= "true" (:aria-modal a)))
    (is (hx/has-class? d "modal"))
    (is (= "Cancel this appointment?" (hx/words (hx/by-id d "cancel-title"))))
    (is (= :h2 (hx/tag (hx/by-id d "cancel-title"))))
    (is (str/includes? (hx/words (first (hx/select d #(hx/has-class? % "modal-box")))) "Hypertension follow-up"))
    (is (= ["Keep" "Cancel"] (map hx/words (hx/by-tag (first (hx/select d #(hx/has-class? % "modal-action"))) :button))))
    (is (= 1 (count (hx/select d #(hx/has-class? % "modal-backdrop")))))
    (is (not-any? #(contains? a %) [:title :actions :open?]))
    (is (not (contains? a :open)) "closed unless it is told to be open")
    (is (not (hx/has-class? d "modal-open")))
    (is (empty? (a11y/problems d)))))

(deftest an-open-dialog-is-shown-over-the-page
  (let [d (c/dialog {:id "x" :title "T" :open? true} "Text")]
    (is (true? (:open (hx/attributes d))))
    (is (hx/has-class? d "modal-open")))
  (testing "its buttons are one above the other on a phone, with the one that decides first, and side by side after"
    (let [actions (first (hx/select (c/dialog {:id "x" :title "T"} "Text") #(hx/has-class? % "modal-action")))]
      (is (every? (hx/classes actions) ["flex-col-reverse" "sm:flex-row" "sm:flex-wrap"])))))

(deftest the-mark-is-the-heart-and-the-name
  (let [l (c/logo)]
    (is (= :span (hx/tag l)))
    (is (= "Pulse" (hx/words l)))
    (is (= 1 (count (hx/by-tag l :svg))))
    (is (every? (hx/classes l) ["inline-flex" "items-center" "gap-2"])))
  (testing "it is a link when it has an address"
    (is (= :a (hx/tag (c/logo {:href "/home"}))))
    (is (= "/home" (:href (hx/attributes (c/logo {:href "/home"}))))))
  (testing "the name can be hidden where there is no room, and the mark is still named"
    (let [l (c/logo {:name-class "hidden lg:inline"})
          name-el (last (hx/child-nodes l))]
      (is (every? (hx/classes name-el) ["hidden" "lg:inline" "text-lg"]))
      (is (not (contains? (hx/attributes l) :name-class)))))
  (is (empty? (a11y/problems (c/logo {:href "/"})))))

;; ---------------------------------------------------------------------------
;; What changed in older components

(deftest a-column-of-a-table-can-have-a-class-of-its-own
  (let [t (c/data-table {:caption "C"
                         :columns [{:key :a :label "A" :header? true}
                                   {:key :b :label "B" :class "hidden xl:table-cell"}]
                         :rows [{:a "1" :b "2"}]})
        [th-a th-b] (hx/select (first (hx/by-tag t :thead)) #(= :th (hx/tag %)))
        body (first (hx/by-tag t :tbody))]
    (is (not (hx/has-class? th-a "hidden")))
    (is (every? (hx/classes th-b) ["hidden" "xl:table-cell"]))
    (is (every? (hx/classes (first (hx/by-tag body :td))) ["hidden" "xl:table-cell"]) "so is the cell")
    (is (not (hx/has-class? (first (hx/by-tag body :th)) "hidden")))))

(deftest an-alert-puts-its-actions-under-the-text-on-a-phone-and-at-the-end-from-a-small-screen
  (let [with (c/alert {:tone :high :title "T" :actions (c/button "Try again")} "Text")
        without (c/alert {:tone :high :title "T"} "Text")
        actions (first (hx/select with #(hx/has-class? % "col-start-2")))]
    (is (every? (hx/classes with) ["grid" "grid-cols-[auto_1fr]" "sm:grid-cols-[auto_1fr_auto]"]))
    (is (every? (hx/classes actions) ["sm:col-start-3" "sm:row-start-1" "flex-wrap"]))
    (is (not (hx/has-class? without "sm:grid-cols-[auto_1fr_auto]")) "no third column when there is nothing for it")
    (is (empty? (hx/select without #(hx/has-class? % "col-start-2"))))
    (is (empty? (a11y/problems with)))))

(deftest the-label-of-a-checkbox-and-a-switch-is-as-high-as-the-control
  (is (hx/has-class? (first (hx/by-tag (c/checkbox-field {:id "a" :label "A"}) :label)) "leading-6"))
  (is (hx/has-class? (first (hx/by-tag (c/toggle-field {:id "a" :label "A"}) :label)) "leading-6"))
  (is (not (hx/has-class? (first (hx/by-tag (c/checkbox-field {:id "a" :label "A"}) :input)) "mt-0.5"))))

(deftest the-new-components-together-have-no-accessibility-problem-a-program-can-see
  (let [page [:main
              [:h1 "Panel"]
              (c/breadcrumbs {:items [{:href "#" :label "Panel"} {:label "Ana"}]})
              (c/search-field {:id "s" :label "Search" :placeholder "Search"})
              (c/user-menu {:name "Ana García" :items [{:href "#" :label "Sign out" :icon :log-out}]})
              (c/pill-nav {:label "Filter" :items [{:href "#" :label "All" :count 4 :current? true}]})
              (c/stat {:label "Patients" :value "4" :icon :users})
              (c/person {:name "Ana García" :detail "Valencia"})
              (c/card {:title "Appointments" :flush? true}
                      (c/list-group {:aria-label "Appointments"}
                                    (c/list-row {:leading (c/date-block {:month "Oct" :day "14"}) :trailing (c/badge "x")} [:p "One"])
                                    (c/list-row {:leading (c/icon-disc {:icon :pill})} [:p "Two"])))
              (c/logo {:href "/"})
              (c/dialog {:id "d" :title "Sure?" :actions (c/button "Yes")} "Text")]]
    (is (= [] (a11y/problems page)))))
