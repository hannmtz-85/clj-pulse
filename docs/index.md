# Pulse documentation

Remote patient monitoring and appointment coordination on FHIR R4, with Keycloak single sign-on. All data is synthetic; this is not a medical device. **Phases 0 to 8 are done; Phase 9 (packaging, integration and end-to-end tests) and Phase 10 (polish and publication) are not built yet** ([02-implementation-plan.md](02-implementation-plan.md)).

## Functional (for a non-technical reader)

- [Overview](functional/overview.md): intent, problem, scope, what is out of scope, who the users are.
- [Patient portal (`web`)](functional/web.md)
- [Clinical workspace (`spa`)](functional/spa.md)
- [Clinical gateway (`api`)](functional/api.md)
- [Scheduler (`scheduler`)](functional/scheduler.md)
- [Roles and permissions](functional/roles-and-permissions.md)
- [Clinical rules and reminders](functional/clinical-rules.md)

## Technical (for an engineer)

- [Technical index](technical/index.md)
- [Architecture](technical/architecture.md): components, data flow, trust boundaries (Mermaid diagrams).
- [Identity and tokens](technical/identity-and-tokens.md)
- [FHIR data model](technical/fhir-data-model.md)
- [api](technical/api.md) · [scheduler](technical/scheduler.md) · [web](technical/web.md) · [spa](technical/spa.md)
- [Design system](technical/design-system.md)
- [Testing strategy](technical/testing-strategy.md)
- [Build and tooling](technical/build-and-tooling.md)
- [Decisions](technical/decisions.md): every significant decision with its ADR, and the gotchas learned the hard way.

## Project records

- [Implementation plan](02-implementation-plan.md): concept, architecture, phases with checklists and acceptance.
- [Stack review](01-stack-review.md): what was kept, upgraded and replaced, and why.
- [Extras backlog](03-extras-backlog.md): everything deliberately deferred or rejected.
- [Architecture decision records](adr/): ADR-0001 to ADR-0029.
- [CLAUDE.md](../CLAUDE.md): detailed working notes on each part of the code.
