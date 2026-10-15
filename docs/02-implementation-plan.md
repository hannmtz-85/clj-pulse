# 02 · Implementation plan

> **Status: validated by the project owner on 2026-10-13. Phases 0 to 4 are done; Phase 5 (`scheduler`, rules and simulator) is next.**
> Evidence and version tables: [01-stack-review.md](01-stack-review.md). Everything deliberately left out of Phases 0-10: [03-extras-backlog.md](03-extras-backlog.md).

## 0. Traceability: request → where it is handled

| Request | Where |
|---|---|
| Analyse whether the stack and packages are right and current | [01-stack-review.md](01-stack-review.md) §1-§3 |
| Document before changing | Process (§7) and the ADRs of Phase 1 |
| Show real Clojure knowledge in the apps | §5 |
| Change the idea if it is too basic; move to medicine and FHIR | §2, Phase 3 |
| Better layout and design | Phases 6, 7 and 8 |
| Problems found by `/init` | [01-stack-review.md](01-stack-review.md) §4 (each with its phase) |
| Get the Windows machine ready | Phase 0 (done) and [01-stack-review.md](01-stack-review.md) §8 |
| Drop the custom hash and use Keycloak's default | Phase 2 |
| Initial roles and users for the new apps | Phase 2 and Annex D |
| Keep `spa`, `api`, `scheduler`, `web` with a new concept | §2 |
| Remove every name from the original proof of concept | Phase 1 (done for files; kept as a rule for all new content) |
| Keep what is left out as extras | [03-extras-backlog.md](03-extras-backlog.md) |
| Step-by-step plan | §6 |

## 1. Goals and limits

**Goals**

1. A GitHub portfolio project where **Clojure is the protagonist**.
2. **Keycloak SSO** (26.0.0) shared by the four apps.
3. A **medical domain on FHIR R4** with synthetic data.
4. Each app demonstrates concrete language techniques (§5).
5. A **polished interface** with a shared design system.
6. **Reproducible** on Windows: `bb doctor` → `bb up` → `bb seed` → `bb dev`.

**Out of scope**

- Not a real health record or medical device: data is 100 % synthetic and clinical thresholds are didactic only.
- Nothing production-grade: no HTTPS, high availability, backups or multi-tenancy.
- The Keycloak version is not changed.
- No Polylith or micro-frontends (too much overhead for four apps).
- Full SMART App Launch is an extra.

## 2. Concept: "Pulse" — remote patient monitoring and appointment coordination

A patient records (or a device sends) blood pressure, glucose, SpO₂ and weight measurements. A rules engine evaluates them and raises alerts. The care team sees the alerts in a workspace, confirms appointments and adjusts follow-up. The patient reviews their trends in a portal. Everything runs on FHIR R4 with SSO.

| Kind | Role | User | Authentication | Responsibility | Clojure techniques it showcases |
|---|---|---|---|---|---|
| `api` | **Clinical gateway** (resource server + FHIR BFF) | `web`, `spa`, `scheduler` | Validates JWT Bearer tokens | Aggregates and validates FHIR data; role plus patient-compartment authorization; OpenAPI | Data-driven Reitit, malli, Integrant, protocols, `iteration` |
| `web` | **Patient portal** (server-rendered) | Patient | Authorization Code + PKCE (confidential client) | Summary, trends with SVG charts, medication, appointments, self-reported measurements | Hiccup 2 + htmx, session security, CSRF and CSP |
| `spa` | **Clinical workspace** | Clinician and administrator | Authorization Code + PKCE (public client) | Patient panel, chart, alert inbox, schedule, job status | Reagent 2 / re-frame, reitit-frontend, shared `.cljc` |
| `scheduler` | **Rules engine and simulator** | Service account | Client Credentials | Evaluates rules, raises alerts, sends reminders, simulates devices | core.async, transducers, pure scheduler, multimethods, a small macro |

### 2.1 FHIR model (R4)

- **Resources:** Patient, Practitioner, Organization, Observation (blood-pressure panel with `component`), Condition, MedicationRequest, AllergyIntolerance, Appointment, Flag (alerts), Communication (reminders). *Extras:* Provenance/AuditEvent, Subscription.
- **Terminologies:** LOINC (copyright notice in `NOTICE`), **ICD-10-CM** for conditions (public domain), RxNorm for drugs and UCUM for units. **SNOMED CT is not used** to avoid licensing doubts in a public repository.
- **Deterministic IDs** (`pt-ana`, `pr-rivera`, …) created with `PUT`, so the Keycloak realm can reference them in the `fhirUser` claim with no manual steps.
- **Clinician-patient link:** `Patient.generalPractitioner` (native `general-practitioner` search parameter).

### 2.2 Roles and identity

- Realm roles: `patient`, `practitioner`, `clinic-admin` and `rules-engine` (the scheduler's service account).
- Claims: `fhirUser` (for example `Patient/pt-ana`), realm roles and audience `pulse-api`.
- Access matrix in Annex A. The "resource owner" rule becomes FHIR's **patient compartment**.

## 3. Architecture

```
 ┌───────────┐   ┌───────────┐   ┌─────────────┐
 │ web :3300 │   │ spa :3000 │   │ scheduler   │    login (code + PKCE) / client credentials
 │ patient   │   │ clinician │   │ :5000       │ ──────────────────────────────┐
 └─────┬─────┘   └─────┬─────┘   └──────┬──────┘                               ▼
       │ Bearer        │ Bearer         │ Bearer                    ┌───────────────────────┐
       │ (user)        │ (user)         │ (service)                 │ Keycloak 26.0.0 :8080 │
       └───────────────┴───────┬────────┘                           │ realm "pulse"         │
                               ▼                                    └───────────▲───────────┘
                     ┌──────────────────┐     validates JWT (JWKS)              │
                     │ api :4000        │ ──────────────────────────────────────┘
                     │ clinical gateway │
                     └────────┬─────────┘
                              │ FHIR R4 (internal network)
                              ▼
                     ┌──────────────────┐        ┌─────────────────┐
                     │ HAPI FHIR :8090  │ ─────► │ PostgreSQL :5432│
                     └──────────────────┘        └─────────────────┘
```

| Service | Host port | Notes |
|---|---|---|
| Keycloak | 8080 (management 9000) | Version 26.0.0, `start-dev --import-realm` |
| HAPI FHIR | 8090 → container 8080 | Avoids clashing with Keycloak; bound to `127.0.0.1` only |
| PostgreSQL | 5432 | Two databases: `keycloak` and `hapi` |
| `api` / `web` / `spa` / `scheduler` | 4000 / 3300 / 3000 / 5000 | shadow-cljs nREPL on 3333 |

## 4. Repository layout (target)

The repository root is this folder; `clj-pulse` is the name of the GitHub repository.

```
./
├─ bb.edn                   # tasks: doctor, up, down, reset, seed, dev, lint, fmt, test, build…
├─ deps.edn                 # root aliases: :dev (all apps in one REPL), :test, :lint, :fmt, :build
├─ compose.yaml             # postgres · keycloak · hapi (+ an "apps" profile)
├─ .env.example             # variables without real secrets
├─ apps/
│  ├─ api/  web/  scheduler/   # each with deps.edn, src, resources, test
│  └─ spa/                     # deps.edn, shadow-cljs.edn, package.json, src, test
├─ libs/
│  ├─ fhir/                    # .cljc: resources as data, malli schemas, units, Bundles; .clj: clients, synthetic cohort (+ resources/pulse/seed/cohort.edn)
│  └─ platform/                # .clj: config (Aero + malli), JWT/JWKS, logging (Telemere), HTTP server mechanisms (problem details, request id, bearer auth, health)
├─ infra/
│  ├─ keycloak/                # realm/pulse-realm.json, themes/pulse/login/…
│  ├─ hapi/                    # application.yaml
│  └─ postgres/                # init.sql
├─ tools/                      # Babashka scripts: doctor, seed, realm utilities
├─ design/                     # tokens and HTML mock-ups
├─ docs/                       # plan, review, extras, ADRs, architecture
└─ .github/workflows/ci.yml
```

Namespaces carry one prefix per app (`pulse.api.*`, `pulse.web.*`, `pulse.scheduler.*`, `pulse.spa.*`, `pulse.fhir.*`, `pulse.platform.*`), which removes the three duplicated `app.core` of the original proof of concept.

## 5. What it demonstrates in Clojure, and where

| Technique | Where it shows |
|---|---|
| **Data over classes**: FHIR resources as maps; polymorphic access to `value[x]` and `component` | `libs/fhir` |
| **malli schemas**: validation at the edges, coercion, generators, errors → `OperationOutcome` | `libs/fhir`, `api` |
| **Routes as data** (Reitit): declarative authorization, coercion and OpenAPI; deny by default | `api` |
| **Lifecycle and configuration**: Integrant + Aero, malli-validated config, REPL workflow (`go`/`reset`/`halt`), all apps in one REPL | `libs/platform`, `dev/` |
| **Protocols**: `FhirClient` with an HTTP implementation and an in-memory fake | `libs/fhir`, tests |
| **Multimethods and hierarchies** (`derive`): rules by type and Observation categories | `scheduler` |
| **core.async + transducers**: simulator → batches → FHIR transaction, with backpressure and orderly shutdown | `scheduler` |
| **JVM concurrency**: fan-out calls with timeouts for `/summary` | `api` |
| **Laziness**: FHIR pagination with `iteration` / `lazy-seq` | `libs/fhir` |
| **A small, justified macro**: `defrule` (with `macroexpand` tests) | `scheduler` |
| **Property-based testing** (test.check + malli generators): unit round trips, `next-run`, rules | every `test/` |
| **Shared `.cljc`** between the JVM and the browser | `libs/fhir` ↔ `spa` |
| **Java interop**: Nimbus JOSE+JWT, `java.time`, `ScheduledExecutorService` | `libs/platform`, `scheduler` |
| **Babashka**: tasks, `doctor`, and `seed` using the same HTTP client as the apps | `tools/`, `bb.edn` |
| **re-frame in depth**: custom effects, interceptors, layered subscriptions, reitit-frontend | `spa` |
| **Server-side hypermedia**: Hiccup 2 + htmx, SVG generated as data | `web` |
| **Quality**: clj-kondo, cljfmt, Kaocha, tools.build, CI, ADRs | repository root |

## 6. Phases

Rough effort: **S** ≈ 1 working session · **M** ≈ 2 · **L** ≈ 3-4. Every phase ends with `bb lint`, `bb fmt:check` and `bb test` green (once Phase 1 provides them), `CLAUDE.md` updated and any needed ADRs written.

### Phase 0 · Prepare the Windows workstation (S) — DONE 2026-10-13

*Goal:* be able to run Clojure, shadow-cljs and the Docker stack on this machine.

- [x] Install the Clojure CLI from `clj-msi` 1.12.6.1673: per-user, no UAC; the MSI's SHA-256 matched the digest GitHub publishes (the MSI is not Authenticode-signed). Installed in `%LOCALAPPDATA%\Apps\clojure`, added to the user `PATH`.
- [x] Babashka 1.13.226 with `winget install Babashka.Babashka`.
- [x] `JAVA_HOME` set at user level (Temurin 21.0.3).
- [x] npm upgraded from 8.1.2 to **11.21.0**. `npm@latest` (12.2.0) needs Node ≥ 24.15 and the machine has 24.12; upgrading Node was not part of the approval, so the newest compatible 11.x was installed instead.
- [x] Docker Desktop started; `%USERPROFILE%\.wslconfig` created with `memory=6GB`, `processors=6`, `swap=2GB` (the engine now reports 6.2 GB and 6 CPUs).
- [x] Verified in PowerShell **and** Git Bash: `clojure --version` (Clojure CLI version (deps.clj) 1.12.6.1673), `clojure -M -e` (Clojure 1.12.6 on Java 21.0.3), resolving and loading a git dependency (`tools.build` v0.10.14), `bb --version` and `bb -e`, `docker run --rm hello-world`, `docker compose version`.
- [ ] Optional (not done, see the extras backlog): long-path support and antivirus exclusions.

*Notes for later sessions:*
- Processes started before the installation (VS Code, its terminals, Calva, agent shells) do not see the new `PATH` and `JAVA_HOME`. **Restart VS Code once.** Until then, prepend the Clojure and Babashka folders manually.
- Free RAM dropped to about 0.7 GB with Docker idle (see risks in §8).
- Docker Desktop's WSL integration also starts the Ubuntu distribution inside the same VM.

### Phase 1 · Repository foundations (M) — DONE 2026-10-13 (CI run pending a remote)

- [x] Clean repository in this folder: `git init -b main`, no history from the original proof of concept. That proof of concept was archived as `..\sso-poc.zip` (verified identical file by file) and removed from the working tree (moved to the Recycle Bin, recoverable).
- [x] `.gitignore`, `README.md` and `CLAUDE.md` rewritten for the new project.
- [x] First commit (`8843f3a`).
- [x] `libs/fhir` and `libs/platform` created, with one namespace prefix per app/lib. `libs/fhir` holds `pulse.fhir.reference` and a property-based test as a smoke test. `apps/`, `infra/`, `data/` and `design/` are created when their first file is (empty directories are not tracked by git).
- [x] Root `deps.edn` with aliases `:dev`, `:test`, `:lint`, `:fmt`, and `bb.edn` with `doctor`, `test`, `lint`, `fmt`, `fmt:check`, `check`. The tasks `up`, `down`, `reset`, `logs`, `seed`, `dev`, `test:it` and `build` arrive with the phase that needs them.
- [x] `.editorconfig`, `.gitattributes` (LF forced), `.env.example`, `.vscode/extensions.json`, `.lsp/config.edn`.
- [x] clj-kondo, cljfmt and Kaocha configured.
- [x] `bb doctor`: JDK ≥ 21, Clojure CLI, bb, Node/npm, git, Docker engine, free RAM, ports, `.env`.
- [x] GitHub Actions workflow (format, lint, test). CLJS compile and uberjars are added with their apps. Not yet run: it needs a GitHub remote.
- [x] ADRs: template and 0001-0005 in `docs/adr/`. Config loader (Aero) and test runner (Kaocha) are decided there.
- [ ] Add `apps/` to the lint and format paths when the first app appears.

*Acceptance:* `bb doctor`, `bb lint`, `bb fmt:check` and `bb test` green on the skeleton (done locally on 2026-10-13); CI green (pending the GitHub remote).

### Phase 2 · Reproducible infrastructure (M) — DONE 2026-10-13

- [x] `compose.yaml`: `postgres:16-alpine` (`init.sql` creates `hapi` and `keycloak`), `keycloak:26.0.0` with `start-dev --import-realm`, `hapiproject/hapi:v8.12.0-2`; named volume, memory limits, variables from `.env`, every port bound to `127.0.0.1`. Postgres and Keycloak have healthchecks and `depends_on: service_healthy`. **HAPI has no container healthcheck**: its image is a distroless JRE (no shell, curl or compiler, and the `HealthCheck` class its README mentions is absent), so `bb up` waits for `http://localhost:8090/actuator/health` from the host instead.
- [x] **Realm as Clojure data** ([ADR-0006](adr/0006-keycloak-realm-as-clojure-data.md)) rendered to `infra/keycloak/realm/pulse-realm.json`: 4 realm roles, 4 clients (`pulse-web`, `pulse-spa`, `pulse-docs`, `pulse-scheduler`) with mandatory PKCE S256, an audience mapper (`pulse-api`) and a `fhirUser` claim mapper, a declared `fhir_user` user attribute, 9 demo users plus the scheduler's service account, 5-minute access tokens. Secrets are `${VAR}` placeholders resolved by Keycloak from the container environment.
- [x] No custom hash provider, no JAR, no custom `passwordPolicy`.
- [x] HAPI `infra/hapi/application.yaml`: R4, `client_id_strategy: ALPHANUMERIC`, page sizes, no Lucene indexing; PostgreSQL and the dialect come from environment variables. Request validation is off while seeding; `$validate` is called explicitly.
- [x] Tasks: `env:init`, `realm:build`, `realm:check`, `realm:smoke`, `token`, `up`, `down`, `reset`, `logs`, `ps`. `realm:check` is part of `bb check`.
- [x] `http/keycloak.http` and `http/hapi.http` (REST Client, no secrets). The per-role requests against `api` arrive with `api` in Phase 4; until then `bb token <user>` prints a token to paste.
- [x] Minimal `pulse` login theme (verified: Keycloak serves `pulse.css` with HTTP 200). The final one arrives in Phase 6.

*Acceptance (verified 2026-10-13):*
- `bb reset` rebuilt everything from scratch in **2 min 8 s**, with no manual steps, and left PostgreSQL and Keycloak healthy and HAPI ready.
- `bb realm:smoke` signed in as all 9 demo users through the real authorization-code + PKCE flow: each token carried exactly its own application role, the right `fhirUser`, audience `pulse-api` and the right `azp`; the scheduler's client-credentials token carried `rules-engine`; a wrong password was rejected. It passed both before and after the reset.
- HAPI answers `fhirVersion` 4.0.1, accepts `PUT Patient/pt-scratch` (201, then 200), rejects an invalid resource at `$validate`, and returns 410 after `DELETE`.
- Memory with the stack up: HAPI about 0.9 GB, Keycloak about 0.6 GB, PostgreSQL about 0.08 GB (about 1.5 GB in total; 2.7 GB of RAM were still free with VS Code and Docker running).
- `bb check` (format, lint, realm check, tests) is green.

*Lessons:*
- Cheshire's pretty printer emits CRLF on Windows; the realm renderer normalises to LF so `realm:check` is stable across machines and CI.
- `babashka.http-client` has no cookie jar (`:cookie-handler` is ignored), so `tools/kc.clj` forwards the `Set-Cookie` values by hand; without them Keycloak answers `cookie_not_found`.
- Git Bash rewrites container paths such as `/tmp/x` into Windows paths; prefix `docker exec`/`docker cp` with `MSYS_NO_PATHCONV=1`.
- HAPI caches search results for about a minute; use `Cache-Control: no-cache` when testing right after a write or delete.

### Phase 3 · FHIR core and seed data (L) — DONE 2026-10-13

- [x] Resources as plain maps: `pulse.fhir.reference`, `coding`, `observation` (a multimethod on `value[x]`, component and blood-pressure accessors) and `resource` (builders for Organization, Practitioner, Patient, Condition, MedicationRequest, AllergyIntolerance, Appointment, Flag, Communication). Portable `.cljc`.
- [x] malli schemas for the R4 subset (`pulse.fhir.schema`, FHIR primitive regexes anchored) and conversion of malli errors to `OperationOutcome` with FHIRPath-like expressions (`pulse.fhir.outcome`).
- [x] UCUM units as data with linear and affine conversion (`pulse.fhir.units`: kg/g/lb, °C/°F/K, mmHg/kPa, mg/dL↔mmol/L) and didactic reference bands (`pulse.fhir.ranges`).
- [x] Terminology catalogue in code (`pulse.fhir.terminology`): 9 LOINC, 4 ICD-10-CM and 9 RxNorm concepts, every code checked against the NLM Clinical Tables and RxNav services. `NOTICE` added.
- [x] `FhirClient` protocol with an HTTP implementation and an in-memory fake; lazy paginated search with `iteration`; HAPI errors become `ex-info` carrying the `OperationOutcome` ([ADR-0007](adr/0007-fhir-client-protocol-and-shared-bb-jvm-code.md)).
- [x] **Deterministic cohort generator** (`pulse.fhir.synthetic`, [ADR-0008](adr/0008-deterministic-synthetic-cohort.md)) reading `libs/fhir/resources/pulse/seed/cohort.edn` from the classpath, and `transaction` Bundles of `PUT`s with fixed ids.
- [x] `bb seed` (idempotent) and `bb seed:validate` (a representative sample, or `--all`); `bb test:it` for integration tests.
- [x] Unit and property tests: 50 tests, 7853 assertions. Properties cover unit round trips, band coverage, schema rejection of every missing required element, and independence of a day's values from the seeding moment.

*Acceptance (verified 2026-10-13):*
- `bb seed` loaded **708 resources in 10.6 s** (1 Organization, 2 Practitioners, 6 Patients, 4 Conditions, 8 MedicationRequests, 1 AllergyIntolerance, 5 Appointments and 681 Observations; the count is up to 690 when seeding after 20:00 UTC, because the last evening readings do not exist yet earlier in the day). The server's per-type counts matched, and a second run left them unchanged.
- HAPI's `$validate` reported **no errors for all 708 resources** (30 s, sequential). Only warnings remain: the missing narrative (`dom-6`) and code systems HAPI does not have loaded (LOINC, ICD-10-CM, RxNorm).
- Content checks through the FHIR API: Dr. Rivera's panel has 4 patients and Dr. Okafor's 2; Ana's last blood pressures were 177/100, 172/102 and 165/106 (rule R1); Maya's weight went from 72.1 to 75.8 kg in four days (rule R4); Noah has no observations.
- Scenario tests prove that rules R1-R4 would fire for every one of the 24 hours of the day at which the cohort could be seeded, and that the healthy control never leaves the normal bands.
- `bb test:it`: 4 integration tests (22 assertions) pass; the HTTP client and the in-memory client give identical answers to 7 kinds of query against the real HAPI, and `next` links are followed.
- `bb check` is green (format, lint with no warnings, realm check, unit tests).

*Lessons:*
- Babashka has neither `StrictMath` nor guaranteed-identical transcendental functions, so the noise is a sum of twelve uniform samples (additions only), identical on the JVM and in `bb`.
- malli's `:re` uses `re-find`: FHIR primitive patterns must be anchored with `^…$` or `"14/03/1968"` passes as a date.
- `(sequence cat …)` is chunked and `mapcat` spreads its first arguments with `apply`; lazy page-by-page flattening needs `lazy-seq` and `rest` (not `next`).
- **HAPI's validator needs memory.** Ten concurrent `$validate` calls exhausted the 768 MB heap (`OutOfMemoryError`, 100 % CPU in garbage collection); the container stayed up but unusable until restarted. Validation is now sequential. If more headroom is wanted, raise `JAVA_TOOL_OPTIONS` and `mem_limit` in `compose.yaml`.
- Cheshire/JSON and Babashka details are recorded in `CLAUDE.md`; `Cache-Control: no-cache` is the default for the HTTP client because HAPI caches searches.

### Phase 4 · `api`: clinical gateway (L) — DONE 2026-10-14

- [x] Integrant system + Aero, with the configuration validated by malli at start-up (`apps/api`, `pulse.platform.config`, [ADR-0003](adr/0003-configuration-and-lifecycle.md)). Profiles `dev`, `lite` (in-memory FHIR) and `test`; `bb dev [profile]`; `go`/`reset`/`halt` in the REPL.
- [x] JWT verification with Nimbus behind a thin facade (`pulse.platform.jwt`, [ADR-0009](adr/0009-access-token-verification-with-nimbus-facade.md)): signature, `iss`, `aud`, `exp`/`nbf` with leeway, `kid`, a JWKS cache with TTL, typed errors and the `WWW-Authenticate` value. Spike (d) of §7, done 2026-10-14: 19 unit tests and 2 integration tests against the real Keycloak.
- [x] Authentication middleware that answers 401 (and 503 when there are no keys) with `WWW-Authenticate` (`pulse.platform.http.auth`).
- [x] Authorization in route data (`:authz`), **deny by default**, through a pure `authorize` function (identity, action, resource → decision and reason): the mechanism, checked when the router is built ([ADR-0011](adr/0011-api-request-pipeline-and-errors.md)).
- [x] The Annex A matrix as the `rules` of `authorize`, by scope (`:own`, `:panel`, `:all`, `:identifiers`), with the resource checks that patient-compartment access needs ([ADR-0012](adr/0012-authorization-by-scope.md)). The matrix is a table in the tests, transcribed apart from the code.
- [x] Annex B endpoints with malli coercion: `GET /healthz`, `/readyz`, `/api/me`, `/api/patients` (the panel, with filter, sorting and paging), `/api/patients/{id}`, `/api/patients/{id}/summary`, `/api/patients/{id}/observations` (`GET` a series, `POST` a reading), `/api/appointments` (`GET`, `POST`), `/api/appointments/{id}` (`PATCH`), `/api/alerts` (`GET`) and `/api/alerts/{id}/ack` (`POST`). `GET /api/admin/jobs` moves to Phase 5, with the scheduler it proxies.
- [x] OpenAPI and Swagger UI ([ADR-0017](adr/0017-api-documentation-openapi-and-swagger-ui.md)): an OpenAPI 3.1 document generated from the route data (`GET /openapi.json`) and completed from what the data says elsewhere: the security requirement and the roles that may call each operation with their scope, read from the rules of `authz` (`x-pulse-access`), the shared problems (400, 401, 403, 404, 5XX) and each endpoint's own 409 and 422, with when. Swagger UI is served under `/docs/` from a jar, with a sign-in by Authorization Code with PKCE as the public client `pulse-docs`; it needs the api's own `swagger-initializer.js`, because Swagger UI takes the client and PKCE only from a call in script. A test compares the page with the committed realm, and `bb realm:smoke` signs in through `pulse-docs` against the real one.
- [x] Observations ([ADR-0014](adr/0014-recording-and-reading-observations.md)): a catalogue of six measurements as data (`pulse.fhir.measurement`, portable), each with the one unit it is stored in, its precision and plausible limits. `POST` converts units and checks the value (400 for the shape of the body, 422 for what cannot be a reading), while the server decides the subject, the performer, the status, the id and the time window (not ahead of the clock by more than 5 minutes, not older than 30 days). `GET` is the series of one measurement, newest first, with `from` (inclusive), `to` (exclusive) and `limit`, and says when it was cut.
- [x] Appointments ([ADR-0015](adr/0015-appointments-lifecycle-and-panel.md)): the lifecycle as data and pure functions (`pulse.fhir.appointment`, portable): a patient's request is `proposed`, a clinician `confirm`s it with a time (`booked`), may `reschedule` it, and may `cancel` it for good. `GET /api/appointments` lists by scope (a patient's own, those a practitioner takes part in, all) with a status filter, sorting and paging. `POST` is a patient's request to their general practitioner (a reason and a window of at most 30 days). `PATCH /api/appointments/{id}` takes a command and answers 409 when the status does not allow it and 422 when the times cannot be right. The authorization step now hands its descriptor to the handler, so that a change works on the very version the decision was about.
- [x] Alerts ([ADR-0016](adr/0016-alerts-inbox-and-acknowledgement.md)): `GET /api/alerts` is the inbox of a practitioner's panel (the patients whose general practitioner they are; the administrator sees all). The panel is found first and its flags are asked for with an OR list of patients, 50 at a time; the status is applied afterwards, because R4 gives Flag no `status` search parameter. `POST /api/alerts/{id}/ack` makes an active alert inactive and ends its period (409 when it is not active). The lifecycle is `pulse.fhir.alert` (portable), and the in-memory FHIR client learned OR on reference parameters, with a contract test against HAPI.
- [x] `/summary` with concurrent fan-out and timeouts ([ADR-0013](adr/0013-patient-summary-fan-out.md)): eleven queries at once on virtual threads (`pulse.platform.fanout`) under one deadline, the patient required and every other section allowed to be `null` and named in `unavailable`. The FHIR HTTP client has request and connect timeouts (a slow server is a 504).
- [x] Uniform errors (RFC 9457 problem details) and `X-Request-Id`.
- [x] In-house CORS and security headers ([ADR-0018](adr/0018-cors-and-security-headers.md)): `pulse.platform.http.cors` (an allow-list of exact origins from `API_CORS_ORIGINS`, the preflight answered before the router, `Vary: Origin`, never `*` and never credentials; a disallowed preflight is a 403 problem that says so) and `pulse.platform.http.headers` (nosniff, no-referrer, frame denial, a restrictive Permissions-Policy, `Cross-Origin-Resource-Policy`, `Cache-Control: no-store` and a Content-Security-Policy on every response, with its own policy for `/docs`); the policy itself is `pulse.api.security`. The pipeline, outermost first: request id, access log, security headers, CORS, exception handler, router.
- [x] Structured logs ([ADR-0010](adr/0010-structured-logging-with-telemere.md)): events as data, a request id in every line, text or JSON; `/healthz` and `/readyz` (Keycloak keys and FHIR).
- [x] An audit event for every authorization decision on clinical data (`:pulse.audit/access`: who, what, which patient, the outcome and the reason, never the record; [ADR-0012](adr/0012-authorization-by-scope.md)), and one for every write the FHIR server accepts (`:pulse.audit/write`: who, the action, the patient and the reference of the new resource, never its value; ADR-0014). What a response contained, such as how many rows, is not in it yet.
- [x] Tests: authorization matrix as a table, contract tests against the fake, properties. 209 unit tests in `apps/api` (five of them are properties: random bodies and queries thrown at the observation and appointment endpoints, which must answer with a valid result or a problem, and the alert inbox checked against a model; `docs-test` holds the table of operations with the statuses each may answer, and `security-test` checks the headers and CORS on real responses, errors among them), 110 in `libs/fhir`, 87 in `libs/platform`, 22 integration tests of the api against the real stack (three of them write readings, an appointment and alerts in HAPI and delete them; one reads the document and signs in with the client of Swagger UI), and contract tests that the in-memory FHIR client and HAPI answer the api's searches alike (to the second at the edges of a period, and with OR lists of references), that everything the api can build is valid on HAPI, and that every search parameter the fake declares exists on HAPI. Mutation checks on the code of each step, 35 changes to this last one alone, each caught.

*Acceptance (verified 2026-10-14):*
- **Swagger UI in headless Chrome 155, against the real Keycloak and HAPI** (a script over the DevTools protocol): the page draws the 13 operations of the document and says OAS 3.1; Authorize opens the Keycloak popup (`client_id=pulse-docs`, `scope=openid`, PKCE S256), the page exchanges the code for a token and Try it out calls the api with it. As Dr. Rivera, `GET /api/me` is 200 (client `pulse-docs`, role `practitioner`) and `GET /api/patients` returns the panel of 4; as Ana, a patient, `GET /api/patients` and `GET /api/alerts` are 403 and `GET /api/appointments` returns Ana's own; as the administrator, `GET /api/patients` returns all 6. The console shows no error and no Content-Security-Policy violation, and a control (an inline script, a `fetch` to another origin) is blocked and seen.
- **CORS in the same browser**, from pages at `localhost:3000` and `localhost:3001`: the workspace's origin reads successes, 401s (and their `WWW-Authenticate`) and 403s, reads `X-Request-Id` and sends a `PATCH` after its preflight, while a `DELETE` is refused by the browser; the other origin can read nothing.
- Negative cases (401, 403, 404) follow the matrix, in tests for every protected endpoint. `bb realm:smoke` signs in through `pulse-docs`, checks its claims, and checks that a return address outside `/docs/` is refused.
- `bb check` is green: format, lint with no warnings, realm check and **406 unit tests, 12 334 assertions** (fhir 110, platform 87, api 209). `bb test:it`: **22 tests, 389 assertions** against the real stack, after which HAPI holds the 708 resources of the cohort and nothing else. No reflection warnings.

*Lessons:*
- reitit-malli closes every `:map` and strips undeclared keys, so a FHIR resource in a response schema is `[:map-of :keyword :any]`; malli does not default `:optional` keys, so a parameter with a default is required for the coercion, and the document has to say it is not.
- Nimbus 10 caches with refresh-ahead by default and rate-limits its JWKS fetches, and Keycloak's key set holds an encryption key that must never verify a signature ([ADR-0009](adr/0009-access-token-verification-with-nimbus-facade.md)). A fan-out on virtual threads carries the caller's bindings with it, and one deadline governs all of it ([ADR-0013](adr/0013-patient-summary-fan-out.md)).
- R4 gives `Flag` no `status` search parameter, and the in-memory FHIR client must reject whatever HAPI rejects, which is what the contract tests are for ([ADR-0016](adr/0016-alerts-inbox-and-acknowledgement.md)).
- A generator lies until its output is counted: 97 % of the first random bodies were 400s, and a `PATCH` property produced 80 % 409s. Tally the statuses before trusting a property. A mutation check found a boundary (a window that ends exactly now) that no test pinned, and Kaocha's exit code is its number of failures, so a mutation is judged by that and not by a regex on its coloured summary.
- OpenAPI: `reitit.ring/router` sets its own `:coerce` (a private function), so the documentation is derived from the routes of the finished router; the artifact is `fi.metosin/reitit-openapi`; `ring-swagger-ui` 5.32.11 lacks `oauth2-redirect.js`; `reitit-openapi` overwrites `components.schemas`; a `:multi` default branch becomes an overlapping `oneOf` alternative, so the message of an unknown value goes on the `:multi` itself; and malli's `:json-schema` property is how a `:fn`, or a net wider than the rule, is described.
- Swagger UI takes the OAuth client and PKCE only from a call in script, and its sign-in popup needs `window.opener`: no `Cross-Origin-Opener-Policy` on `/docs`. It runs under `script-src 'self'` with no eval and needs only `style-src 'unsafe-inline'`. Reitit's resource handler matches by prefix and ignores the method; ring refuses `..`.
- A check that looks for violations has to be shown one: an inline script and a `fetch` to another origin were blocked and noticed before "no violations" meant anything.
- The order of the pipeline decides what can be seen: the log outside CORS sees the preflights, and CORS outside the exception handler lets the browser read a 500. Tests move each layer to prove it.

### Phase 5 · `scheduler`: rules and simulator (M)

- [x] Jobs as data (`{:id … :schedule … :handler …}`) and a pure `next-run` (intervals and daily-at with a time zone), with property tests. ([ADR-0019](adr/0019-scheduler-jobs-as-data-and-a-pure-next-run.md): `pulse.scheduler.schedule` and `pulse.scheduler.job`; the daily case is checked against an oracle that walks the minutes, in zones that change their clocks at midnight, by half an hour and by three quarters.)
- [ ] Integrant executor on `ScheduledExecutorService` with orderly shutdown; protected `GET /jobs` and `POST /jobs/:id/run` (`clinic-admin`).
- [ ] `GET /api/admin/jobs` in `api` (Annex B, the action `:jobs/read` already has its rule): the scheduler's status through the api. It was left out of Phase 4 because it proxies a service that did not exist, and how the api authenticates to it needs its own ADR first: forwarding the caller's token fails the scheduler's `aud` check, so it is the api's own service-account token, or a token exchange. It will appear in the OpenAPI document by itself.
- [ ] Client-credentials token cached with early renewal.
- [ ] Rules engine: `defrule` (a small macro) + a multimethod per rule type; Annex C rules; output as a `Flag` created with *conditional create* (`If-None-Exist`), hence idempotent.
- [ ] Device simulator: core.async pipeline (producer → transducers → batches → Bundle) with backpressure; `dev` profile only.
- [ ] Appointment reminders → `Communication`.

*Acceptance:* with `bb dev`, new Observations appear and abnormal ones create a `Flag` with no duplicates in under a minute (accelerated intervals in dev); tests green.

### Phase 6 · Design system and mock-ups (M) · *visual checkpoint*

- [ ] Tokens (colour, typography, spacing, radii, shadows), light and dark themes, AA contrast.
- [ ] Tailwind 4 + daisyUI 5 with a `pulse` theme; the same CSS for `web`, `spa` and the Keycloak login.
- [ ] Base layout (sidebar + header) and components: card, stat, badge, table, form, empty state, notice; chart style.
- [ ] Navigable HTML mock-ups: login, patient home, vitals trends, clinician panel, patient chart, alert inbox.
- [ ] Final Keycloak login theme.

*Acceptance:* **the owner approves the mock-ups before any interface is written.** It can move earlier, anywhere after Phase 2; the recommendation is right after Phase 3 so it can be judged with real data.

### Phase 7 · `web`: patient portal (L)

- [ ] Integrant system; secure session (`HttpOnly`, `SameSite=Lax`), CSRF, CSP and security headers.
- [ ] Login with ring-oauth2 + PKCE and verification through the shared JWT facade; expiry and refresh (**spike**: ring-oauth2's documentation does not mention refresh); application-initiated logout.
- [ ] Pages: home, trends, medication, appointments (request/cancel) and profile; htmx partials; SVG charts as Hiccup.
- [ ] Accessibility (landmarks, focus, contrast, tables with headers) and empty/error states.
- [ ] Tests: handlers with ring-mock, Hiccup snapshots, properties of the SVG generator.

*Acceptance:* a patient signs in through SSO, sees only their own data, requests an appointment and signs out properly.

### Phase 8 · `spa`: clinical workspace (L)

- [ ] **Spike (≤ 1 h):** shadow-cljs 3 + Reagent 2 + React 19 + re-frame 1.4.7 + keycloak-js 26.0.8 + Tailwind 4. Plan B: Reagent 1.x with React 18.
- [ ] `shadow-cljs.edn` with `:deps true`, npm only, real scripts and re-frame-10x in development.
- [ ] keycloak-js wrapper as an effect/interceptor; the token stays in memory (never in the console or `localStorage`).
- [ ] In-house `:http` effect over `fetch` + `AbortController`.
- [ ] Routing with reitit-frontend and controllers: data loading leaves the render function.
- [ ] Views: panel, patient chart (vitals with Chart.js, problems, medication, appointments), alert inbox with acknowledge, schedule and job status.
- [ ] Forms validated with the shared `.cljc` malli schemas.
- [ ] Tests: events and subscriptions with `day8.re-frame/test`; `.cljc` logic tested on the JVM and on Node.

*Acceptance:* a clinician signs in through SSO, filters their panel, opens a chart with graphs, acknowledges an alert and confirms an appointment.

### Phase 9 · Packaging, integration and E2E (M)

- [ ] Multi-stage Dockerfile per app (tools.build → JRE 21); the SPA is served as static files by nginx.
- [ ] `docker compose --profile apps up` starts everything. Mind the issuer: inside Docker the issuer stays `http://localhost:8080` but the back channel is `keycloak:8080` (`hostname-backchannel-dynamic` and separate `:issuer`/`:jwks-uri` in the configuration).
- [ ] Integration tests (`bb test:it`) against real HAPI and Keycloak.
- [ ] CI: an optional integration job with services.

*Acceptance:* clean clone → `bb doctor && bb up && bb seed && docker compose --profile apps up` works; integration tests pass.

### Phase 10 · Polish and publication (M)

- [ ] README: pitch, GIF/screenshots, diagram, quick start in a few commands, demo users (local only), "what it demonstrates in Clojure" with links to the code, ADRs, limits and the synthetic-data notice.
- [ ] `LICENSE`, and re-check the attribution texts in `NOTICE` (LOINC, RxNorm, UCUM) against their current licences, including the LOINC copyright years.
- [ ] Secret scan (`gitleaks`).
- [ ] Publish on GitHub (`gh repo create`), topics, CI badge and release `v0.1.0`. **Only with the owner's explicit confirmation at that moment.**

### After Phase 10

Everything else lives in [03-extras-backlog.md](03-extras-backlog.md).

## 7. How we will work

- **Document first.** Every stack change gets a short ADR (context, decision, alternatives, consequences) before code.
- **One phase = one demonstrable increment**, with its acceptance criteria checked and a summary of the verification.
- **Bounded spikes** for the risky parts: (a) shadow-cljs 3 + Reagent 2 + React 19; (b) ring-oauth2 with PKCE and refresh; (c) HAPI + Postgres + client IDs + `$validate` (covered in Phases 2-3); (d) Nimbus/JWKS from Clojure (done 2026-10-14, [ADR-0009](adr/0009-access-token-verification-with-nimbus-facade.md)).
- **What I do:** write code, documentation and tests; run the project's lint, tests and builds; propose system-level commands.
- **What I need from the owner:** approve plans and decisions; confirm system installs; review mock-ups; authorize publication; review secrets before publishing.
- **Not done without explicit confirmation at that moment:** installing software, changing Windows/WSL/Docker configuration, starting containers, publishing to GitHub, permanently deleting files. Removals go to the Recycle Bin or to an archive first.
- Language: everything written in the repository is in English; the conversation with the owner stays in Spanish unless they ask otherwise.
- `CLAUDE.md` is kept current at the end of each phase.

## 8. Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Not enough RAM: **already observed**, about 0.7 GB free with Docker idle (the WSL VM holds about 3.3 GB); Keycloak + HAPI + Postgres need about 3 GB more | High | High | Close Chrome/Slack/Steam while running the stack; stop the Ubuntu WSL distribution (`wsl --terminate Ubuntu`) or disable its Docker integration; optional `autoMemoryReclaim=gradual` in `.wslconfig`; JVM limits; "lite" profile; all apps in one REPL |
| The shadow-cljs 3 + Reagent 2 + React 19 spike fails | Medium | Medium | 1-hour spike; plan B with Reagent 1.x and React 18 |
| ring-oauth2 has no token refresh | Medium | Medium | Spike; in-house refresh middleware or re-login |
| deps.clj differs from the official CLI | Low | Medium | Smoke-tested in Phase 0 (git dependency, Clojure 1.12.6); plan B: WSL2 |
| HAPI starts slowly and is heavy | Medium | Medium | Healthchecks, pinned tag, `JAVA_TOOL_OPTIONS`, cached image |
| Scope creep | High | High | MVP versus extras; demonstrable phases; visual checkpoint |
| Low-activity libraries (Aero, Buddy, Kaocha) | Low | Low | Isolated behind thin facades; alternative recorded in the ADR |
| Terminology licences | Low | Medium | LOINC with `NOTICE`; ICD-10-CM; no SNOMED CT; synthetic data |
| Keycloak 26.0.0 has known vulnerabilities | Certain | Low (local only) | Documented; never exposed to the Internet; 26.0.8 option |
| Windows: CRLF in mounted files, long paths, a space in the path | Medium | Low | `.gitattributes` with LF, long paths, keep paths quoted |
| Secrets in git history | Avoided | n/a | Clean repository from the start, `.env`, `gitleaks` in CI |

## 9. Decisions

All eight open questions were answered by the owner on 2026-10-13.

| # | Topic | Decision |
|---|---|---|
| P1 | Concept | **"Pulse"** approved: remote monitoring + appointments; `web` = patient portal, `spa` = clinical workspace, `api` = FHIR gateway, `scheduler` = rules and simulator |
| P2 | FHIR server | **HAPI FHIR** as the primary server; Blaze only as an optional extra |
| P3 | Naming | Product "Pulse", repository `clj-pulse`, realm `pulse`. Every name from the original proof of concept is removed from files, configuration, documentation and caches, and must not reappear |
| P4 | Language | **English for everything** written in the repository: code, comments, UI, documentation, ADRs and commit messages |
| P5 | Repository | New repository **in this folder**, no history from the proof of concept. Licence: MIT is proposed (see below) |
| P6 | UI | Tailwind CSS 4 + daisyUI 5 shared by `web`, `spa` and the Keycloak login; server-side SVG charts in `web`, Chart.js in `spa` |
| P7 | Scope | Phases 0-10 are the MVP; everything else is in the extras backlog |
| P8 | Workstation | Authorized and executed in Phase 0 |

**Pending, non-blocking**

- **Licence.** MIT is the working proposal (EPL-2.0 is the alternative). It only matters at publication (Phase 10): confirm the choice and the copyright holder's name for `LICENSE` then.
- **RAM.** Decide whether to stop the Ubuntu WSL distribution or disable its Docker integration (see §8).
- **Node.** Optionally upgrade Node to ≥ 24.15 to use npm 12.
- **Patients cancelling.** Annex A gives patients no way to cancel an appointment, while the page of the portal in Phase 7 says "appointments (request/cancel)". The api follows Annex A (ADR-0015). Letting a patient withdraw their own request would be one more action with the `:own` scope: decide before Phase 7.

---

## Annex A · Access matrix (draft)

| Action | `patient` | `practitioner` | `clinic-admin` | `rules-engine` |
|---|---|---|---|---|
| View own record and summary | Own only | Patients on their panel | All | Read observations |
| List patients | No | Their panel (`general-practitioner`) | All | Identifiers only |
| Record an observation | Own (self-reported) | On their panel | No | Yes (simulator, `dev` only) |
| Request an appointment | Own | – | – | – |
| Confirm or cancel an appointment | No | Their panel | All | – |
| View and acknowledge alerts | No | Their panel | All | Creates and updates `Flag` |
| Job status | No | No | Yes | – |

## Annex B · `api` endpoints (draft)

| Method and path | Who | Description |
|---|---|---|
| `GET /healthz`, `/readyz` | Public | Liveness and dependencies (Keycloak JWKS, HAPI) |
| `GET /docs` | Public | Swagger UI (OpenAPI) |
| `GET /api/me` | Any user | Identity, roles and linked FHIR resource |
| `GET /api/patients` | `practitioner`, `clinic-admin` | Panel with filters, sorting and paging |
| `GET /api/patients/{id}` | Own · their panel · admin | Demographics |
| `GET /api/patients/{id}/summary` | Same | Conditions, medication, allergies, latest vitals, appointments and alerts |
| `GET /api/patients/{id}/observations` | Same | Series by code and date range |
| `POST /api/patients/{id}/observations` | Patient (own) · their clinician | Record a validated measurement |
| `GET`/`POST /api/appointments` | By role | List / request |
| `PATCH /api/appointments/{id}` | `practitioner`, `clinic-admin` | Confirm, cancel or reschedule |
| `GET /api/alerts` | `practitioner`, `clinic-admin` | Alerts (`Flag`) for their panel |
| `POST /api/alerts/{id}/ack` | `practitioner`, `clinic-admin` | Acknowledge an alert |
| `GET /api/admin/jobs` | `clinic-admin` | Scheduler status (through `api`). Phase 5, with the scheduler it proxies |

## Annex C · Demo cohort and rules

All data is synthetic, and the codes must be checked against LOINC / ICD-10-CM / RxNorm when implemented.

| Patient | Profile | 30-day scenario | Expected result |
|---|---|---|---|
| Ana García (`pt-ana`), 58 | Hypertension (I10) | Blood pressure rising over the last 10 days | Alert R1 |
| Liam Chen (`pt-liam`), 46 | Type 2 diabetes (E11.9) | Post-meal spikes and one hypoglycaemia | Alert R3 |
| Sofía Rossi (`pt-sofia`), 67 | COPD (J44.9) | Intermittently low SpO₂ | Alert R2 |
| Omar Haddad (`pt-omar`), 35 | Healthy | Stable values | No alerts |
| Maya Patel (`pt-maya`), 52 | Heart failure (I50.9) | Weight gain over 3 days | Alert R4 |
| Noah Kim (`pt-noah`), 29 | Newly registered | No measurements | Empty state in the UI |

Clinicians: Dr. Rivera (`pr-rivera`) with Ana, Liam, Sofía and Omar; Dr. Okafor (`pr-okafor`) with Maya and Noah. Organization: "Pulse Demo Clinic".

| Rule | Condition (didactic thresholds, not clinical advice) | Observation (LOINC) | Output |
|---|---|---|---|
| R1 | BP ≥ 160/100 in ≥ 2 of the last 3 readings | 85354-9 (8480-6, 8462-4) | `Flag` "BP stage 2" |
| R2 | SpO₂ < 92 % in ≥ 2 readings within 24 h | 59408-5 / 2708-6 | `Flag` "Low SpO₂" |
| R3 | Glucose > 250 or < 70 mg/dL | 2339-0 | `Flag` "Glucose out of range" |
| R4 | Weight +2 kg in 3 days (patients with I50.x) | 29463-7 | `Flag` "Rapid weight gain" |
| R5 | Confirmed appointment in under 24 h with no reminder | `Appointment` | `Communication` |

## Annex D · Realm contents

**Clients**

| Client | Type | Flow | Redirect / origin | Notes |
|---|---|---|---|---|
| `pulse-web` | Confidential | Authorization Code + PKCE S256 | `http://localhost:3300/oauth2/callback` | Secret through `${WEB_CLIENT_SECRET}` |
| `pulse-spa` | Public | Authorization Code + PKCE S256 | `http://localhost:3000/*` | keycloak-js 26.0.8 |
| `pulse-scheduler` | Confidential | Client Credentials | – | Role `rules-engine`; secret through `${SCHEDULER_CLIENT_SECRET}` |
| `pulse-docs` | Public | Authorization Code + PKCE S256 | `http://localhost:4000/docs/*` | Swagger UI of `api` |

All clients get an audience mapper for `pulse-api`. Access tokens last 5 minutes (the original proof of concept used 60) so refresh is exercised.

**Demo users** (local-only password taken from `${DEMO_USER_PASSWORD}`)

| User | Role | `fhirUser` |
|---|---|---|
| `admin@pulse.test` | `clinic-admin` | – |
| `dr.rivera@pulse.test`, `dr.okafor@pulse.test` | `practitioner` | `Practitioner/pr-rivera`, `Practitioner/pr-okafor` |
| `ana.garcia@pulse.test`, `liam.chen@pulse.test`, `sofia.rossi@pulse.test`, `omar.haddad@pulse.test`, `maya.patel@pulse.test`, `noah.kim@pulse.test` | `patient` | `Patient/pt-ana`, `pt-liam`, `pt-sofia`, `pt-omar`, `pt-maya`, `pt-noah` |
| `service-account-pulse-scheduler` | `rules-engine` | – |
