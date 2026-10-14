# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Status

Pulse is a portfolio project built from scratch in phases. **Phases 0 to 3 are done (workstation, foundations, infrastructure, FHIR core and seed data); Phase 4, the `api` app, is under way: the access-token verifier in `libs/platform` is done, the app skeleton is next.** The code so far is `libs/fhir` (FHIR data model, schemas, clients, synthetic cohort), `libs/platform` (the token verifier) and the infrastructure tooling in `tools/`; there are no apps yet.

## Commands

Run from the repository root (any shell; quote the path, it has a space). Do not invent others: the remaining `bb` tasks arrive with their phase.

- `bb doctor`: checks JDK, Clojure CLI, Babashka, Node, Docker, free RAM and ports (ports in use are expected while the stack is up).
- `bb test`: Kaocha unit tests (config in `tests.edn`; tests tagged `^:integration` are skipped). Pass Kaocha args through, for example `bb test --focus pulse.fhir.reference-test/parse-examples` to run a single test. `bb test:it` runs the integration tests against the running stack.
- `bb seed` loads the demo cohort into HAPI (idempotent, about 700 resources); `bb seed:validate` runs HAPI's `$validate` on a representative sample, `--all` on every resource.
- `bb lint` (clj-kondo), `bb fmt` (cljfmt fix), `bb fmt:check`, and `bb check` (format + lint + realm check + test, what CI runs).
- Stack (needs Docker and `.env`): `bb up` (creates `.env` with random secrets if missing, rebuilds the realm JSON, starts PostgreSQL, Keycloak and HAPI, waits for readiness), `bb down`, `bb ps`, `bb logs [service]`, and `bb reset` which **destroys the stack's data** and starts clean (about 2 minutes).
- Keycloak realm: edit `tools/realm.clj`, run `bb realm:build` and commit the generated JSON (`bb realm:check` fails if it is stale). Keycloak skips the import when the realm exists, so apply changes locally with `bb reset`. `bb realm:smoke` signs in as every demo user and checks their token claims; `bb token <username|scheduler>` prints an access token (for REST Client's `@token`).
- REPL: alias `:dev` (see `dev/user.clj`), for example Calva jack-in.
- New source directories (such as `apps/`) must be added to the lint and format paths in `deps.edn` and `cljfmt.edn`.

## libs/fhir

FHIR R4 resources are plain keyword-keyed maps. Portable `.cljc`: `reference`, `coding`, `terminology` (verified LOINC/ICD-10-CM/RxNorm catalogue), `units` (UCUM as data), `ranges`, `observation` (multimethod on `value[x]`), `resource` (builders), `schema` (malli, open maps), `outcome`, `bundle`, `time`. JVM/Babashka only: `client` (the `FhirClient` protocol and lazy paged search), `client.http`, `client.memory` (a faithful fake server for tests and the "lite" profile), `synthetic` (the deterministic cohort from `resources/pulse/seed/cohort.edn`). The same code runs in the apps, the tests and `bb` (see `bb.edn` `:paths`).

Gotchas learned the hard way: malli `:re` uses `re-find`, so patterns need `^…$`; Babashka has no `StrictMath` (the generator's noise is additions only); `sequence`+`cat` and `mapcat` over `iteration` fetch extra pages (see `client/paged`); HAPI's validator runs out of its 768 MB heap under concurrent `$validate` calls (keep validation sequential).

## libs/platform

JVM only. So far just `pulse.platform.jwt`, the access-token verifier ([ADR-0009](docs/adr/0009-access-token-verification-with-nimbus-facade.md)): `jwks-source` and `verifier` build it, `verify` returns `{:ok claims}` or `{:error {:type ...}}` and never throws because of a bad token, and `errors` and `challenge` give the HTTP status and the `WWW-Authenticate` value. Its unit tests (`bb test`) mint tokens with throwaway RSA keys and serve the JWKS from a local HTTP server; `bb test:it` also verifies tokens from the real Keycloak (it runs `bb token`).

Gotchas learned the hard way: Nimbus 10 enables refresh-ahead caching by default (a background thread; switched off explicitly); its JWKS rate limiter lets two fetches through per window and then throws `RateLimitReachedException`, which must not be reported as an unknown key when no key set was ever fetched; Keycloak's JWKS also holds an encryption key (`use: enc`) that must never verify a signature; Keycloak access tokens carry the application role next to its own default roles in `realm_access.roles`.

## Local stack

Everything binds to 127.0.0.1: Keycloak 8080 (management/health 9000), HAPI FHIR 8090 (`/fhir`, `/actuator/health`), PostgreSQL 5432. About 1.5 GB of RAM in total. Demo users and their password come from `tools/realm.clj` and `.env` (`DEMO_USER_PASSWORD`). HAPI has no container healthcheck (distroless JRE); `bb up` polls it from the host. HAPI caches searches for about a minute (send `Cache-Control: no-cache` when testing).

`docs/` is the source of truth:
- `docs/02-implementation-plan.md`: concept, architecture, phases with checklists and acceptance criteria. Read the current phase before starting work and tick items as they are completed.
- `docs/01-stack-review.md`: why each dependency was chosen, with versions as of 2026-10-13.
- `docs/03-extras-backlog.md`: everything deliberately deferred. Add new ideas there instead of expanding the current phase.

## What it is

Remote patient monitoring and appointment coordination on FHIR R4 with Keycloak SSO. Four apps of different kinds share one realm (`pulse`) and one FHIR server (HAPI): `api` (resource server and FHIR gateway), `web` (server-rendered patient portal), `spa` (ClojureScript clinical workspace) and `scheduler` (rules engine and simulator).

## Working agreements

- Everything written in the repository is in English: code, comments, UI text, docs, ADRs, commit messages. The conversation with the owner is in Spanish.
- Document first: a stack or architecture change gets a short ADR (`docs/adr/`, created in Phase 1) before the code.
- The names used by the original proof of concept must not reappear anywhere in the repository (code, config, docs, comments, sample data).
- Data is synthetic only. No secrets in git: use `.env` (ignored) and commit `.env.example`.
- Do not install software, change Windows/WSL/Docker configuration, start containers, publish to GitHub, make commits or permanently delete files without the owner's explicit confirmation at that moment. Send removals to the Recycle Bin or an archive first.

## Fixed decisions

- Keycloak stays at **26.0.0** (keycloak-js on the 26.0.x line) with Keycloak's default password hashing. No custom hash provider.
- FHIR R4 on HAPI FHIR with a pinned image tag; resources use deterministic client-assigned IDs (`PUT`).
- Terminologies: LOINC, ICD-10-CM, RxNorm and UCUM. No SNOMED CT.
- Clojure 1.12.x on JDK 21 (shadow-cljs 3 requires JDK ≥ 21). UI: Tailwind CSS 4 + daisyUI 5.

## Workstation (Windows 10; PowerShell and Git Bash)

- The Clojure CLI is the `clj-msi` build (deps.clj, `clojure --version` prints "Clojure CLI version (deps.clj)") in `%LOCALAPPDATA%\Apps\clojure`. Babashka comes from winget. npm is 11.21 (npm 12 needs Node ≥ 24.15 and the machine has 24.12). `JAVA_HOME` is set at user level.
- Processes started before Phase 0 (VS Code, its terminals, agent shells) do not see the new `PATH`. If `clojure` or `bb` is not found, restart VS Code or prepend `%LOCALAPPDATA%\Apps\clojure` and the Babashka winget package folder to `PATH`.
- Docker Desktop is capped by `~/.wslconfig` at 6 GB and 6 CPUs. Free RAM is scarce (about 0.7 GB with Docker idle), so do not start the full stack casually.
- The project path contains a space (`Clojure Porfolio`): always quote paths.
- In PowerShell, `"text $var: more"` is parsed as a scope-qualified variable and fails; write `${var}:`.
- In Git Bash, prefix `docker exec`/`docker cp` that use container paths with `MSYS_NO_PATHCONV=1`, or `/tmp/x` becomes a Windows path.
- `babashka.http-client` has no cookie jar and Cheshire's pretty printer emits CRLF on Windows (see `tools/kc.clj` and `tools/realm.clj`).
