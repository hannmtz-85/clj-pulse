(ns pulse.spa.events
  "The events of the workspace: pure functions from the state and an event to the effects that follow (ADR-0029, point 3). What
  they ask for is data; `pulse.spa.auth` and `pulse.spa.fetch` do it.

  The state holds who the person is and never a token. What the workspace is doing is its `:status`: `:starting` (asking the identity
  provider), `:signed-in`, `:refused` (signed in, and the workspace is not for them), `:unauthorized` (the api did not accept the
  sign-in), `:signed-out` (the page of someone who has signed out) or `:failed` (the sign-in could not be made). Each thing that is
  loaded is a part of its own, `{:state :loading|:loaded|:failed ...}`, so that what could not be loaded is said and is never shown
  as empty.

  Where the workspace is, is its `:route` (`{:name :params}`, see `pulse.spa.route`), which the history of the browser tells
  `::navigated` every time it changes: that is what starts the panel when the person comes to it and stops it when they leave, and
  what the title of the tab and the focus follow."
  (:require [pulse.spa.auth :as auth]
            [pulse.spa.fetch :as fetch]
            [pulse.spa.person :as person]
            [pulse.spa.route :as route]
            [pulse.spa.router :as router]
            [re-frame.core :as rf]))

(rf/reg-event-fx
 ::start
 (fn [_ _]
   {:db {:status :starting}
    ::auth/init {:on-success [::signed-in] :on-failure [::sign-in-failed]}}))

(rf/reg-event-fx
 ::signed-in
 (fn [{:keys [db]} [_ claims]]
   (if-let [who (person/from-claims claims)]
     (if (person/allowed? who)
       {:db (assoc db :status :signed-in :person who)
        :fx [[:dispatch [::load-me]]
             [::router/start nil]]}
       ;; the workspace is not for them: nothing is asked of the api, which would refuse everything
       {:db (assoc db :status :refused :person who)})
     {:db (assoc db :status :failed)})))

(rf/reg-event-db
 ::sign-in-failed
 (fn [db _]
   (assoc db :status :failed)))

(rf/reg-event-db
 ::signed-out
 (fn [_ _]
   {:status :signed-out}))

(rf/reg-event-fx
 ::sign-out
 (fn [_ _]
   {::auth/logout nil}))

(rf/reg-event-fx
 ::navigated
 (fn [{:keys [db]} [_ here]]
   (let [{:keys [page-change? stop start] updates :update} (route/transition (:route db) here (:person db))]
     {:db (assoc db :route here)
      :fx (-> []
              (into (map (fn [event] [:dispatch event])) stop)
              (into (map (fn [event] [:dispatch event])) start)
              (into (map (fn [event] [:dispatch event])) updates)
              (cond-> page-change? (conj [::router/arrived {:title (route/title (:name here))
                                                            ;; the first page is not one the person came to: nothing is moved for it
                                                            :focus? (some? (:route db))}])))})))

(rf/reg-event-db
 ::unauthorized
 (fn [db _]
   (assoc db :status :unauthorized)))

;; ---------------------------------------------------------------------------
;; Who the api says the person is: the first call the workspace makes, which shows that the api accepts its token

(rf/reg-event-fx
 ::load-me
 (fn [{:keys [db]} _]
   {:db (assoc db :me {:state :loading})
    ::fetch/request {:method :get
                     :path ["api" "me"]
                     :id ::me
                     :on-success [::me-loaded]
                     :on-failure [::me-failed]}}))

(rf/reg-event-db
 ::me-loaded
 (fn [db [_ {:keys [body]}]]
   (assoc db :me {:state :loaded :data body})))

(rf/reg-event-db
 ::me-failed
 (fn [db [_ {:keys [kind]}]]
   (assoc db :me {:state :failed :kind kind})))
