# ADR-0003: Configuration with Aero, lifecycle with Integrant

- **Status:** accepted
- **Date:** 2026-10-13

## Context

The original proof of concept hard-coded URLs, ports and secrets, loaded data relative to the working directory, and fetched the identity provider key lazily in a `delay` that cached failures.

## Decision

- Configuration is data: an EDN file per app read with **Aero** (`#env`, `#or`, `#profile`, `#ref`), then validated with a malli schema at start-up so a bad configuration fails fast and loudly.
- Components (HTTP server, JWKS cache, FHIR client, scheduler executor) are started and stopped by **Integrant**, with `integrant.repl` for the `go` / `reset` / `halt` REPL workflow.
- Secrets only come from the environment (`.env`, never committed).

## Alternatives considered

- **In-house EDN loader (about 25 lines):** the fallback. Aero has had no release since 2020, but it is stable and dependency-free, so the risk is low and the decision is reversible behind the config namespace.
- **Mount / Component:** Integrant keeps the system definition as plain data.
- **Environment variables read directly:** no validation, no profiles.

## Consequences

- Every external URL (Keycloak issuer vs back-channel JWKS URI, FHIR base URL) is a config key, which is what makes the Docker profile possible.
- Apps can be started from any directory.
