# ADR-0016: Alerts: the inbox of a panel, acknowledging, and what R4 cannot filter

- **Status:** accepted
- **Date:** 2026-10-14

## Context

`GET /api/alerts` and `POST /api/alerts/{id}/ack` are the last clinical endpoints of Annex B. An alert is a FHIR `Flag` that the rules engine raises (Phase 5) by writing to FHIR itself; the api only lists and acknowledges. Four things were open. What does "their panel" mean for a list of flags, and how is it asked of FHIR without fetching everyone's? What does acknowledging change in the Flag? What does the api answer for an alert that is no longer active? And how are the active alerts found when R4 gives Flag no `status` search parameter (HAPI answers HAPI-0524)?

## Decision

- **An alert is acknowledged by becoming inactive.** `pulse.fhir.alert` (portable) has `active?` and `acknowledge`, which turns an active Flag into an `inactive` one whose period ends at the time of the acknowledgement (never before it began) and answers `{:conflict message}` for a Flag that is not active. Nothing else changes. Who acknowledged is in the audit event, and a persisted record of it (a Provenance) is extra X-06. Raising alerts is for the rules engine, not for the api.
- **The panel of an alert is the panel of its patient** (Annex A, as for observations): the patients whose general practitioner is the caller. A practitioner's list is two queries: the panel, which is the query behind `/api/patients`, and then the flags of those patients in one query with an OR list of patient references, in chunks of 50 to keep the URL short. An empty panel asks nothing, since an empty filter would mean everyone. The administrator's list is one query for every flag. Nothing outside the scope is fetched.
- **Active is filtered afterwards.** R4 has no `status` search parameter for Flag, so the flags of the scope are fetched newest first (at most 1000: more than that is a mistake to find out about) and `status` is applied here: `active` by default, or `inactive`. An `entered-in-error` flag is never listed. This is a filter on what the scope already limited and not a way around the scope. An alert is shown as the summary shows it, plus `patient` and `until` (when it was acknowledged); the list is newest first by default (`sort=-since`, or `since`), with `limit` (50, at most 100), `offset` and `total` as the other lists.
- **Acknowledging**, `POST /api/alerts/{id}/ack`, answers 200 with the alert, or 409 when it is not active: an alert that a colleague has just acknowledged is reported and not silently accepted. The resolver reads the Flag and, for a practitioner, the patient. Its descriptor carries the Flag, so the handler works on the version the decision was about (ADR-0015). A Flag whose subject is not a patient does not exist for this endpoint.
- **Audit:** `:pulse.audit/write` with `:updated` and `:change :acknowledge`, the reference of the Flag and the patient's.
- **The in-memory client learns OR on reference parameters** (`patient=Patient/a,Patient/b`), as HAPI has it, with a contract test against HAPI.

## Alternatives considered

- **A chained search** (`Flag?patient.general-practitioner=...`): one query, but the in-memory client would have to learn chaining, and the OR list does the same with what it already knows.
- **Fetching the newest flags of everyone and keeping the panel's:** the very thing ADR-0012 forbids, and wrong besides, because the alerts of other panels could fill the window.
- **A custom search parameter `Flag.status` in HAPI:** it works on HAPI and nowhere else, and it is configuration that the in-memory client and a fresh machine would need as well.
- **One query per patient, in parallel:** a panel of fifty is fifty queries.
- **Acknowledging idempotently** (200 for an alert that is already inactive): kinder to a retry, but it hides that someone else got there first.

## Consequences

- A practitioner's list costs two queries, and a large panel one more for every 50 patients.
- With more than 1000 flags in a scope the oldest are not seen. An active clinic would need the status filter on the server (a custom search parameter) before that.
- An acknowledged alert does not come back by itself. Whether a condition that persists raises a new alert is for the rules engine to decide (Phase 5).
- There is no patient filter on the list and no `GET /api/alerts/{id}`: the patient's summary already lists that patient's active alerts.
