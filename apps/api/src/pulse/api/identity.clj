(ns pulse.api.identity
  "Who is calling, as the api understands it: a few facts taken from the claims of a token
  whose signature has been verified. Authorization decides what such an identity may do."
  (:require [pulse.fhir.reference :as ref]))

(def roles
  "The application roles of the `pulse` realm (see tools/realm.clj). A token carries them in
  `realm_access.roles` next to Keycloak's own (`default-roles-pulse`, `offline_access`, ...),
  which mean nothing here and are dropped."
  #{:patient :practitioner :clinic-admin :rules-engine :api-service})

(defn from-claims
  "The identity behind `claims` (keyword keys, as `jwt/verify` returns them):

  `:subject`    the stable user id
  `:username`   `preferred_username`, for humans and logs
  `:client`     the OAuth client the token was issued to (`azp`)
  `:roles`      a set of keywords, a subset of `roles`
  `:fhir-user`  the FHIR resource that is this person, `{:type \"Patient\" :id \"pt-ana\"}`,
                or nil (the admin and the scheduler have none)"
  [claims]
  {:subject (:sub claims)
   :username (:preferred_username claims)
   :client (:azp claims)
   :roles (into #{} (comp (map keyword) (filter roles)) (get-in claims [:realm_access :roles]))
   :fhir-user (ref/parse (:fhirUser claims))})
