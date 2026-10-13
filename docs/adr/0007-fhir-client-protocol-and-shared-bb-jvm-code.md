# ADR-0007: A FhirClient protocol with HTTP and in-memory implementations, shared by Babashka and the JVM

- **Status:** accepted
- **Date:** 2026-10-13

## Context

Four things talk to FHIR: `api`, `scheduler`, the `bb seed` task and the tests. They should not care whether the server is HAPI, a different FHIR server or a fake. The `api` also has to run on a machine without enough RAM for HAPI (the "lite" profile). The seeding task is a Babashka script, and we want it to reuse the library code of the apps instead of re-implementing it.

## Decision

- `pulse.fhir.client/FhirClient` is a protocol (`read-resource`, `search`, `put!`, `delete!`, `transact!`, `validate`) with two implementations: `pulse.fhir.client.http` for a real server and `pulse.fhir.client.memory`, a small in-memory FHIR server that validates with the malli schemas, executes transactions atomically and answers searches with filters, sorting and `next`-link paging.
- The HTTP client uses `babashka.http-client` and `clojure.data.json`, which run unchanged on the JVM and in Babashka. `jsonista` (fast, but Jackson-based and so unavailable in Babashka) stays an `api`-server concern, behind muuntaja.
- Searches return a lazy sequence built with `clojure.core/iteration`; each page is fetched only when the previous one is consumed. Flattening is hand-written because `sequence`+`cat` is chunked and `mapcat` spreads its first arguments with `apply`, both of which fetch pages nobody asked for.
- The in-memory client is kept honest by an integration test (`bb test:it`) that runs the same queries against HAPI and against the fake and requires identical answers.
- `bb seed` and `bb seed:validate` are Babashka tasks that load `pulse.fhir.*` from `libs/fhir`.

## Alternatives considered

- **Mocking the HTTP layer in tests:** tests would encode our assumptions about HAPI instead of the behaviour of a server.
- **A different HTTP/JSON pair per runtime (hato + jsonista on the JVM, something else in bb):** two code paths to keep identical.
- **`pmap` for `$validate`:** tried; ten concurrent validations ran HAPI (768 MB heap) out of memory, so validation is sequential.

## Consequences

- Application code depends on the protocol only; swapping HAPI for another server (extra X-16) means a new test run, not new code.
- The fake supports only the search parameters listed in `search-params` and rejects the rest, like a real server; adding one is one map entry.
- HAPI's search cache means a client must send `Cache-Control: no-cache` to see its own writes; `http/client` does by default.
