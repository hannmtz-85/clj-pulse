# 03 · Extras backlog (after Phase 10)

> Everything deliberately left out of Phases 0-10, written down so it is not lost. Nothing here blocks the MVP.
> **Effort:** S ≈ 1 working session · M ≈ 2 · L ≈ 3-4. **Needs** lists what must exist first.
> When an extra is promoted, move it into a numbered phase of [02-implementation-plan.md](02-implementation-plan.md) and tick it here.

## A. Identity and security

| ID | Extra | What it adds | Why it is not in 0-10 | Needs | Effort |
|---|---|---|---|---|---|
| X-01 | SMART-style scopes and discovery | Keycloak client scopes such as `patient/*.rs`, `user/*.rs`, `system/*.cud`; `api` enforces scopes on top of roles; serves `/.well-known/smart-configuration` | Roles plus patient compartment already cover the MVP; this is a spec-compliance layer | Phases 2, 4 | M |
| X-02 | Full SMART App Launch | EHR launch and standalone launch with `launch/patient` context | Large and spec-heavy | X-01 | L |
| X-03 | Dependency vulnerability scanning in CI | `nvd-clojure` and/or Dependabot or Renovate | Phase 10 only runs the secret scan | Phase 1 | S |
| X-04 | Pre-commit hooks | gitleaks, cljfmt and clj-kondo before every commit (lefthook or pre-commit) | CI already enforces the same checks | Phase 1 | S |
| X-05 | Rate limiting in `api` | Per-client throttling, a limit on the size of a request body (the POST endpoints parse whatever JSON arrives once the token is valid) and basic abuse protection | Not needed for a local demo | Phase 4 | S-M |
| X-06 | Persisted audit trail | FHIR `AuditEvent` and `Provenance` stored in HAPI instead of structured logs only | Logs are enough for the MVP | Phase 4 | M |
| X-07 | Keycloak patch bump | 26.0.0 → 26.0.8 inside the same line, for security patches | The owner pinned 26.0.0 | Phase 2 | S |
| X-08 | Stricter access-token checks | Token introspection (RFC 7662) or a revocation list so a revoked session stops working before the token expires; an allow-list of `azp` clients; `jti` replay checks; sender-constrained tokens (DPoP, RFC 9449) | Access tokens live 5 minutes and stateless validation is enough for the demo (ADR-0009) | Phase 4 | M |
| X-09 | JWKS observability and refresh-ahead | Nimbus event listeners feeding the structured logs (fetch, rate limited, outage served from cache), and optionally refresh-ahead caching so that no request ever waits for a key fetch | A lazy cache costs one short fetch per TTL and has no thread to manage (ADR-0009) | Phase 4 | S |

## B. FHIR and clinical features

| ID | Extra | What it adds | Why it is not in 0-10 | Needs | Effort |
|---|---|---|---|---|---|
| X-10 | FHIR Subscriptions (rest-hook) | Near-real-time rule evaluation instead of polling: HAPI notifies the scheduler of new Observations | Polling is simpler and enough | Phases 2, 5 | M |
| X-11 | IPS-style patient summary | A `Composition` document Bundle and an export (JSON/PDF) of the International Patient Summary | Nice showcase, not core | Phases 3, 4 | M-L |
| X-12 | Bulk Data `$export` and analysis notebook | NDJSON export from HAPI plus a Clerk or Portal notebook exploring it | Analytics is a second story | Phase 3 | M |
| X-13 | Terminology service | `$lookup` / `$expand` against the server instead of the static EDN catalogue | The static catalogue is enough for the demo cohort | Phase 3 | M |
| X-14 | Rules as persisted, versioned FHIR | `PlanDefinition` / `Library` resources with an admin editing screen in the SPA | MVP rules live as EDN in the repository | Phases 5, 8 | L |
| X-15 | CDS Hooks service | Clinical decision support endpoint backed by the rules engine | Beyond the MVP | Phases 4, 5 | L |
| X-16 | Blaze compose profile | A second, Clojure-based FHIR server (`samply/blaze`) behind the same `FhirClient`, plus a server-independence test matrix and CQL `$evaluate-measure` cohort analytics. ⚠ Blaze's Subscription and auth support is not verified | Doubles the test matrix | Phases 2, 3 | M-L |
| X-17 | Multi-organization separation | Organization-scoped compartments and data separation | Single demo clinic is enough | Phase 4 | L |
| X-19 | Generated narrative | A `text.div` for every resource, silencing the `dom-6` validator warning (708 of them) and making resources readable in generic FHIR tools | Cosmetic: only a warning | Phase 3 | S |
| X-20 | Terminologies loaded into HAPI | LOINC, ICD-10-CM and RxNorm as code systems in HAPI, so `$validate` checks codes instead of warning "CodeSystem is unknown" | Large downloads and licences; the codes were verified against the NLM services instead | Phases 2, 3 | M |
| X-24 | Panel at scale | Sorting and paging of `GET /api/patients` pushed down to FHIR (`_sort`, `_count`, `_offset`, with the in-memory client learning `_sort=family`), and the Patient read of a practitioner's request shared between the authorization step and the handler instead of done twice | A panel is dozens of patients and sorting them in Clojure is exact and simple; the second read is cheap against HAPI (ADR-0012) | Phase 4 | S-M |
| X-25 | Corrections to recorded readings | A reading can be marked `entered-in-error` (by whom is a rule to write), and series, summary and rules leave such readings out with a `status` filter; today a wrong reading that passed the checks stays | Needs the filter in both FHIR clients and a rule for who may correct what | Phases 4, 5 | S-M |
| X-26 | Idempotent recording | An `Idempotency-Key` header on the POST endpoints, mapped to a conditional create (`If-None-Exist`), so a request retried after a timeout records one reading and not two (ADR-0014) | A retry is rare locally; a deterministic id from patient, code and time would let two different readings in the same second replace one another | Phase 4 | S |
| X-27 | Content errors as 415 | A body that is not JSON is a 400 whose `errors` is `["invalid type"]`, because it is never decoded, and so is a missing body; a 415 for the first and one shape of `errors` (a map) for all, as the document of the api already says (ADR-0017) | Cosmetic: the clients of this api send JSON | Phase 4 | S |
| X-28 | Optimistic locking for updates | `If-Match` with the resource version on the read-modify-write updates (today the appointment `PATCH`), so that two clinicians changing one appointment at the same moment cannot overwrite each other: a conditional update in the `FhirClient` protocol, both clients and a contract test, and a 409 or 412 for the loser (ADR-0015) | The last write wins today, which is enough for a demo with one clinic | Phase 4 | S-M |
| X-29 | Double-booking detection | A 409 when a doctor already has a booked appointment that overlaps the one being confirmed or moved: a search of the doctor's booked appointments around the time, and an overlap test in `pulse.fhir.appointment` (ADR-0015) | Annex B does not ask for it, and the demo clinic has no agenda of slots | Phase 4 | S |
| X-33 | Server-side status filter for alerts | A custom `SearchParameter` for `Flag.status` on HAPI (and the same in the in-memory client), so that the inbox asks for active alerts instead of fetching the flags of the scope and filtering them: needed when a scope holds more than 1000 flags (ADR-0016) | R4 gives Flag no such parameter, and a clinic with a panel of dozens does not get near the limit | Phases 4, 5 | S-M |
| X-18 | FHIR R5 compatibility | `libs/fhir` normalises R4/R5 differences behind version-aware accessors (for example `MedicationRequest.medication`, a `medication[x]` choice in R4 and a `CodeableReference` in R5; `AllergyIntolerance` fields) with multimethods dispatching on version, plus an optional compose profile running HAPI in R5 and a test matrix. Shows R5 knowledge without depending on it | R4 is the base (see ADR-0002); few partners support R5 and R6 is coming | Phases 3, 4 | M |

## C. Real time and integration

| ID | Extra | What it adds | Why it is not in 0-10 | Needs | Effort |
|---|---|---|---|---|---|
| X-21 | Real notification channel | Reminders sent through SMTP (MailHog in compose) or web push instead of a `Communication` record only | Needs extra services | Phase 5 | M |
| X-22 | Device gateway simulation | Measurements arriving over MQTT or HTTP from a separate simulated gateway instead of the in-process simulator | In-process simulator is simpler | Phase 5 | M |
| X-23 | Server-Sent Events for alerts | Push new alerts from `api` to the SPA (and to `web` through the htmx SSE extension) | Polling is enough | Phases 4, 8 | M |
| X-34 | Calendar schedules | Weekdays, days of the month and "last Friday" for the `scheduler`, as one more type of schedule next to `:interval` and `:daily` (`{:type :weekly :days #{:mon :thu} ...}`), with the same pure `next-run` and the same kind of property tests; or a cron expression parsed into that data (ADR-0019) | Every job of the MVP is an interval or a time of day | Phase 5 | S |
| X-35 | A timeout for each job | A `:timeout-ms` in a job that interrupts a run that goes on for longer, recorded as its own outcome, so a handler that hangs on something that has no timeout of its own cannot hold its job for ever (ADR-0020) | Every handler so far is bounded by the timeouts of the FHIR client | Phase 5 | S |
| X-36 | Run history that survives a restart | The last runs of each job kept in FHIR (an `AuditEvent` or a `Task`) or in a small table, so that the status after a restart shows what happened before it (ADR-0020) | The status in memory is enough to see that the jobs are alive | Phase 5 | M |

## D. UX and product

| ID | Extra | What it adds | Why it is not in 0-10 | Needs | Effort |
|---|---|---|---|---|---|
| X-30 | Bilingual UI (EN/ES) | Message bundles in `web`, i18n in `spa`, Keycloak theme `messages_es` | English-only is enough for the MVP | Phases 6-8 | M |
| X-31 | Patient portal as a PWA | Installable app shell with offline fallback | Not core | Phase 7 | M |
| X-32 | Automated accessibility and visual checks | axe or Lighthouse CI, and screenshot regression tests | Phases 7-8 check accessibility by hand | Phases 7, 8 | S-M |

## E. Quality and delivery

| ID | Extra | What it adds | Why it is not in 0-10 | Needs | Effort |
|---|---|---|---|---|---|
| X-40 | Playwright end-to-end tests | Logging in through Keycloak and walking the key flows, in CI | Phase 9 has integration tests and an optional smoke test | Phase 9 | M |
| X-41 | Metrics | Prometheus `/metrics` endpoints and Grafana dashboards | Not needed locally | Phases 4, 5 | M |
| X-42 | Docs as notebooks | A Clerk notebook walking through the FHIR toolkit; generated API docs | Documentation polish | Phase 3 | S-M |
| X-43 | Load tests | k6 or Gatling scenarios for `api` (`/summary` fan-out) | Not needed for a demo | Phase 4 | S |
| X-44 | Faster start-up | AppCDS or GraalVM native image for `api` (native image with Jetty is non-trivial) | Optimisation | Phase 9 | M |
| X-45 | Release automation | Semantic versioning, changelog, GitHub Releases with uberjars, images in GHCR | Phase 10 cuts one manual release | Phase 10 | S-M |
| X-46 | Formal validation of the OpenAPI document | `/openapi.json` checked against the official OpenAPI 3.1 JSON Schema in the tests (swagger-parser, or a JSON Schema validator with the schema vendored), and the real responses of the endpoints checked against the document (`nl.jomco/openapi-v3-validator` checks traffic against a spec) | The tests of the document (descriptions, references, security, statuses against a table) and Swagger UI drawing it cover what matters today; a validator is a heavy dependency (ADR-0017) | Phase 4 | S-M |
| X-47 | The sign-in of Swagger UI as a task | The script that was run by hand to close Phase 4 (headless Chrome over the DevTools protocol, no dependencies: open `/docs/`, sign in through the Keycloak popup, try operations as a patient, a practitioner and the administrator, fail on a console or policy error), kept in `tools/` and run by a `bb` task, or as part of X-40 | It needs Chrome and a running stack, and `bb realm:smoke` and the realm test already check the client | Phase 4 | S |
| X-48 | Stable `operationId`s | An `:operationId` in the route data of each endpoint, such as `listPatients`, and a test that they are unique, so that clients generated from the document get names that do not come from the paths | Nothing generates a client yet | Phase 4 | S |

## F. Platform and operations

| ID | Extra | What it adds | Why it is not in 0-10 | Needs | Effort |
|---|---|---|---|---|---|
| X-50 | Production hardening profile | HTTPS through Caddy or Traefik, Keycloak production mode (`start --optimized`), Docker secrets, backups, restart policies | Explicitly out of scope (local demo) | Phase 9 | L |
| X-51 | Kubernetes manifests or a Helm chart | Cluster deployment | Out of scope | X-50 | L |

## G. This Windows machine

| ID | Extra | What it does | Effort |
|---|---|---|---|
| X-60 | Long paths | Enable Windows long paths and `git config core.longpaths true` | S |
| X-61 | Antivirus exclusions | Exclude `%USERPROFILE%\.m2`, `%USERPROFILE%\.gitlibs`, `.cpcache` and `node_modules` to speed up dependency resolution | S |
| X-62 | Reclaim memory from WSL | Add `[experimental] autoMemoryReclaim=gradual` to `.wslconfig`; stop the Ubuntu distribution (`wsl --terminate Ubuntu`) or disable its Docker Desktop integration. Free RAM was about 0.7 GB with Docker idle | S |
| X-63 | Upgrade Node | Move from 24.12 to ≥ 24.15 (or the latest LTS) so npm 12 can be used | S |
| X-64 | Upgrade the JDK patch level | Temurin 21.0.3 (April 2024) → latest 21.0.x for security fixes; stay on 21 | S |

## H. Considered and rejected (kept for the record)

| Option | Decision | Reason |
|---|---|---|
| Polylith | Rejected | Too much structural overhead for four apps; `apps/` + `libs/` with `:local/root` is enough |
| Micro-frontends | Rejected | Same reason |
| SNOMED CT | Rejected | Affiliate licence; ICD-10-CM covers the demo conditions |
| Aidbox as FHIR server | Rejected | Licence friction for anyone cloning the repo (from memory, not verified) |
| yarn | Rejected | Single package manager (npm) and a single lockfile |
| Buddy for JWT/JWKS | Rejected | Maintenance mode; Nimbus JOSE+JWT behind a thin facade instead |
| clj-time, clj-json, cheshire, ring-json | Rejected | Abandoned or redundant; `java.time` and jsonista instead |
| chime | Rejected | Replaced by a pure `next-run` function over `ScheduledExecutorService` |
| ring-cors | Rejected | Last release 2019; in-house middleware instead |
| clj-http | Rejected | `babashka.http-client` shares one API between bb and the JVM |
| Custom password-hash provider | Rejected | Keycloak's default hashing is enough for a portfolio |
| Upgrading Keycloak beyond 26.0.x | Rejected | Owner decision: the focus is Clojure, not Keycloak |
| FHIR R5 as the primary version | Rejected (2026-10-13) | US Core 9.0.0 and the IPS are R4; Epic and Oracle Health do not support R5; R6 started normative ballot in January 2026, so the likely path is R4 → R6. Kept as extra X-18 |

## I. Decisions that will be resolved inside the phases

These are open on purpose and each ends in a short ADR, so they are not extras but are listed here so they are not forgotten.

| Decision | Options | Where it is resolved |
|---|---|---|
| Configuration loader | Aero (no release since 2020) or an in-house EDN loader of about 25 lines | **Decided in Phase 1:** Aero, in-house loader as fallback (ADR-0003) |
| Test runner | Kaocha (no release since 2024-05) or Cognitect test-runner | **Decided in Phase 1:** Kaocha, verified on Clojure 1.12.6 (ADR-0005) |
| Logging library | mulog or Telemere | **Decided in Phase 4:** Telemere (ADR-0010) |
| HTTP client | `babashka.http-client` or hato | Phase 3 |
| JWT verification | Nimbus JOSE+JWT facade (preferred) or Buddy with an in-house JWKS cache | **Decided in Phase 4:** Nimbus facade, policy in Clojure (ADR-0009) |
| Licence | MIT (proposed) or EPL-2.0, and the copyright holder's name | Phase 10 |
