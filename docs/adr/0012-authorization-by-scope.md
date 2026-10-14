# ADR-0012: Authorization by scope: the Annex A matrix as data

- **Status:** accepted
- **Date:** 2026-10-14

## Context

ADR-0004 fixed the principles: authorization lives in route data, denies by default and is decided by a pure function `authorize`. Annex A of the plan holds the access matrix: a patient sees their own record, a practitioner the patients on their panel, the administrator everything, the rules engine a few specific things. Still open: how to express "own", "their panel" and "all"; where the facts about a resource come from (a patient's practitioners are in FHIR) while `authorize` stays pure; what to answer when the resource does not exist or is out of reach; and what to audit.

## Decision

- **The matrix is data, in scopes.** `pulse.api.authz/rules` maps each action to the scope each role has: `:own` (records of the caller's own `fhirUser`), `:panel` (records of patients whose `generalPractitioner` includes the caller), `:all`, or `:identifiers` (every patient, identifiers only; the rules engine lists patients this way). A role that is not listed has no access, and the key `:any` stands for every authenticated caller. A caller with several roles gets the widest scope. A scope that is about the caller's own FHIR resource only counts if they have one of the right type, so a practitioner whose token has no `fhirUser` has no panel rather than every panel.
- **`authorize` is pure:** `(authorize caller action resource)` returns `{:allow? true :scope s}` or `{:allow? false :reason r}`. With no resource it checks the role only and returns the scope, which collection endpoints use to restrict their own query (a panel becomes `general-practitioner=<me>`); nothing fetches everything and filters afterwards. With a resource descriptor, `{:patient "Patient/pt-ana" :practitioners #{"Practitioner/pr-rivera"}}`, it also checks that the resource lies within the scope.
- **The route builds the descriptor.** An endpoint on a single resource declares `:authz {:action a :resource (fn [request scope] ...)}`. The function reads FHIR only when the scope needs it (a panel needs the patient's practitioners; `:own` and `:all` are decided from the id in the path) and returns nil when there is no such resource.
- **403 or 404.** No scope for the action, or the resource outside it: 403 with a generic text; the reason goes to the log. No such resource: 404. A patient asking for someone else's record is refused from the ids alone, before any lookup, so a patient cannot probe which records exist; staff get 404 for a missing patient and 403 for one outside their panel. An id that cannot be a FHIR id cannot exist: 404 with no lookup, which also keeps odd characters out of the URLs sent to FHIR.
- **Every decision on clinical data is audited.** Each allow, deny and miss on an action other than `:identity/read` raises `:pulse.audit/access`: who (subject, username, roles, client), what (action, patient reference, scope), the outcome and the reason, and the request id (from the log context). Nothing from the record itself is logged. Denials and misses are warnings.

## Alternatives considered

- **Filtering after fetching:** works until one handler forgets, and reads data the caller may not see.
- **Reading the resource for every request** to build the descriptor: an extra FHIR read for administrators and patients that no decision needs.
- **A policy engine (OPA, Cedar):** rejected in ADR-0004.

## Consequences

- Annex A exists twice: as `rules` in code and as a table in the tests, transcribed independently, so an edit to one shows up against the other.
- Adding a row is adding a map entry; adding an endpoint is picking its action and, for a single resource, its descriptor function.
- A practitioner's request on a patient costs one extra Patient read (cacheable later).
- The matrix says the simulator may write observations "in `dev` only". The api cannot tell a simulator from the rules engine, so that restriction belongs to the scheduler's profile, not to the api.
