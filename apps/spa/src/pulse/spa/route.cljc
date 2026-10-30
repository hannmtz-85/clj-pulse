(ns pulse.spa.route
  "Where the workspace is, and what that asks of it (ADR-0029, point 5): the routes as data with deny by default, the parameters of
  an address read the way a person who edited it by hand must still get a page, and what starts and stops when the route changes.
  Pure, and `.cljc`, so that the JVM and Node run the same tests over it; the history of the browser and the click on a link are
  `pulse.spa.router`, which only the browser can run.

  A route says `:roles` (a set: who may open it) or `:public? true` (the page of someone who has signed out, which asks nobody to sign
  in), and **a route that says neither is a start-up error**, as the api's `check-routes!` is: the table is checked when this namespace
  loads. A route may say `:start` and `:stop`, the events to dispatch when the person comes to it and leaves it, and `:update`, those
  to dispatch when the parameters of the page change and the page is the same; `:page-params` names the parameters of its path that
  make it another page when they change (the id of a patient: the chart of one patient is not the chart of another). The routes here
  are the pages the workspace has: **a page that is not here yet is not linked to** (each step of the phase adds its own)."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [pulse.fhir.reference :as reference]
            [pulse.spa.http :as http]
            [pulse.spa.patient :as patient]
            [pulse.spa.person :as person]
            [pulse.view.views :as views]
            [reitit.core :as r]))

(def signed-out-path
  "Where the identity provider sends the browser once it has signed the person out (the realm registers every address of the
  workspace as a place it may do that). It is a page of the workspace that does **not** ask the person to sign in: it says they
  have signed out, and a person who wants to sign in again says so."
  "/signed-out")

;; ---------------------------------------------------------------------------
;; The table

(def panel-filters
  "What the panel can be narrowed to, by the `show` of its address: with active alerts, away from normal, normal."
  #{:alerts :away :normal})

(def search-limit
  "The longest search the panel takes from an address: more is cut."
  100)

(def routes
  "The pages of the workspace: `[path data]`. The data of a route is its `:name`, its `:title` (what the tab says, and what is
  announced when the person comes to it), `:roles` or `:public?`, `:page-params` (a vector of the keywords of the parameters of its
  path that say which page of it this is), and `:start`, `:update` and `:stop`, each a vector of events."
  [["/" {:name :panel
         :title "Panel"
         :roles person/workspace-roles
         :start [[:pulse.spa.panel/load]]
         :stop [[:pulse.spa.panel/leave]]}]
   ["/patients/:id" {:name :patient
                     :title "Patient chart"
                     :roles person/workspace-roles
                     :page-params [:id]
                     :start [[:pulse.spa.patient/load]]
                     :update [[:pulse.spa.patient/choose]]
                     :stop [[:pulse.spa.patient/leave]]}]
   [signed-out-path {:name :signed-out
                     :title "Signed out"
                     :public? true}]])

(defn problems
  "What is wrong with a table of routes, as a vector of texts: empty when it is right. Every route has a unique `:name` (a keyword),
  a `:title` and either `:roles` (a set with at least one role of the application) or `:public? true`, its `:page-params` are
  keywords, and the events of `:start`, `:update` and `:stop` are vectors that begin with a keyword."
  [routes]
  (let [data (map second routes)
        names (keep :name data)
        events-ok? (fn [events] (and (vector? events) (every? #(and (vector? %) (keyword? (first %))) events)))]
    (vec (concat
          (for [[path {route-name :name :as route}] routes
                problem [(when-not (keyword? route-name) "has no name")
                         (when-not (and (string? (:title route)) (not (str/blank? (:title route)))) "has no title")
                         (cond
                           (and (:roles route) (:public? route)) "is both for some roles and public"
                           (:public? route) nil
                           (not (and (set? (:roles route)) (seq (:roles route)) (every? person/roles (:roles route))))
                           "says neither which roles may open it nor that it is public")
                         (when-not (events-ok? (:start route [])) "has a :start that is not a vector of events")
                         (when-not (events-ok? (:update route [])) "has an :update that is not a vector of events")
                         (when-not (events-ok? (:stop route [])) "has a :stop that is not a vector of events")
                         (when-not (let [page-params (:page-params route [])]
                                     (and (vector? page-params) (every? keyword? page-params)))
                           "has :page-params that are not a vector of keywords")]
                :when problem]
            (str "the route " (pr-str path) " " problem))
          (for [[route-name n] (frequencies names)
                :when (< 1 n)]
            (str "the name " (pr-str route-name) " is used by " n " routes"))))))

(defn check!
  "Fails with an `ex-info` that lists `(problems routes)` when there are any: a route that says neither who may open it nor that it is
  public would be a page open to whoever gets to it. Answers `routes`."
  [routes]
  (when-let [found (seq (problems routes))]
    (throw (ex-info (str "The routes are not right: " (str/join "; " found)) {:problems (vec found)})))
  routes)

(def router
  "The routes as reitit's router, which matches a path to its route. It is made when this namespace loads, so a table that is not right
  stops the workspace from starting."
  (r/router (check! routes)))

;; ---------------------------------------------------------------------------
;; Matching

(defn- data-of [match]
  (when match (:data match)))

(defn match-path
  "The route data of `path` (the path of an address, with no query), or nil when the workspace has no such page. A path may end in
  one slash, as the server of the release answers the page for it too."
  [path]
  (when (string? path)
    (let [clean (if (and (< 1 (count path)) (str/ends-with? path "/") (not (str/ends-with? path "//")))
                  (subs path 0 (dec (count path)))
                  path)]
      (data-of (r/match-by-path router clean)))))

(defn signed-out?
  "Whether `pathname`, the path of the address of the page, is the page of someone who has signed out. The server answers the
  page for a path with one slash at its end too."
  [pathname]
  (= :signed-out (:name (match-path pathname))))

(defn public?
  "Whether `pathname` is a page that asks nobody to sign in."
  [pathname]
  (boolean (:public? (match-path pathname))))

;; ---------------------------------------------------------------------------
;; The parameters of an address

(def ^:private show-schema
  (into [:enum] (map name) (sort panel-filters)))

(def ^:private search-schema
  [:string {:max search-limit}])

(defn- first-of
  "A query parameter that came twice (`?q=a&q=b`) is its first value; a value that is not text is nothing."
  [value]
  (let [value (if (sequential? value) (first value) value)]
    (when (string? value) value)))

(defn panel-params
  "What the panel is narrowed to, from the `query` of its address (a map from keywords to texts, or to vectors of them, as the
  history of the browser reads it): `{:show :all|:alerts|:away|:normal :q text}`. **It never fails**: a hand-written address gets
  the panel, with what is wrong in it left out (a `show` that is none of the above is all of them, a search that is longer than
  `search-limit` is cut, one that is not text is none)."
  [query]
  (let [show (first-of (:show query))
        q (first-of (:q query))]
    {:show (if (m/validate show-schema show) (keyword show) :all)
     :q (cond
          (nil? q) ""
          (m/validate search-schema q) q
          :else (subs q 0 search-limit))}))

(defn query-map
  "The query of the panel narrowed to `params` (`{:show :q}`) as the history of the browser takes it, a map from keywords to texts,
  with what is the default (all, no search) left out."
  [{:keys [show q]}]
  (cond-> {}
    (contains? panel-filters show) (assoc :show (name show))
    (and (string? q) (not (str/blank? q))) (assoc :q q)))

(defn panel-path
  "The path of the panel narrowed to `params` (`{:show :q}`), as a link holds it: `/`, or `/?q=ana&show=alerts`. What is the default
  (all, no search) is left out, so that the panel with nothing narrowed has the address of the panel."
  ([] (panel-path nil))
  ([params]
   (str "/" (some->> (http/query-string (query-map params)) (str "?")))))

(defn patient-id?
  "Whether `id` can be the id of a patient: the id of a FHIR resource, and not `.` or `..`, which no address holds as a segment (a call to
  the api is never made with one: see `pulse.spa.http/path`). An address with another is the page of nothing."
  [id]
  (boolean (and (string? id)
                (not (contains? #{"." ".."} id))
                (reference/parse (str "Patient/" id)))))

(def ^:private kind-names
  (into #{} (map name) views/kinds))

(defn patient-params
  "What the chart of a patient is narrowed to, from the `query` of its address (as `panel-params` takes it) and the `path` parameters
  that matched (`{:id}`): `{:id :measurement :days}`, where `:measurement` is a keyword of `pulse.view.views/kinds` or nil, for the
  patient's headline reading to choose, and `:days` one of `periods`. **It never fails**: a measurement that is none is nil, and a
  period that is none is `default-days`; the periods are compared as the text the page writes (`7`, not `+7` or `07`)."
  [query path]
  (let [measurement (first-of (:measurement query))
        days (first-of (:days query))]
    {:id (:id path)
     :measurement (when (contains? kind-names measurement) (keyword measurement))
     :days (or (first (filter #(= days (str %)) patient/periods)) patient/default-days)}))

(defn patient-path
  "The path of the chart of the patient `id`, narrowed to `params` (`{:measurement :days}`), as a link holds it: `/patients/pt-ana`, or
  `/patients/pt-ana?days=30&measurement=glucose`. What is the default (the headline measurement, 14 days) is left out."
  ([id] (patient-path id nil))
  ([id {:keys [measurement days]}]
   (str "/patients/" (http/encode id)
        (some->> (http/query-string (cond-> {}
                                      (some #{measurement} views/kinds) (assoc :measurement (name measurement))
                                      (and (some #{days} patient/periods) (not= days patient/default-days)) (assoc :days days)))
                 (str "?")))))

(defn params-of
  "The parameters of the route `route-name` from the `query` of an address and, for a route that has them, the `path` parameters."
  ([route-name query] (params-of route-name query nil))
  ([route-name query path]
   (case route-name
     :panel (panel-params query)
     :patient (patient-params query path)
     {})))

(defn from-match
  "The route the workspace is on, from reitit's `match` of an address (`nil` when it matched none): `{:name :params}`, where `:name`
  is `:not-found` for an address that is no page of the workspace, the chart of an id that cannot be a patient's among them."
  [match]
  (if-let [data (data-of match)]
    (let [params (params-of (:name data) (:query-params match) (:path-params match))]
      (if (and (= :patient (:name data)) (not (patient-id? (:id params))))
        {:name :not-found :params {}}
        {:name (:name data) :params params}))
    {:name :not-found :params {}}))

(defn data
  "The data of the route `route-name` (`:title`, `:roles`...), or nil."
  [route-name]
  (some-> (r/match-by-name router route-name) :data))

(defn title
  "What the tab says on the route `route-name`, such as `Panel · Pulse`; for a page that is not there, \"Page not found\"."
  [route-name]
  (str (if (= :not-found route-name) "Page not found" (or (:title (data route-name)) "Workspace")) " · Pulse"))

(defn allowed?
  "Whether `person` may open the route `route-name`: a public route is for everyone, and any other for a person who has one of its
  roles. A route that is not there, `:not-found`, is open to be told so. It is for the person's sake, who is told so and not shown a
  page of refusals; the api decides all the same (ADR-0012)."
  [route-name person]
  (let [{:keys [roles public?] :as route-data} (data route-name)]
    (cond
      (= :not-found route-name) true
      (nil? route-data) false
      public? true
      :else (boolean (some roles (:roles person))))))

(defn- page-of
  "What makes `here` (`{:name :params}`) the page it is: its route, and the parameters of its path that say which page of the route it
  is (the id of the patient, for the chart)."
  [here]
  [(:name here) (select-keys (:params here) (:page-params (data (:name here))))])

(defn transition
  "What the change from the route `from` to the route `to` (each `{:name :params}`, or nil before the first) starts, updates and
  stops: the `:stop` events of the route that is left, then the `:start` events of the route that is come to, **only when the page
  changes** (another route, or another page of the route: the chart of another patient). A change of the other parameters is the same
  page: the filter or the search of the panel loads nothing again, and the measurement or the period of a chart is the `:update` of
  its route, if it has one. `:page-change?` says it, for the focus. The events of `:start` and `:update` are given the parameters of
  the route. A route that the person may not open starts and updates nothing."
  [from to person]
  (let [changed? (not= (page-of from) (page-of to))
        route-data (data (:name to))
        allowed (allowed? (:name to) person)
        with-params (fn [events] (mapv #(conj % (:params to)) events))]
    {:page-change? changed?
     :stop (if changed? (vec (:stop (data (:name from)))) [])
     :start (if (and changed? allowed) (with-params (:start route-data)) [])
     :update (if (and (not changed?) allowed (not= (:params from) (:params to))) (with-params (:update route-data)) [])}))
