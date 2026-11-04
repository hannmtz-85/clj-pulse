# Identity and tokens

> The realm, the clients, how each service gets and verifies tokens. Sources: [ADR-0006](../adr/0006-keycloak-realm-as-clojure-data.md), [ADR-0009](../adr/0009-access-token-verification-with-nimbus-facade.md), [ADR-0021](../adr/0021-service-tokens-client-credentials-cached-and-renewed-early.md), [ADR-0025](../adr/0025-job-status-through-the-api-its-own-service-account-and-a-role-for-it.md), [ADR-0028](../adr/0028-the-patient-portal-a-server-side-client-of-the-api-with-its-own-sign-in-and-a-token-keeper-for-each-session.md), [ADR-0029](../adr/0029-the-clinical-workspace-a-browser-client-of-the-api-with-keycloak-js-re-frame-and-the-pages-of-the-mock-ups.md).

## Keycloak

- **Version 26.0.0**, fixed by the owner (CLAUDE.md "Fixed decisions"; the 26.0.8 patch bump is X-07). `start-dev --import-realm`, admin through `KC_BOOTSTRAP_ADMIN_*`, hostname v2 ([01-stack-review.md](../01-stack-review.md) §6).
- Default password hashing; the custom hash provider of the original proof of concept was removed (plan Phase 2).
- **The realm is Clojure data** (`tools/realm.clj`) rendered by `bb realm:build` to `infra/keycloak/realm/pulse-realm.json`, keys sorted and LF line endings. The JSON is committed; `bb realm:check` fails if it is stale. Secrets are `${VAR}` placeholders that Keycloak resolves from its container environment on import (ADR-0006).
- **Import is skipped when the realm exists**, so a realm change needs `bb reset` locally (which destroys the stack's data). Applying a change without losing data is X-75.
- Realm settings (`tools/realm.clj`): `accessTokenLifespan` 300 s, `ssoSessionIdleTimeout` 1800 s, `ssoSessionMaxLifespan` 36000 s, `loginTheme "pulse"`. The 5-minute token (the proof of concept had 60) is deliberate, so that renewal is exercised (plan Annex D).

### Roles, users and claims

- Realm roles: `patient`, `practitioner`, `clinic-admin`, `rules-engine`, `api-service`.
- Nine demo users plus two service accounts (`service-account-pulse-scheduler`, `service-account-pulse-api`). The demo password is `DEMO_USER_PASSWORD` from `.env`, never committed.
- The custom user attribute `fhir_user` is declared in the Keycloak 24+ user profile (an embedded JSON string, the reason the realm is generated, ADR-0006) and mapped to the `fhirUser` claim (`Patient/pt-ana`, `Practitioner/pr-rivera`).
- Access tokens carry `typ: Bearer`, `aud` including `pulse-api`, `azp` (the client), no `nbf`, and `realm_access.roles` with Keycloak's own default roles next to the application role; the application role is picked from the known set. Service-account tokens have no `fhirUser` (ADR-0009 "Consequences").

### Clients

| Client | Type | Flow | Redirect / origin | Audience mapper |
|---|---|---|---|---|
| `pulse-web` | Confidential (`WEB_CLIENT_SECRET`) | Code + PKCE S256 | `http://localhost:3300/oauth2/callback`; post-logout `http://localhost:3300/*` | `pulse-api` |
| `pulse-spa` | Public | Code + PKCE S256 | `http://localhost:3000/*`; post-logout `http://localhost:3000/*` | `pulse-api` |
| `pulse-docs` | Public | Code + PKCE S256 | `http://localhost:4000/docs/*` | `pulse-api` |
| `pulse-scheduler` | Confidential (`SCHEDULER_CLIENT_SECRET`) | Client credentials | – | `pulse-api` |
| `pulse-api` | Confidential (`API_CLIENT_SECRET`) | Client credentials | – | **`pulse-scheduler` only** |

Source: `tools/realm.clj` `clients`. `pulse-api`'s token is for the scheduler and **not** for the api, so the api would not accept its own token (ADR-0025).

`bb realm:smoke` signs in as every demo user through the real code + PKCE flow, through `pulse-docs`, and as both service accounts, and checks each token's role, `fhirUser`, audience and `azp`; `bb token <user|scheduler|api> [pulse-docs]` prints a token (CLAUDE.md).

## Verifying access tokens (`pulse.platform.jwt`)

From ADR-0009:

- **Nimbus JOSE+JWT 10 does the JOSE work** (parsing, key selection by `kty`/`use`/`alg`/`kid`, signature, JWKS fetch and cache) behind a thin facade; **the claims policy is plain Clojure**: `check-claims` is a pure function of claims, options and `now`.
- Signature first, then claims. Only asymmetric algorithms from an allow-list (default `RS256`); a configuration with HMAC is refused; keys come only from the JWKS (`jku`, `jwk`, `x5c` headers ignored; `alg: none` fails at parsing).
- Claims: `iss` exact, `aud` must include the configured audience, `exp`/`nbf` with 30 s leeway, optional `typ` (`Bearer` keeps ID and refresh tokens out).
- `verify` returns `{:ok claims}` or `{:error {:type ...}}` and never throws for a bad token. `errors` maps a type to a status and RFC 6750 code; `challenge` renders `WWW-Authenticate`. A bad token is 401 `invalid_token`; **no key set at all is 503**, so clients do not loop re-signing in.
- JWKS policy: lazy TTL cache of 5 minutes, refetch on unknown `kid` (key rotation), rate limiting (window 10 s), the last good set served up to an hour when a fetch fails, HTTP timeouts of 2 s. Nimbus's default refresh-ahead thread is switched off.
- The issuer and the JWKS URI are separate settings, because Keycloak writes `iss` from its configured hostname whatever address it is reached at (which the Docker profile of Phase 9 needs: inside the compose network the services ask `keycloak:8080` for keys and tokens, and the tokens still say `iss` `http://localhost:8080/realms/pulse`; `bb smoke` checks it from inside the containers).
- Measured: about 160 µs per verification warm (p99 365 µs).

Each service configures its own audience: the api `pulse-api`, the scheduler `pulse-scheduler`. The portal has two verifiers: the access token (`pulse-api`, `typ` Bearer) and the ID token (`pulse-web`, `typ` ID) (CLAUDE.md, apps/web).

## A service's own token (`pulse.platform.token`)

From ADR-0021:

- Client credentials with **HTTP Basic** client authentication (RFC 6749 §2.3.1), not the secret in the body.
- **Lazy, no thread**: nothing is asked at start-up; renewal happens on a call. The expiry is `expires_in` counted from when the request was sent. From `expiry − margin` (the smaller of 60 s and half the lifetime) the token is "near its end".
- **Single flight**: in the early window, the first caller renews and the others keep the valid token; with no valid token, one asks and the rest wait. A `ReentrantLock` taken interruptibly (not `synchronized`, which pins a virtual thread).
- **Never an expired token**: with a valid token a failed renewal is logged and the caller gets the token it has; attempts are spaced 5 s; with no valid token `access-token` throws `::unavailable` with a `:reason` (`:connect`, `:timeout`, `:rejected`, `:server`, `:bad-response`, `:unexpected`, `:backing-off` with `:last-reason`).
- Secrets are `Secret` values (`#secret` reader in the config) that print as `#secret[REDACTED]` everywhere, Integrant's dump of a failed component included.
- The scheduler's `/readyz` has a `token` check, so a wrong secret shows as "not ready".
- The keeper is a record that prints as `#token-source[client]` and accepts an `:initial` grant, which is how the portal starts a per-session keeper from the code exchange.

## The api's call to the scheduler

From ADR-0025: `:pulse.api/token` (client `pulse-api`) gets a token for audience `pulse-scheduler`; `GET /api/admin/jobs` sends **that** token, never the caller's, plus `X-Request-Id`. The scheduler's rules: `:jobs/read` for `clinic-admin` and `api-service`, `:jobs/run` for `clinic-admin` only. Rejected alternatives: forwarding the admin's token (the audience would protect nothing), token exchange (a preview feature in 26.0.0), giving the service account `clinic-admin`.

## The portal's sign-in (`pulse.web.oidc`, `pulse.web.signin`)

From ADR-0028 (points 1–5 and the update of 2026-10-21):

1. `/sign-in` creates a **pending sign-in** on the server (`state`, PKCE verifier, `nonce`, return path) under a new key and redirects to Keycloak with `scope openid profile`.
2. `/oauth2/callback` compares `state` in constant time (two missing values are not equal), **takes the pending sign-in out of the store atomically** (two identical callbacks cannot both exchange the code, which would make Keycloak revoke the first's tokens), exchanges the code, verifies both tokens, the `nonce` and that they are of the same person.
3. Only a `patient` gets a session (a **new** entry and key: no fixation). Anyone else: 403 page, no session, an audit event `:portal/sign-in :denied`.
4. The session holds `{:person ... :tokens <keeper> :csrf <token>}`. The keeper renews with the refresh grant, keeping the refresh token and the last ID token (a `Secret`). Twenty concurrent requests on a token near its end make one refresh.
5. A refusal from Keycloak ends the session ("Your session has ended"); Keycloak unreachable keeps it ("Sign-in is not available right now").
6. `POST /sign-out` (CSRF) deletes the session first, then 303 to the end-session endpoint with `id_token_hint` and `post_logout_redirect_uri` `/signed-out`.

`ring-oauth2` was evaluated in a spike and rejected (no refresh, no new session id, a `state` check that passes when both are missing, no timeouts, clj-http and 16 more jars): see [decisions.md](decisions.md).

## The workspace's sign-in (`pulse.spa.auth`, `pulse.spa.fetch`)

From ADR-0029 (point 1 and the update of 2026-10-26):

- keycloak-js 26.0.8 wrapped as a re-frame effect: `init` with `login-required`, `pkceMethod "S256"`, `checkLoginIframe false`; `response_mode=fragment`.
- **The token never enters the re-frame `db`**: it stays in the `Keycloak` object; `db` holds who the person is, read from the claims **as data with string keys** (Closure's advanced compilation renamed `.-preferred_username` to nil in the release).
- At each call: `updateToken(30)`, then the token goes in the `Authorization` header and nowhere else (`credentials: "omit"`, `redirect: "error"`). **A 401 is one forced renewal and one repeat**; a second 401 is `:unauthorized` and the page "Your sign-in was not accepted".
- Role gate: `practitioner` or `clinic-admin` (`person/workspace-roles`); a patient gets a page and no api call.
- Sign-out: `keycloak.logout` with `redirectUri` the public page `/signed-out`, which does not ask the identity provider to sign anyone in.
- The release journey checks that no token is in `localStorage`, `sessionStorage`, cookies or the console.

## Swagger UI's sign-in

From ADR-0017: public client `pulse-docs`, code + PKCE S256, scope `openid`, configured through the api's own `swagger-initializer.js` (Swagger UI takes PKCE only from `initOAuth`). No `Cross-Origin-Opener-Policy` on `/docs`, because the popup needs `window.opener`.

## Known limits

- Stateless verification: a token stays valid up to 5 minutes after sign-out; no introspection, revocation list, `azp` allow-list, `jti` replay check or sender constraint (X-08).
- No back-channel logout for the portal (X-91): a sign-out elsewhere is seen at the next renewal.
- Keycloak 26.0.0 has known vulnerabilities; local use only (plan §8 "Risks").
- Tokens protect the api and the scheduler, not the FHIR server: **HAPI has no authentication**, and the api and the scheduler call it without credentials. Its only protections are the `127.0.0.1` binding and the api being the single policy enforcement point (ADR-0002). In a real deployment this is the first gap to close, with network isolation or authentication in front of HAPI ([architecture.md](architecture.md#trust-boundaries), point 5).
