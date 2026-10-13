# ADR-0002: FHIR R4 on HAPI FHIR with deterministic IDs

- **Status:** accepted
- **Date:** 2026-10-13

## Context

The domain moves to medicine. We need a FHIR server that supports CRUD, search, transactions and validation, and that is recognised in the industry. Blaze (a Clojure FHIR server) was evaluated but is aimed at analytics and research.

## Decision

Use the HAPI FHIR JPA Starter (image tag pinned, PostgreSQL storage) serving FHIR R4. Resources are created with `PUT` and fixed client-assigned IDs (`pt-ana`, `pr-rivera`, ...), so the Keycloak realm can refer to them in the `fhirUser` claim with no manual step. HAPI is only reachable from the host on `127.0.0.1`; the `api` app is the only policy enforcement point.

## Alternatives considered

- **FHIR R5 as the base version (evaluated 2026-10-13, rejected):** R5 is the latest published release (2023-03-26) but is not backward compatible with R4 and has little production adoption. US Core 9.0.0 (HL7) and the International Patient Summary are R4; per Health Samurai, Epic and Oracle Health do not support R5; R6 started its first normative ballot in January 2026 and will not be final before 2027, so the likely migration path is R4 → R6. HAPI supports R5, so switching stays possible. R5 compatibility is kept as extra X-18.
- **Blaze:** kept as an optional compose profile in the extras backlog.
- **Aidbox:** licence friction for anyone cloning the repository.
- **FHIR facade over our own store:** much more work, little to show.

## Consequences

- Seed data is reproducible and idempotent.
- HAPI is heavy (about 1 GB of RAM); a "lite" profile with an in-memory fake `FhirClient` is needed for small machines.
- Terminologies are LOINC, ICD-10-CM, RxNorm and UCUM; SNOMED CT is avoided for licensing reasons.
