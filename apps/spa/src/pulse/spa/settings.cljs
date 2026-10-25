(ns pulse.spa.settings
  "What the browser needs to know of the places the workspace talks to (ADR-0029): where the identity provider is and as which
  client the workspace signs in, and where the api is. They are defined at compile time (`:closure-defines` of the build can
  change them), and the defaults are those of the local stack, which the Content-Security-Policy of `pulse.spa.security`
  names as the only servers the page may call.")

(goog-define keycloak-url "http://localhost:8080")

(goog-define keycloak-realm "pulse")

(goog-define keycloak-client-id "pulse-spa")

(goog-define api-base-url "http://localhost:4000")
