# ADR-0021: A service's own access token: client credentials, cached and renewed early

- **Status:** accepted
- **Date:** 2026-10-15

## Context

The scheduler is a client of the api: it reads patients and readings and, in development, records the readings its simulator makes. It does so as a service and not as a person, with the OAuth *client credentials* grant (RFC 6749 section 4.4): a confidential client of the realm (`pulse-scheduler`) whose service account has the role `rules-engine`. In step 7 the api will call the scheduler the same way (ADR-0020). So two services need an access token that no user gave them.

Three things make that more than one HTTP call:

- **Tokens are short** (5 minutes in this realm, so that expiry is exercised) and the grant gives **no refresh token**: renewing is asking again. Asking for a token on every call would put Keycloak on the path of every job run; waiting for a token to expire would make the call at the boundary fail, or wait.
- **Keycloak can be down or slow.** That must not become a storm of requests from every job, and it must never become the use of an expired token.
- **The secret is a secret.** Not in logs, not in errors, not in the dump that Integrant makes of a component's configuration when it fails to start.

## Decision

`pulse.platform.token` (in the platform library, because the api needs the same thing) keeps the token of one client of one token endpoint.

- **Lazy, with no thread of its own.** Nothing is asked of Keycloak when the service starts (it may start before Keycloak is up, and its readiness says whether it can get a token); the first call asks. Renewal happens on a call, not on a timer: nothing renews a token nobody uses, and there is no thread to die silently.
- **Early renewal.** The expiry is `expires_in` counted from the moment the request was sent. From `expiry − margin` the token is *near its end*, with the margin the smaller of the configured one (60 s) and half the lifetime, so a short token is not renewed all the time. Before that, `access-token` is a read of an atom.
- **Nobody waits for a renewal they do not need.** In the early window the first caller renews and the others keep using the token, which is still valid. When there is no valid token (the first call, or after expiry) one caller asks and the rest wait for that one answer. The lock is a `ReentrantLock` taken interruptibly, not `synchronized`, which pins a virtual thread while it waits on I/O, and a job interrupted at shutdown can leave the queue.
- **A failure never becomes an expired token.** With a valid token, a failed renewal is logged and the caller gets the token it has. Renewal attempts are spaced by a delay (5 s), so a Keycloak that is down is not asked by every caller. With no valid token `access-token` throws an `ex-info` of type `::unavailable` with a `:reason` (`:connect`, `:timeout`, `:rejected` for a 4xx, `:server` for a 5xx, `:bad-response`, `:unexpected` for anything else that was thrown, and `:backing-off` when it has not tried again yet, with the `:last-reason` of the attempt that failed) and, where there is one, the `:status` and the OAuth `:error` code. An interruption (a job cut short at shutdown) is not a failure of the endpoint: it is rethrown as it is and nothing is held against Keycloak. Its message and data never hold the secret, the request or the body of the answer.
- **The client authenticates with HTTP Basic** (RFC 6749 section 2.3.1: the id and the secret are form-encoded, then joined and Base64-encoded), which servers must support, and not with the secret in the body, which the RFC discourages. The token URL is a setting of its own, like the address of the JWKS, since inside a container Keycloak is reached at a different address from the one in `iss`.
- **A secret is a `Secret`.** `#secret` in the configuration file wraps a value in a type that prints as `#secret[REDACTED]` (`str`, `pr-str` and Integrant's dump of a failed component alike) and is opened only where it is used. A missing secret is reported in words, with the name of the variable and never a value.
- **Readiness asks for it.** `/readyz` of the scheduler adds `token`: whether a token can be had now, from the cache or from Keycloak. A wrong secret then shows as unready and not as jobs that fail.

## Alternatives considered

- **A thread that renews on a timer:** it renews when nobody needs a token, it has to be started and stopped, and a thread that died is a silent outage that shows as expired tokens.
- **Renewing when the resource server answers 401:** the first call after every expiry fails and every call site has to retry.
- **A token for every call:** simple and wasteful, and it makes Keycloak a dependency of each run.
- **An OAuth client library** (Nimbus's SDK, ring-oauth2, Buddy): the SDK is much larger than the one POST of this grant, and the other two are for user flows.
- **The secret in the request body:** works with Keycloak, but the RFC does not recommend it, and a body is more likely to reach a log than a header.
- **Forwarding or exchanging user tokens:** ADR-0020.

## Consequences

- Keycloak is asked about once every four minutes by each service, whatever its load.
- The first call after a long idle period (or after a restart: the token is only in memory) waits for one request of a few milliseconds. Calls in the early window never wait.
- Validity is judged by this process's clock on a *duration*, `expires_in`, so a skew between machines does not change our decision; the resource server applies its own leeway to its own check.
- The scheduler's secret is the one in `.env` (`SCHEDULER_CLIENT_SECRET`), which Keycloak imported with the realm: rotating it means `bb reset` locally.
- Anything that reads this configuration (the logs, a REPL, Integrant's errors) sees `#secret[REDACTED]`, and a test checks that.
