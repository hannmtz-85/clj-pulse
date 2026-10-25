(ns pulse.web.my-record-details-test
  "What a mutation analysis found that the tests of the record did not notice: a list that the summary names as lost and holds all the
  same, a state that is not text, and what the handler passes to the frame of the page (the person, the token of the menu)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [pulse.design.build :as build]
            [pulse.design.portal :as portal]
            [pulse.ui.test.hiccup :as hx]
            [pulse.ui.test.html :as parsed]
            [pulse.web.html :as html]
            [pulse.web.pages.record :as record]
            [pulse.web.test.cohort :refer [csrf-of main-of page-of signed-in with-cohort]]
            [pulse.web.test.harness :as h]))

(def ^:private ana (portal/record-model "pt-ana"))

(defn- read-back [model]
  (parsed/document (html/render [:main (record/content model {:retry "/record"})])))

(defn- alerts [tree]
  (hx/select tree #(and (= :div (hx/tag %)) (hx/has-class? % "bg-tone-high-soft"))))

(deftest a-list-the-summary-names-as-lost-is-lost-even-when-it-holds-something
  (doseq [[kind title] [["medications" "Your medication could not be loaded"]
                        ["conditions" "Your conditions could not be loaded"]
                        ["allergies" "Your allergies could not be loaded"]]]
    (let [tree (read-back {:summary (assoc (:summary ana) :unavailable [kind] :allergies [{:display "latex" :reaction "Rash"}])})]
      (is (= [title] (map #(hx/words (first (hx/by-tag % :p))) (alerts tree))) kind))))

(deftest a-state-that-is-not-text-is-no-line
  (doseq [status [nil "" "  " 5 {:a 1} ["active"] true]]
    (let [tree (read-back {:summary (assoc (:summary ana) :conditions [{:display "Asthma" :status status :onset "2020-01-02"}])})
          row (first (hx/by-tag (first (hx/select tree #(and (= :ul (hx/tag %)) (= "Conditions" (:aria-label (hx/attributes %)))))) :li))]
      (is (= ["Asthma"] (map hx/words (hx/by-tag row :p))) (pr-str status))))
  (testing "and one that is text is capitalised"
    (let [tree (read-back {:summary (assoc (:summary ana) :conditions [{:display "Asthma" :status "inactive"}])})]
      (is (str/includes? (hx/words tree) "AsthmaInactive")))))

(deftest the-menu-of-the-record-is-the-persons-with-the-token-of-their-session
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [b (signed-in web)
            page (page-of (h/visit! b :get "/record"))
            token (csrf-of web b)
            menu (first (hx/by-tag page :details))]
        (is (= "Account menu of Ana García" (:aria-label (hx/attributes (first (hx/by-tag menu :summary))))))
        (is (= [{:type "hidden" :name "__anti-forgery-token" :value token}]
               (map hx/attributes (hx/by-tag (first (filter #(= "account-sign-out" (:id (hx/attributes %))) (hx/by-tag page :form))) :input))))
        (is (= {:X-CSRF-Token token}
               (json/read-value (:hx-headers (hx/attributes (first (hx/by-tag page :body)))) json/keyword-keys-object-mapper)))
        (is (some? (main-of page)))))))

(deftest the-mock-ups-of-the-record-and-the-profile-are-titled-as-the-review-calls-them
  (let [context (build/context)]
    (is (= ["My record · Pulse"] (map hx/words (hx/by-tag (portal/record context) :title))))
    (is (= ["Profile · Pulse"] (map hx/words (hx/by-tag (portal/profile context) :title))))))
