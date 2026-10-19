(ns pulse.ui.component
  "The presentational components of the interfaces (ADR-0026), as functions that return hiccup: no state and no
  handlers. The first argument may be a map of attributes; the keys a component owns are taken out of it and
  the rest goes through to the element, so `hx-get` or `:on-click` works on any of them. Hiccup 2 renders them
  to strings in `web` and Reagent to React elements in `spa`.

  Every class is a whole string literal, here or in a table (see `pulse.ui.attrs`). The colours are those of the
  theme, never a fixed value, so the same markup is light or dark and teal or blue."
  (:require [clojure.string :as str]
            [pulse.ui.attrs :as attrs]
            [pulse.ui.icon :as i]
            [pulse.ui.tone :as tone]))

(defn- pick
  "The value of `k` in `table`; throws for a key the component does not know, so that a misspelt variant is
  found when the page is made and not as a button that looks like nothing."
  [table k what]
  (if (contains? table k)
    (get table k)
    (throw (ex-info (str "There is no " what " called " (pr-str k)) {:what what :given k :known (keys table)}))))

;; ---------------------------------------------------------------------------
;; Buttons

(def ^:private button-variants
  {:primary "btn-primary"
   :neutral "btn-neutral"
   :outline "btn-outline btn-primary"
   :subtle "btn-accent"
   :ghost "btn-ghost"
   :danger "btn-error"
   :link "btn-link"})

(def ^:private button-sizes
  {:xs "btn-xs" :sm "btn-sm" :md nil :lg "btn-lg"})

(defn button
  "A button, or a link that looks like one when it has an `:href`. Options: `:variant` (`:primary`, `:neutral`,
  `:outline`, `:subtle`, `:ghost`, `:danger` or `:link`), `:size` (`:xs`, `:sm`, `:md` or `:lg`: 24, 32, 40 and
  48 px high; the portal uses `:lg`, for a target of 44 px or more), `:icon` and `:icon-end` (names of icons),
  `:loading?` (it cannot be pressed and says it is busy) and `:block?` (the whole width)."
  [& args]
  (let [[{:keys [variant size icon icon-end loading? block? href]
          :or {variant :primary size :md}
          :as attributes} children] (attrs/args args)
        attributes (-> attributes
                       (attrs/owned :variant :size :icon :icon-end :loading? :block?)
                       (attrs/with-classes "btn" (pick button-variants variant "button variant")
                         (pick button-sizes size "button size") (when block? "btn-block")))
        attributes (if href
                     attributes
                     (cond-> (update attributes :type #(or % "button"))
                       loading? (assoc :disabled true :aria-busy "true")))]
    (-> [(if href :a :button) attributes]
        (into (when loading? [[:span {:class "loading loading-spinner loading-sm" :aria-hidden "true"}]]))
        (into (when (and icon (not loading?)) [(i/icon icon {:size :sm})]))
        (into children)
        (into (when icon-end [(i/icon icon-end {:size :sm})])))))

(defn icon-button
  "A button that is only an icon. `:label` is required: it is what a screen reader says."
  [{:keys [icon label] :as attributes}]
  (when (str/blank? label)
    (throw (ex-info "An icon button needs a :label" {:icon icon})))
  (button (-> attributes
              (attrs/owned :icon :label)
              (assoc :aria-label label)
              (attrs/with-classes "btn-square"))
          (i/icon icon {:size :md})))

;; ---------------------------------------------------------------------------
;; Badges and tones

(def ^:private badge-variants
  {:neutral "badge-neutral" :primary "badge-primary" :accent "badge-accent" :outline "badge-outline"})

(defn badge
  "A small label. `:variant` is `:neutral`, `:primary`, `:accent` or `:outline`."
  [& args]
  (let [[{:keys [variant] :or {variant :neutral} :as attributes} children] (attrs/args args)]
    (into [:span (-> attributes
                     (attrs/owned :variant)
                     (attrs/with-classes "badge" (pick badge-variants variant "badge variant")))]
          children)))

(def ^:private chip-sizes
  {:sm "gap-1 px-2 py-0.5 text-xs" :md "gap-1.5 px-2.5 py-1 text-sm"})

(defn tone-badge
  "A tone as a chip with its icon and its word, never its colour alone. `:tone` is `:normal`, `:elevated`,
  `:high`, `:critical` or `:info`; the word is the children, or the name of the tone; `:icon` replaces the
  tone's own icon; `:size` is `:sm` or `:md`."
  [& args]
  (let [[{tone-key :tone icon :icon size :size :or {tone-key :info size :md} :as attributes} children] (attrs/args args)
        t (tone/tone tone-key)]
    [:span (-> attributes
               (attrs/owned :tone :icon :size)
               (attrs/with-classes "inline-flex items-center whitespace-nowrap rounded-full border font-medium"
                 (pick chip-sizes size "chip size") (:surface t)))
     (i/icon (or icon (:icon t)) {:size :sm :class (:icon-colour t)})
     (if (seq children) children (:label t))]))

(defn band-badge
  "The chip of a band of a reading, given as the api names it (`very-high`), or nil when the reading has none.
  `attributes` are passed to the chip."
  ([band-key] (band-badge band-key nil))
  ([band-key attributes]
   (when-let [{:keys [label icon] tone-key :tone} (tone/band band-key)]
     (tone-badge (assoc attributes :tone tone-key :icon icon) label))))

;; ---------------------------------------------------------------------------
;; Cards, tiles and facts

(defn card
  "A surface with an optional header: `:title`, `:subtitle` and `:actions` (hiccup, on the right). `:heading` is
  the level of the title (`:h2` by default), so that the headings of a page stay in order; `:flush?` leaves the
  body without padding, for a table or a list that reaches the edges."
  [& args]
  (let [[{:keys [title subtitle actions heading flush?] :or {heading :h2} :as attributes} children] (attrs/args args)]
    (into [:section (-> attributes
                        (attrs/owned :title :subtitle :actions :heading :flush?)
                        (attrs/with-classes "overflow-hidden rounded-box border border-base-300 bg-base-100 shadow-card"))
           (when (or title actions)
             [:header {:class "flex items-start justify-between gap-3 px-5 pt-4"}
              [:div
               (when title [heading {:class "text-base font-semibold"} title])
               (when subtitle [:p {:class "text-sm text-muted"} subtitle])]
              (when actions [:div {:class "flex shrink-0 items-center gap-2"} actions])])]
          [(into [:div {:class (if flush? "mt-3" "px-5 py-4")}] children)])))

(defn- edge-bar
  "The bar on the left edge of a tile or an alert, in the colour of a tone. It is inside the box, which clips it to its
  rounded corners, so nothing moves."
  [tone-key]
  [:span {:class (attrs/classes "absolute inset-y-0 left-0 w-1" (:dot (tone/tone tone-key))) :aria-hidden "true"}])

(defn vital-tile
  "The latest reading of one measurement: its `:label` and `:icon`, the `:value` and `:unit` as text, the `:band`
  as the api names it (`elevated`), and when it was taken (`:at`, as text). A reading away from normal also has
  a bar on the left edge, in the colour of its tone."
  [{:keys [label icon value unit band at] :as attributes}]
  (let [b (tone/band band)
        away? (and b (not= :normal (:tone b)))]
    [:div (-> attributes
              (attrs/owned :label :icon :value :unit :band :at)
              (attrs/with-classes "relative overflow-hidden rounded-box border border-base-300 bg-base-100 p-4 shadow-card"))
     (when away? (edge-bar (:tone b)))
     [:div {:class "flex min-h-6 items-center justify-between gap-2"}
      [:p {:class "flex items-center gap-2 text-sm font-medium text-muted"}
       (when icon (i/icon icon {:size :md}))
       label]
      (band-badge band {:size :sm})]
     [:p {:class "mt-3 flex items-baseline gap-1.5"}
      [:span {:class "text-4xl font-semibold tracking-tight tabular-nums"} value]
      ;; a space between the number and the unit, for a screen reader: the flex layout ignores it
      (when unit " ")
      (when unit [:span {:class "text-base text-muted"} unit])]
     (when at [:p {:class "mt-1 text-sm text-muted"} at])]))

(defn facts
  "A list of terms and their details: `pairs` is `[[\"Born\" \"14 March 1968\"] ...]`."
  ([pairs] (facts nil pairs))
  ([attributes pairs]
   (into [:dl (attrs/with-classes (or attributes {}) "grid grid-cols-[max-content_1fr] gap-x-6 gap-y-2 text-sm")]
         (mapcat (fn [[term detail]]
                   [[:dt {:class "text-muted"} term]
                    [:dd {:class "font-medium"} detail]]))
         pairs)))

;; ---------------------------------------------------------------------------
;; Messages and states

(defn alert
  "A message in the colour of a tone, with the icon of the tone: `:tone`, `:title` and `:actions`. Pass
  `:role \"alert\"` for one that appears on a page that is already open."
  [& args]
  (let [[{tone-key :tone :keys [title actions] :or {tone-key :info} :as attributes} children] (attrs/args args)
        t (tone/tone tone-key)]
    [:div (-> attributes
              (attrs/owned :tone :title :actions)
              (attrs/with-classes "relative grid grid-cols-[auto_1fr] items-start gap-x-3 gap-y-3 overflow-hidden rounded-box border p-4 pl-5"
                (when actions "sm:grid-cols-[auto_1fr_auto]") (:surface t)))
     (edge-bar tone-key)
     (i/icon (:icon t) {:size :md :class (attrs/classes "mt-0.5" (:icon-colour t))})
     [:div {:class "min-w-0"}
      (when title [:p {:class "font-semibold"} title])
      (when (seq children) (into [:div {:class "text-sm"}] children))]
     ;; under the text on a phone, and at the end of the row from a small screen on
     (when actions [:div {:class "col-start-2 flex flex-wrap items-center gap-2 sm:col-start-3 sm:row-start-1"} actions])]))

(defn empty-state
  "What a list shows when it has nothing: an icon, a `:title`, a text for the children and `:actions`. The title is a
  paragraph, unless `:heading` gives it a level (`:h1` for a page that has nothing else to say, such as one that cannot be
  found), so that the headings of a page stay in order."
  [& args]
  (let [[{:keys [icon title actions heading] :or {icon :inbox heading :p} :as attributes} children] (attrs/args args)]
    [:div (-> attributes
              (attrs/owned :icon :title :actions :heading)
              (attrs/with-classes "flex flex-col items-center gap-3 px-6 py-10 text-center"))
     [:span {:class "grid size-12 place-items-center rounded-full bg-accent text-accent-content"}
      (i/icon icon {:size :lg})]
     (when title [heading {:class "text-base font-semibold"} title])
     (when (seq children) (into [:div {:class "max-w-sm text-sm text-muted"}] children))
     (when actions [:div {:class "mt-1 flex items-center gap-2"} actions])]))

(defn spinner
  "A spinner; `:label` is what a screen reader says (\"Loading\" by default)."
  ([] (spinner nil))
  ([{:keys [label] :or {label "Loading"}}]
   [:span {:role "status"}
    [:span {:class "loading loading-spinner loading-md text-primary" :aria-hidden "true"}]
    [:span {:class "sr-only"} label]]))

(defn skeleton
  "A grey shape where something is on its way. It is hidden from a screen reader, so put a `spinner` or a
  live region beside the group."
  [& args]
  (let [[attributes _] (attrs/args args)]
    [:div (-> attributes
              (attrs/with-classes "skeleton h-4 w-full")
              (assoc :aria-hidden "true"))]))

;; ---------------------------------------------------------------------------
;; People

(defn initials
  "The initials of a name: the first letter of the first and of the last word, or the first letter of a single
  one."
  [full-name]
  (let [words (remove str/blank? (str/split (str full-name) #"\s+"))]
    (str/upper-case
     (case (count words)
       0 ""
       1 (subs (first words) 0 1)
       (str (subs (first words) 0 1) (subs (last words) 0 1))))))

(def ^:private avatar-sizes
  {:sm "size-8 text-xs" :md "size-10 text-sm" :lg "size-14 text-lg"})

(defn avatar
  "A circle with the initials of `:name`. It is hidden from a screen reader: the name is always beside it."
  [{:keys [name size] :or {size :md} :as attributes}]
  [:span (-> attributes
             (attrs/owned :name :size)
             (attrs/with-classes "inline-grid shrink-0 place-items-center rounded-full bg-accent font-semibold text-accent-content"
               (pick avatar-sizes size "avatar size"))
             (assoc :aria-hidden "true"))
   (initials name)])

;; ---------------------------------------------------------------------------
;; Forms

(defn- described-by
  "The ids of the hint and the error of a field that has them, or nil."
  [id hint error]
  (let [ids (cond-> [] hint (conj (str id "-hint")) error (conj (str id "-error")))]
    (when (seq ids) (str/join " " ids))))

(defn- field-shell
  "A control with its label, its hint and its error."
  [{:keys [id label hint error optional?]} control]
  [:div {:class "flex flex-col gap-1.5"}
   [:label {:for id :class "text-sm font-medium"}
    label
    (when optional? [:span {:class "font-normal text-muted"} " (optional)"])]
   control
   (when hint [:p {:id (str id "-hint") :class "text-sm text-muted"} hint])
   (when error
     [:p {:id (str id "-error") :class "flex items-center gap-1.5 text-sm font-medium text-tone-critical-ink"}
      (i/icon :circle-alert {:size :sm :class "text-tone-critical-edge"})
      error])])

(defn- control-attributes
  "The attributes that tie a control to its hint and its error, and say it is invalid when it has an error:
  its classes, and the one that draws the border of an error."
  [attributes control-classes error-class]
  (let [{:keys [id hint error]} attributes
        described (described-by id hint error)]
    (cond-> (-> attributes
                (attrs/owned :label :hint :error :optional?)
                (attrs/with-classes control-classes (when error error-class)))
      described (assoc :aria-describedby described)
      error (assoc :aria-invalid "true"))))

(defn text-field
  "A labelled text input. Options: `:id` and `:label` (both required), `:type` (\"text\" by default), `:hint`,
  `:error` and `:optional?`. Any other attribute goes to the `<input>`."
  [{:keys [type] :or {type "text"} :as attributes}]
  (field-shell attributes
               [:input (assoc (control-attributes attributes "input w-full" "input-error") :type type)]))

(defn select-field
  "A labelled select. `:options` is `[[value label] ...]` and `:value` the one chosen."
  [{:keys [options value] :as attributes}]
  (field-shell attributes
               (into [:select (control-attributes (attrs/owned attributes :options :value) "select w-full" "select-error")]
                     (for [[v text] options]
                       [:option (cond-> {:value v} (= v value) (assoc :selected true)) text]))))

(defn textarea-field
  "A labelled text area; `:value` is its text."
  [{:keys [value rows] :or {rows 4} :as attributes}]
  (field-shell attributes
               [:textarea (assoc (control-attributes (attrs/owned attributes :value) "textarea w-full" "textarea-error")
                                 :rows rows)
                value]))

(defn checkbox-field
  "A checkbox with its label to the right; `:hint` goes under the label."
  [{:keys [id label hint] :as attributes}]
  [:div {:class "flex items-start gap-3"}
   [:input (-> (attrs/owned attributes :label :hint)
               (assoc :type "checkbox")
               (attrs/with-classes "checkbox checkbox-primary")
               (cond-> hint (assoc :aria-describedby (str id "-hint"))))]
   [:div {:class "flex flex-col"}
    [:label {:for id :class "text-sm leading-6 font-medium"} label]
    (when hint [:p {:id (str id "-hint") :class "text-sm text-muted"} hint])]])

(defn toggle-field
  "A switch with its label to the left."
  [{:keys [id label hint] :as attributes}]
  [:div {:class "flex items-start justify-between gap-4"}
   [:div {:class "flex flex-col"}
    [:label {:for id :class "text-sm leading-6 font-medium"} label]
    (when hint [:p {:id (str id "-hint") :class "text-sm text-muted"} hint])]
   [:input (-> (attrs/owned attributes :label :hint)
               (assoc :type "checkbox" :role "switch")
               (attrs/with-classes "toggle toggle-primary")
               (cond-> hint (assoc :aria-describedby (str id "-hint"))))]])

(defn radio-group
  "A group of radio buttons under a legend: `:name`, `:legend`, `:options` as `[[value label] ...]`, `:value` the
  one chosen, and `:hint`."
  [{group :name :keys [legend options value hint]}]
  [:fieldset {:class "flex flex-col gap-2"}
   [:legend {:class "mb-1 text-sm font-medium"} legend]
   (for [[v text] options
         :let [id (str group "-" v)]]
     [:div {:class "flex items-center gap-3"}
      [:input (cond-> {:type "radio" :class "radio radio-primary" :name group :id id :value v}
                (= v value) (assoc :checked true))]
      [:label {:for id :class "text-sm"} text]])
   (when hint [:p {:class "text-sm text-muted"} hint])])

;; ---------------------------------------------------------------------------
;; Tables and navigation

(def ^:private alignments
  {:start nil :end "text-right" :center "text-center"})

(defn data-table
  "A table. `:caption` names it (it is read, not shown, unless `:show-caption?`); `:columns` is
  `[{:key :label :align :header? :class} ...]`, where `:align` is `:start`, `:end` or `:center`, `:header?` makes the
  cells of that column the headings of their rows and `:class` is added to the heading and the cells (a column that a
  narrow screen hides is `\"hidden xl:table-cell\"`); `:rows` are maps and a cell is anything hiccup can show;
  `:dense?` makes it tighter. It scrolls sideways inside its own region, which a keyboard can reach."
  [{:keys [caption columns rows dense? show-caption?]}]
  [:div {:class "overflow-x-auto rounded-box border border-base-300 bg-base-100" :role "region"
         :aria-label caption :tabindex "0"}
   [:table {:class (attrs/classes "table" (when dense? "table-sm"))}
    [:caption {:class (if show-caption? "px-4 py-3 text-left text-sm font-semibold" "sr-only")} caption]
    [:thead
     (into [:tr]
           (for [{:keys [label align] column-class :class} columns]
             [:th {:scope "col" :class (attrs/classes "text-xs font-semibold text-muted" (pick alignments (or align :start) "alignment") column-class)}
              label]))]
    (into [:tbody]
          (for [row rows]
            (into [:tr {:class "hover:bg-base-200"}]
                  (for [{k :key column-class :class :keys [align header?]} columns
                        :let [cell-class (attrs/classes "tabular-nums" (pick alignments (or align :start) "alignment") column-class)]]
                    (if header?
                      [:th {:scope "row" :class (attrs/classes cell-class "font-medium")} (get row k)]
                      [:td {:class cell-class} (get row k)])))))]])

(defn tab-nav
  "Links that look like tabs, for the sections of one page or one record: `:label` names the navigation and
  `:items` is `[{:href :label :current?} ...]`. The current one is marked for a screen reader as well."
  [{:keys [label items]}]
  [:nav {:aria-label label}
   (into [:ul {:class "flex gap-1 overflow-x-auto border-b border-base-300"}]
         (for [{:keys [href current?] text :label} items]
           [:li
            [:a (cond-> {:href href
                         :class (attrs/classes "-mb-px inline-flex items-center border-b-2 px-4 py-3 text-sm font-medium whitespace-nowrap"
                                               (if current?
                                                 "border-primary text-primary"
                                                 "border-transparent text-muted hover:border-base-300 hover:text-base-content"))}
                  current? (assoc :aria-current "page"))
             text]]))])

(defn page-header
  "The top of a page: a `:title` (the `h1`), a `:description` and `:actions` on the right."
  [{:keys [title description actions]}]
  [:header {:class "flex flex-wrap items-start justify-between gap-4"}
   [:div
    [:h1 {:class "text-2xl font-semibold tracking-tight"} title]
    (when description [:p {:class "mt-1 text-base text-muted"} description])]
   (when actions [:div {:class "flex flex-wrap items-center gap-2"} actions])])

;; ---------------------------------------------------------------------------
;; Figures, people, lists and dates

(defn stat
  "A figure with its label, for a summary: `:label`, `:value` (as text), `:hint` (a line under it), `:icon` and `:tone`
  (the colour of the icon and of the figure, for one that asks for attention)."
  [{:keys [label value hint icon tone] :as attributes}]
  (let [t (some-> tone tone/tone)]
    [:div (-> attributes
              (attrs/owned :label :value :hint :icon :tone)
              (attrs/with-classes "rounded-box border border-base-300 bg-base-100 p-4 shadow-card"))
     [:p {:class "flex items-center gap-2 text-sm font-medium text-muted"}
      (when icon (i/icon icon {:size :md :class (:icon-colour t)}))
      label]
     [:p {:class (attrs/classes "mt-2 text-3xl font-semibold tracking-tight tabular-nums" (:ink t))} value]
     (when hint [:p {:class "mt-1 text-sm text-muted"} hint])]))

(defn person
  "A person: an avatar, the `:name` and a line under it, the `:detail`. `:size` is the size of the avatar."
  [{:keys [name detail size] :or {size :md} :as attributes}]
  [:div (-> attributes
            (attrs/owned :name :detail :size)
            (attrs/with-classes "flex items-center gap-3"))
   (avatar {:name name :size size})
   [:div {:class "min-w-0"}
    [:p {:class "truncate font-medium"} name]
    (when detail [:p {:class "truncate text-xs text-muted"} detail])]])

(defn list-group
  "A list whose rows are `list-row`s, with a line between them."
  [& args]
  (let [[attributes children] (attrs/args args)]
    (into [:ul (attrs/with-classes attributes "divide-y divide-base-300")] children)))

(defn list-row
  "A row of a `list-group`: `:leading` (an avatar, an icon or a date), the children (the text) and `:trailing` (a chip,
  the buttons). When there is no room the text gives way, unless `:trailing-wraps?` is true, which is for chips and
  buttons that are wider than a short word: then the trailing part goes under the text."
  [& args]
  (let [[{:keys [leading trailing trailing-wraps?] :as attributes} children] (attrs/args args)]
    [:li (-> attributes
             (attrs/owned :leading :trailing :trailing-wraps?)
             (attrs/with-classes "flex flex-wrap items-center gap-x-4 gap-y-3 px-5 py-4"))
     leading
     (into [:div {:class (attrs/classes "min-w-0 flex-1" (when trailing-wraps? "basis-44"))}] children)
     (when trailing [:div {:class "flex max-w-full shrink-0 flex-wrap items-center gap-2"} trailing])]))

(defn date-block
  "A small calendar page: the `:month` (\"Oct\") over the `:day` (\"14\"), or, for what has no day yet, an `:icon` in
  its place and a paler page. It is hidden from a screen reader: the date is always written beside it."
  [{:keys [month day icon] :as attributes}]
  [:div (-> attributes
            (attrs/owned :month :day :icon)
            (attrs/with-classes "grid min-h-14 w-14 shrink-0 place-items-center rounded-field py-1.5"
              (if icon "bg-base-200 text-muted" "bg-accent text-accent-content"))
            (assoc :aria-hidden "true"))
   (if icon
     (i/icon icon {:size :lg})
     (list [:span {:class "text-xs font-semibold uppercase tracking-wide"} month]
           [:span {:class "text-2xl leading-none font-semibold tabular-nums"} day]))])

(def ^:private disc-sizes
  {:md "size-10" :lg "size-12"})

(def ^:private disc-variants
  {:accent "bg-accent text-accent-content" :muted "bg-base-200 text-muted"})

(defn icon-disc
  "A circle with an icon in it, for the start of a row: `:icon`, `:size` (`:md`, 40 px, or `:lg`, 48) and `:variant`
  (`:accent` or `:muted`). It is hidden from a screen reader: the words beside it say what it is."
  [{:keys [icon size variant] :or {size :md variant :accent} :as attributes}]
  [:span (-> attributes
             (attrs/owned :icon :size :variant)
             (attrs/with-classes "inline-grid shrink-0 place-items-center rounded-full"
               (pick disc-sizes size "icon disc size") (pick disc-variants variant "icon disc variant"))
             (assoc :aria-hidden "true"))
   (i/icon icon {:size :md})])

;; ---------------------------------------------------------------------------
;; Navigation and search

(def ^:private pill-sizes
  {:md "px-3.5 py-1.5 text-sm" :lg "min-h-11 px-4 text-base"})

(defn pill-nav
  "Links as pills, to filter a list: `:label` names the navigation and `:items` is `[{:href :label :count :current?}
  ...]`. The current one is marked for a screen reader as well. `:size` is `:md`, for a desk, or `:lg`, whose pills
  are 44 px high, for a finger."
  [{:keys [label items size] :or {size :md}}]
  [:nav {:aria-label label}
   (into [:ul {:class "flex flex-wrap gap-2"}]
         (for [{:keys [href current? count] text :label} items]
           [:li
            [:a (cond-> {:href href
                         :class (attrs/classes "inline-flex items-center gap-2 rounded-full border font-medium whitespace-nowrap"
                                               (pick pill-sizes size "pill size")
                                               (if current?
                                                 "border-primary bg-accent text-accent-content"
                                                 "border-base-300 bg-base-100 text-base-content hover:bg-base-200"))}
                  current? (assoc :aria-current "page"))
             text
             (when count
               [:span {:class "rounded-full bg-base-100 px-2 text-xs text-base-content tabular-nums"} (str count)])]]))])

(defn breadcrumbs
  "The path to this page: `:items` is `[{:href :label} ...]`, the last being the page itself, which has no link."
  [{:keys [items]}]
  (let [n (count items)]
    [:nav {:aria-label "Breadcrumb"}
     (into [:ol {:class "flex flex-wrap items-center gap-1.5 text-sm text-muted"}]
           (map-indexed
            (fn [index {:keys [href] text :label}]
              (if (= (inc index) n)
                [:li {:aria-current "page" :class "font-medium text-base-content"} text]
                [:li {:class "flex items-center gap-1.5"}
                 [:a {:href href :class "hover:text-base-content hover:underline"} text]
                 (i/icon :chevron-right {:size :sm})]))
            items))]))

(defn search-field
  "A search box with a magnifying glass. `:label` is what a screen reader says (there is no visible label), `:id`,
  `:placeholder` and `:value`; any other attribute goes to the `<input>`."
  [{:keys [id label placeholder] :as attributes}]
  [:label {:class "input w-full"}
   (i/icon :search {:size :md :class "text-muted"})
   [:input (-> attributes
               (attrs/owned :label)
               (assoc :type "search" :aria-label label :id id :placeholder placeholder)
               (attrs/with-classes "grow"))]])

(defn user-menu
  "The menu of the person who is signed in: their avatar and name, and a list of links when it is opened. `:items` is
  `[{:href :label :icon} ...]`; it opens and closes without a script, with `<details>`. `:name-class` is added to the
  name, which a screen that shows the person elsewhere hides (`\"lg:hidden\"`)."
  [{:keys [name items name-class] :as attributes}]
  [:details (-> attributes
                (attrs/owned :name :items :name-class)
                (attrs/with-classes "dropdown dropdown-end"))
   [:summary {:class "flex cursor-pointer list-none items-center gap-2 rounded-field px-2 py-1.5 hover:bg-base-200"
              :aria-label (str "Account menu of " name)}
    (avatar {:name name :size :sm})
    [:span {:class (attrs/classes "hidden text-sm font-medium sm:inline" name-class)} name]
    (i/icon :chevron-down {:size :sm})]
   (into [:ul {:class "menu dropdown-content z-10 mt-2 w-56 rounded-box border border-base-300 bg-base-100 p-2 shadow-overlay"}]
         (for [{:keys [href icon] text :label} items]
           [:li [:a {:href href} (when icon (i/icon icon {:size :md})) text]]))])

;; ---------------------------------------------------------------------------
;; Dialogs and toasts

(defn dialog
  "A dialog that asks for a decision: `:id` and `:title` (which name it for a screen reader), the children (what it
  says) and `:actions` (the buttons). `:open?` shows it, over the page."
  [& args]
  (let [[{:keys [id title actions open?] :as attributes} children] (attrs/args args)]
    [:dialog (-> attributes
                 (attrs/owned :title :actions :open?)
                 (attrs/with-classes "modal" (when open? "modal-open"))
                 (assoc :aria-labelledby (str id "-title") :aria-modal "true")
                 (cond-> open? (assoc :open true)))
     [:div {:class "modal-box"}
      [:h2 {:id (str id "-title") :class "text-lg font-semibold"} title]
      (into [:div {:class "mt-3 text-sm"}] children)
      ;; one above the other on a phone, with the one that decides first; side by side from a small screen on
      [:div {:class "modal-action flex-col-reverse sm:flex-row sm:flex-wrap"} actions]]
     [:div {:class "modal-backdrop"}]]))

;; ---------------------------------------------------------------------------
;; The mark

(defn logo
  "The mark and the name of Pulse. `:href` makes it a link; `:name-class` is added to the name, which a narrow
  sidebar hides (`\"hidden lg:inline\"`)."
  ([] (logo {}))
  ([{:keys [href name-class] :as attributes}]
   [(if href :a :span) (-> attributes
                           (attrs/owned :name-class)
                           (attrs/with-classes "inline-flex items-center gap-2"))
    [:span {:class "grid size-8 shrink-0 place-items-center rounded-field bg-primary text-primary-content"}
     (i/icon :heart-pulse {:size :md})]
    [:span {:class (attrs/classes "text-lg font-semibold tracking-tight" name-class)} "Pulse"]]))
