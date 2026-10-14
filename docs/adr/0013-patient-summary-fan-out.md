# ADR-0013: The patient summary: concurrent fan-out under one deadline, and honest partial answers

- **Status:** accepted
- **Date:** 2026-10-14

## Context

`GET /api/patients/{id}/summary` joins about a dozen FHIR queries: the patient, conditions, active medication, allergies, the latest reading of each vital sign (five), appointments and active alerts. Run one after another their latencies add up, and one slow query holds the whole page. One failing query should not hide the others, but a summary that silently lacks the allergies looks exactly like the summary of a patient with none, which in a clinical screen is worse than an error.

## Decision

- **Fan-out.** All queries start together, each on its own virtual thread (JDK 21), and the response waits for them under **one deadline for the lot** (default 4 s, configurable). `pulse.platform.fanout/run` does it, takes a map of named tasks and returns a map of outcomes (`:ok`, `:error` or `:timeout`). Tasks still running at the deadline are cancelled, which interrupts their HTTP requests, and the response does not wait for them. The dynamic bindings, and with them the request id of the log context, are carried to the other threads.
- **The patient is required, the rest is not.** No such patient is a 404; a FHIR server that cannot answer is a 502, and one that is too slow a 504. Any other section that fails or misses the deadline becomes `null`, its name goes into the `unavailable` list, and the status stays 200. A section that is `[]` (or a vital sign that is `null` and not listed) is empty, not missing: the client can tell "nothing to show" from "could not be loaded", and must show the list prominently. A degraded summary is logged as `:pulse.api/summary-degraded`, with the reason of each failure and never its message.
- **Timeouts at two levels.** The FHIR HTTP client abandons a request after 10 s and a connection after 3 s (both configurable), so that no endpoint can hang on a stuck server. The fan-out deadline is the tighter one that governs the summary.
- **The views are display-ready.** Not raw FHIR: conditions, medication, allergies, appointments and alerts as small maps; each vital sign as `{value, unit, at, band}`, where the band is the didactic reference band of `pulse.fhir.ranges` (a blood pressure takes the worse of its two components).
- **Latest readings are queries of one.** `_sort=-date&_count=1` per vital sign, taking only the first element of the lazy search so that no further page is fetched.
- **Alerts are the newest fifty, filtered.** R4 gives `Flag` no `status` search parameter (HAPI answers HAPI-0524; the in-memory fake had invented one, which is how it was found). The query asks for the patient's newest flags by `date`, at most fifty, and the active ones are kept afterwards: a patient's active alerts are among their newest.

## Alternatives considered

- **Sequential queries:** latency is the sum, and the slowest one decides everything.
- **`Patient/$everything`:** one call, but it returns the whole record, unbounded, and the in-memory fake would have to implement it.
- **A FHIR batch Bundle of searches:** one round trip, but the server runs the entries one after another, there is no deadline per query, and the fake would need batch support.
- **All or nothing** (any failure fails the request): simpler and safe, but one slow alert query would blank a chart that is otherwise complete.

## Consequences

- Latency is that of the slowest section, capped by the deadline.
- One summary is about a dozen concurrent requests to HAPI; they are small searches, but it is the first endpoint where that matters, and the limit on what the api sends to HAPI is the deadline.
- The contract with the clients includes `unavailable`; the SPA and the portal have to honour it.
- Fan-out is a library function, not specific to the summary: the scheduler can use it too.
