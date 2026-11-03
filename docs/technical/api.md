# `apps/api`: the clinical gateway

> Resource server and FHIR gateway. Functional view: [../functional/api.md](../functional/api.md). Sources: ADR-0003, ADR-0004, ADR-0009 to ADR-0018, ADR-0025, ADR-0027; `apps/api/src/pulse/api/*`; CLAUDE.md (apps/api).

## Stack and lifecycle

Ring + Jetty 12 + Reitit 0.11, malli coercion through `reitit-malli`, muuntaja restricted to JSON (jsonista), Integrant for the components, Aero for the configuration ([01-stack-review.md](../01-stack-review.md) §3.2; [ADR-0003](../adr/0003-configuration-and-lifecycle.md)).

- `resources/pulse/api/config.edn` is the Integrant system map, read by Aero (`#env`, `#or`, `#profile`, `#ref`, plus the platform's `#csv` and `#secret`) and checked by `pulse.api.config/schema` (closed maps: a misspelt key fails at start-up). Profiles `dev`, `lite` (in-memory FHIR) and `test`, chosen by `API_PROFILE`.
- `pulse.api.system` starts the components; logging, JWKS, verifier and server `derive` from `:pulse.platform/*` components (ADR-0020).
- Configuration of note: `:issuer` (the IdP as the browser reaches it), `:cors {:allowed-origins}` (`API_CORS_ORIGINS`, default `http://localhost:3000`), `:pulse.api/token` (`API_CLIENT_SECRET`), `:pulse.api/scheduler` (`SCHEDULER_BASE_URL`).

## Namespaces

| Namespace | Role |
|---|---|
| `routes` | The route table as data |
| `handler` | The pipeline |
| `authz` | `rules` (Annex A as data), `authorize`, `scope-of`, `permits?`, the middleware, `check-routes!` |
| `identity` | Verified claims → identity (role list held to the committed realm by a test) |
| `audit` | `:pulse.audit/access` and `:pulse.audit/write` |
| `fhir` | How the api reads FHIR: failure → 502, slow → 504, `find-one`, capped `search`, `put!` |
| `times` | Parsing of request times and their schemas |
| `patients`, `summary`, `observations`, `appointments`, `alerts`, `me`, `jobs` | The endpoints |
| `docs` | OpenAPI 3.1 from the routes; Swagger UI under `/docs` |
| `security` | The CSP of the api and of `/docs` |
| `health` | `/healthz`, `/readyz` (Keycloak keys and FHIR) |

## The pipeline

Outermost first ([ADR-0011](../adr/0011-api-request-pipeline-and-errors.md), [ADR-0018](../adr/0018-cors-and-security-headers.md)):

1. Request id (`X-Request-Id`, kept in the log context of everything the request does).
2. Access log (outside the rest, so it sees preflights and CORS refusals).
3. Security headers on every response (`nosniff`, `Referrer-Policy: no-referrer`, `X-Frame-Options: DENY`, `Permissions-Policy`, `Cross-Origin-Resource-Policy: same-site`, `Cache-Control: no-store`, `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`; a separate policy for `/docs`).
4. CORS (outside the exception handler, so a 500 is readable by the browser app).
5. Exception handler (an unexpected exception is a generic 500 problem; the cause is logged).
6. The `/docs` handler, then the router: **authorization (which authenticates) before parsing**, then content negotiation, query parameters, request coercion, response coercion, handler.

Tests move each layer to prove the order matters.

## Errors

One shape, **RFC 9457 problem details** (`application/problem+json`): `type`, `title`, `status`, `detail`, `instance`, `requestId`; `errors` for 400 and 422 (field → messages, never the values sent). The body is encoded where the problem is made, so a middleware, a handler and the router's 404/405 answer alike. FHIR's `OperationOutcome` stays for problems inside FHIR resources (ADR-0011).

Status conventions across endpoints: 400 shape (coercion), 422 a well-formed body that cannot be right, 409 the status does not allow the action, 403 outside scope, 404 does not exist, 502 FHIR/scheduler failed, 504 too slow, 503 no keys. **The shape of a body is judged before the status of the resource**, so a wrong body is 400 even when the status would be 409 (CLAUDE.md).

## Authorization

[ADR-0004](../adr/0004-authorization-as-route-data.md), [ADR-0012](../adr/0012-authorization-by-scope.md):

- Every endpoint declares `:authz`: `:public`, or `{:action a}`, plus `:resource (fn [request scope])` for one resource. `check-routes!` makes the start-up fail for an endpoint without a valid `:authz` or with an action with no rule; Reitit's automatic `OPTIONS` endpoints are off because they would carry no `:authz`.
- `rules` maps each action to a role → scope (`:own`, `:panel`, `:all`, `:identifiers`; `:any` for every caller); widest scope wins; `:own`/`:panel` count only with a `fhirUser` of the right type. The table is in [../functional/roles-and-permissions.md](../functional/roles-and-permissions.md).
- `authorize` is pure. Without a resource it returns the scope, and **collection endpoints restrict their own FHIR query** (a panel becomes `general-practitioner=<me>`): nothing fetches everything and filters afterwards. With a descriptor `{:patient ... :practitioners #{...}}` it checks the resource is in scope.
- The descriptor can carry the resource itself, so `PATCH` and `ack` work on the version the decision was about (one read).
- A patient asking for another's record is refused from the ids alone; staff get 404 for a missing patient and 403 for one outside the panel; an id that cannot be a FHIR id is a 404 with no lookup.
- Audit: `:pulse.audit/access` for every decision on clinical data (who, action, patient, scope, outcome, reason, request id; never the record) and `:pulse.audit/write` for each write FHIR accepted (who, action, patient, reference; never a value).
- The Annex A matrix exists twice: `rules` in code and a table in `authz-test`, transcribed independently.

## Endpoints

| Endpoint | Notes | ADR |
|---|---|---|
| `GET /api/me` | Identity, roles, linked resource; for a patient `generalPractitioner {reference, name, specialty}` (one more read) | ADR-0027 |
| `GET /api/patients` | The panel by scope, filter, sorting, paging (`limit` ≤ 100, `total`) | ADR-0012 |
| `GET /api/patients/{id}` | Demographics | ADR-0012 |
| `GET /api/patients/{id}/summary` | ~12 FHIR queries on virtual threads under one 4 s deadline; patient required; any other section `null` + listed in `unavailable`; display-ready views; latest reading per measurement is a query of one (`_sort=-date&_count=1`); alerts are the newest 50 flags filtered to active | ADR-0013 |
| `GET /api/patients/{id}/observations` | `measurement` required, `from` inclusive, `to` exclusive (years 1900–2199), newest first, `limit` 100 (≤ 500), `truncated`; no offsets: page by `to` | ADR-0014 |
| `POST /api/patients/{id}/observations` | `{measurement, value, unit?, at?}` (or `systolic` and `diastolic` for blood pressure); the server decides subject, performer, status `final`, id, time window (≤ 5 min ahead, ≤ 30 days back); 201 with the reading, no `Location`; the patient must exist | ADR-0014 |
| `GET /api/appointments` | By scope (`patient=`, `actor=`, all), `status`, `sort` (`start`/`-start`), `limit` 50 (≤ 100), `offset`, `total` | ADR-0015 |
| `POST /api/appointments` | A patient's request to their GP: description 1–200 chars, window ≤ 30 days, starts within 180 days, end in the future; 409 for no GP | ADR-0015 |
| `PATCH /api/appointments/{id}` | `{action, start, end}` with `confirm` or `reschedule`, or `{action: cancel, reason?}`; times not past, ≤ 1 year ahead, 5 min – 4 h; 403 for a command the kind of actor may not take (judged after the shape, before the status), 409 status, 422 values; read-modify-write PUT | ADR-0015, ADR-0027 |
| `GET /api/alerts` | Panel first, then flags with an OR list of patient references, 50 at a time; an empty panel asks nothing; `status` filtered afterwards (≤ 1000 flags per scope) | ADR-0016 |
| `POST /api/alerts/{id}/ack` | 200 or 409 when not active | ADR-0016 |
| `GET /api/admin/jobs` | Asks the scheduler's `GET /jobs` with the api's own token; the answer is validated against the api's schema (502 when it does not fit); 502 unreachable/refused/failed/no token, 504 slow; a bug is a 500, not a 502; `/readyz` does not ask the scheduler | ADR-0025 |
| `/openapi.json`, `/docs/` | Generated OpenAPI 3.1, Swagger UI 5.33.0 from a jar | ADR-0017 |

Time comes from a `:clock` among the route deps, so tests fix it.

## Documentation generated from the routes

[ADR-0017](../adr/0017-api-documentation-openapi-and-swagger-ui.md):

- `reitit-openapi` (`fi.metosin/reitit-openapi`) turns the route data into OpenAPI **3.1.0** (the library defaults to 3.2.0). `pulse.api.docs/document` completes it from the finished router: the `oauth2` security requirement, `x-pulse-access` (roles and scopes read from `authz/rules`), the shared problems (400, 401, 403, 404, 5XX), and an endpoint's own 409/422 from `:problems`.
- Descriptions are data on routes and malli schemas; a test fails for a missing one. `docs-test` holds a table of every operation with its statuses.
- Swagger UI is served by a handler that checks the method and the path (GET/HEAD, whole path segments under `/docs`); `ring-swagger-ui` 5.33.0 is pinned because 5.32.11 lacks `oauth2-redirect.js`.

## Adding an endpoint

From CLAUDE.md: the route (`:tags`, `:summary`, `:description`, a `:description` per response, `:problems` for 409/422), the schemas (`:description` on each parameter and field), `:authz`, a rule if the action is new, and tests (the Annex A table in `authz-test`, the operations table in `docs-test`).

## Tests

`apps/api/test-support` (`pulse.api.test.harness`: fake IdP, in-memory FHIR with the cohort, tokens for the demo people, `call`, a FHIR spy; `with-api` takes `:cors-origins` and `:scheduler`); property tests throw random bodies and queries at the observation and appointment endpoints; `docs-test`, `security-test`; `stack-test` against the real stack with `bb test:it` (plan Phase 4; CLAUDE.md). See [testing-strategy.md](testing-strategy.md).

## Known limits

X-24 (panel sorting pushed down to FHIR), X-25/X-26 (corrections, idempotent recording), X-27 (415 for non-JSON), X-28 (`If-Match`), X-29 (double booking), X-33 (`Flag.status` parameter), X-05 (rate limiting, body size), X-46 (formal validation of the OpenAPI document), X-48 (stable `operationId`s).
