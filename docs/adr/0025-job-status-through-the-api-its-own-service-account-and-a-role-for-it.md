# ADR-0025: The status of the jobs through the api: its own service account, and a role for it

- **Status:** accepted (the design was validated point by point by the project owner on 2026-10-16; that the api uses a token of its own had been agreed on 2026-10-15; to be implemented in step 7 of Phase 5)
- **Date:** 2026-10-16

## Context

Annex B has `GET /api/admin/jobs`: the clinic administrator sees what the scheduler is doing. It was left out of Phase 4 because it proxies a service that did not exist; the scheduler now has its interface (ADR-0020), a token of its own (ADR-0021) and three jobs. What is left is the call from the api to the scheduler, and four things have to be decided:

- **What credentials the api presents.** The scheduler accepts only tokens whose audience is `pulse-scheduler` (ADR-0020), so the token of the administrator, which is for the api, is refused there.
- **How the scheduler can tell that the caller is the api**, without the api becoming an administrator.
- **What the endpoint answers**, and how it fails when the scheduler is down, slow or wrong.
- **How an existing stack picks the change up:** it needs a new client in the realm and a new secret.

## Decision

**The api is a client of the realm.** `pulse-api` is a confidential client with a service account, the client-credentials grant and no other, as `pulse-scheduler` is; its secret comes from `API_CLIENT_SECRET`. The api asks for its token with the keeper of ADR-0021 and sends that token, never the caller's, in each call to the scheduler. The token is for `pulse-scheduler`: the client has an audience mapper for that and **not** the one every other client has for `pulse-api`, so the token of the api's service account is not one that the api itself would accept.

**A role for it: `api-service`**, "service role of the api: reads the status of the scheduler's jobs". The service account has that role and no other role of the application. The scheduler's rules become `:jobs/read` for `clinic-admin` and `api-service`, and `:jobs/run` for `clinic-admin` alone: through the api the status can be read and nothing can be started, which is all Annex B asks. The role of the administrator is the api's to check, with the rule `:jobs/read` it already has; the scheduler checks who is calling it. Both services keep a list of the roles of the realm, and the tests that compare it with the committed realm now include the new one.

**The endpoint.** `GET /api/admin/jobs`, for `clinic-admin`, answers `{total, items}`; each item is a job as the scheduler shows it (`id`, `description`, `schedule`, `state`, `runningSince`, `nextRun`, `lastRun` and `counts`), described in the OpenAPI document. The schema is the api's own, and what the scheduler answers is checked against it before it goes on: an answer that does not fit is a 502, not an error of the api. The api forwards its request id to the scheduler in `X-Request-Id` (the platform client learns to send headers), so a request has one id in both logs and both audit trails.

**Failures are not the caller's.** A 502 when the scheduler cannot be reached, refuses the api's token, fails or answers something that is not what it should, and when the api cannot get its own token; a 504 when the scheduler is too slow. The text is generic and the cause goes to the log, as it does for FHIR (`pulse.api.fhir`). The readiness of the api (`/readyz`) does not ask the scheduler: the api works without it.

**Configuration.** `:pulse.api/token` (client `pulse-api`, the secret a `#secret` from `API_CLIENT_SECRET`, a fixed one in the test profile) and `:pulse.api/scheduler` (`SCHEDULER_BASE_URL`, `http://localhost:5000` by default; 5 seconds to answer and 2 to connect), which the handler of the api is given. As for the scheduler's, the `dev` and `lite` profiles cannot start without the variable, and say which it is.

**An existing stack.** `compose.yaml` passes the secret to Keycloak, which resolves the placeholder of the realm when it imports it, and `.env.example` has the variable. `bb env:init` used to leave an existing `.env` alone, so a new variable would have made `bb up` fail with "run bb env:init first" while `bb env:init` said that the file was fine: it now adds the variables that `.env` lacks, with random secrets, and changes none that it has. Keycloak skips the import when the realm exists, so the new client and role are loaded by `bb reset`, which destroys the data of the stack and is asked of the owner first. `bb token api` prints the service account's token and `bb realm:smoke` checks its claims: the role, the audience `pulse-scheduler` and not `pulse-api`, and the client.

## Alternatives considered

- **Forwarding the administrator's token:** the scheduler would have to accept the audience of the api, and then the audience would protect nothing, since any token that opens the api would open the scheduler. Each service's authorization stays its own.
- **Token exchange (RFC 8693)**, a token for the scheduler on behalf of the administrator: in Keycloak 26.0.0, which the project keeps, it is a preview feature that has to be switched on when the server starts; the supported one arrived with 26.2 ([announcement](https://www.keycloak.org/2025/05/standard-token-exchange-kc-26-2)). It would also give the scheduler the identity of the administrator, which it does not need to read a list.
- **Giving the service account the role `clinic-admin`:** nothing to change in the scheduler, but the service account would be an administrator wherever that role counts, and the api counts it for every record.
- **Authorizing by client (`azp`) in the scheduler:** a second mechanism next to the roles that the rest of the project uses.
- **Passing the scheduler's JSON through:** less code, and an api whose document says nothing of what it returns.
- **Asking the scheduler in `/readyz`:** an api taken out of rotation because an administrative extra is down.
- **Applying the realm with a partial import through the admin API, to keep the data:** more tooling for a change that happens a few times; `bb reset` takes two minutes and seeds the cohort again (extra X-75).
- **Authenticating the client without a secret (a signed JWT, mutual TLS):** stronger, and not what the scheduler does either; this is a local realm.

## Consequences

- The api has a second service to depend on, and a request to it can hold a thread for as long as its timeout.
- Two schemas describe one view, the scheduler's and the api's; `pulse.dev-test` runs the two services together, so if they drift apart it fails there.
- `bb dev` needs `API_CLIENT_SECRET`, which a `.env` made before this step gets from `bb env:init`, and the realm needs one `bb reset`.
- Starting a job through the api is not possible. It would be one more action for `api-service` and a `POST` (extra X-74).
- With this step the scheduler's phase is closed.
