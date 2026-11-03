# `apps/web`: the patient portal

> A server-side client of the api (a "backend for frontend"). Functional view: [../functional/web.md](../functional/web.md). Source: [ADR-0028](../adr/0028-the-patient-portal-a-server-side-client-of-the-api-with-its-own-sign-in-and-a-token-keeper-for-each-session.md) and its updates of 2026-10-19 to 2026-10-25; `apps/web/src/pulse/web/*`; CLAUDE.md (apps/web).

## Shape

- Pages rendered on the server with Hiccup 2; htmx 2.0.11 for partial updates, and every page works without a script.
- The browser holds **one opaque cookie**; tokens never leave the server; every piece of data comes from the api with **the patient's own access token**, so the api keeps deciding what a person may see.
- `resources/pulse/web/config.edn` (Integrant + Aero, closed schema; profiles `dev`/`test`, `WEB_PROFILE`): `logging`, `jwks`, two verifiers (`access-verifier` audience `pulse-api` `typ` Bearer; `id-verifier` audience `pulse-web` `typ` ID), `api` (a `service-client` with no token of its own), `oidc` (`WEB_CLIENT_SECRET` as `#secret`, `WEB_BASE_URL`, Keycloak addresses for the browser and the back channel), `sessions`, `handler`, `server` (`WEB_HOST`, `WEB_PORT` 3300).

## Pipeline

Outermost first: request id → access log → security headers (HSTS when `WEB_BASE_URL` is https) → exceptions (`:unexpected errors/server-error`, a page and not a problem) → origin check (`csrf/wrap-origin`) → session (`session/wrap-session`) → Reitit router (`authz/middleware`, `parameters-middleware`, `csrf/middleware`). Unknown paths: `errors/default-handler` (404, 405 with `Allow`).

## Routes and authorization

Every route says `:authz :public` or `:authz :patient`; `authz/check-routes!` stops the start-up otherwise (`apps/web/src/pulse/web/routes.clj`).

| Route | Methods | Who |
|---|---|---|
| `/` (home) | GET | patient |
| `/readings`, `/readings/new` | GET; GET + POST | patient |
| `/appointments`, `/appointments/{id}/cancel` | GET + POST; GET + POST | patient |
| `/record`, `/profile` | GET | patient |
| `/sign-out` | POST | patient |
| `/sign-in`, `/oauth2/callback`, `/signed-out` | GET | public |
| `/healthz`, `/readyz`, `/assets/*path` | GET (+ HEAD for assets) | public |

A `:patient` route with no session is a 303 to `/sign-in?return-to=...`; an htmx request gets a 401 with `HX-Redirect` instead, so a sign-in is never swapped into a fragment; a stale cookie gets "Your session has ended" (401) and the cookie is dropped. `return_to` must be a path of this server (one slash, printable ASCII, no backslash, ≤ 2000 chars), checked by a property test.

When the session's keeper cannot give a token, `authz` answers: refused by the IdP ends the session; anything else is 503 "Sign-in is not available right now" and the session stays.

## Sign-in, session, CSRF

Covered in [identity-and-tokens.md](identity-and-tokens.md#the-portals-sign-in-pulseweboidc-pulsewebsignin). The session store (`pulse.web.session`) is Ring's `SessionStore` over memory with what Ring's own lacks:

- 30 minutes idle, 10 hours absolute (the realm's numbers); a pending sign-in 10 minutes.
- At most 1,000 pending sign-ins (the oldest goes, never a session) and 10,000 sessions (LRU), so an anonymous visitor cannot fill it.
- 256-bit keys; a key chosen by the browser is never used; a write that changes the kind of entry is a new entry; it prints a count, never its content.
- Cookie `pulse-session`: `HttpOnly`, `SameSite=Lax` (it must come with the top-level redirect back from Keycloak), session cookie, `Secure` when https.

CSRF (`pulse.web.csrf`): an `Origin` check for every unsafe request (a foreign or `null` Origin is refused), and for patient routes the token made at sign-in, read from the field `__anti-forgery-token` or the header `X-CSRF-Token` by `ring-anti-forgery` 1.4 with **a strategy of our own** that reads it from the session (its session strategy would create a session for any visitor). htmx sends the token through `hx-headers` on `<body>`.

## Security policy

`pulse.web.security`: `default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'self' <Keycloak's origin>`. Keycloak's origin is in `form-action` because sign-out is a form the portal answers with a 303 to Keycloak, and Chrome applies `form-action` to that redirect (found in the spike). `Cache-Control: no-store`, `Referrer-Policy: same-origin` (with `no-referrer` a browser sends `Origin: null` on same-site form posts). htmx is configured by a meta tag: no eval, `includeIndicatorStyles` false (it would inject a `<style>`), `historyCacheSize` 0 (no page of a record kept in `localStorage`).

## How a page is made

- Each page is a **pure** function of `{...api shapes...}` and `links` (`pulse.web.pages.*`). **A link that is not in `links` is a page that does not exist yet, and the control that would lead there is not drawn.** The mock-ups call the same functions, and tests hold the served page to the mock-up's ("what is reviewed is what is served").
- Handlers ask the api with `(assoc api-client :token (session/tokens-of request))` and forward the request id (`pulse.web.api`).
- `api/degradable?` separates failures a page can go on without (unreachable, slow, 5xx, not JSON) from those that are the whole page's (4xx: the portal's doing). A lost part is a message with its own title and a retry, never an empty list. `errors/api-failure` maps the rest: 403 forbidden; otherwise 502, or 504 when the api was slow.
- Home: `/api/me`, then the summary, then **at once under one deadline** (5 s) the newest five readings of each measurement the patient has.
- Readings: `/api/me`, the summary (only to choose what to offer), the series with `from` = now − 7/14/30 days, `limit` 500. Charts for phone and desk (`pulse.view.charts`).
- Recording: `pulse.web.recording` (pure) parses what was typed (`taken-at` is `fmt/local->instant` on the clinic's clock), `api/post-json` answers `{:created}` or `{:refused {:status :errors}}`; the api's errors go beside the fields (`pulse.view.refusal/place`). A saved reading is a 303 with a **flash** in the session, told once. A failure distinguishes `:unsaved` (before sending) from `:unconfirmed` (on the way).
- Appointments: `GET /api/appointments?sort=-start&limit=100` made into three sections (`views/appointment-sections`). Requests (`pulse.web.visits`, days of the clinic's clock: a window from the first day's midnight to the midnight after the last). Cancelling: the portal reads who the person is and the list, finds the appointment, checks the lifecycle (`views/cancel-blocker`) and only then sends `PATCH`; a 409 makes it read the list again and say why.
- Record: three lists from the summary; Profile: only `/api/me`, which already holds the Patient and the doctor.

## Static files

`resources/public` (stylesheet, htmx 2.0.11, Inter with its licence, icon) is generated by `bb design:web` and committed; a test fails when a committed file is not what the generator makes, or a page uses a class with no rule. `/assets/*path` serves only plain segments with a known extension, `Cache-Control: no-cache`, 304 when unchanged.

## Tests

`apps/web/test-support` (`pulse.web.test.harness`: `with-web` starts a fake OpenID Connect provider, a stand-in api and a hand-moved clock; `browser`, `visit!`, `sign-in!` keep cookies and follow redirects; `pulse.web.test.cohort` answers from the cohort and judges posts as the api does). `signin-test` (every failure mode, replay, concurrency), `session-test` (against a model), `csrf-test`, `oidc-test`, page tests with a11y rules, `crawl-test` (follows every link and GET form as Ana and Noah), `pulse.dev-test` (one JVM, real api), `pulse.web-stack-test` (`bb test:it`, real Keycloak and its login page; the kept token is refused another patient's data by the real api), and `bb web:journey` (62 checks in headless Chrome). See [testing-strategy.md](testing-strategy.md).

## Known limits

X-91 back-channel logout, X-92 shared session store (a restart ends every session; one process), X-94 `__Host-` cookie, X-95 two sign-ins at once from one browser, X-96 Fetch Metadata, X-97 zone as a setting, X-98/X-99/X-107 fewer calls, X-101 idempotent recording, X-102–X-106 limits of readings, units, time and appointments, X-110 the journey in CI.
