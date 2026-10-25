(ns pulse.web.crawl-test
  "The portal as a whole (ADR-0028): a patient who starts at the home and follows every link, and every form that only asks for a page,
  reaches every page of a patient that the route table has, and every one of them is a page that answers, that a person can use (the
  checks of accessibility a program can make, `pulse.ui.test.a11y`) and that is for them alone. No link leads nowhere, and no page
  is out of reach: a route that a patient could never get to by following the portal fails here, and so does a page that breaks a
  rule of accessibility in any state of the stand-in for the api."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.ui.test.a11y :as a11y]
            [pulse.ui.test.hiccup :as hx]
            [pulse.web.pages :as pages]
            [pulse.web.routes :as routes]
            [pulse.web.test.cohort :refer [page-of signed-in with-cohort]]
            [pulse.web.test.harness :as h :refer [header]]))

(defn- patient-pages
  "The paths of the route table that a patient asks for with a GET, as patterns: `/appointments/:id/cancel`."
  []
  (sort (for [[path methods] (routes/routes {})
              :when (= :patient (get-in methods [:get :authz]))]
          path)))

(defn- pattern-of
  "The regular expression that a path of the route table matches: a segment that starts with a colon is any segment."
  [path]
  (re-pattern (str "^" (str/replace path #":[a-z-]+" "[^/?#]+") "$")))

(defn- internal-links
  "The addresses of the portal that `page` leads to: its links, and the forms that only ask for a page. The anchor of the skip link,
  a file of the portal and the way out (a form that posts) are not pages to follow."
  [page]
  (let [attributes (map hx/attributes (concat (hx/by-tag page :a) (filter #(= "get" (str/lower-case (or (:method (hx/attributes %)) "get")))
                                                                          (hx/by-tag page :form))))]
    (->> attributes
         (keep (fn [{:keys [href action]}] (or href action)))
         (filter #(and (str/starts-with? % "/") (not (str/starts-with? % "//"))))
         (remove #(str/starts-with? % "/assets/"))
         (map #(first (str/split % #"#" 2)))
         (remove str/blank?)
         distinct)))

(defn- crawl!
  "Follows every internal link from `start`, as `browser` does, once each. Returns the pages by address: `{:status :page :response}`."
  [browser start]
  (loop [queue [start] seen {}]
    (if-let [address (first queue)]
      (if (contains? seen address)
        (recur (rest queue) seen)
        (let [response (h/follow! browser address)
              page (when (= 200 (:status response)) (page-of response))]
          (recur (into (vec (rest queue)) (when page (internal-links page)))
                 (assoc seen address {:status (:status response) :page page :response response}))))
      seen)))

(defn- path-of [address] (first (str/split address #"\?" 2)))

(deftest a-patient-reaches-every-page-by-following-the-portal-and-every-link-leads-to-a-page
  (doseq [[who person patient] [["a patient with readings and appointments" :ana "pt-ana"]
                                ["a new patient" :noah "pt-noah"]]]
    (testing who
      (with-cohort patient {} {}
        (fn [web]
          (let [pages (crawl! (signed-in web :person person) "/")
                visited (set (map path-of (keys pages)))]
            (testing "every link answers"
              (is (= {} (into {} (remove (fn [[_ {:keys [status]}]] (= 200 status))) pages))))
            (testing "every page of a patient in the route table is reached by following the portal"
              (doseq [pattern (patient-pages)
                      :let [re (pattern-of pattern)]]
                (is (some #(re-matches re %) visited) (str pattern " is not linked from anywhere"))))
            (testing "and every one is a page a person can use, and for them alone"
              (doseq [[address {:keys [page response]}] pages]
                (is (= [] (a11y/problems page {:page? true})) address)
                (is (= "no-store" (header response "Cache-Control")) address)
                (is (= "text/html" (h/content-type response)) address)
                (is (= 1 (count (hx/by-tag page :h1))) address)))
            (testing "the portal is more than the home"
              (is (>= (count visited) 7)))))))))

(deftest the-navigation-leads-to-the-pages-of-the-route-table-and-only-to-them
  (let [navigation (map :href pages/nav)
        patterns (map pattern-of (patient-pages))]
    (doseq [href navigation]
      (is (some #(re-matches % href) patterns) (str href " is not a page of a patient")))
    (is (= (count navigation) (count (distinct navigation))))))
