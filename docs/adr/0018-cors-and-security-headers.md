# ADR-0018: CORS and security headers, in house

- **Status:** accepted
- **Date:** 2026-10-14

## Context

The api is called by a browser application on another origin (the SPA, `http://localhost:3000`), so it must answer CORS preflights, and every response of a service that carries clinical data should say what a browser may do with it. `ring-cors` was replaced by an in-house middleware in the stack review (last release 2019), and ADR-0011 left the preflight to "a global middleware", because Reitit's automatic `OPTIONS` endpoints are off. Open questions: how strictly origins are matched; what a disallowed origin gets; which headers every response carries and which of them differ for the page of Swagger UI (ADR-0017).

## Decision

- **CORS is an allow-list of exact origins** (`scheme://host:port`), taken from the configuration (`API_CORS_ORIGINS`, comma separated, default only the SPA's origin). Never `*`, never an origin reflected without being on the list, and `null` is not an origin. A configuration with a wildcard, a path or a trailing slash fails at start-up.
- **The preflight is answered before the router**, so before authentication, as it carries no credentials and must not need them. An allowed origin gets 204 with the allowed methods (`GET`, `POST`, `PATCH`), the allowed request headers (`Authorization`, `Content-Type`, `X-Request-Id`) and a max age of ten minutes. A disallowed origin gets a 403 problem that says so, which is easier to find in a browser's network tab than a silent failure. An `OPTIONS` request that is not a preflight is not CORS's: it gets the 405 it always had.
- **Other responses to a request with an allowed `Origin`** carry `Access-Control-Allow-Origin` with that origin and expose `X-Request-Id` and `WWW-Authenticate`, so that a client can quote the first and read why a token was refused. `Vary: Origin` is on every response to a request that has an `Origin`, allowed or not, so that no cache serves one origin's answer to another.
- **No credentials mode.** The api authenticates with a bearer token in the `Authorization` header, which is not a credential in the sense of CORS (cookies, HTTP authentication, client certificates): `Access-Control-Allow-Credentials` is never sent, and a cookie could not be used against this api from another site.
- **Security headers on every response, whoever made it** (a handler, an error, the router's 404, a static file): `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, `X-Frame-Options: DENY`, `Permissions-Policy` with the powerful features off, `Cross-Origin-Resource-Policy: same-site`, `Cache-Control: no-store` (the answers are clinical data) and, for the api, `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`. A response that sets one of them itself keeps its own. For the page of Swagger UI (`/docs`) the Content-Security-Policy is the one it needs and no more (own scripts and styles, images, connections to this server and Keycloak, no framing, no plugins, no base tag), and `Cache-Control` is left alone, so the 1.5 MB bundle can be cached. The rules are data: a default set and a set per path prefix, where a `nil` value removes a header.
- **Not set:** `Strict-Transport-Security`, because the local stack speaks HTTP (a browser ignores it there; production hardening is extra X-50), and `Cross-Origin-Opener-Policy` and `Cross-Origin-Embedder-Policy`, because the login of Swagger UI needs its popup to keep its opener (ADR-0017).
- **Order of the pipeline, outermost first:** request id, access log, security headers, CORS, exception handler, then the router. The log is outside the rest so that it sees what they answer: the preflights, which CORS answers before the router, and the refusals of an origin, are in it like any request. The headers wrap everything that answers, an error or a preflight included. CORS is outside the exception handler, so that a 500 carries `Access-Control-Allow-Origin` too: a browser application that cannot read an error cannot tell its user what went wrong. Tests pin each of these by moving the layer.
- **The mechanisms are in `libs/platform`** (`pulse.platform.http.cors` and `.headers`), pure Ring middleware that know nothing of the api; the policy (the origins, the Content-Security-Policy texts) is the api's.

## Alternatives considered

- **`ring-cors`:** unmaintained since 2019.
- **`Access-Control-Allow-Origin: *`:** harmless for an api that authenticates by header alone, but it says that any site may read the answers of a user who is signed in elsewhere, and it cannot be combined with the headers we expose. An allow-list costs one line of configuration.
- **Answering a disallowed preflight with 204 and no CORS headers:** the browser blocks it either way, and the developer is left guessing.
- **A response header per route in route data:** more flexible, but the headers are a property of the service and of the page, not of each endpoint, and a forgotten one is a hole. A global middleware with a rule per path prefix cannot forget.

## Consequences

- A browser application on another origin must be added to the configuration before it can call the api; the scheduler and the portal call it from servers, which CORS does not concern.
- The headers cannot be seen to work in the tests of the middleware alone: the api's tests check them on real responses, including errors, and over a real socket. The Content-Security-Policy of the docs page, which a test can only read, was checked in a browser (headless Chrome 155, Swagger UI 5.33.0, the real Keycloak): the page draws, signs in through the popup, exchanges the code for a token and calls the api with no violation and with no `unsafe-eval` and no inline script. A control showed the policy bites and the check sees it: an inline script did not run and a `fetch` to another origin was blocked, each with its message in the console.
- CORS was checked in the same browser with two pages that call the api, one at the origin of the workspace (`http://localhost:3000`) and one at another (`http://localhost:3001`). The first reads a success, a 401 (with its `WWW-Authenticate`) and a 403, reads `X-Request-Id`, and sends a `PATCH` after the preflight; a `DELETE` is refused by the browser itself (`MethodDisallowedByPreflightResponse`). The second can read nothing, not even a `GET` with no token (`MissingAllowOriginHeader`, `PreflightMissingAllowOriginHeader`).
- The page needs `'unsafe-inline'` for styles, because Swagger UI styles its elements from script; scripts are the risk, and they come from this server only.
- Disabling the cache for everything but the docs costs a little on a local network and nothing in a clinic, where a cached health record on a shared machine is the risk.
