(ns pulse.spa.pages.shell
  "The frame of every page of the clinical workspace (ADR-0029): the sidebar, the header and the menu of the person, around the
  page. Pure hiccup of the components of `libs/ui`, with no Ring and no Reagent, so that the SPA and the mock-ups of the review
  make the same frame.

  Where each page is comes in `links` (`:panel`, `:alerts`, `:appointments`, `:jobs`): **a link that is not in `links` is a page
  that the workspace does not have yet, and the item that would lead there is not there**, as in the portal."
  (:require [pulse.spa.person :as person]
            [pulse.ui.layout :as layout]))

(defn nav
  "The groups of the sidebar, from the pages that exist (`links`, the address of each). The administrator has one more group,
  with the jobs of the scheduler, which a practitioner does not see. `:active-alerts` is the count that the Alerts item shows
  (none when there are none, or when it is not known)."
  [{:keys [admin? active-alerts]} links]
  (let [main (cond-> []
               (:panel links) (conj {:id :panel :href (:panel links) :label "Panel" :icon :layout-dashboard})
               (:alerts links) (conj (cond-> {:id :alerts :href (:alerts links) :label "Alerts" :icon :bell}
                                       (pos? (or active-alerts 0)) (assoc :count active-alerts
                                                                          :count-label (str active-alerts " active"))))
               (:appointments links) (conj {:id :appointments :href (:appointments links) :label "Appointments" :icon :calendar}))]
    (cond-> []
      (seq main) (conj {:items main})
      (and admin? (:jobs links)) (conj {:heading "Administration"
                                        :items [{:id :jobs :href (:jobs links) :label "Jobs" :icon :server}]}))))

(defn shell
  "The workspace around `content`. `options` are `:current` (the id of the item of this page), `:person` (`{:name :detail}`),
  `:nav` (from `nav`), `:search` (the arguments of the search box, when there is one) and `:menu`, the items of the menu of the
  person (`{:href :label :icon}`, as `user-menu` takes them: none until the workspace can sign a person out, step 4 of the
  phase). The children are the page."
  [{:keys [current person nav search menu]} & content]
  (apply layout/workspace-shell
         ;; the buttons of the mock-up for the menu of a narrow screen and for the theme do nothing by themselves, and nothing
         ;; here gives them anything to do yet: a button that does nothing is not shown (X-93 for the theme)
         {:current current
          :nav nav
          :search search
          :user {:name (:name person) :items (vec menu)}
          :user-detail (:detail person)
          :menu-button? false
          :theme-button? false}
         content))

(defn frame
  "The workspace around a page of the person who is signed in: the shell with the sidebar of the pages there are, the header and the
  menu of the person. `options` are `:current` (the id of the item of this page), `:person` (who is signed in), `:me` (what the api
  said of them: see `pulse.spa.person/display`), `:links` (where the pages are: see `nav`), `:handlers` (`:sign-out`, which puts
  \"Sign out\" in the menu of the person, and `:search`, a function of the text typed in the box, which puts the box in the header) and
  `:search-value` (what the box holds). The children are the page."
  [{:keys [current person me links handlers search-value]} & content]
  (apply shell
         {:current current
          :person (person/display person me)
          :nav (nav {:admin? (person/admin? person)} links)
          :search (when-let [on-search (:search handlers)]
                    {:id "search" :name "q" :label "Search patients" :placeholder "Search patients"
                     :value (or search-value "") :on-change on-search :autocomplete "off"})
          :menu (when-let [sign-out (:sign-out handlers)]
                  [{:label "Sign out" :icon :log-out :on-click sign-out}])}
         content))
