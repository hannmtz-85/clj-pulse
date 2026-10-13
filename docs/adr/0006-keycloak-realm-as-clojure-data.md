# ADR-0006: The Keycloak realm is Clojure data, rendered to JSON

- **Status:** accepted
- **Date:** 2026-10-13

## Context

Keycloak imports a realm from JSON at start-up (`--import-realm`). The Phase 2 plan assumed a hand-written JSON file. Writing it by hand turned out awkward:

- Since Keycloak 24, custom user attributes must be declared in the *user profile*, which is stored as a **JSON document embedded as a string** inside the realm JSON. Escaping it by hand is error-prone.
- Four clients and nine users share most of their structure (protocol mappers, flags, the demo-user shape).
- The same list of demo users is needed by the sign-in smoke test.

## Decision

The realm is defined as Clojure data in `tools/realm.clj`. `bb realm:build` renders it to `infra/keycloak/realm/pulse-realm.json` (keys sorted and line endings normalised to LF, so the output is identical on Windows and Linux). The generated JSON is committed so that a plain `docker compose up` works on a clean clone, and `bb realm:check` (part of `bb check` and CI) fails when it is stale. `bb realm:smoke` signs in as every demo user through the real authorization-code + PKCE flow and checks the claims the API relies on.

Secrets never appear in the data: they are `${VAR}` placeholders that Keycloak resolves from the container environment on import.

## Alternatives considered

- **Hand-written JSON:** the original plan; rejected because of the embedded user-profile string and the repetition.
- **Terraform / the Keycloak admin API:** one more tool and a running server needed just to configure it.
- **EDN files plus a generic converter:** loses the ability to generate users and clients with functions.

## Consequences

- Roles, clients and users have one source of truth that is testable and reviewable as code.
- After editing `tools/realm.clj`, run `bb realm:build` and commit both files; the import is skipped for an existing realm, so use `bb reset` to apply changes locally.
- Cheshire's pretty printer uses the platform line separator, so normalising it is required for the `realm:check` comparison to be stable.
