(ns pulse.spa.person
  "Who is signed in, as the workspace knows them: made from the claims of the access token that the identity provider gave
  (ADR-0029, point 1). The claims are data (a map with string keys), never a JavaScript object: an optimized build renames the
  properties of an object that it does not know, and the name and the roles of the person came out `nil` that way. Pure."
  (:require [clojure.string :as str]))

(def roles
  "The application roles of the `pulse` realm (see `tools/realm.clj`), which a token carries in `realm_access.roles` next to the
  provider's own (`default-roles-pulse`, `offline_access`...). A test holds this set to the api's, which holds it to the realm."
  #{:patient :practitioner :clinic-admin :rules-engine :api-service})

(def workspace-roles
  "The roles that may use the workspace: the clinic's own people. The patient has the portal, and the two service accounts of the
  realm (`rules-engine` and `api-service`) are not people who sign in."
  #{:practitioner :clinic-admin})

(defn text-of
  "`x` when it is text with something in it, else nil."
  [x]
  (when (and (string? x) (not (re-find #"^\s*$" x))) x))

(defn from-claims
  "`{:id :username :name :roles}` of the person the claims `claims` are of, or nil when there is nobody in them (no claims, or no
  `sub`). `:name` is the name the provider has for them, or their username when it has none; `:roles` is a set of keywords, only
  the application roles."
  [claims]
  (when-let [id (text-of (get claims "sub"))]
    (let [username (text-of (get claims "preferred_username"))]
      {:id id
       :username username
       :name (or (text-of (get claims "name")) username id)
       :roles (into #{}
                    (comp (filter string?) (map keyword) (filter roles))
                    (let [granted (get-in claims ["realm_access" "roles"])]
                      (when (sequential? granted) granted)))})))

(defn allowed?
  "Whether the person may use the workspace: they are a practitioner or the clinic's administrator. It is for the person's sake,
  who is told so and not shown a workspace that would answer them with refusals; the api decides all the same (ADR-0012)."
  [person]
  (boolean (some workspace-roles (:roles person))))

(defn admin?
  "Whether the person is the clinic's administrator, who sees every patient and the jobs of the scheduler."
  [person]
  (contains? (:roles person) :clinic-admin))

(defn role-words
  "How the roles of the person are said, the first being the one that says most: `Clinic administrator`, `Practitioner` or
  `Patient`."
  [{:keys [roles]}]
  (->> [[:clinic-admin "Clinic administrator"] [:practitioner "Practitioner"] [:patient "Patient"]]
       (keep (fn [[role words]] (when (contains? roles role) words)))))

(defn display
  "How the workspace writes who is signed in, `{:name :detail}`, for the sidebar and the menu: the name the identity provider has
  for them and their first role, or, once the api has said who they are (`me`, a part: `{:state :loaded :data ...}`) and they are a
  practitioner, the name as the clinic writes it (`Dr. Elena Rivera`) and what they are qualified in. What the api gave that is
  not text is left out."
  [person me]
  (let [resource (when (= :loaded (:state me)) (get-in me [:data :resource]))
        written (first (when (sequential? (:name resource)) (:name resource)))
        pieces (when (map? written)
                 (concat (when (sequential? (:prefix written)) (:prefix written))
                         (when (sequential? (:given written)) (:given written))
                         [(:family written)]))
        full-name (when-let [words (seq (keep text-of pieces))] (str/join " " words))
        qualification (first (when (sequential? (:qualification resource)) (:qualification resource)))]
    {:name (or full-name (:name person))
     :detail (or (text-of (get-in qualification [:code :text])) (first (role-words person)))}))
