# `apps/spa`: the clinical workspace

> A ClojureScript single-page application and a public OIDC client of the api. Functional view: [../functional/spa.md](../functional/spa.md). Source: [ADR-0029](../adr/0029-the-clinical-workspace-a-browser-client-of-the-api-with-keycloak-js-re-frame-and-the-pages-of-the-mock-ups.md) and its updates of 2026-10-25 to 2026-11-02; `apps/spa/src/pulse/spa/*`; CLAUDE.md (apps/spa).

## Stack

shadow-cljs 3.5.7 (`:deps true`, pinned in `deps.edn` and `package.json`), Reagent 2.0.1 on React 19, re-frame 1.4.7, reitit 0.11 (`reitit.core` for matching in `.cljc`, `reitit-frontend` for history), keycloak-js 26.0.8, malli, test.check. `libs/fhir`, `libs/ui` and `libs/view` are compiled to JavaScript **as they are** (`.cljc`). A spike on 2026-10-25 confirmed the combination before the ADR (ADR-0029 "Context").

## Layers

| Layer | Namespaces | Notes |
|---|---|---|
| Pure models (`.cljc`, run on the JVM and on Node) | `route`, `http`, `person`, `panel`, `patient`, `inbox`, `ack`, `watch`, `appointments`, `appointment-command`, `jobs`, `patients` | Machines of state as data: what to ask for, what an answer means, what the page shows |
| Events and effects (`.cljs`) | `events`, `*_events`, `fetch`, `auth`, `router`, `subs`, `views`, `core` | Events are pure `reg-event-fx`; effects at the edge: `::fetch/request`, `::fetch/cancel`, `::auth/*`, `::router/*`, timers |
| Pages (`.cljc`, pure hiccup) | `pages/shell`, `start`, `panel`, `patient`, `alerts`, `appointments`, `jobs`, `parts` | Called by the views **and** by `design` to build the mock-ups |
| Adapter | `hiccup/->reagent` | The only place that knows `libs/ui` was written for a server: `:key` by position on every child sequence, React's attribute spellings (`:tabindex` → `:tab-index`) |
| Release server (JVM) | `config`, `system`, `main`, `handler`, `files`, `security` | `bb spa:serve` |
| nginx configuration (JVM, a generator) | `nginx` | `bb spa:nginx` writes `infra/spa/nginx.conf` from `security` and `files`; the container serves the release with it ([ADR-0030](../adr/0030-packaging-the-apps-compiled-uberjars-two-dockerfiles-nginx-for-the-workspace-and-a-compose-profile.md)) |

Rules that hold everywhere: **the token never enters the `db`**; **a view never dispatches while it renders** (loading is the route's `:start`); **no optimistic updates** (a command is sent, the api answers, the part is read again); **a control with nothing to lead to is not drawn**; **a part that failed is a message, never an empty list**.

## The `:http` effect (`pulse.spa.http`, `pulse.spa.fetch`)

- `http` (pure): `path` (segments encoded from UTF-8 bytes; empty, `.` and `..` refused), `query-string`, `request-id` (UUID), and `outcome`: 2xx success (unless the body is not JSON), 400/409/422 **refusals** with the api's problem, 401 `:unauthorized`, 403 `:forbidden`, 404 `:not-found`, 5xx `:server`, other `:unexpected`.
- `fetch`: `fetch` with an `AbortController`, `X-Request-Id`, `credentials: "omit"`, `redirect: "error"`, 6 s for the call, its renewal and its repeat. Token from `auth/token!` (`updateToken(30)`) at the moment of the call, in the header only. **A 401 is one forced renewal and one repeat.** A call with an `:id` aborts the previous one with the same id; an aborted call tells nobody. A path that cannot be built is a failure `:unexpected` of the call, not an exception out of the effect.
- Rejected: `re-frame-http-fx` + `cljs-ajax`, client caches (ADR-0029 "Alternatives").

## Routing (`route.cljc`, `router.cljs`)

- `routes` is a table: `/` (panel), `/patients/:id`, `/alerts`, `/appointments`, `/appointments/:id/confirm|reschedule|cancel`, `/jobs` (`:roles #{:clinic-admin}`), `/signed-out` (`:public? true`). Each says `:roles` or `:public?`, a `:title`, and vectors of events `:start`, `:update`, `:refresh`, `:stop`. **`check!` runs when the namespace loads**, so a bad table stops the workspace from starting.
- `transition` decides what a change of route starts and stops: only when the **page** changes (a change of filter or search, or opening a dialog, is the same page: `:page-params`, `:page`, `:update`).
- Parameters are read by malli schemas that **never throw** (a filter that is none is all; a repeated parameter is its first; a search longer than 100 is cut).
- History mode (`reitit.frontend.easy`, `:use-fragment false`); the server answers `index.html` for any non-file path. `::arrived` sets `document.title` and, after a navigation, moves focus to `main h1` two animation frames later. `ignore-anchor-click?` leaves in-page links (the skip link) to the browser.

## The pages and their machines

| Page | Machine | What it asks | Notable rules |
|---|---|---|---|
| Panel | `panel.cljc` | `GET /api/patients?limit=100&sort=family`, then each patient's summary and 14-day series of the headline measurement, plus alert counts and requests | **≤ 6 calls at once, one 10 s deadline**, generations so a late answer of an old load is not heard; order of urgency set **once** when settled; filters and search in the address |
| Chart | `patient.cljc` | Patient, summary, series `[kind days]`, evidence of active alerts | The Patient is the gate (403/404 → "gone", nothing else asked); epochs so an old deadline cannot fail a newer choice; a series answer for another measurement is dropped |
| Inbox | `inbox.cljc` | Active and acknowledged alerts, patient names, evidence | **Never moves on its own**: a check keeps new lists apart in `:latest` and shows "The alerts have changed"; after an acknowledgement both lists are read again and adopted |
| Acknowledge | `ack.cljc` | `POST /api/alerts/{id}/ack` | `:sending` → `:settling` (busy until the page is re-read) → done or `:failed`; 200 and 409 both mean acknowledged; focus returns to the alert's button on failure |
| Beat | `watch.cljc` | `GET /api/alerts?status=active&limit=1` every 30 s while the tab is visible | Epochs; a hidden tab asks nothing; `visibilitychange` starts a new run; an unknown count is `?` |
| Appointments + dialogs | `appointments.cljc`, `appointment_command.cljc` | Three lists (`proposed`, `booked`, `cancelled`, 100 each), names, `PATCH` | Buttons from `appointment/available-actions` as a `:clinician`; `appointment/change` checked at the press; the api's errors placed beside fields (`pulse.view.refusal/place`); busy until the three lists are re-read; close by replacing the history entry; focus to the row |
| Jobs | `jobs.cljc` | `GET /api/admin/jobs` once | 403 "You cannot see the jobs"; other failures "could not be loaded", never "No jobs" |

Every id a page writes is made by `command/dom-id` (a dot becomes an underscore). Lists whose items hold controls are keyed by item id, because React reconciles by position otherwise and handed the focus to the wrong alert.

## States, error boundary, accessibility (step 9)

- `pulse.spa.test.states` is an inventory: `vocabulary` `:loading :empty :partial :failed :gone :forbidden :static`, examples per route and state, and pages outside the table. `states_test` holds `route/routes` to it (a route with `:start` has `:loading` and `:failed`, an `:id` route `:gone`, an admin route `:forbidden`...), checks words on visible text only (with the `sr-only` live region removed), and runs `pulse.ui.test.a11y`.
- `views/boundary`: a Reagent class with `get-derived-state-from-error`; a page that cannot be drawn shows "Something went wrong" (cause in the console only, X-143).
- A sign-in that takes over 10 s says so (`events/start-patience-ms`).
- Forced colours: `[aria-current="page"]` gets weight and underline (`design/css/base.css`).

## The release and its policy

- `bb spa:build`: `:advanced` optimizations, `main.<hash>.js`, about 0.9 MB unzipped; **the build fails on any compiler warning**, inference warnings included (`tools/spa_output.clj` reads the output), because Closure silently renamed claim properties in the spike.
- `bb design:spa` writes the committed static files into `apps/spa/public` (not `resources/public`, which would clash with the portal's `public/pulse.css` on a shared classpath); `release!` copies them beside the script and writes the page that loads it by its hashed name.
- `pulse.spa.files`: GET/HEAD only; plain segments after percent-decoding; known types; a name with an extension is that file or a 404; anything else is `index.html`; hashed names are `immutable` for a year, the rest `no-cache` with 304.
- `pulse.spa.security/policy`: `default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'self' <api> <Keycloak>; object-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'`, `Referrer-Policy: same-origin`, HSTS for https. The origins come from `API_BASE_URL` and `KEYCLOAK_ISSUER` and must match the build's `goog-define`s (`pulse.spa.settings`), which a test holds to the server's and the realm's.
- The dev server (`bb spa`) has **no** CSP (its client needs `eval` and a WebSocket); the policy is checked on the release.
- In the container (`bb apps`, Phase 9) the release is served by `nginx-unprivileged`, whose configuration `pulse.spa.nginx` makes from the same data: the headers and the policy once at the level of the server, the kinds of file and their charsets, the rules that decide what a path is (regular expressions in the order nginx tries them), the hashed script kept for a year and the rest asked about, and the problems of a 404 and a 405. The Ring server and nginx answer one table of 45 requests alike; where they cannot is written in the generated file (nginx resolves `.` and `..` itself, a bad percent-encoding is a 400, and a problem has no `instance`). The page and the policy are for the addresses the realm registers, so other addresses are another file (`bb spa:nginx --api-base-url ...`).

## Tests

`bb test --focus :spa` (JVM: the `.cljc` models and pages, the server) and `bb spa:test` (Node, `:node-test`, part of `bb check`: the same `.cljc`, `day8.re-frame/test` event flows with effects replaced by recorders, `fetch_test` against a real Node HTTP server, `render_test` that renders every page and state with React and fails on any console message). `bb spa:journey` is the acceptance in a real browser (225 checks). See [testing-strategy.md](testing-strategy.md).

## Known limits

X-79 a panel in one request (the browser fan-out is the current design), X-111 phone layout, X-112 theme, X-113 re-frame-10x on React 19, X-114 code splitting, X-115 renewal in the journey, X-117 a reference for "sign-in not accepted", X-118 building for another address, X-120 more than 100 patients, X-121 panel refresh, X-123 alert evidence from the api, X-127 server push, X-132 refresh of appointments and jobs, X-133 native modal dialogs, X-140 a screen-reader pass, X-142 other browsers.
