(ns pulse.spa.person
  "Who is signed in, as the workspace knows them: made from the claims of the access token that the identity provider gave
  (ADR-0029, point 1). The claims are data (a map with string keys), never a JavaScript object: an optimized build renames the
  properties of an object that it does not know, and the name and the roles of the person came out `nil` that way. Pure.")

(def roles
  "The application roles of the `pulse` realm (see `tools/realm.clj`), which a token carries in `realm_access.roles` next to the
  provider's own (`default-roles-pulse`, `offline_access`...). A test holds this set to the api's, which holds it to the realm."
  #{:patient :practitioner :clinic-admin :rules-engine :api-service})

(defn- text-of
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

(defn admin?
  "Whether the person is the clinic's administrator, who sees every patient and the jobs of the scheduler."
  [person]
  (contains? (:roles person) :clinic-admin))
