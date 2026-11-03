# The clinical gateway (`api`)

> What the api is for and what it does, without code. Technical detail: [../technical/api.md](../technical/api.md).

## Purpose and users

The api is the one door to the clinical data. The patient portal, the clinical workspace and the scheduler all ask it, never the FHIR server directly for reading records ([ADR-0002](../adr/0002-fhir-r4-on-hapi.md): "the `api` app is the only policy enforcement point"). Its job ([02-implementation-plan.md](../02-implementation-plan.md) §2):

- Check that the caller signed in and who they are.
- Decide what they may see or do ([roles-and-permissions.md](roles-and-permissions.md)).
- Read and write the FHIR server on their behalf, turning FHIR's records into small, readable answers.
- Record an audit event for every decision on clinical data.

A person never uses it directly, except developers through its documentation page.

## What it offers

From Annex B of the plan and `apps/api/src/pulse/api/routes.clj`:

| What | Address | Who |
|---|---|---|
| Is it up? Is it ready? | `GET /healthz`, `GET /readyz` | Anyone |
| Who am I (and, for a patient, my doctor's name and specialty) | `GET /api/me` | Anyone signed in |
| The panel of patients, with filter, sorting and paging | `GET /api/patients` | Practitioner (their panel), administrator (all), scheduler (identifiers) |
| A patient's details | `GET /api/patients/{id}` | Own, their panel, all |
| A patient's summary: conditions, medication, allergies, latest readings, appointments, active alerts | `GET /api/patients/{id}/summary` | Same |
| A series of one measurement over a period | `GET /api/patients/{id}/observations` | Same, plus the scheduler |
| Record a reading | `POST /api/patients/{id}/observations` | Patient (own), practitioner (their panel), scheduler |
| List appointments / ask for one | `GET`, `POST /api/appointments` | By role; only a patient asks |
| Confirm, reschedule or cancel an appointment | `PATCH /api/appointments/{id}` | Clinicians; a patient may only cancel their own |
| The inbox of alerts | `GET /api/alerts` | Practitioner (their panel), administrator |
| Acknowledge an alert | `POST /api/alerts/{id}/ack` | Same |
| The status of the scheduler's jobs | `GET /api/admin/jobs` | Administrator |
| The documentation, with a "try it" sign-in | `/docs/` (Swagger UI), `/openapi.json` | Anyone |

## The flows, step by step

**Trying the api from the documentation page** ([ADR-0017](../adr/0017-api-documentation-openapi-and-swagger-ui.md)):

1. Open <http://localhost:4000/docs/>.
2. Press **Authorize**: a Keycloak window opens; sign in as a demo user.
3. Try an operation. The same request answers differently by role: as Dr. Rivera, `GET /api/patients` gives her four patients; as the administrator, all six; as Ana, a refusal (403).

**Recording a reading** ([ADR-0014](../adr/0014-recording-and-reading-observations.md)): the caller sends the measurement name, the value(s), optionally the unit and the time. The api converts the unit to the one it stores, rounds it, checks it is believable, and decides itself whose reading it is (the patient in the address), who took it (the caller) and its status. The answer is the reading as stored, with its band.

**Asking for an appointment** ([ADR-0015](../adr/0015-appointments-lifecycle-and-panel.md)): the patient sends a reason and a window (at most 30 days long, starting within 180 days). The request always goes to the patient's own doctor; a patient with no doctor is refused.

**Changing an appointment**: a clinician confirms a request with a time (5 minutes to 4 hours long, not in the past, at most a year ahead), reschedules a booked one, or cancels; a patient may only cancel their own, until it begins ([ADR-0027](../adr/0027-a-patient-cancels-their-own-appointment-the-lifecycle-says-who-and-me-names-the-doctor.md)).

**Acknowledging an alert** ([ADR-0016](../adr/0016-alerts-inbox-and-acknowledgement.md)): the alert becomes inactive; if it was no longer active, the caller is told (409) instead of it being silently accepted.

## What it answers in each state

| State | Answer |
|---|---|
| Success | The data (200), or the new record (201) |
| Not signed in, or the sign-in is not valid | 401, with a header saying why |
| The sign-in service's keys are not available | 503 (so that clients do not loop asking people to sign in again) ([ADR-0009](../adr/0009-access-token-verification-with-nimbus-facade.md)) |
| Not allowed | 403 with a generic text |
| No such record | 404 |
| The request is malformed | 400, with messages per field |
| The request is well-formed but cannot be right (an impossible reading, an end before a start) | 422, with messages per field |
| The status does not allow the action (confirming a cancelled appointment, acknowledging an inactive alert) | 409, with a message naming the status |
| The FHIR server or the scheduler failed / was too slow | 502 / 504, with a generic text; the cause is in the log |

Every error has one shape (RFC 9457 "problem details") and carries a **request id** that also appears in the logs ([ADR-0011](../adr/0011-api-request-pipeline-and-errors.md)).

**A partial summary is still an answer.** If one part of a patient's summary (for example the allergies) cannot be loaded in time, the rest is returned and the missing part is **named** as unavailable, so that a screen can say "could not be loaded" instead of showing "no allergies" ([ADR-0013](../adr/0013-patient-summary-fan-out.md)).

## Limits

From ADR-0014, ADR-0015, ADR-0016 and the backlog:

- A reading recorded twice (a retry) is stored twice, and cannot be corrected (X-25, X-26).
- Two clinicians changing the same appointment at once: the last write wins (X-28). Overlapping bookings of one doctor are not detected (X-29).
- Series give at most 500 readings per request; lists at most 100 items per page.
- At most 1,000 alerts per scope are looked at (ADR-0016).
- A sign-in stays usable up to 5 minutes after signing out, because tokens are checked without asking Keycloak each time (ADR-0028 "Consequences"; X-08).
- Starting a scheduler job is not possible through the api (X-74).
- No rate limiting or body size limit (X-05).
