(ns pulse.web.profile-page-test
  "The profile of the portal as a function of what the api answers (ADR-0028): who the patient is to the clinic, their doctor and the
  way out; what it shows when the clinic holds little, when it holds nothing, and when it holds what other systems wrote oddly; and that
  there is nothing to edit, since the api has no way to change it."
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
            [pulse.web.pages.profile :as profile]))

(def ^:private ana (portal/profile-model "pt-ana"))

(def ^:private noah (portal/profile-model "pt-noah"))

(def ^:private links {:retry "/profile"})

(defn- read-back
  [model & [the-links]]
  (parsed/document (html/render [:main (profile/content model (or the-links links))])))

(defn- words [tree] (hx/words tree))

(defn- card [tree title]
  (first (filter #(= title (words (first (hx/by-tag % :h2)))) (hx/select tree #(= :section (hx/tag %))))))

(defn- alerts [tree]
  (hx/select tree #(and (= :div (hx/tag %)) (hx/has-class? % "bg-tone-high-soft"))))

(defn- with-resource [model f]
  (update-in model [:me :resource] f))

(defn- with-me [model me] (assoc model :me me))

;; ---------------------------------------------------------------------------
;; The profile of a patient

(deftest the-profile-has-these-parts-in-this-order
  (let [tree (read-back ana)]
    (is (= ["Profile"] (map words (hx/by-tag tree :h1))))
    (is (= ["Who you are" "Your care team"] (map words (hx/by-tag tree :h2))))
    (is (= "What the clinic knows about you. To change it, tell them." (words (first (hx/by-tag tree :p)))))
    (is (= 1 (count (hx/by-tag tree :form))) "the way out")
    (is (= [] (a11y/problems tree)))))

(deftest who-you-are-is-the-name-the-email-and-what-the-clinic-holds
  (let [tree (read-back ana)
        who (card tree "Who you are")]
    (is (= ["Ana García" "ana.garcia@pulse.test"] (map words (hx/by-tag who :p))))
    (is (= [["Date of birth" "14 March 1968"] ["Patient number" "PULSE-0001"] ["City" "Valencia"]]
           (map (juxt (comp words first) (comp words second))
                (partition 2 (hx/select (first (hx/by-tag who :dl)) #(#{:dt :dd} (hx/tag %)))))))
    (is (= ["AG"] (map words (hx/select who #(hx/has-class? % "rounded-full"))))
        "the initials, hidden from a screen reader, beside a name that is not")))

(deftest the-care-team-is-the-doctor-and-what-they-are-qualified-in
  (let [tree (read-back ana)
        team (card tree "Your care team")]
    (is (= ["Dr. Elena Rivera" "Internal medicine" "Requests for a visit go to your doctor."] (map words (hx/by-tag team :p))))
    (is (str/includes? (words team) "Requests for a visit go to your doctor."))
    (is (not (str/includes? (words tree) "Pulse Demo Clinic")) "the api does not know the name of a clinic")
    (testing "another patient, another doctor"
      (is (str/includes? (words (card (read-back noah) "Your care team")) "Dr. Chidi Okafor")))))

(deftest the-way-out-is-a-form-that-posts-with-the-token-of-the-session
  (let [tree (read-back ana)
        form (first (hx/by-tag tree :form))]
    (is (= {:method "post" :action "/sign-out"} (hx/attributes form)))
    (is (= [{:type "hidden" :name "__anti-forgery-token" :value "the-token-of-the-session"}] (map hx/attributes (hx/by-tag form :input))))
    (is (= ["Sign out"] (map words (hx/by-tag form :button))))
    (is (= "submit" (:type (hx/attributes (first (hx/by-tag form :button))))))
    (testing "the review of the mock-ups leads it to the page of signing in"
      (is (= "../login.html" (:action (hx/attributes (first (hx/by-tag (read-back ana {:sign-out "../login.html"}) :form)))))))))

(deftest there-is-nothing-to-edit
  (let [tree (read-back ana)]
    (is (= ["hidden"] (map (comp :type hx/attributes) (hx/by-tag tree :input))) "the only field is the token")
    (is (empty? (hx/by-tag tree :textarea)))
    (is (empty? (hx/by-tag tree :select)))
    (is (= ["Sign out"] (map words (hx/by-tag tree :button))) "the only button")))

;; ---------------------------------------------------------------------------
;; When the clinic holds little

(deftest a-patient-with-no-doctor-is-told-so
  (doseq [me [(assoc (:me ana) :generalPractitioner nil)
              (dissoc (:me ana) :generalPractitioner)
              (assoc (:me ana) :generalPractitioner {:reference "Practitioner/pr-x" :name nil :specialty nil})
              (assoc (:me ana) :generalPractitioner {:reference "Practitioner/pr-x" :name "  " :specialty "General practice"})
              (assoc (:me ana) :generalPractitioner {:reference "Practitioner/pr-x" :name 42})]
          :let [team (card (read-back (with-me ana me)) "Your care team")]]
    (is (= ["We do not know who your doctor is. Tell your clinic."] (map words (hx/by-tag team :p))) (pr-str (:generalPractitioner me)))
    (is (not (str/includes? (words team) "Requests for a visit")))))

(deftest a-doctor-with-no-specialty-is-just-a-name
  (let [team (card (read-back (with-me ana (assoc-in (:me ana) [:generalPractitioner :specialty] nil))) "Your care team")]
    (is (= ["Dr. Elena Rivera" "Requests for a visit go to your doctor."] (map words (hx/by-tag team :p))))))

(deftest a-detail-the-clinic-does-not-hold-is-left-out-not-written-as-missing
  (let [facts (fn [resource-fn]
                (let [who (card (read-back (with-resource ana resource-fn)) "Who you are")]
                  (if-let [dl (first (hx/by-tag who :dl))]
                    (map words (hx/select dl #(= :dt (hx/tag %))))
                    [])))]
    (is (= ["Date of birth" "Patient number" "City"] (facts identity)))
    (is (= ["Patient number" "City"] (facts #(dissoc % :birthDate))))
    (is (= ["Date of birth" "City"] (facts #(dissoc % :identifier))))
    (is (= ["Date of birth" "Patient number"] (facts #(dissoc % :address))))
    (is (= ["Date of birth" "Patient number"] (facts #(assoc % :address [{:text "no city"}]))))
    (is (= [] (facts #(dissoc % :birthDate :identifier :address))))
    (testing "no name, no email, nothing: the card says so, and still has a title"
      (let [tree (read-back (with-resource ana #(select-keys % [:id :resourceType])))
            who (card tree "Who you are")]
        (is (some? who))
        (is (= ["Your clinic has no details of you on file. Tell them."] (map words (hx/by-tag who :p))))
        (is (empty? (hx/by-tag who :dl)))
        (is (= [] (a11y/problems tree)))))))

(deftest the-email-is-the-contact-point-that-is-an-email
  (let [who (fn [telecom] (card (read-back (with-resource ana #(assoc % :telecom telecom))) "Who you are"))]
    (is (str/includes? (words (who [{:system "phone" :value "+34 600 000 000"} {:system "email" :value "ana@example.test"}])) "ana@example.test")
        "not the first, the one that is an email")
    (is (not (str/includes? (words (who [{:system "phone" :value "+34 600 000 000"}])) "+34 600")) "a phone is not shown as an email")
    (doseq [telecom [nil [] [{:system "email"}] [{:system "email" :value " "}] [{:value "ana@example.test"}] {:system "email" :value "x"} "ana@example.test"]]
      (is (= ["Ana García"] (map words (hx/by-tag (who telecom) :p))) (pr-str telecom)))))

(deftest a-name-is-the-given-names-and-the-family-name
  (let [name-of (fn [name] (profile/full-name {:name name}))]
    (is (= "Ana García" (name-of [{:given ["Ana"] :family "García"}])))
    (is (= "Ana María García López" (name-of [{:given ["Ana" "María"] :family "García López"}])))
    (is (= "García" (name-of [{:family "García"}])))
    (is (= "Ana" (name-of [{:given ["Ana"]}])))
    (is (= "Ana García" (name-of [{:given ["Ana" " " nil 5] :family "García"}])) "what is not a name is not part of one")
    (doseq [odd [nil [] [{}] [{:given [] :family ""}] [{:given "Ana"}] {:family "x"} "Ana" [nil]]]
      (is (nil? (name-of odd)) (pr-str odd)))
    (is (nil? (profile/full-name nil)))))

;; ---------------------------------------------------------------------------
;; What the api could not give

(deftest details-that-could-not-be-loaded-are-a-message-in-the-first-card-and-the-doctor-is-still-there
  (doseq [me [(assoc (:me ana) :resource nil) (dissoc (:me ana) :resource) (assoc (:me ana) :resource "Ana") (assoc (:me ana) :resource [])]
          :let [tree (read-back (with-me ana me))]]
    (is (= ["Your details could not be loaded"] (map #(words (first (hx/by-tag % :p))) (alerts tree))) (pr-str (:resource me)))
    (is (str/includes? (words (first (alerts tree))) "The rest of the page is up to date. The problem is on our side, not yours."))
    (is (= "/profile" (:href (hx/attributes (first (filter #(= "Try again" (words %)) (hx/by-tag tree :a)))))))
    (is (str/includes? (words (card tree "Your care team")) "Dr. Elena Rivera"))
    (is (= 1 (count (hx/by-tag tree :form))) "and the way out")
    (is (= [] (a11y/problems tree)))))

(deftest no-me-at-all-is-a-page-with-the-same-message-and-the-way-out
  (let [tree (read-back (with-me ana nil))]
    (is (= ["Your details could not be loaded"] (map #(words (first (hx/by-tag % :p))) (alerts tree))))
    (is (= ["We do not know who your doctor is. Tell your clinic."] (map words (hx/by-tag (card tree "Your care team") :p))))
    (is (= 1 (count (hx/by-tag tree :form))))))

(deftest the-way-to-try-again-is-there-only-when-the-page-has-one
  (let [tree (read-back (with-me ana nil) {})]
    (is (= 1 (count (alerts tree))))
    (is (empty? (hx/by-tag (first (alerts tree)) :a)))))

;; ---------------------------------------------------------------------------
;; Data that other systems wrote

(deftest a-date-of-birth-that-cannot-be-read-is-left-out
  (doseq [born [nil "" "  " "yesterday" "1968-13-45" "1968" "1968-03" "14/03/1968" 19680314 {:a 1} "1968-03-14T00:00:00Z"]]
    (let [who (card (read-back (with-resource ana #(assoc % :birthDate born))) "Who you are")]
      (is (not (str/includes? (words who) "Date of birth")) (pr-str born))
      (is (str/includes? (words who) "PULSE-0001") "the rest is there")))
  (is (= "14 March 1968" (profile/birth-date {:birthDate "1968-03-14"})))
  (is (= "1 January 2000" (profile/birth-date {:birthDate "2000-01-01"}))))

(deftest what-the-clinic-wrote-is-text-whatever-it-holds
  (let [hostile "<script>alert(1)</script>"
        text (html/render [:main (profile/content
                                  {:me (-> (:me ana)
                                           (assoc-in [:resource :name] [{:given [hostile] :family hostile}])
                                           (assoc-in [:resource :telecom] [{:system "email" :value hostile}])
                                           (assoc-in [:resource :identifier] [{:value hostile}])
                                           (assoc-in [:resource :address] [{:city hostile}])
                                           (assoc-in [:generalPractitioner] {:name hostile :specialty hostile}))
                                   :csrf-token hostile}
                                  links)])]
    (is (not (str/includes? text "<script>alert")))
    (is (= 8 (count (re-seq #"&lt;script&gt;alert\(1\)&lt;/script&gt;" text))) "the name twice (given and family), the email, the number, the city, the doctor, the specialty and the token")))

;; ---------------------------------------------------------------------------
;; Properties

(def ^:private odd-value
  (gen/one-of [(gen/return nil) gen/string gen/small-integer (gen/return "1968-03-14") (gen/return "<b>x</b>") (gen/return [])
               (gen/return {}) (gen/return [{:system "email" :value "a@b.test"}])]))

(def ^:private odd-resource
  (gen/let [name odd-value born odd-value identifier odd-value address odd-value telecom odd-value]
    {:name name :birthDate born :identifier identifier :address address :telecom telecom}))

(deftest any-resource-is-a-profile-with-the-way-out
  (let [result (tc/quick-check
                300
                (prop/for-all [resource (gen/one-of [odd-resource (gen/return nil) odd-value])
                               doctor (gen/one-of [(gen/return nil) odd-value (gen/return {:name "Dr. X" :specialty "Y"})])]
                  (let [tree (read-back {:me {:resource resource :generalPractitioner doctor} :csrf-token "t"})]
                    (and (= ["Profile"] (map words (hx/by-tag tree :h1)))
                         (= ["t"] (map (comp :value hx/attributes) (hx/by-tag tree :input)))
                         (= 1 (count (hx/by-tag tree :form)))
                         (= [] (a11y/problems tree))))))]
    (is (:pass? result) (pr-str (select-keys result [:shrunk :fail])))))
