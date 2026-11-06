# Technical documentation

> For an engineer. Start with the architecture, then the area you care about; the reasons behind each choice are in [decisions.md](decisions.md) and the [ADRs](../adr/). The functional view is in [../functional/overview.md](../functional/overview.md); the overall index is [../index.md](../index.md).

| Page | Covers |
|---|---|
| [architecture.md](architecture.md) | Components, ports, the four OAuth flows, data flows, trust boundaries, request pipelines |
| [identity-and-tokens.md](identity-and-tokens.md) | Keycloak realm as data, clients and roles, token verification, service tokens, the portal's and the workspace's sign-in |
| [fhir-data-model.md](fhir-data-model.md) | Resources, maps not classes, ids and identifiers, the measurement catalogue, lifecycles, the `FhirClient` protocol, the synthetic cohort |
| [api.md](api.md) | The gateway: pipeline, errors, authorization by scope, endpoints, generated OpenAPI |
| [scheduler.md](scheduler.md) | Pure schedules, the executor, the rules engine, the simulator pipeline, reminders |
| [web.md](web.md) | The patient portal as a server-side client: sessions, CSRF, CSP, pages as pure functions |
| [spa.md](spa.md) | The clinical workspace: re-frame layers, the `:http` effect, routing, the page machines, the release and its policy |
| [design-system.md](design-system.md) | Tokens, contrast by test, shared components, `libs/view`, generated mock-ups, the Keycloak theme |
| [testing-strategy.md](testing-strategy.md) | Runners, fakes and contract tests, properties, accessibility, browser journeys, mutation analysis |
| [build-and-tooling.md](build-and-tooling.md) | Monorepo layout, Babashka tasks, configuration, local stack, the images and the compose profile `apps`, CI |
| [decisions.md](decisions.md) | Context, choice, alternatives and consequences of each ADR, and the gotchas |

**State:** All ten phases are built (Phase 9: the images, the compose profile `apps`, `bb smoke` and the integration workflow; Phase 10: the README, the check of the notices, the scan for secrets and the release `v0.1.0` of 2026-11-05) ([../02-implementation-plan.md](../02-implementation-plan.md)).
