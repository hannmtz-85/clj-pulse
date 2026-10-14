# ADR-0011: The api's request pipeline: errors as problem details, authorization before parsing, and routes that must say who may call them

- **Status:** accepted
- **Date:** 2026-10-14

## Context

ADR-0004 decided that authorization lives in route data and denies by default. Building the first routes left open how errors look, in what order the middleware runs, and how "denies by default" is made hard to get wrong. In the original proof of concept an invalid token surfaced as HTTP 500 and a new route was open to anyone with a token.

## Decision

- **One error shape: RFC 9457 problem details** (`application/problem+json`) for every error, whoever raises it: `type`, `title`, `status`, `detail`, `instance` (the path) and `requestId`. The body is encoded to a string where the problem is made, so it is the same for a handler, a middleware that runs before content negotiation and the router's own 404 or 405. `detail` is written for the client; exception messages and causes go to the log, and an unexpected exception is always a generic 500. Deliberate errors are thrown with `problem/ex`.
- **Pipeline, outermost first:** request id (also in the log context of everything the request does), access log, exception handler; then, inside the router, authorization (which authenticates), content negotiation, query parameters, request coercion, response coercion, handler. **Authorization comes before parsing**: nobody reaches the JSON parser without a valid token. Public endpoints (`:authz :public`) get no middleware at all and ignore credentials.
- **Deny by default is checked when the router is built.** `check-routes!` fails the start-up and names every endpoint that has no valid `:authz` or names an action without a rule. An endpoint that gets through anyway answers 403 to everybody. Reitit's automatic `OPTIONS` endpoints are switched off because they would carry no `:authz`; a CORS preflight will be answered by a global middleware.
- **JSON only.** Muuntaja is restricted to JSON (it also speaks EDN and Transit); another format is added when a client needs it.
- Coercion errors are 400 problems whose `errors` member holds malli's humanized messages, never the values sent. A response that breaks its own schema is our bug and a 500.

## Alternatives considered

- **Reitit's exception middleware:** it sits inside the router, so it would miss failures raised by outer layers, and its bodies are not problem details. An exception handler of our own, outermost, is about twenty lines.
- **FHIR `OperationOutcome` for errors:** right for FHIR servers; the api's own endpoints are not FHIR. `OperationOutcome` stays for reporting problems in FHIR resources.
- **A runtime-only default deny:** a forgotten rule would show up as a 403 in production instead of a failed start-up.

## Consequences

- Every client handles one error format, and a request can be followed through response header, error body and log by its id.
- Adding an endpoint means adding its `:authz`, or the system will not start; adding an action means adding its rule, or no one can use it.
- reitit-malli closes every `:map` schema and strips the keys it does not declare. That is a good default for responses (nothing leaks by accident) but turned a FHIR resource into `{}` until the schema became `[:map-of :keyword :any]`; a test now covers it.
- The platform library holds the mechanisms (`pulse.platform.http.*`: problem, request id, access log, exceptions, bearer authentication, health); the api holds the policy (`pulse.api.authz`, `pulse.api.identity`), so the scheduler can reuse the first.
