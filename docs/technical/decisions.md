# Decisions

> Each significant decision: the context, the choice, what was rejected and why, and what it means for the solution, with the ADR that records it. The ADRs are the source of truth ([docs/adr/](../adr/)); this page is a map of them. The second half collects the "gotchas learned the hard way" recorded in `CLAUDE.md`, the plan and the ADRs.

## Foundations

### Monorepo with deps.edn and Babashka tasks · [ADR-0001](../adr/0001-monorepo-with-deps-edn-and-babashka.md)
- **Context:** four apps share FHIR and platform code; the original proof of concept was four unrelated projects with the same `app.core`; the machine is Windows, without `make`.
- **Choice:** `apps/` + `libs/`, a `deps.edn` each, root aliases with `:local/root`, Babashka tasks, one namespace prefix per app.
- **Rejected:** Polylith (too much structure for four apps), separate repositories (shared code would need publishing), Makefile/shell (not portable).
- **Consequence:** one REPL loads every app (saves RAM); tasks do not depend on the current directory.

### FHIR R4 on HAPI with deterministic ids · [ADR-0002](../adr/0002-fhir-r4-on-hapi.md)
- **Context:** a medical domain needs CRUD, search, transactions, validation, and industry recognition.
- **Choice:** HAPI FHIR JPA Starter (pinned tag, PostgreSQL), R4, `PUT` with fixed ids so the realm's `fhirUser` can name them; HAPI only on `127.0.0.1` and the api as the single policy enforcement point.
- **Rejected:** R5 (not backward compatible, little adoption; US Core and IPS are R4), Blaze (analytics-oriented; kept as X-16), Aidbox (licence friction), a FHIR facade over our own store.
- **Consequence:** reproducible, idempotent seed; HAPI is heavy (~1 GB), hence the `lite` profile; SNOMED CT avoided.

### Configuration with Aero, lifecycle with Integrant · [ADR-0003](../adr/0003-configuration-and-lifecycle.md)
- **Context:** the proof of concept hard-coded URLs and secrets and cached a key-loading failure in a `delay`.
- **Choice:** EDN + Aero, validated by malli at start-up; Integrant components; secrets only from the environment.
- **Rejected:** an in-house loader (the fallback: Aero has had no release since 2020), Mount/Component, reading environment variables directly.
- **Consequence:** every external URL is a key (issuer vs JWKS URI), which a container profile needs; apps start from any directory.

### Authorization as route data, deny by default · [ADR-0004](../adr/0004-authorization-as-route-data.md)
- **Context:** in the proof of concept a separate rule table ended in "any authenticated user", so a new route was open; bad tokens were 500s.
- **Choice:** each route declares `:authz`; a pure `authorize`; a route without it is rejected.
- **Rejected:** a separate table (fails open), a policy engine (OPA, Cedar: one more moving part).
- **Consequence:** authorization is a decision table testable without HTTP, and appears in the OpenAPI document.

### Tooling: Kaocha, cljfmt, clj-kondo through deps aliases · [ADR-0005](../adr/0005-tooling-kaocha-cljfmt-kondo.md)
- **Choice:** Kaocha + test.check + matcher-combinators, clj-kondo and cljfmt as aliases, driven by `bb`.
- **Rejected:** Cognitect test-runner (the fallback: fewer features), zprint.
- **Consequence:** no Node for Clojure tooling; cljfmt needs `:extra-indents` for `defrule` and `for-all`.

### The realm as Clojure data · [ADR-0006](../adr/0006-keycloak-realm-as-clojure-data.md)
- **Context:** since Keycloak 24 custom attributes live in a user profile that is a JSON string embedded in the realm JSON; clients and users repeat structure.
- **Choice:** `tools/realm.clj` rendered to committed JSON; `realm:check` fails if stale; `${VAR}` placeholders for secrets; `realm:smoke` signs in as everyone.
- **Rejected:** hand-written JSON, Terraform or the admin API, EDN with a generic converter.
- **Consequence:** one testable source of truth; changes need `bb reset` because the import skips an existing realm.

## FHIR and data

### A `FhirClient` protocol shared by Babashka and the JVM · [ADR-0007](../adr/0007-fhir-client-protocol-and-shared-bb-jvm-code.md)
- **Choice:** a protocol with an HTTP client (`babashka.http-client` + `clojure.data.json`, which run in both) and an in-memory server; lazy paging with `iteration`; contract tests against HAPI.
- **Rejected:** mocking HTTP (encodes assumptions), a different HTTP/JSON stack per runtime, `pmap` for `$validate` (ran HAPI out of memory).
- **Consequence:** code depends on the protocol; the fake rejects undeclared parameters like a server.

### A deterministic, scenario-driven synthetic cohort · [ADR-0008](../adr/0008-deterministic-synthetic-cohort.md)
- **Choice:** the cohort as EDN; `generate` pure; Irwin–Hall noise (additions only) for bit-identical output on JVM and bb; ids from patient, signal and hour; each scenario fires its rule at any seeding hour.
- **Rejected:** Synthea (heavy, random, cannot target scenarios), random data per run.
- **Consequence:** tests assert on exact scenarios; changing the generator changes the data.

### Recording and reading observations · [ADR-0014](../adr/0014-recording-and-reading-observations.md)
- **Choice:** a catalogue of six measurements as data; units settled at the door (one stored unit per measurement); 400 for shape vs 422 for what cannot be a reading; the server decides subject, performer, status, id and time window; a bounded series with `from`/`to`/`limit`/`truncated`, no offsets.
- **Rejected:** a FHIR Observation as the body, storing the sent unit, plausibility from reference bands (conflates unusual and impossible), offset paging, a deterministic id (two readings in one second would replace each other).
- **Consequence:** retries record twice (X-26); no corrections (X-25); a new measurement is one catalogue entry.

### Appointments: a lifecycle as data · [ADR-0015](../adr/0015-appointments-lifecycle-and-panel.md)
- **Choice:** `transitions` as data, `change` as a pure function returning `:ok`/`:conflict`/`:errors`; commands (`confirm`, `reschedule`, `cancel`) through one `PATCH`; a request always to the patient's GP; "panel" for an appointment is the appointments the practitioner takes part in; read-modify-write `PUT`.
- **Rejected:** a partial update of fields, one endpoint per action, the panel by the patient's GP (a Patient read per decision), letting a patient choose the doctor, double-booking detection now (X-29).
- **Consequence:** last write wins (X-28); the SPA and the scheduler reuse the lifecycle.

### A patient cancels their own appointment · [ADR-0027](../adr/0027-a-patient-cancels-their-own-appointment-the-lifecycle-says-who-and-me-names-the-doctor.md)
- **Context:** the owner decided on 2026-10-16 that a patient may cancel; Annex A did not allow it.
- **Choice:** the lifecycle says **who** (`:by` per transition: `:clinician`, `:patient {:until-start? true}`); `change` adds `:forbidden`, checked before the status; the api grants `:appointment/manage :own` to patients; `/api/me` returns the patient's GP name and specialty.
- **Rejected:** a separate cancel endpoint, one authz rule per command (authorized before the body is read, and not usable by the UIs to show buttons), cancelling at any time, a coded reason, a `GET /api/practitioners/{id}`.
- **Consequence:** the portal's and the workspace's buttons come from the same function; nobody is notified (X-90).

## The api

### Token verification with a Nimbus facade · [ADR-0009](../adr/0009-access-token-verification-with-nimbus-facade.md)
- **Context:** the proof of concept used Buddy, loaded one key lazily, ignored `kid`, cached failures, and answered 500 for bad tokens.
- **Choice:** Nimbus does JOSE and JWKS; the claims policy is pure Clojure with an injected clock; errors are data; no keys is a 503; issuer and JWKS URI separate.
- **Rejected:** Buddy (maintenance mode), Nimbus's own claims verifier (string errors, system clock), introspection per request (X-08), refresh-ahead caching (a thread; X-09).
- **Consequence:** ~160 µs per verification; revocation before expiry not covered.

### Structured logging with Telemere · [ADR-0010](../adr/0010-structured-logging-with-telemere.md)
- **Choice:** events as data, request id in the signal context, text or JSON output (jsonista as `:pr-fn`), `telemere-slf4j` so Jetty logs through it.
- **Rejected:** mulog (no SLF4J bridge), tools.logging + Logback (strings).
- **Consequence:** one logging system; tests read signals with `with-signals`.

### The request pipeline and errors · [ADR-0011](../adr/0011-api-request-pipeline-and-errors.md)
- **Choice:** RFC 9457 problems for every error; authorization before parsing; `check-routes!` at start-up; JSON only; humanized coercion errors.
- **Rejected:** Reitit's exception middleware (inside the router), `OperationOutcome` for the api's own errors, a runtime-only default deny.
- **Consequence:** one error format; a missing `:authz` is a failed start-up; mechanisms in `libs/platform`, policy in the app.

### Authorization by scope · [ADR-0012](../adr/0012-authorization-by-scope.md)
- **Choice:** `rules` maps action → role → scope; `authorize` pure; collection endpoints restrict their own query; the route builds a descriptor; 403 vs 404 rules; every decision audited without clinical values.
- **Rejected:** filtering after fetching, reading the resource for every request, a policy engine.
- **Consequence:** Annex A exists twice (code and test table); a practitioner's request costs one Patient read.

### The summary as a fan-out under one deadline · [ADR-0013](../adr/0013-patient-summary-fan-out.md)
- **Choice:** ~12 queries on virtual threads, one 4 s deadline; the patient required, other sections `null` and named in `unavailable`; display-ready views.
- **Rejected:** sequential queries, `Patient/$everything` (unbounded), a batch Bundle (sequential on the server), all-or-nothing.
- **Consequence:** latency of the slowest section; clients must honour `unavailable`; `fanout` is a library function.

### Alerts: inbox and acknowledgement · [ADR-0016](../adr/0016-alerts-inbox-and-acknowledgement.md)
- **Choice:** acknowledging makes the Flag inactive and ends its period; the inbox finds the panel first, then flags by an OR list of 50 patients; `status` filtered afterwards (R4 has no `Flag.status` search); 409 for an inactive alert.
- **Rejected:** chained search, fetching everyone's flags, a custom search parameter on HAPI only (X-33), one query per patient, idempotent acknowledgement (hides that someone got there first).
- **Consequence:** two queries per practitioner list; over 1,000 flags in a scope the oldest are not seen.

### OpenAPI generated from route data · [ADR-0017](../adr/0017-api-documentation-openapi-and-swagger-ui.md)
- **Choice:** `reitit-openapi`, OpenAPI 3.1.0, completed from `:authz` (`x-pulse-access`) and `:problems`; descriptions as data with a test; Swagger UI from a jar with PKCE through `pulse-docs`.
- **Rejected:** hand-written YAML (drifts), 3.2.0, a CDN, hand-written access rules, Redoc/Scalar (no try-it with login), a formal schema check (heavy; X-46).
- **Consequence:** adding an endpoint documents it; forgetting a description fails a test.

### CORS and security headers in house · [ADR-0018](../adr/0018-cors-and-security-headers.md)
- **Choice:** an allow-list of exact origins, preflight before the router, never `*`, never credentials; security headers on every response by path-prefix rules.
- **Rejected:** `ring-cors` (unmaintained), `*`, a silent 204 for disallowed preflights, per-route headers.
- **Consequence:** a new browser origin must be configured; CSP of `/docs` needs `style-src 'unsafe-inline'`; no HSTS locally.

## The scheduler

### Jobs as data and a pure `next-run` · [ADR-0019](../adr/0019-scheduler-jobs-as-data-and-a-pure-next-run.md)
- **Choice:** schedules as EDN maps; interval on the epoch grid; daily with a required zone; `next-run` pure.
- **Rejected:** `scheduleAtFixedRate` (drift, bursts, untestable), cron (a parser), Quartz, start-up-relative grids, `Duration` objects (not EDN).
- **Consequence:** tests run through clock changes in milliseconds; handlers must be safe to repeat.

### The executor and its HTTP surface · [ADR-0020](../adr/0020-scheduler-executor-and-http-surface.md)
- **Choice:** one tick thread, handlers on virtual threads; a tick arms the next first; no overlap, no catch-up; atomic claims; status as data; orderly stop; the scheduler is its own resource server with audience `pulse-scheduler`; shared pieces moved to `libs/platform`.
- **Rejected:** one pool for ticks and handlers, arming at the end of a run (a hung handler stops the job), queueing missed runs, accepting the api's tokens, copying the api's pipeline.
- **Consequence:** time is driven by hand in tests; no history after a restart (X-36), no per-job timeout (X-35).

### Service tokens: client credentials, cached and renewed early · [ADR-0021](../adr/0021-service-tokens-client-credentials-cached-and-renewed-early.md)
- **Choice:** lazy, no thread; early renewal; single flight with an interruptible lock; never an expired token; HTTP Basic; `Secret` values; readiness asks for a token.
- **Rejected:** a renewal thread, renew on 401, a token per call, an OAuth library, the secret in the body.
- **Consequence:** Keycloak asked about once every four minutes per service.

### The rules engine: rules as data, an alert raised once · [ADR-0022](../adr/0022-rules-engine-rules-as-data-alerts-raised-once.md)
- **Choice:** `defrule` keeps the condition as code and as quoted data; `evaluate` a multimethod by type; an alert identified by rule, patient and newest supporting reading; conditional create; reads through the api, writes to FHIR.
- **Rejected:** plain functions (the explanation can drift), Clara/Drools, rules in config with `eval`, one alert for ever, one per run, search-then-create (a race), reading everything from FHIR, a new api write endpoint.
- **Consequence:** at most one active alert per rule and patient; the engine depends on api and FHIR, and its readiness says so.

### The device simulator: a core.async pipeline · [ADR-0023](../adr/0023-device-simulator-core-async-pipeline-to-a-fhir-transaction.md)
- **Choice:** a job; a pure `plan`; noise cut at 2 SD; episodes as data; a generic bounded pipeline with one writer thread; writes FHIR transactions; development only; `bb dev` runs api and scheduler in one JVM.
- **Rejected:** a pipeline of its own outside the executor, writing through the api, random values, the cohort's ramps, MQTT (X-22), `go` blocks for blocking I/O.
- **Consequence:** about 1,700 readings an hour accumulate in development HAPI (X-70).

### Reminders: a Communication sent once · [ADR-0024](../adr/0024-appointment-reminders-a-communication-sent-once-from-what-fhir-holds.md)
- **Choice:** one FHIR search per run; key `<appointment>/<start UTC>`; conditional create; English text in the clinic's zone; FHIR only.
- **Rejected:** reading through the api, search-then-create, one reminder per appointment whatever its time, the patient's language (X-30), Subscriptions, `CommunicationRequest`/`Task`.
- **Consequence:** a run touches every appointment in the window each minute (X-73); nothing reads reminders yet (X-82).

### Job status through the api, with its own service account · [ADR-0025](../adr/0025-job-status-through-the-api-its-own-service-account-and-a-role-for-it.md)
- **Choice:** `pulse-api` client with role `api-service` and a token for audience `pulse-scheduler` only; the scheduler lets `api-service` read, not run; the api validates the scheduler's answer against its own schema; 502/504 for scheduler failures.
- **Rejected:** forwarding the admin's token, token exchange (preview in 26.0.0), `clinic-admin` for the service account, authorizing by `azp`, passing JSON through, asking the scheduler in `/readyz`.
- **Consequence:** `bb env:init` learned to add missing variables; the realm needed one `bb reset`.

## The interfaces

### The design system as data · [ADR-0026](../adr/0026-design-system-as-data-tokens-theme-components-and-contrast-by-test.md)
- **Choice:** tokens in EDN, theme generated, contrast as a test, Tailwind + daisyUI from npm, shared hiccup components, generated mock-ups, a Keycloak theme generated from the same components.
- **Rejected:** tokens in CSS, Style Dictionary, Tailwind without daisyUI, CDNs, hand-written mock-ups, components copied between apps, Figma.
- **Consequence:** a Node toolchain for development; classes always whole literals; generated CSS and templates committed.

### The patient portal: a server-side client with its own sign-in · [ADR-0028](../adr/0028-the-patient-portal-a-server-side-client-of-the-api-with-its-own-sign-in-and-a-token-keeper-for-each-session.md)
- **Context:** 5-minute tokens and longer visits; login CSRF, fixation and open redirects; sessions that grow; a strict CSP.
- **Choice:** a BFF with server-side sessions; our own code + PKCE + `state` + `nonce` flow; a token keeper per session; only patients; sign-out as a CSRF-protected POST; `ring-anti-forgery` + `SameSite=Lax` + `Origin` check; CSP with Keycloak in `form-action`; routes with deny by default.
- **Rejected:** `ring-oauth2` (the spike found no refresh, no session renewal, a `state` check that passes when both are missing, no timeouts, clj-http and 16 more jars), tokens in an encrypted cookie (~3.3 KB before encryption, no single renewal, no server-side end), the portal calling FHIR directly, oauth2-proxy, Redis now (X-92).
- **Consequence:** a restart ends every session; one process; up to 5 minutes of token validity after sign-out.

### The clinical workspace: keycloak-js, re-frame, the mock-ups' pages · [ADR-0029](../adr/0029-the-clinical-workspace-a-browser-client-of-the-api-with-keycloak-js-re-frame-and-the-pages-of-the-mock-ups.md)
- **Choice:** a public client with PKCE through keycloak-js, the token never in the state; pages as `.cljc` shared with `design`; re-frame in layers with effects at the edge; an in-house `:http` effect over `fetch`; reitit history mode with routes as data and deny by default; the panel as a browser fan-out (≤ 6 at once, one deadline); the shared SVG chart; a release served by our own Ring server with a strict CSP; the build fails on any warning.
- **Rejected:** a BFF for the workspace too (the project shows four OAuth flows on purpose, and a SPA with a public client is the common case), `oidc-client-ts` or our own flow, **Chart.js** (which the plan listed), separate ClojureScript pages, a panel endpoint now (X-79), hash routing (it would share the hash with `response_mode=fragment`), optimistic updates and client caches, a date library, code splitting now (X-114).
- **Consequence:** the token is reachable by any script in the page, so the CSP and the absence of third-party scripts are the defence; `bb check` grows by the Node tests.

## Smaller decisions recorded outside ADRs

| Decision | Source |
|---|---|
| `babashka.http-client` instead of clj-http (one API in bb and on the JVM) | [01-stack-review.md](../01-stack-review.md) §3.2 |
| jsonista as the single JSON library in the services (but `clojure.data.json` in the FHIR client, for Babashka) | §3.2; ADR-0007 |
| `java.time` instead of clj-time | §3.2 |
| In-house scheduler instead of chime | §3.2; ADR-0019 |
| npm only, no yarn | §3.1; backlog section H |
| No SNOMED CT; LOINC, ICD-10-CM, RxNorm, UCUM | Plan §2.1; backlog section H |
| Keycloak stays at 26.0.0 with default hashing | CLAUDE.md "Fixed decisions"; stack review §6 |
| Access tokens of 5 minutes so renewal is exercised | Plan Annex D |
| Inbox that never moves on its own (WCAG 2.2.2) | ADR-0029, update of 2026-10-31 (the owner's choice) |
| "Your sign-in was not accepted" instead of looping to sign in again on a second 401 | ADR-0029, update of 2026-10-26 (the owner's choice) |

## Gotchas learned the hard way

From `CLAUDE.md` (the sections "Gotchas learned the hard way" of each part), the plan's "Lessons", and the ADRs. Selected for what an engineer would be asked about.

### Clojure, malli and Reitit
- malli `:re` uses `re-find`: FHIR regexes need `^…$`, or `"14/03/1968"` passes as a date (plan Phase 3).
- reitit-malli closes every `:map` and strips undeclared keys: a FHIR resource in a response schema must be `[:map-of :keyword :any]`, or it becomes `{}` (ADR-0011).
- malli does not apply defaults to `:optional` keys: a parameter with a default is declared required with `:default`, and the docs mark it not required.
- Reitit adds an `OPTIONS` endpoint to every route unless switched off; query parameters are parsed only with `parameters-middleware`.
- `reitit.ring/router` sets a private `:coerce`, so documentation is derived from the finished router; `reitit-openapi` overwrites `components.schemas`.
- In a query string `+` is a space: send `%2B02:00`.
- `(sequence cat …)` is chunked and `mapcat` applies its first arguments, both fetching extra pages; lazy paging needs `lazy-seq` + `rest` (ADR-0007).
- A destructuring `:or` does not apply to a key present with nil.
- Comparing a rise in doubles gave 1.999999999999993 for 64.1 − 62.1; use rationals (ADR-0022).
- `with-redefs` on a private function works through its var.

### JVM concurrency and time
- Nimbus 10 enables refresh-ahead (a background thread) by default; its rate limiter lets two fetches per window then throws, which must not be reported as an unknown key (ADR-0009).
- Keycloak's JWKS includes an encryption key that must never verify a signature.
- `synchronized` pins a virtual thread during I/O; use an interruptible `ReentrantLock` (ADR-0021).
- A `ScheduledThreadPoolExecutor` keeps a task's exception in its future silently: a tick catches everything and arms the next first (ADR-0020).
- `executor/stop!` had to wait for the tick thread after `shutdownNow`; a race showed once in ~80 runs.
- A daily time can need the day before (Greenland since 2024) or the day after next (Newfoundland until 2010) (ADR-0019).
- The night the clocks go back, 30 days are 721 hours; windows of days must be measured in the clinic's days (X-105).
- `bound-fn*` carries Telemere's context to another thread.

### FHIR and HAPI
- R4 `Flag` has no `status` search parameter (HAPI-0524); the in-memory fake had invented one (ADR-0013).
- HAPI's validator runs out of its 768 MB heap under concurrent `$validate` (plan Phase 3).
- HAPI caches searches for about a minute: send `Cache-Control: no-cache`.
- HAPI reads a bare date in its own time zone: send UTC instants (ADR-0014).
- Concurrent conditional creates store one resource, but most losers get 409, not 200 (ADR-0024).
- Servers do not order readings of the same instant: impose a total order (ADR-0022).
- A search of the in-memory client is lazy and computed from the state at each page: realize it before deleting.

### Keycloak and OAuth
- Keycloak's import skips an existing realm: `bb reset` to apply changes (ADR-0006).
- Keycloak 24+ drops undeclared user attributes; `fhir_user` is declared in the user profile.
- Access tokens carry Keycloak's default roles in `realm_access.roles` next to the application role.
- A code used twice makes Keycloak revoke the tokens of the first exchange: the callback must consume the pending sign-in atomically (ADR-0028).
- An expired ID token is still accepted as `id_token_hint` (ADR-0028 spike).
- Chrome applies `form-action` to the redirect after a form post: the portal's CSP names Keycloak (ADR-0028).
- Swagger UI takes PKCE only from `initOAuth`, and its popup needs `window.opener`: no COOP on `/docs` (ADR-0017).
- Token exchange is a preview feature in 26.0.0 (ADR-0025).

### The portal and the browser
- With `Referrer-Policy: no-referrer` a browser sends `Origin: null` on a same-site form post; use `same-origin`.
- htmx injects a `<style>` unless `includeIndicatorStyles` is false, and stores pages in `localStorage` unless `historyCacheSize` is 0.
- Ring's session middleware does not drop the cookie when a session is deleted, and `:recreate` metadata is what renews the key.
- `ring-anti-forgery`'s session strategy writes a token into any session, so the portal reads the one made at sign-in.
- The JDK's HTTP client retries a GET once when the connection drops.
- `java.net.CookieManager` refuses cookies of `localhost`; tests keep cookies by hand.
- CDP's `Runtime.evaluate` lets `eval` through a CSP unless told otherwise; a control must say so.

### ClojureScript and the workspace
- Closure's advanced compilation renamed `tokenParsed.preferred_username` to nil in the release; only an inference warning said so: read claims with `js->clj` and string keys, and fail the build on any warning (ADR-0029).
- Reagent warns about missing `:key` on every `for`, and React refuses `:tabindex`: the `->reagent` adapter.
- A vector of vectors as children is an element named by a vector (hiccup) or a crash "Invalid arity: 0" (Reagent).
- `reitit.frontend.easy/replace-state` echoes to `on-navigate`, so a search box uses `history.replaceState` directly.
- A controlled input whose state comes back next turn loses the cursor: `dispatch-sync` from the UI event.
- `Intl.DateTimeFormat` with `hour12: false` can write midnight as 24: use `hourCycle: "h23"`.
- React hands the focused DOM node to whatever is at the same position in an unkeyed list: key lists of controls by id.
- A disabled (busy) button loses focus: give it back when a command fails.
- In a `cljs.test/async` block, a `let` in argument position is compiled with `await`; and a `doseq` of `async` blocks runs none.
- The release server's static `public` directory could not be in `resources` because the portal's `public/pulse.css` is on the same classpath.
- re-frame-10x has preloads for React 17 and 18 only (X-113).

### Design and Tailwind
- Tailwind 4 does not read git-ignored files, and finds only whole class literals.
- daisyUI puts components in a layer, so overrides are unlayered; `--input-color` is both an unchecked border and a checked fill.
- In `theme.properties`, `kcFormGroupClass=mb-5` joins the class to the key for Tailwind: write ` = ` with spaces (ADR-0026).
- Hiccup writes attributes alphabetically and escapes FreeMarker inside them; regexes that expect `id` before `action` fail.
- Keycloak's `msg` is a MessageFormat: an apostrophe swallows the braces after it.
- A word with no spaces (90 times "Ñandú") scrolls a page sideways at 390 px: `wrap-anywhere`; an `alert` with `overflow-hidden` cuts text written by someone else.

### Testing
- A generator lies until its output is counted (97 % 400s; 80 % 409s).
- A rule of the a11y checker that never fires passes every page: each rule has its own failing case.
- An assertion on the whole text of a page is satisfied by the `sr-only` live region repeating the message.
- A system test with a job on its real schedule can have the job fire in the middle (a week's interval for jobs run by hand).
- Kaocha's exit code is its number of failures; a mutation is judged by it.

### The workstation
- Cheshire's pretty printer emits CRLF on Windows (the realm renderer normalizes to LF).
- `babashka.http-client` has no cookie jar, and follows redirects unless the *client* is built with `:follow-redirects :never`.
- Git Bash rewrites `/tmp/x` into a Windows path (`MSYS_NO_PATHCONV=1`).
- shadow-cljs via deps.clj needs `powershell` on the `PATH`.
- Double quotes in arguments Java hands to `bb.exe` are lost on Windows.
