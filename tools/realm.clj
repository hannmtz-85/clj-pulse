(ns realm
  "The Keycloak realm `pulse` as Clojure data. `bb realm:build` writes it as the JSON that
  Keycloak imports at start-up; `bb realm:check` fails when the committed JSON is stale.

  Why data and not hand-written JSON: the realm needs a JSON document embedded as a string
  (the user profile), roles/clients/users share structure, and the same `people` list drives
  the smoke test in `kc`. Secrets never appear here: they are ${VAR} placeholders that
  Keycloak resolves from the container environment on import."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.walk :as walk]))

(def realm-name "pulse")
(def out-file "infra/keycloak/realm/pulse-realm.json")

(def audience "pulse-api")

;; ---------------------------------------------------------------------------
;; People: the single source for demo users (realm import and smoke test)

(def people
  [{:username "admin@pulse.test" :first "Sam" :last "Admin" :role "clinic-admin"}
   {:username "dr.rivera@pulse.test" :first "Elena" :last "Rivera" :role "practitioner"
    :fhir-user "Practitioner/pr-rivera"}
   {:username "dr.okafor@pulse.test" :first "Chidi" :last "Okafor" :role "practitioner"
    :fhir-user "Practitioner/pr-okafor"}
   {:username "ana.garcia@pulse.test" :first "Ana" :last "García" :role "patient"
    :fhir-user "Patient/pt-ana"}
   {:username "liam.chen@pulse.test" :first "Liam" :last "Chen" :role "patient"
    :fhir-user "Patient/pt-liam"}
   {:username "sofia.rossi@pulse.test" :first "Sofía" :last "Rossi" :role "patient"
    :fhir-user "Patient/pt-sofia"}
   {:username "omar.haddad@pulse.test" :first "Omar" :last "Haddad" :role "patient"
    :fhir-user "Patient/pt-omar"}
   {:username "maya.patel@pulse.test" :first "Maya" :last "Patel" :role "patient"
    :fhir-user "Patient/pt-maya"}
   {:username "noah.kim@pulse.test" :first "Noah" :last "Kim" :role "patient"
    :fhir-user "Patient/pt-noah"}])

(defn- user [{:keys [username first last role fhir-user]}]
  (cond-> {:username username
           :email username
           :emailVerified true
           :enabled true
           :firstName first
           :lastName last
           :credentials [{:type "password" :value "${DEMO_USER_PASSWORD}" :temporary false}]
           :realmRoles [(str "default-roles-" realm-name) role]}
    fhir-user (assoc :attributes {"fhir_user" [fhir-user]})))

(def service-account-user
  {:username "service-account-pulse-scheduler"
   :enabled true
   :serviceAccountClientId "pulse-scheduler"
   :realmRoles [(str "default-roles-" realm-name) "rules-engine"]})

;; ---------------------------------------------------------------------------
;; Roles

(def roles
  [{:name "patient" :description "A patient using the portal; sees only their own record"}
   {:name "practitioner" :description "A clinician; sees the patients on their panel"}
   {:name "clinic-admin" :description "Clinic administrator; sees every patient and operational status"}
   {:name "rules-engine" :description "Service role of the scheduler: reads observations and raises alerts"}])

;; ---------------------------------------------------------------------------
;; Clients

(def audience-mapper
  {:name "pulse-api-audience"
   :protocol "openid-connect"
   :protocolMapper "oidc-audience-mapper"
   :consentRequired false
   :config {"included.custom.audience" audience
            "id.token.claim" "false"
            "access.token.claim" "true"
            "introspection.token.claim" "true"}})

(def fhir-user-mapper
  {:name "fhirUser"
   :protocol "openid-connect"
   :protocolMapper "oidc-usermodel-attribute-mapper"
   :consentRequired false
   :config {"user.attribute" "fhir_user"
            "claim.name" "fhirUser"
            "jsonType.label" "String"
            "id.token.claim" "true"
            "access.token.claim" "true"
            "userinfo.token.claim" "true"
            "introspection.token.claim" "true"}})

(def base-client
  {:protocol "openid-connect"
   :enabled true
   :consentRequired false
   :standardFlowEnabled false
   :implicitFlowEnabled false
   :directAccessGrantsEnabled false
   :serviceAccountsEnabled false
   :fullScopeAllowed true
   :protocolMappers [audience-mapper fhir-user-mapper]})

(def pkce {"pkce.code.challenge.method" "S256"})

(def clients
  [(merge base-client
          {:clientId "pulse-web"
           :name "Pulse web portal"
           :publicClient false
           :clientAuthenticatorType "client-secret"
           :secret "${WEB_CLIENT_SECRET}"
           :standardFlowEnabled true
           :redirectUris ["http://localhost:3300/oauth2/callback"]
           :webOrigins ["http://localhost:3300"]
           :attributes (assoc pkce "post.logout.redirect.uris" "http://localhost:3300/*")})
   (merge base-client
          {:clientId "pulse-spa"
           :name "Pulse clinical workspace"
           :publicClient true
           :standardFlowEnabled true
           :redirectUris ["http://localhost:3000/*"]
           :webOrigins ["http://localhost:3000"]
           :attributes (assoc pkce "post.logout.redirect.uris" "http://localhost:3000/*")})
   (merge base-client
          {:clientId "pulse-docs"
           :name "Pulse API documentation (Swagger UI)"
           :publicClient true
           :standardFlowEnabled true
           :redirectUris ["http://localhost:4000/docs/*"]
           :webOrigins ["http://localhost:4000"]
           :attributes pkce})
   (merge base-client
          {:clientId "pulse-scheduler"
           :name "Pulse scheduler (service account)"
           :publicClient false
           :clientAuthenticatorType "client-secret"
           :secret "${SCHEDULER_CLIENT_SECRET}"
           :serviceAccountsEnabled true})])

;; ---------------------------------------------------------------------------
;; User profile (Keycloak 24+ drops attributes it does not know about, so `fhir_user`
;; must be declared). The first four attributes are Keycloak's own defaults.

(def name-permissions {:view ["admin" "user"] :edit ["admin" "user"]})

(def user-profile
  {:attributes
   [{:name "username" :displayName "${username}"
     :validations {:length {:min 3 :max 255}
                   :username-prohibited-characters {}
                   :up-username-not-idn-homograph {}}
     :permissions name-permissions :multivalued false}
    {:name "email" :displayName "${email}"
     :validations {:email {} :length {:max 255}}
     :required {:roles ["user"]}
     :permissions name-permissions :multivalued false}
    {:name "firstName" :displayName "${firstName}"
     :validations {:length {:max 255} :person-name-prohibited-characters {}}
     :required {:roles ["user"]}
     :permissions name-permissions :multivalued false}
    {:name "lastName" :displayName "${lastName}"
     :validations {:length {:max 255} :person-name-prohibited-characters {}}
     :required {:roles ["user"]}
     :permissions name-permissions :multivalued false}
    {:name "fhir_user" :displayName "FHIR user (Patient/... or Practitioner/...)"
     :permissions {:view ["admin"] :edit ["admin"]} :multivalued false}]
   :groups [{:name "user-metadata"
             :displayHeader "User metadata"
             :displayDescription "Attributes, which refer to user metadata"}]})

;; ---------------------------------------------------------------------------
;; Output

(defn- sorted
  "Recursively turns every map into a string-keyed sorted map, so the generated JSON is
  deterministic and diffs stay small."
  [data]
  (walk/postwalk
   (fn [x]
     (if (map? x)
       (into (sorted-map) (map (fn [[k v]] [(name k) v])) x)
       x))
   data))

(defn realm
  "The complete realm representation."
  []
  {:realm realm-name
   :displayName "Pulse"
   :enabled true
   :sslRequired "external"
   :registrationAllowed false
   :verifyEmail false
   :loginWithEmailAllowed true
   :duplicateEmailsAllowed false
   :resetPasswordAllowed false
   :editUsernameAllowed false
   :bruteForceProtected false
   :loginTheme "pulse"
   :accessTokenLifespan 300
   :ssoSessionIdleTimeout 1800
   :ssoSessionMaxLifespan 36000
   :roles {:realm roles}
   :clients clients
   :users (conj (mapv user people) service-account-user)
   :components
   {"org.keycloak.userprofile.UserProfileProvider"
    [{:providerId "declarative-user-profile"
      :subComponents {}
      :config {"kc.user.profile.config" [(json/generate-string (sorted user-profile))]}}]}})

(defn- normalize
  "Line endings as LF. Cheshire's pretty printer uses the platform separator (CRLF on
  Windows), which would make the output differ between this machine and CI."
  [s]
  (str/replace s "\r\n" "\n"))

(defn render
  "The realm as the pretty-printed JSON text that is committed."
  []
  (str (normalize (json/generate-string (sorted (realm)) {:pretty true})) "\n"))

(defn build!
  "Writes the realm JSON used by Keycloak's --import-realm."
  []
  (fs/create-dirs (fs/parent out-file))
  (spit out-file (render))
  (println "Wrote" out-file))

(defn check!
  "Exits 1 when the committed JSON differs from what `build!` would write."
  []
  (if (and (fs/exists? out-file) (= (normalize (slurp out-file)) (render)))
    (println out-file "is up to date")
    (do (println out-file "is stale: run `bb realm:build`")
        (System/exit 1))))
