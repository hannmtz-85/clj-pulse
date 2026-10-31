(ns pulse.spa.route-appointments-test
  "The routes of the appointments and of the jobs (ADR-0029, point 5): the list of appointments and the dialogs over it, which are one page
  so that opening a dialog loads nothing again, the parameters of their addresses read the way a hand-written one still gets a page, and the
  jobs, which are the administrator's. These run on the JVM and on Node."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.spa.person :as person]
            [pulse.spa.route :as route]
            [reitit.core :as r]))

(def ^:private rivera {:id "r1" :name "Elena Rivera" :roles #{:practitioner}})
(def ^:private sam {:id "s1" :name "Sam Admin" :roles #{:clinic-admin}})
(def ^:private ana {:id "a1" :name "Ana García" :roles #{:patient}})

(def ^:private list-here {:name :appointments :params {:show :all}})
(def ^:private confirm-here {:name :appointment-confirm :params {:show :all :id "appt-sofia-1" :action :confirm}})
(def ^:private cancel-here {:name :appointment-cancel :params {:show :booked :id "appt-ana-1" :action :cancel}})
(def ^:private chart-here {:name :patient :params {:id "pt-ana" :measurement nil :days 14}})
(def ^:private panel-here {:name :panel :params {:show :all :q ""}})

;; ---------------------------------------------------------------------------
;; The table

(deftest the-table-of-routes-has-the-appointments-and-the-jobs
  (is (= [] (route/problems route/routes)))
  (is (= [:panel :patient :alerts :appointments :appointment-confirm :appointment-reschedule :appointment-cancel :jobs :signed-out]
         (map (comp :name second) route/routes)))
  (is (= ["/" "/patients/:id" "/alerts" "/appointments" "/appointments/:id/confirm" "/appointments/:id/reschedule" "/appointments/:id/cancel"
          "/jobs" "/signed-out"]
         (map first route/routes))))

(deftest the-appointments-are-for-the-clinic-and-are-one-page-with-their-dialogs
  (doseq [route-name [:appointments :appointment-confirm :appointment-reschedule :appointment-cancel]
          :let [data (route/data route-name)]]
    (is (= person/workspace-roles (:roles data)) (name route-name))
    (is (not (:public? data)) (name route-name))
    (is (= :appointments (:page data)) "the list and each dialog are one page")
    (is (= [[:pulse.spa.appointments/load]] (:start data)))
    (is (= [[:pulse.spa.appointments/choose]] (:update data)))
    (is (= [[:pulse.spa.appointments/leave]] (:stop data)))
    (is (nil? (:refresh data)) "nothing repeats it: the beat does not look at it")
    (is (nil? (:page-params data)) "the appointment of the dialog is not what makes it another page")))

(deftest the-titles-of-the-tab-say-which-dialog-is-open
  (is (= "Appointments · Pulse" (route/title :appointments)))
  (is (= "Confirm an appointment · Pulse" (route/title :appointment-confirm)))
  (is (= "Reschedule an appointment · Pulse" (route/title :appointment-reschedule)))
  (is (= "Cancel an appointment · Pulse" (route/title :appointment-cancel))))

(deftest a-table-with-a-page-that-is-not-a-keyword-is-an-error
  (is (= ["the route \"/x\" has a :page that is not a keyword"]
         (route/problems [["/x" {:name :x :title "X" :roles person/workspace-roles :page "x"}]])))
  (is (= [] (route/problems [["/x" {:name :x :title "X" :roles person/workspace-roles :page :y}]]))))

;; ---------------------------------------------------------------------------
;; The addresses

(deftest the-list-is-all-of-the-appointments-unless-the-address-narrows-it
  (is (= {:show :all} (route/appointments-params nil)))
  (is (= {:show :all} (route/appointments-params {})))
  (is (= {:show :requests} (route/appointments-params {:show "requests"})))
  (is (= {:show :booked} (route/appointments-params {:show "booked"})))
  (is (= {:show :cancelled} (route/appointments-params {:show "cancelled"}))))

(deftest an-address-of-the-list-written-by-hand-still-gets-the-list
  (doseq [query [{:show "all"} {:show "REQUESTS"} {:show ""} {:show "requests "} {:show nil} {:show 5} {:show [5]}
                 {:show {:a 1}} {:q "ana"} {"show" "requests"}]]
    (is (map? (route/appointments-params query)) (pr-str query))
    (is (contains? #{:all :requests :booked :cancelled} (:show (route/appointments-params query))) (pr-str query)))
  (is (= :booked (:show (route/appointments-params {:show ["booked" "requests"]}))) "a parameter that came twice is its first")
  (is (= :all (:show (route/appointments-params {:show "all"}))) "all is not a narrowing")
  (is (= :all (:show (route/appointments-params {:show "REQUESTS"}))) "the names are those of the page"))

(deftest the-address-of-the-list-leaves-out-what-is-the-default
  (is (= "/appointments" (route/appointments-path)))
  (is (= "/appointments" (route/appointments-path nil)))
  (is (= "/appointments" (route/appointments-path {:show :all})))
  (is (= "/appointments?show=requests" (route/appointments-path {:show :requests})))
  (is (= "/appointments?show=booked" (route/appointments-path {:show :booked})))
  (is (= "/appointments?show=cancelled" (route/appointments-path {:show :cancelled})))
  (is (= "/appointments" (route/appointments-path {:show :nothing})) "what is not a narrowing is none")
  (is (= {} (route/appointments-query :all)))
  (is (= {:show "booked"} (route/appointments-query :booked))))

(deftest the-address-of-a-dialog-names-the-appointment-and-the-command-and-keeps-the-narrowing-of-the-list
  (is (= "/appointments/appt-sofia-1/confirm" (route/appointment-path "appt-sofia-1" :confirm)))
  (is (= "/appointments/appt-sofia-1/reschedule" (route/appointment-path "appt-sofia-1" :reschedule)))
  (is (= "/appointments/appt-sofia-1/cancel" (route/appointment-path "appt-sofia-1" :cancel)))
  (is (= "/appointments/appt-ana-1/cancel?show=booked" (route/appointment-path "appt-ana-1" :cancel {:show :booked})))
  (is (= "/appointments/appt-ana-1/cancel" (route/appointment-path "appt-ana-1" :cancel {:show :all})))
  (testing "an id is a segment of its own"
    (is (= "/appointments/a%2Fb/confirm" (route/appointment-path "a/b" :confirm)))
    (is (= "/appointments/a%20b/confirm" (route/appointment-path "a b" :confirm)))))

(defn- query-map
  "The query of `path` as the history of the browser reads it: a map from keywords to texts."
  [path]
  (when-let [query (second (str/split path #"\?"))]
    (into {} (map #(let [[k v] (str/split % #"=")] [(keyword k) v])) (str/split query #"&"))))

(deftest the-address-of-a-dialog-and-what-it-is-read-from-go-round
  (doseq [[id action show] [["appt-1" :confirm :all] ["appt-2" :reschedule :requests] ["a.b" :cancel :booked] ["x" :cancel :cancelled]]
          :let [path (route/appointment-path id action {:show show})
                match (assoc (r/match-by-path route/router (first (str/split path #"\?"))) :query-params (query-map path))]]
    (is (= {:name (keyword (str "appointment-" (name action))) :params {:show show :id id :action action}} (route/from-match match)) path)))

(deftest the-dialog-the-workspace-is-on-is-read-from-the-path-and-the-query
  (is (= {:name :appointment-confirm :params {:show :all :id "appt-sofia-1" :action :confirm}}
         (route/from-match (r/match-by-path route/router "/appointments/appt-sofia-1/confirm"))))
  (is (= {:name :appointment-reschedule :params {:show :booked :id "appt-ana-1" :action :reschedule}}
         (route/from-match (assoc (r/match-by-path route/router "/appointments/appt-ana-1/reschedule") :query-params {:show "booked"}))))
  (is (= {:name :appointments :params {:show :all}} (route/from-match (r/match-by-path route/router "/appointments")))))

(deftest a-dialog-for-an-id-that-cannot-be-an-appointments-is-the-page-of-nothing
  (doseq [id ["." ".." "a b" "a?b" "a:b" (apply str (repeat 65 "a")) "a_b"]
          action [:appointment-confirm :appointment-reschedule :appointment-cancel]]
    (is (= {:name :not-found :params {}} (route/from-match {:data (route/data action) :path-params {:id id} :query-params {}})) (str id " " action)))
  (is (not (route/appointment-id? "..")))
  (is (route/appointment-id? "appt-1")))

(deftest the-jobs-are-for-the-administrator-alone
  (let [data (route/data :jobs)]
    (is (= #{:clinic-admin} (:roles data)))
    (is (not (:public? data)))
    (is (= [[:pulse.spa.jobs/load]] (:start data)))
    (is (= [[:pulse.spa.jobs/leave]] (:stop data)))
    (is (nil? (:refresh data)) "nothing repeats it"))
  (is (true? (route/allowed? :jobs sam)))
  (is (false? (route/allowed? :jobs rivera)) "a practitioner has no jobs")
  (is (false? (route/allowed? :jobs ana)))
  (is (false? (route/allowed? :jobs {:roles #{:rules-engine :api-service}})))
  (is (false? (route/allowed? :jobs nil)))
  (is (= "Jobs · Pulse" (route/title :jobs)))
  (is (= "/jobs" route/jobs-path))
  (is (= {:name :jobs :params {}} (route/from-match (r/match-by-path route/router "/jobs")))))

(deftest every-route-of-the-appointments-is-open-to-the-clinic-and-to-nobody-else
  (doseq [route-name [:appointments :appointment-confirm :appointment-reschedule :appointment-cancel]]
    (is (true? (route/allowed? route-name rivera)))
    (is (true? (route/allowed? route-name sam)))
    (is (false? (route/allowed? route-name ana)))
    (is (false? (route/allowed? route-name nil)))))

;; ---------------------------------------------------------------------------
;; What the change of route starts, updates and stops

(deftest coming-to-the-list-starts-it-and-leaving-it-stops-it
  (is (= {:page-change? true :stop [] :start [[:pulse.spa.appointments/load {:show :all}]] :update []}
         (route/transition nil list-here rivera)))
  (is (= {:page-change? true :stop [[:pulse.spa.panel/leave]] :start [[:pulse.spa.appointments/load {:show :all}]] :update []}
         (route/transition panel-here list-here rivera)))
  (is (= {:page-change? true :stop [[:pulse.spa.appointments/leave]] :start [[:pulse.spa.panel/load {:show :all :q ""}]] :update []}
         (route/transition list-here panel-here rivera)))
  (is (= {:page-change? true :stop [[:pulse.spa.appointments/leave]] :start [[:pulse.spa.jobs/load {}]] :update []}
         (route/transition list-here {:name :jobs :params {}} sam))))

(deftest coming-to-a-dialog-from-another-page-starts-the-page-with-the-dialog-the-address-says
  (is (= {:page-change? true :stop [] :start [[:pulse.spa.appointments/load (:params confirm-here)]] :update []}
         (route/transition nil confirm-here rivera)) "an address that was written, or a link from outside")
  (is (= {:page-change? true :stop [[:pulse.spa.patient/leave]] :start [[:pulse.spa.appointments/load (:params confirm-here)]] :update []}
         (route/transition chart-here confirm-here rivera))
      "the button of an appointment on a chart leads to the dialog over the list"))

(deftest opening-and-closing-a-dialog-is-the-same-page-and-loads-nothing-again
  (is (= {:page-change? false :stop [] :start [] :update [[:pulse.spa.appointments/choose (:params confirm-here)]]}
         (route/transition list-here confirm-here rivera)) "the dialog is what the address says")
  (is (= {:page-change? false :stop [] :start [] :update [[:pulse.spa.appointments/choose {:show :all}]]}
         (route/transition confirm-here list-here rivera)) "and the list has none")
  (is (= {:page-change? false :stop [] :start [] :update [[:pulse.spa.appointments/choose (:params cancel-here)]]}
         (route/transition confirm-here cancel-here rivera)) "from one dialog to another")
  (is (= {:page-change? false :stop [] :start [] :update [[:pulse.spa.appointments/choose (assoc (:params confirm-here) :id "appt-ana-2")]]}
         (route/transition confirm-here (assoc-in confirm-here [:params :id] "appt-ana-2") rivera)) "to another appointment"))

(deftest narrowing-the-list-is-the-same-page-and-loads-nothing-again
  (doseq [to [{:name :appointments :params {:show :requests}} {:name :appointments :params {:show :booked}}]]
    (is (= {:page-change? false :stop [] :start [] :update [[:pulse.spa.appointments/choose (:params to)]]}
           (route/transition list-here to rivera)) (pr-str to))))

(deftest leaving-by-a-dialog-stops-the-page
  (is (= {:page-change? true :stop [[:pulse.spa.appointments/leave]] :start [[:pulse.spa.panel/load {:show :all :q ""}]] :update []}
         (route/transition confirm-here panel-here rivera))))

(deftest a-dialog-the-person-may-not-open-starts-nothing
  (is (= {:page-change? true :stop [] :start [] :update []} (route/transition nil confirm-here ana)))
  (is (= {:page-change? false :stop [] :start [] :update []} (route/transition list-here confirm-here ana))))

(deftest the-jobs-are-started-by-the-administrator-alone
  (is (= {:page-change? true :stop [] :start [[:pulse.spa.jobs/load {}]] :update []}
         (route/transition nil {:name :jobs :params {}} sam)))
  (is (= {:page-change? true :stop [] :start [] :update []} (route/transition nil {:name :jobs :params {}} rivera))
      "a practitioner who writes the address starts nothing, and no call is made"))

(deftest the-beat-does-not-look-at-the-appointments-or-the-jobs
  (is (= [] (route/refresh-events list-here rivera)))
  (is (= [] (route/refresh-events confirm-here rivera)))
  (is (= [] (route/refresh-events {:name :jobs :params {}} sam))))
