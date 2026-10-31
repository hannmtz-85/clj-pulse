(ns pulse.spa.pages.jobs
  "The page of the scheduler's jobs, for the clinic's administrator (ADR-0029): how many there are, how many are running and how many failed
  their last run, an alert for each of those, and for each job what it does, when it is due and how its last run went. Pure hiccup of the
  components of `libs/ui`, with no Reagent, so that the workspace and the mock-up of the review make the same page: `content` is what both
  show, and `page` is the workspace around it.

  What the page is a function of is the state of the jobs as `pulse.spa.jobs` keeps it. **What could not be loaded is said, in its place,
  and is never shown as empty**: the scheduler is another service, and when the api cannot ask it the page is a message with a button to
  try again, and not \"No jobs\".

  A control that has nothing to lead to is not there: the button that tries again only when `:retry` is among the `handlers`, and the one
  that goes back to the panel only when `:panel` is among the `links`."
  (:require [clojure.string :as str]
            [pulse.spa.jobs :as jobs]
            [pulse.spa.pages.parts :as parts]
            [pulse.spa.pages.shell :as shell]
            [pulse.ui.component :as c]))

;; ---------------------------------------------------------------------------
;; A job

(defn- outcome-chip
  "How a run ended, in a word and an icon, never in colour alone."
  [outcome]
  (case outcome
    :ok (c/tone-badge {:tone :normal :size :sm} "OK")
    :failed (c/tone-badge {:tone :critical :size :sm} "Failed")
    :interrupted (c/tone-badge {:tone :high :size :sm} "Interrupted")
    (c/badge {:variant :outline} "Unknown")))

(defn- run-line
  "How the last run of a job went: its outcome, when it began, how long it took, and what it reported (or the class of its error)."
  [{:keys [outcome started duration result error]}]
  (let [timing (str/join " · " (keep identity [started duration]))]
    [:span {:class "flex flex-wrap items-center gap-x-2 gap-y-1"}
     (outcome-chip outcome)
     (when (seq timing) [:span timing])
     (cond result [:span {:class "text-muted wrap-anywhere"} result]
           error [:span {:class "text-muted wrap-anywhere"} (str "Error: " error)])]))

(defn- state-chip [{:keys [running? since]}]
  (if running?
    (c/tone-badge {:tone :info :icon :loader-circle} (if since (str "Running since " since) "Running"))
    (c/badge {:variant :outline} "Idle")))

(defn- job-row [{:keys [id description schedule last-run next-run runs] :as row}]
  (c/list-row
   {:trailing-wraps? true :trailing (state-chip row)}
   [:p {:class "font-semibold wrap-anywhere"} id]
   (when description [:p {:class "mb-3 text-muted wrap-anywhere"} description])
   (c/facts (keep identity [(when schedule ["Schedule" schedule])
                            ["Last run" (if last-run (run-line last-run) "Has not run yet")]
                            ["Next run" (or next-run "Not scheduled")]
                            (when runs ["Runs" runs])]))))

;; ---------------------------------------------------------------------------
;; The page

(defn- figures [{:keys [total running failed]}]
  [:div {:class "grid gap-4 sm:grid-cols-3"}
   (c/stat {:label "Jobs" :value (str total) :icon :timer})
   (c/stat {:label "Running now" :value (str running) :icon :loader-circle})
   (c/stat {:label "Last run failed" :value (str failed) :icon :circle-alert :tone (when (pos? failed) :high)})])

(defn- failure-alert
  "The alert for a job whose last run failed. What the scheduler reports (the class of an error, such as
  `java.util.concurrent.CompletionException`, which has no place to break) must not be cut by the edge of the alert on a phone."
  [{:keys [id text]}]
  (apply c/alert {:tone :high :title (parts/wrapped (str "The last run of " id " failed"))} (when (seq text) [(parts/wrapped text)])))

(defn- loaded [{:keys [stats failures rows]}]
  (list
   (figures stats)
   (for [failure failures] (failure-alert failure))
   (c/card {:flush? true}
           (if (seq rows)
             (c/list-group {:aria-label "Jobs of the scheduler"} (map job-row rows))
             (c/empty-state {:icon :timer :title "No jobs"} "The scheduler has no jobs.")))))

(defn- forbidden [links]
  (c/card (c/empty-state {:icon :lock
                          :title "You cannot see the jobs"
                          :actions (when-let [panel (:panel links)]
                                     (c/button {:variant :outline :href panel} "Back to the panel"))}
                         "Only the clinic's administrator can.")))

(defn content
  "The jobs as a page: `state` is the state of the jobs (see `pulse.spa.jobs`; nil before it begins to load), and `options` are
  `:links` (`:panel`, the address of the panel, for the way back from a page that is not for the person) and `:handlers` (`:retry`)."
  [state {:keys [links handlers]}]
  (let [model (jobs/model (or state jobs/not-begun))]
    [:div {:class "space-y-6"}
     (c/page-header {:title (:title model) :description (:description model)})
     [:p {:class "sr-only" :role "status"} (:live model)]
     (case (:state model)
       :loading [:div {:class "space-y-4"}
                 [:div {:class "grid gap-4 sm:grid-cols-3"} (repeat 3 (c/skeleton {:class "h-24 w-full"}))]
                 (c/card {:flush? true}
                         (c/list-group {:aria-label "Jobs of the scheduler"}
                                       (repeat 3 [:li {:class "px-5 py-4"} (c/skeleton {:class "h-16 w-full"})])))]
       :forbidden (forbidden links)
       :lost (parts/not-loaded "The jobs could not be loaded" nil handlers)
       :loaded (loaded model))]))

(defn page
  "The workspace around the jobs. `options` are those of `content`, and `:person` (who is signed in), `:me` (what the api said of them:
  see `pulse.spa.person/display`), `:alert-count` (how many alerts need a decision: see `pulse.spa.pages.shell/nav`), `:links` (`:panel`,
  `:alerts`, `:appointments` and `:jobs`, where the pages are for the sidebar) and `:handlers` (`:sign-out`, which puts \"Sign out\" in
  the menu of the person). There is no search box: it would have nothing to search here."
  [state {:keys [person me links handlers alert-count] :as options}]
  (shell/frame {:current :jobs :person person :me me :links links :handlers handlers :alert-count alert-count}
               (content state options)))
