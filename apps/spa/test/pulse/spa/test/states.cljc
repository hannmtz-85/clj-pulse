(ns pulse.spa.test.states
  "Every state a page of the workspace can be in besides showing what it is for, as an example of it (ADR-0029, Phase 8, step 9): what
  it says while it is on its way, when there is nothing to show, when some of it could not be loaded, when all of it could not, when
  what the address names is not there and when the api refuses the person. Not a test (its name does not end in `-test`): the
  inventory test holds the routes to it, and the test that renders to React uses it too.

  An example is `{:page (fn []) :says [text ...] :never [text ...]}`: the page, as a function so that nothing is built until it is
  asked for, what it must say, and what it must not (the words of the state it is not in: a list that failed is never \"No alerts\")."
  (:require [pulse.spa.appointments :as appointments]
            [pulse.spa.inbox :as inbox]
            [pulse.spa.jobs :as jobs]
            [pulse.spa.pages.alerts :as alerts-page]
            [pulse.spa.pages.appointments :as appointments-page]
            [pulse.spa.pages.jobs :as jobs-page]
            [pulse.spa.pages.panel :as panel-page]
            [pulse.spa.pages.patient :as patient-page]
            [pulse.spa.pages.start :as start]
            [pulse.spa.panel :as panel]
            [pulse.spa.patient :as patient]
            [pulse.spa.test.appointments-fixtures :as afx]
            [pulse.spa.test.inbox-fixtures :as ifx]
            [pulse.spa.test.jobs-fixtures :as jfx]
            [pulse.spa.test.panel-fixtures :as fx]
            [pulse.spa.test.patient-fixtures :as pfx]))

(def vocabulary
  "The states there are, and what each one means for a page:
  `:loading` the page has been asked for and nothing has come yet; `:empty` it has loaded and there is nothing to show;
  `:partial` some of it could not be loaded and the rest is shown; `:failed` none of it could; `:gone` what the address names is not
  there, or the api will not show it; `:forbidden` the api refuses the person the whole page; `:static` the page has nothing to load."
  #{:loading :empty :partial :failed :gone :forbidden :static})

(def rivera {:id "r1" :username "dr.rivera@pulse.test" :name "Elena Rivera" :roles #{:practitioner}})

(def sam {:id "s1" :username "admin@pulse.test" :name "Sam Admin" :roles #{:clinic-admin}})

(def links
  {:panel "/" :alerts "/alerts" :appointments "/appointments" :jobs "/jobs" :close "/appointments"
   :filter (fn [show] (str "/?show=" (name show)))
   :patient (fn [id] (str "/patients/" id))
   :chart (fn [{:keys [measurement days]}] (str "/patients/pt-ana?days=" days "&measurement=" (name measurement)))
   :dialog (fn [id action] (str "/appointments/" id "/" (name action)))})

(def handlers
  {:retry (fn []) :sign-out (fn []) :search (fn [_]) :acknowledge (fn [_ _]) :show-changes (fn [])
   :edit (fn [_ _]) :submit (fn [_]) :key-down (fn [_])})

(defn- options [person extra]
  (merge {:person person :links links :handlers handlers :alert-count 2} extra))

(defn- the-panel [state person]
  (panel-page/page state {:show :all :q ""} (options person nil)))

(defn- the-chart [state person]
  (patient-page/page state (options person nil)))

(defn- the-inbox [state person]
  (alerts-page/page state (options person {:show :all})))

(defn- the-appointments [state person]
  (appointments-page/page state (options person {:show :all})))

(defn- the-jobs [state]
  (jobs-page/page state (options sam nil)))

(defn- with-dialog [state id action]
  (appointments/sync-dialog state {:id id :action action}))

(defn- a-dialog
  "The examples of a dialog that answers an appointment with `action`: the appointment on its way, one that could not be loaded and
  one that is nowhere."
  [action]
  {:loading {:page #(the-appointments (with-dialog (assoc (afx/settled) :requests afx/loading) "appt-sofia-1" action) rivera)
             :says ["Loading the appointment"]
             :never ["We cannot find that appointment"]}
   :failed {:page #(the-appointments (with-dialog (assoc (afx/settled) :booked afx/failed) "appt-nobody" action) rivera)
            :says ["The appointment could not be loaded"]
            :never ["We cannot find that appointment"]}
   :gone {:page #(the-appointments (with-dialog (afx/settled) "appt-nobody" action) rivera)
          :says ["We cannot find that appointment"]}})

(def examples
  "The examples of each route of the workspace, by the name of the route and then by state."
  {:panel
   {:loading {:page #(the-panel (panel/start fx/now) rivera)
              :says ["Loading your panel"]
              :never ["You have no patients yet"]}
    :empty {:page #(the-panel (-> (fx/settled)
                                  (assoc-in [:list :data] {:total 0 :offset 0 :limit 100 :items []})
                                  (assoc :rows {} :order []))
                              rivera)
            :says ["You have no patients yet"]}
    :partial {:page #(the-panel (-> (fx/settled)
                                    (assoc-in [:rows "pt-liam" :summary] fx/failed)
                                    (assoc-in [:rows "pt-ana" :series] fx/failed))
                                rivera)
              :says ["Ana García" "could not be loaded"]}
    :failed {:page #(the-panel (assoc (fx/settled) :list fx/failed :rows {} :order []) rivera)
             :says ["Your panel could not be loaded"]
             :never ["You have no patients yet" "No patients match"]}}

   :patient
   {:loading {:page #(the-chart (patient/start pfx/now {:id "pt-ana" :measurement nil :days 14}) rivera)
              :says ["Loading the chart of the patient"]
              :never ["No readings yet"]}
    :empty {:page #(the-chart (pfx/settled-noah) rivera)
            :says ["Noah Kim" "No readings yet"]}
    :partial {:page #(the-chart (-> (pfx/settled-ana)
                                    (assoc :summary {:state :failed :kind :server})
                                    (assoc :series {:state :failed :kind :timeout :measurement :blood-pressure :days 14}))
                                rivera)
              :says ["Ana García" "could not be loaded"]
              :never ["No readings yet"]}
    :failed {:page #(the-chart (assoc (pfx/settled-ana) :patient {:state :failed :kind :server}) rivera)
             :says ["The patient could not be loaded"]}
    :gone {:page #(the-chart (assoc (pfx/settled-ana) :patient pfx/forbidden) rivera)
           :says ["We cannot find that patient"]
           :never ["Ana García" "BP stage 2"]}}

   :alerts
   {:loading {:page #(the-inbox (inbox/start ifx/now) rivera)
              :says ["Loading the alerts"]
              :never ["No alerts"]}
    :empty {:page #(the-inbox (-> (ifx/settled) (assoc-in [:active :data] ifx/none) (assoc-in [:acknowledged :data] ifx/none)) rivera)
            :says ["No alerts"]}
    :partial {:page #(the-inbox (assoc (ifx/settled) :patients ifx/failed) rivera)
              :says ["The names of the patients could not be loaded" "Needs a decision"]
              :never ["No alerts"]}
    :failed {:page #(the-inbox (assoc (ifx/settled) :active ifx/failed :acknowledged ifx/failed) rivera)
             :says ["The alerts could not be loaded" "The acknowledged alerts could not be loaded"]
             :never ["No alerts" "Nothing has been acknowledged yet."]}}

   :appointments
   {:loading {:page #(the-appointments (appointments/start afx/now) rivera)
              :says ["Loading the appointments"]
              :never ["Nothing is booked."]}
    :empty {:page #(the-appointments (assoc (afx/settled)
                                            :requests (afx/loaded afx/none) :booked (afx/loaded afx/none) :cancelled (afx/loaded afx/none))
                                     rivera)
            :says ["No request is waiting for an answer." "Nothing is booked." "Nothing has been cancelled."]}
    :partial {:page #(the-appointments (assoc (afx/settled) :booked afx/failed) rivera)
              :says ["The booked appointments could not be loaded" "Requests to answer"]
              :never ["Nothing is booked."]}
    :failed {:page #(the-appointments (assoc (afx/settled) :requests afx/failed :booked afx/failed :cancelled afx/failed) rivera)
             :says ["The requests could not be loaded" "The booked appointments could not be loaded" "The cancelled appointments could not be loaded"]
             :never ["No request is waiting for an answer." "Nothing is booked." "Nothing has been cancelled."]}}

   :appointment-confirm (a-dialog :confirm)
   :appointment-reschedule (a-dialog :reschedule)
   :appointment-cancel (a-dialog :cancel)

   :jobs
   {:loading {:page #(the-jobs (jobs/start jfx/now))
              :says ["Loading the jobs"]
              :never ["No jobs"]}
    :empty {:page #(the-jobs {:now jfx/now :list (jfx/loaded {:total 0 :items []})})
            :says ["No jobs"]}
    :failed {:page #(the-jobs {:now jfx/now :list jfx/failed})
             :says ["The jobs could not be loaded"]
             :never ["No jobs"]}
    :forbidden {:page #(the-jobs {:now jfx/now :list {:state :failed :kind :forbidden}})
                :says ["You cannot see the jobs"]
                :never ["No jobs"]}}

   :signed-out
   {:static {:page #(start/signed-out {:links {:sign-in "/"}})
             :says ["You have signed out"]}}})

(def outside
  "The pages that are not a route: what the workspace shows before it is open, when it could not be, when a page cannot be drawn, for
  an address that is no page and for a page the person's role does not open. By name."
  {:starting {:page #(start/starting)
              :says ["Signing in"]
              :never ["taking longer"]}
   :slow-start {:page #(start/starting {:slow? true :handlers {:retry (fn [])}})
                :says ["This is taking longer than usual" "Try again"]}
   :sign-in-failed {:page #(start/failed {:retry (fn [])})
                    :says ["We could not sign you in" "Try again"]}
   :refused {:page #(start/refused {:links {:portal "http://localhost:3300"} :handlers {:sign-out (fn [])}})
             :says ["This workspace is for the clinic" "Go to the patient portal" "Sign out"]}
   :unauthorized {:page #(start/unauthorized {:links {:sign-in "/"}})
                  :says ["Your sign-in was not accepted" "Sign in again"]}
   :crashed {:page #(start/crashed {:handlers {:reload (fn []) :panel (fn []) :sign-out (fn [])}})
             :says ["Something went wrong" "The workspace could not show this page." "Reload the page" "Go to the panel" "Sign out"]}
   :not-found {:page #(start/not-found {:person rivera :links {:panel "/"} :handlers {:sign-out (fn [])}})
               :says ["We cannot find that page" "Go to the panel"]}
   :not-allowed {:page #(start/not-allowed {:person rivera :links {:panel "/"} :handlers {:sign-out (fn [])}})
                 :says ["You cannot open that page" "Go to the panel"]}})
