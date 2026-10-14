# ADR-0009: Access tokens are verified with Nimbus JOSE+JWT behind a thin facade, and the token policy is plain Clojure

- **Status:** accepted
- **Date:** 2026-10-14

## Context

The original proof of concept verified tokens with Buddy: the public key was loaded lazily and a failure stayed cached, only the first key of the set was used and `kid` was ignored, and an invalid or missing token escaped as an HTTP 500. Buddy is also in maintenance mode (stack review, section 3.2). The plan asked for a bounded spike on Nimbus JOSE+JWT from Clojure before building the `api` on it. Two facts about Keycloak 26.0.0 matter: its JWKS holds a signature key (RS256) and an encryption key (RSA-OAEP), and it signs ID tokens with the same key as access tokens.

## Decision

`pulse.platform.jwt` (in `libs/platform`, JVM only) is the only code that touches Nimbus.

- **Nimbus does the JOSE work:** parsing, key selection by key type, `use`, algorithm and `kid`, signature checks, and JWKS retrieval and caching (`JWKSourceBuilder`).
- **The policy is plain Clojure:** `check-claims` is a pure function of the claims, the options and `now`. It checks `iss` (exact), `aud` (must include `pulse-api`), `exp` and `nbf` with a leeway (30 s by default) and, optionally, the `typ` claim (`Bearer`), which keeps ID and refresh tokens out. Nimbus's own claims verifier reports failures as message strings and reads the system clock; ours returns a typed error and takes the time as an argument, so every boundary is a unit test.
- **The signature is verified before any claim is read.** Only asymmetric algorithms from a configured allow-list are accepted (default `RS256`), and a configuration with an HMAC algorithm is refused. `alg: none`, the "public key as HMAC secret" attack and the `jku`/`jwk`/`x5c` headers get no chance: keys only come from the JWKS.
- **Bad tokens are data, not exceptions.** `verify` returns `{:ok claims}` or `{:error {:type … :message …}}`. The `errors` table maps each type to an HTTP status, the RFC 6750 error code and a generic client-facing text that reveals nothing configured; `challenge` renders `WWW-Authenticate`. A bad token is a 401 with `error="invalid_token"`, a request without credentials gets the bare challenge, and **having no key set at all is a 503**: a 401 would send well-behaved clients into a pointless re-login loop.
- **JWKS policy:** a lazy TTL cache of 5 minutes; an unknown `kid` makes it refetch, which is how a rotation is picked up; fetches are rate limited; when a fetch fails the last good set is served for up to an hour; HTTP timeouts of 2 s.
- The issuer and the JWKS URI are separate settings, as the Docker profile needs: Keycloak reports `iss` from its configured hostname (`http://localhost:8080`) whatever address it is reached at.

## Alternatives considered

- **Buddy (`buddy-sign`, `buddy-auth`):** maintenance mode, and the JWKS handling would have had to be written anyway.
- **Nimbus's `DefaultJWTProcessor` with `DefaultJWTClaimsVerifier`:** fewer lines, but failures come as exception messages and the clock cannot be injected without subclassing.
- **Token introspection on every request:** a network round trip per call and a hard dependency on Keycloak being up, for tokens that live 5 minutes. Extra X-08.
- **Refresh-ahead caching:** no request ever waits for a fetch, but it starts a thread to be shut down. Extra X-09.

## Consequences

- Tokens of every demo role and of the scheduler's service account verify against Keycloak 26.0.0. A verification costs about 160 µs once the JVM is warm (p99 365 µs); the first one also fetches the key set (about 15 ms).
- Things the spike found, which shaped the code and are easy to get wrong:
  - Nimbus 10 turns on refresh-ahead caching **by default** (a background thread), and the builder rejects a TTL shorter than its timings. It is switched off explicitly.
  - Its rate limiter lets **two** fetches through per window (the one that opens it and one more) and then throws `RateLimitReachedException` until the window ends. A rotation is therefore seen at once, but after a start-up with Keycloak down, a third attempt is refused even when Keycloak is back. With no key set that is reported as the 503, never as an unknown key. The window is 10 s instead of Nimbus's 30 s.
  - Keycloak's encryption key is ignored by the key matcher; a token signed with it is rejected as `:unknown-key`.
  - `alg: none` and an empty signature fail while parsing (`:malformed`), before any key lookup.
  - Keycloak access tokens carry `typ: Bearer`, `aud: [pulse-api, account]`, `azp` (the client), no `nbf`, and `realm_access.roles` including its own default roles, so the application role must be picked from the known set. Service-account tokens have no `fhirUser`.
- 19 unit tests (141 assertions, 4 of them properties) need no network: tokens are minted with throwaway RSA keys and the JWKS comes from a local HTTP server, which is what makes caching, rotation and outages testable. 2 integration tests (`bb test:it`) use the real Keycloak.
- The Phase 4 middleware only has to call `bearer-token` and `verify` and answer from `errors` and `challenge`. Mapping roles and `fhirUser` to an identity stays in the authorization layer of ADR-0004.
- Not covered, on purpose: revocation before expiry, an allow-list of `azp` clients, `jti` replay checks and sender-constrained tokens (extra X-08).
