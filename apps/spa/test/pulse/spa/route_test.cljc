(ns pulse.spa.route-test
  "Where the workspace is, and what that asks of it (ADR-0029, point 5): the table of routes and its rules, the matching of an address,
  the parameters of an address read the way a hand-written one still gets a page, and what starts and stops when the route changes.
  These run on the JVM and on Node."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.spa.patient :as patient]
            [pulse.spa.person :as person]
            [pulse.spa.route :as route]
            [reitit.core :as r]))

(def ^:private rivera {:id "r1" :name "Elena Rivera" :roles #{:practitioner}})
(def ^:private sam {:id "s1" :name "Sam Admin" :roles #{:clinic-admin}})
(def ^:private ana {:id "a1" :name "Ana García" :roles #{:patient}})

;; ---------------------------------------------------------------------------
;; Signing out

(deftest the-page-of-someone-who-signed-out-is-one-path-with-or-without-a-slash-at-its-end
  (is (= "/signed-out" route/signed-out-path))
  (is (true? (route/signed-out? "/signed-out")))
  (is (true? (route/signed-out? "/signed-out/"))))

(deftest every-other-path-asks-for-the-sign-in
  (doseq [pathname ["/" "" nil "/signed-out/x" "/signed-out//" "/signed-outx" "/x/signed-out" "/Signed-Out" "/SIGNED-OUT" "signed-out"
                    "/signed-out.html" "/signed_out" "/signed-out%2F" " /signed-out" "/signed-out "
                    "/patients/pt-ana" "/alerts" "/jobs"]]
    (testing (pr-str pathname)
      (is (false? (route/signed-out? pathname))))))

(deftest only-the-page-of-someone-who-signed-out-is-public
  (is (true? (route/public? "/signed-out")))
  (is (true? (route/public? "/signed-out/")))
  (is (false? (route/public? "/")))
  (is (false? (route/public? "/nowhere")))
  (is (false? (route/public? nil))))

;; ---------------------------------------------------------------------------
;; The table

(deftest the-table-of-routes-is-right
  (is (= [] (route/problems route/routes)))
  (is (= route/routes (route/check! route/routes)))
  (testing "the pages the workspace has so far"
    (is (= [:panel :patient :signed-out] (map (comp :name second) route/routes)))
    (is (= ["/" "/patients/:id" "/signed-out"] (map first route/routes))))
  (testing "the panel is for the clinic's people and for nobody else"
    (is (= person/workspace-roles (:roles (route/data :panel))))
    (is (not (:public? (route/data :panel)))))
  (testing "and so is the chart of a patient, which is a page for each patient"
    (is (= person/workspace-roles (:roles (route/data :patient))))
    (is (not (:public? (route/data :patient))))
    (is (= [:id] (:page-params (route/data :patient))))))

(deftest a-route-that-says-neither-who-may-open-it-nor-that-it-is-public-is-an-error
  (let [route-with (fn [data] [["/x" (merge {:name :x :title "X"} data)]])]
    (testing "it says who, or that it is public"
      (is (= [] (route/problems (route-with {:roles #{:practitioner}}))))
      (is (= [] (route/problems (route-with {:public? true}))))
      (is (= [] (route/problems (route-with {:roles #{:practitioner} :page-params [:id] :update [[:a/b]]})))))
    (testing "and nothing else is right"
      (doseq [[label [data message]] {"neither" [{} "says neither which roles may open it nor that it is public"]
                                      "no roles" [{:roles #{}} "says neither which roles may open it nor that it is public"]
                                      "roles that are not a set" [{:roles [:practitioner]} "says neither which roles may open it nor that it is public"]
                                      "a role the realm does not have" [{:roles #{:superuser}} "says neither which roles may open it nor that it is public"]
                                      "a role of the realm and one it does not have" [{:roles #{:practitioner :superuser}} "says neither which roles may open it nor that it is public"]
                                      "both" [{:roles #{:practitioner} :public? true} "is both for some roles and public"]
                                      "public, but not truly" [{:public? false} "says neither which roles may open it nor that it is public"]
                                      "no name" [{:name nil :roles #{:practitioner}} "has no name"]
                                      "a name that is not a keyword" [{:name "x" :roles #{:practitioner}} "has no name"]
                                      "no title" [{:title nil :roles #{:practitioner}} "has no title"]
                                      "an empty title" [{:title " " :roles #{:practitioner}} "has no title"]
                                      "a :start that is not a vector of events" [{:roles #{:practitioner} :start [:a]} "has a :start that is not a vector of events"]
                                      "a :stop that is not a vector of events" [{:roles #{:practitioner} :stop :a} "has a :stop that is not a vector of events"]
                                      "an :update that is not a vector of events" [{:roles #{:practitioner} :update :a} "has an :update that is not a vector of events"]
                                      "an :update with an event that does not begin with a keyword" [{:roles #{:practitioner} :update [[1]]} "has an :update that is not a vector of events"]
                                      "page parameters that are not a vector" [{:roles #{:practitioner} :page-params :id} "has :page-params that are not a vector of keywords"]
                                      "page parameters that are not keywords" [{:roles #{:practitioner} :page-params ["id"]} "has :page-params that are not a vector of keywords"]
                                      "an event that does not begin with a keyword" [{:roles #{:practitioner} :start [["a"]]} "has a :start that is not a vector of events"]}
              :let [problems (route/problems (route-with data))]]
        (testing label
          (is (= [(str "the route \"/x\" " message)] problems)))))
    (testing "check! throws, and says which"
      (let [thrown (try (route/check! (route-with {})) (catch #?(:clj Exception :cljs :default) e e))]
        (is (= ["the route \"/x\" says neither which roles may open it nor that it is public"] (:problems (ex-data thrown))))
        (is (= "The routes are not right: the route \"/x\" says neither which roles may open it nor that it is public" (ex-message thrown)))))
    (testing "and says every problem there is"
      (let [thrown (try (route/check! [["/a" {:name :a :title "A"}] ["/b" {:name :a :title " " :public? true}]]) (catch #?(:clj Exception :cljs :default) e e))]
        (is (= 3 (count (:problems (ex-data thrown)))))
        (is (re-find #"; " (ex-message thrown)) "joined by semicolons")))
    (testing "two routes with one name are one too many"
      (let [problems (route/problems [["/a" {:name :x :title "A" :public? true}] ["/b" {:name :x :title "B" :public? true}]])]
        (is (= ["the name :x is used by 2 routes"] problems))))))

(deftest the-router-is-made-of-the-table
  (is (some? (r/match-by-name route/router :panel)))
  (is (= "/" (:path (r/match-by-name route/router :panel))))
  (is (= "/signed-out" (:path (r/match-by-name route/router :signed-out))))
  (is (= "/patients/pt-ana" (:path (r/match-by-name route/router :patient {:id "pt-ana"})))))

;; ---------------------------------------------------------------------------
;; Matching

(deftest an-address-is-the-route-it-names
  (is (= :panel (:name (route/match-path "/"))))
  (is (= :signed-out (:name (route/match-path "/signed-out"))))
  (is (= :signed-out (:name (route/match-path "/signed-out/"))) "a path may end in one slash")
  (is (= :patient (:name (route/match-path "/patients/pt-ana"))))
  (is (= :patient (:name (route/match-path "/patients/pt-ana/"))) "and so may the chart of a patient")
  (testing "and no other, though it looks like one"
    (doseq [path ["/panel" "/patients" "/patients/" "/patients//" "/patients/pt-ana/summary" "/patients/pt-ana//" "/alerts" "//" "/x/" ""
                  "signed-out" "/signed-out//" "/ " nil 5 :panel]]
      (is (nil? (route/match-path path)) (pr-str path)))))

(deftest the-title-of-the-tab-is-the-title-of-the-page
  (is (= "Panel · Pulse" (route/title :panel)))
  (is (= "Patient chart · Pulse" (route/title :patient)))
  (is (= "Signed out · Pulse" (route/title :signed-out)))
  (is (= "Page not found · Pulse" (route/title :not-found)))
  (is (= "Workspace · Pulse" (route/title :nowhere)) "for a name there is no page of"))

;; ---------------------------------------------------------------------------
;; The parameters of the panel

(deftest the-panel-is-all-of-the-patients-unless-the-address-narrows-it
  (is (= {:show :all :q ""} (route/panel-params nil)))
  (is (= {:show :all :q ""} (route/panel-params {})))
  (is (= {:show :alerts :q ""} (route/panel-params {:show "alerts"})))
  (is (= {:show :away :q ""} (route/panel-params {:show "away"})))
  (is (= {:show :normal :q ""} (route/panel-params {:show "normal"})))
  (is (= {:show :all :q "ana garcia"} (route/panel-params {:q "ana garcia"})))
  (is (= {:show :alerts :q "ana"} (route/panel-params {:show "alerts" :q "ana"}))))

(deftest an-address-written-by-hand-still-gets-the-panel
  (testing "a filter that is none is all"
    (doseq [show ["" "ALERTS" "all" "alerts " "nonsense" "<script>" "0" nil [] {} 5 :alerts true]]
      (is (= :all (:show (route/panel-params {:show show}))) (pr-str show))))
  (testing "a parameter that came twice is its first"
    (is (= {:show :alerts :q "ana"} (route/panel-params {:show ["alerts" "away"] :q ["ana" "liam"]}))))
  (testing "a search that is not text is none"
    (doseq [q [nil 5 :a {} [] [5] true]]
      (is (= "" (:q (route/panel-params {:q q}))) (pr-str q))))
  (testing "a search that is too long is cut where it is allowed to end"
    (let [long-one (apply str (repeat 150 "a"))]
      (is (= 100 route/search-limit))
      (is (= (apply str (repeat 100 "a")) (:q (route/panel-params {:q long-one}))))
      (is (= (apply str (repeat 100 "b")) (:q (route/panel-params {:q (apply str (repeat 100 "b"))}))) "exactly the limit is kept")
      (is (= 100 (count (:q (route/panel-params {:q (apply str (repeat 101 "c"))})))))))
  (testing "a search is kept as it was typed: spaces, accents and all"
    (is (= " Ana  García " (:q (route/panel-params {:q " Ana  García "}))))))

(deftest the-address-of-the-panel-leaves-out-what-is-the-default
  (is (= "/" (route/panel-path)))
  (is (= "/" (route/panel-path nil)))
  (is (= "/" (route/panel-path {})))
  (is (= "/" (route/panel-path {:show :all :q ""})))
  (is (= "/" (route/panel-path {:show :all :q "   "})) "a search of spaces is none")
  (is (= "/?show=alerts" (route/panel-path {:show :alerts})))
  (is (= "/?show=away" (route/panel-path {:show :away :q ""})))
  (is (= "/?show=normal" (route/panel-path {:show :normal})))
  (is (= "/?q=ana" (route/panel-path {:q "ana"})))
  (is (= "/?q=ana&show=alerts" (route/panel-path {:show :alerts :q "ana"})) "in the order of their names")
  (testing "what is not a filter is left out, and the search is written so that an address can hold it"
    (is (= "/" (route/panel-path {:show :nonsense})))
    (is (= "/?q=ana%20garc%C3%ADa" (route/panel-path {:q "ana garcía"})))
    (is (= "/?q=a%26b%3Dc%2Bd%23e%25f%3F" (route/panel-path {:q "a&b=c+d#e%f?"})))))

(deftest the-address-of-the-panel-and-the-parameters-it-is-read-from-go-round
  (doseq [params [{:show :all :q ""} {:show :alerts :q ""} {:show :away :q "ana"} {:show :normal :q "ana garcía"}
                  {:show :all :q "a&b=c+d#e%f?"} {:show :alerts :q "x y"}]]
    (is (= params (route/panel-params (route/query-map params)))
        (str "the query of the address, read back, is the same panel: " (pr-str params)))))

(deftest the-query-of-a-narrowed-panel-is-a-map-of-texts
  (is (= {} (route/query-map nil)))
  (is (= {} (route/query-map {:show :all :q ""})))
  (is (= {:show "alerts" :q "ana"} (route/query-map {:show :alerts :q "ana"})))
  (is (= {:q "ana"} (route/query-map {:q "ana"})))
  (is (= {} (route/query-map {:show :bogus :q nil}))))

;; ---------------------------------------------------------------------------
;; The parameters of the chart of a patient

(deftest the-chart-of-a-patient-is-of-the-headline-reading-and-fourteen-days-unless-the-address-says-more
  (is (= [7 14 30] patient/periods))
  (is (= 14 patient/default-days))
  (is (= {:id "pt-ana" :measurement nil :days 14} (route/patient-params nil {:id "pt-ana"})))
  (is (= {:id "pt-ana" :measurement nil :days 14} (route/patient-params {} {:id "pt-ana"})))
  (is (= {:id "pt-ana" :measurement :glucose :days 14} (route/patient-params {:measurement "glucose"} {:id "pt-ana"})))
  (is (= {:id "pt-ana" :measurement nil :days 30} (route/patient-params {:days "30"} {:id "pt-ana"})))
  (is (= {:id "pt-ana" :measurement :blood-pressure :days 7} (route/patient-params {:measurement "blood-pressure" :days "7"} {:id "pt-ana"})))
  (testing "every measurement the interfaces show can be asked for"
    (is (= [:blood-pressure :heart-rate :spo2 :glucose :weight :temperature]
           (map #(:measurement (route/patient-params {:measurement (name %)} {:id "x"})) [:blood-pressure :heart-rate :spo2 :glucose :weight :temperature]))))
  (testing "every period"
    (is (= [7 14 30] (map #(:days (route/patient-params {:days (str %)} {:id "x"})) patient/periods)))))

(deftest an-address-of-a-chart-written-by-hand-still-gets-a-chart
  (testing "a measurement that is none is the headline reading"
    (doseq [measurement ["" "Glucose" "GLUCOSE" "glucose " "bp" "blood pressure" "<script>" "0" nil [] {} 5 :glucose true]]
      (is (nil? (:measurement (route/patient-params {:measurement measurement} {:id "x"}))) (pr-str measurement))))
  (testing "a period that is none is fourteen days"
    (doseq [days ["" "0" "1" "8" "15" "31" "365" "+7" "07" "7.0" "7 " " 7" "seven" "-7" "1e1" nil [] {} 7 :7 true]]
      (is (= 14 (:days (route/patient-params {:days days} {:id "x"}))) (pr-str days))))
  (testing "a parameter that came twice is its first"
    (is (= {:id "x" :measurement :glucose :days 7} (route/patient-params {:measurement ["glucose" "weight"] :days ["7" "30"]} {:id "x"})))))

(deftest the-address-of-a-chart-leaves-out-what-is-the-default
  (is (= "/patients/pt-ana" (route/patient-path "pt-ana")))
  (is (= "/patients/pt-ana" (route/patient-path "pt-ana" nil)))
  (is (= "/patients/pt-ana" (route/patient-path "pt-ana" {})))
  (is (= "/patients/pt-ana" (route/patient-path "pt-ana" {:measurement nil :days 14})))
  (is (= "/patients/pt-ana?days=30" (route/patient-path "pt-ana" {:days 30})))
  (is (= "/patients/pt-ana?days=7" (route/patient-path "pt-ana" {:days 7})))
  (is (= "/patients/pt-ana?measurement=glucose" (route/patient-path "pt-ana" {:measurement :glucose})))
  (is (= "/patients/pt-ana?days=30&measurement=blood-pressure" (route/patient-path "pt-ana" {:measurement :blood-pressure :days 30}))
      "in the order of their names")
  (testing "what is not a measurement or a period is left out"
    (is (= "/patients/pt-ana" (route/patient-path "pt-ana" {:measurement :nonsense :days 5})))
    (is (= "/patients/pt-ana" (route/patient-path "pt-ana" {:measurement "glucose" :days "30"})) "a text is not a keyword, nor a number")
    (is (= "/patients/pt-ana" (route/patient-path "pt-ana" {:measurement 5})))
    (is (= "/patients/pt-ana" (route/patient-path "pt-ana" {:measurement :x/glucose})))))

(deftest the-address-of-a-chart-and-the-parameters-it-is-read-from-go-round
  (doseq [id ["pt-ana" "pt.a-1" "A" (apply str (repeat 64 "z"))]
          measurement [nil :blood-pressure :heart-rate :spo2 :glucose :weight :temperature]
          days patient/periods
          :let [params {:id id :measurement measurement :days days}
                path (route/patient-path id params)
                [pathname query] (let [[a b] (str/split path #"\?" 2)] [a b])
                parsed (into {} (for [pair (when query (str/split query #"&"))
                                      :let [[k v] (str/split pair #"=" 2)]]
                                  [(keyword k) v]))]]
    (is (= params (route/patient-params parsed {:id (subs pathname (count "/patients/"))})) path)))

(deftest only-an-id-that-can-be-a-patients-is-a-chart
  (doseq [id ["pt-ana" "a" "A1" "pt.ana" "1" "x-y.z" (apply str (repeat 64 "a"))]]
    (is (true? (route/patient-id? id)) (pr-str id)))
  (doseq [id [nil "" "." ".." "pt ana" "pt/ana" "pt_ana" "pt:ana" "pt-ana?" "pt-ana#" "pt-ana%" "ñ" "pt-aña" (apply str (repeat 65 "a"))
              "a\n" "\na" 5 :pt-ana ["pt-ana"] {:id "pt-ana"} true]]
    (is (false? (route/patient-id? id)) (pr-str id))))

;; ---------------------------------------------------------------------------
;; From what the history says

(deftest the-route-the-workspace-is-on-is-read-from-what-the-history-says
  (let [match (fn [path query] (assoc (r/match-by-path route/router path) :query-params query))]
    (is (= {:name :panel :params {:show :all :q ""}} (route/from-match (match "/" {}))))
    (is (= {:name :panel :params {:show :alerts :q "ana"}} (route/from-match (match "/" {:show "alerts" :q "ana"}))))
    (is (= {:name :panel :params {:show :all :q ""}} (route/from-match (match "/" {:show "junk" :q 5}))) "a hand-written address is the panel")
    (is (= {:name :signed-out :params {}} (route/from-match (match "/signed-out" {:show "alerts"}))) "and a page with no parameters has none")
    (is (= {:name :not-found :params {}} (route/from-match nil)) "an address that is no page")
    (is (= {:name :not-found :params {}} (route/from-match (r/match-by-path route/router "/nope"))))))

(deftest the-chart-the-workspace-is-on-is-read-from-the-path-and-the-query
  (let [match (fn [path query] (assoc (r/match-by-path route/router path) :query-params query))]
    (is (= {:name :patient :params {:id "pt-ana" :measurement nil :days 14}} (route/from-match (match "/patients/pt-ana" {}))))
    (is (= {:name :patient :params {:id "pt-ana" :measurement :glucose :days 30}}
           (route/from-match (match "/patients/pt-ana" {:measurement "glucose" :days "30"}))))
    (is (= {:name :patient :params {:id "pt-ana" :measurement nil :days 14}}
           (route/from-match (match "/patients/pt-ana" {:measurement "junk" :days "junk"}))) "a hand-written address is a chart")
    (is (= "pt-ana" (:id (:params (route/from-match (match "/patients/pt%2Dana" {}))))) "the id is read as the address says it")))

(deftest an-address-of-an-id-that-cannot-be-a-patients-is-the-page-of-nothing
  (let [match (fn [path] (r/match-by-path route/router path))]
    (doseq [path ["/patients/pt%20ana" "/patients/pt%2Fana" "/patients/%2E" "/patients/%2e%2e" "/patients/pt%3Fana" "/patients/pt%00"
                  (str "/patients/" (apply str (repeat 65 "a")))]]
      (testing path
        (is (= {:name :not-found :params {}} (route/from-match (match path))))))))

;; ---------------------------------------------------------------------------
;; Who may open what

(deftest a-route-is-open-to-the-roles-it-says
  (is (true? (route/allowed? :panel rivera)))
  (is (true? (route/allowed? :panel sam)))
  (is (false? (route/allowed? :panel ana)) "a patient has the portal")
  (is (false? (route/allowed? :panel {:roles #{}})))
  (is (false? (route/allowed? :panel {:roles #{:rules-engine :api-service}})))
  (is (false? (route/allowed? :panel nil)))
  (is (true? (route/allowed? :panel {:roles #{:patient :practitioner}})) "one of the roles is enough"))

(deftest a-public-page-and-the-page-that-is-not-there-are-open-to-everyone
  (doseq [person [rivera sam ana {:roles #{}} nil]]
    (is (true? (route/allowed? :signed-out person)))
    (is (true? (route/allowed? :not-found person)))))

(deftest a-route-that-is-not-in-the-table-is-open-to-nobody
  (is (false? (route/allowed? :nowhere sam)))
  (is (false? (route/allowed? nil sam)))
  (is (false? (route/allowed? "panel" sam))))

;; ---------------------------------------------------------------------------
;; What the change of route starts, updates and stops

(deftest coming-to-the-panel-starts-it-with-the-parameters-of-the-address
  (let [here {:name :panel :params {:show :alerts :q "ana"}}]
    (is (= {:page-change? true :stop [] :start [[:pulse.spa.panel/load {:show :alerts :q "ana"}]] :update []}
           (route/transition nil here rivera)) "the first page the person comes to")
    (is (= {:page-change? true :stop [] :start [[:pulse.spa.panel/load {:show :alerts :q "ana"}]] :update []}
           (route/transition {:name :not-found :params {}} here sam)))))

(deftest leaving-the-panel-stops-it
  (is (= {:page-change? true :stop [[:pulse.spa.panel/leave]] :start [] :update []}
         (route/transition {:name :panel :params {:show :all :q ""}} {:name :not-found :params {}} rivera)))
  (is (= {:page-change? true :stop [[:pulse.spa.panel/leave]] :start [] :update []}
         (route/transition {:name :panel :params {}} {:name :signed-out :params {}} rivera))))

(deftest changing-the-filter-or-the-search-is-the-same-page-and-loads-nothing-again
  (let [from {:name :panel :params {:show :all :q ""}}]
    (doseq [to [{:name :panel :params {:show :alerts :q ""}} {:name :panel :params {:show :all :q "ana"}} {:name :panel :params {:show :all :q ""}}]]
      (is (= {:page-change? false :stop [] :start [] :update []} (route/transition from to rivera)) (pr-str to)))))

(deftest a-page-the-person-may-not-open-starts-nothing
  (is (= {:page-change? true :stop [] :start [] :update []}
         (route/transition nil {:name :panel :params {}} ana)) "a patient who got here sees a page that says so, and nothing is asked of the api")
  (is (= {:page-change? true :stop [] :start [] :update []}
         (route/transition nil {:name :panel :params {}} nil)))
  (is (= {:page-change? true :stop [] :start [] :update []}
         (route/transition nil {:name :patient :params {:id "pt-ana" :measurement nil :days 14}} ana))
      "and neither is the chart of a patient"))

(deftest a-page-with-nothing-to-start-starts-nothing
  (is (= {:page-change? true :stop [] :start [] :update []}
         (route/transition nil {:name :signed-out :params {}} rivera)))
  (is (= {:page-change? true :stop [] :start [] :update []}
         (route/transition nil {:name :not-found :params {}} rivera))))

;; The chart of a patient

(def ^:private ana-chart {:name :patient :params {:id "pt-ana" :measurement nil :days 14}})

(deftest coming-to-the-chart-of-a-patient-starts-it-with-the-parameters-of-the-address
  (is (= {:page-change? true :stop []
          :start [[:pulse.spa.patient/load {:id "pt-ana" :measurement nil :days 14}]] :update []}
         (route/transition nil ana-chart rivera)) "the first page the person comes to")
  (is (= {:page-change? true :stop [[:pulse.spa.panel/leave]]
          :start [[:pulse.spa.patient/load {:id "pt-ana" :measurement nil :days 14}]] :update []}
         (route/transition {:name :panel :params {:show :all :q "ana"}} ana-chart sam)) "from the panel, which is stopped"))

(deftest leaving-the-chart-of-a-patient-stops-it
  (is (= {:page-change? true :stop [[:pulse.spa.patient/leave]]
          :start [[:pulse.spa.panel/load {:show :all :q ""}]] :update []}
         (route/transition ana-chart {:name :panel :params {:show :all :q ""}} rivera)))
  (is (= {:page-change? true :stop [[:pulse.spa.patient/leave]] :start [] :update []}
         (route/transition ana-chart {:name :not-found :params {}} rivera))))

(deftest the-chart-of-another-patient-is-another-page-that-stops-the-one-and-starts-the-other
  (let [liam {:name :patient :params {:id "pt-liam" :measurement nil :days 14}}]
    (is (= {:page-change? true :stop [[:pulse.spa.patient/leave]]
            :start [[:pulse.spa.patient/load {:id "pt-liam" :measurement nil :days 14}]] :update []}
           (route/transition ana-chart liam rivera)) "the back button from one patient to another is a new chart")
    (is (= {:page-change? true :stop [[:pulse.spa.patient/leave]]
            :start [[:pulse.spa.patient/load {:id "pt-liam" :measurement :glucose :days 30}]] :update []}
           (route/transition ana-chart {:name :patient :params {:id "pt-liam" :measurement :glucose :days 30}} rivera))
        "whatever else changes with it")))

(deftest another-measurement-or-period-of-the-same-chart-is-an-update-and-not-another-page
  (doseq [to [{:name :patient :params {:id "pt-ana" :measurement :glucose :days 14}}
              {:name :patient :params {:id "pt-ana" :measurement nil :days 30}}
              {:name :patient :params {:id "pt-ana" :measurement :heart-rate :days 7}}]]
    (is (= {:page-change? false :stop [] :start [] :update [[:pulse.spa.patient/choose (:params to)]]}
           (route/transition ana-chart to rivera)) (pr-str to)))
  (testing "and nothing that has not changed is an update"
    (is (= {:page-change? false :stop [] :start [] :update []} (route/transition ana-chart ana-chart rivera)))
    (is (= {:page-change? false :stop [] :start [] :update []}
           (route/transition ana-chart (update ana-chart :params dissoc :nothing) rivera)))))

(deftest a-chart-the-person-may-not-open-is-not-updated
  (is (= {:page-change? false :stop [] :start [] :update []}
         (route/transition ana-chart (assoc-in ana-chart [:params :days] 30) ana))))

(deftest a-page-with-no-update-has-none-whatever-changes
  (is (= [] (:update (route/transition {:name :panel :params {:show :all :q ""}} {:name :panel :params {:show :away :q "x"}} rivera)))))
