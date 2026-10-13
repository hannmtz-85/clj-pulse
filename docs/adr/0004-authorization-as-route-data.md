# ADR-0004: Authorization as route data, deny by default

- **Status:** accepted
- **Date:** 2026-10-13

## Context

In the original proof of concept, access rules lived in a table separate from the routes and ended with an "any authenticated user" rule, so a new route was silently open to everyone with a token. Invalid tokens also surfaced as HTTP 500.

## Decision

Each Reitit route declares what it needs in its own data (`:authz`, for example required roles and a resource-ownership check). One middleware reads the matched route data and calls a pure function `authorize` (identity, action, resource) returning an allow/deny decision with a reason. A route with no `:authz` is rejected, so the default is deny. Token problems return 401 with a `WWW-Authenticate` header; authorization failures return 403.

Patient access follows the FHIR patient compartment: a patient reads only resources whose subject is their own `fhirUser`; a practitioner reads patients whose `generalPractitioner` is them; `clinic-admin` reads all.

## Alternatives considered

- **Separate rule table (as before):** easy to forget an entry, fails open.
- **A policy engine (OPA, Cedar):** one more moving part for a demo.

## Consequences

- Authorization is testable without HTTP: a decision table of identity x action x resource.
- Routes, schemas and permissions are visible together, and show up in the generated OpenAPI.
