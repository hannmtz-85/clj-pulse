# Roles and permissions

> Who may do what in Pulse, in plain words. The rules are written once in the code, `pulse.api.authz/rules` (`apps/api/src/pulse/api/authz.clj`), and checked again by a table in the tests that was written separately ([ADR-0012](../adr/0012-authorization-by-scope.md)). The original matrix is Annex A of [02-implementation-plan.md](../02-implementation-plan.md).

## The roles

Five roles exist in the realm `pulse` (`tools/realm.clj`, `roles`):

| Role | Who has it | Described in the realm as |
|---|---|---|
| `patient` | The six demo patients | "A patient using the portal; sees only their own record" |
| `practitioner` | Dr. Rivera and Dr. Okafor | "A clinician; sees the patients on their panel" |
| `clinic-admin` | Sam Admin | "Clinic administrator; sees every patient and operational status" |
| `rules-engine` | The scheduler's service account (a program) | "Service role of the scheduler: reads observations and raises alerts" |
| `api-service` | The api's own service account (a program) | "Service role of the api: reads the status of the scheduler's jobs" |

A person's account also says which clinical record is theirs (`fhirUser`: `Patient/pt-ana` for Ana, `Practitioner/pr-rivera` for Dr. Rivera). That link is how "own" and "panel" are decided.

## Scopes: "own", "their panel", "all"

Every permission is a **scope** rather than a yes or no ([ADR-0012](../adr/0012-authorization-by-scope.md)):

- **Own**: records of the person's own patient record. Only works if the person's account is linked to a patient record.
- **Panel**: records of the patients whose general practitioner is this clinician. Only works if the account is linked to a practitioner record.
- **All**: every record.
- **Identifiers**: every patient, but only who they are, not their record (used by the rules engine).

Anything not listed is refused. A new screen or endpoint that forgets to say who may use it stops the application from starting ("deny by default", [ADR-0004](../adr/0004-authorization-as-route-data.md), [ADR-0011](../adr/0011-api-request-pipeline-and-errors.md)).

## What each role may do

From `pulse.api.authz/rules`:

| Action | Patient | Practitioner | Administrator | Rules engine (scheduler) |
|---|---|---|---|---|
| Say who I am (`GET /api/me`) | Yes | Yes | Yes | Yes |
| List patients | No | Their panel | All | Identifiers only |
| Read a patient's record and summary | Own | Their panel | All | No |
| Read readings (observations) | Own | Their panel | All | All |
| Record a reading | Own | Their panel | **No** | All |
| See appointments | Own | Their panel | All | No |
| Ask for an appointment | Own | No | No | No |
| Change an appointment (see below) | Own (cancel only) | Their panel | All | No |
| See alerts | No | Their panel | All | No |
| Acknowledge an alert | No | Their panel | All | No |
| See the status of the scheduler's jobs | No | No | Yes | No |

**What a person may do to an appointment** is decided by the appointment lifecycle, not by the table above ([ADR-0027](../adr/0027-a-patient-cancels-their-own-appointment-the-lifecycle-says-who-and-me-names-the-doctor.md); `libs/fhir/src/pulse/fhir/appointment.cljc`):

- A **clinician** (practitioner or administrator) may confirm a request with a time, reschedule a booked appointment, and cancel a request or a booked appointment at any time.
- A **patient** may only cancel: a request at any time, and a booked appointment **until it begins**. After it has begun, only the clinic can cancel it ("It has already begun, so only the clinic can cancel it.").

For an appointment, "their panel" means the appointments the practitioner takes part in ([ADR-0015](../adr/0015-appointments-lifecycle-and-panel.md)).

## The service accounts

- **The scheduler** (`pulse-scheduler`, role `rules-engine`) lists patients by identifier and reads their readings through the api, to run the alert rules. It writes alerts, reminders and simulated readings straight into the FHIR server, not through the api ([ADR-0022](../adr/0022-rules-engine-rules-as-data-alerts-raised-once.md), [ADR-0023](../adr/0023-device-simulator-core-async-pipeline-to-a-fhir-transaction.md), [ADR-0024](../adr/0024-appointment-reminders-a-communication-sent-once-from-what-fhir-holds.md)).
- **The api** (`pulse-api`, role `api-service`) has its own account only to ask the scheduler for the status of its jobs on behalf of the administrator. The scheduler lets this role **read** the jobs and not start them; starting a job is for `clinic-admin` only, and the api offers no way to do it ([ADR-0025](../adr/0025-job-status-through-the-api-its-own-service-account-and-a-role-for-it.md); `apps/scheduler/src/pulse/scheduler/authz.clj`).

## Which application each person can use

| Application | Who is let in | What others see |
|---|---|---|
| Patient portal (`web`) | `patient` only | "This portal is for patients", with a way to sign out ([ADR-0028](../adr/0028-the-patient-portal-a-server-side-client-of-the-api-with-its-own-sign-in-and-a-token-keeper-for-each-session.md) point 4) |
| Clinical workspace (`spa`) | `practitioner` and `clinic-admin` | "This workspace is for the clinic", with a link to the patient portal (`apps/spa/src/pulse/spa/pages/start.cljc`) |
| The jobs page of the workspace | `clinic-admin` only | A practitioner who types the address is told "You cannot open that page" and nothing is asked of the api (`apps/spa/src/pulse/spa/route.cljc`) |

These gates are for the person's convenience. The real decision is always the api's: even if a screen let someone through, the api would refuse data outside their scope (ADR-0028, ADR-0029 point 1).

## What happens when access is refused

From [ADR-0012](../adr/0012-authorization-by-scope.md):

- No permission for the action, or a record outside the person's scope: **refused** (HTTP 403) with a generic message.
- A record that does not exist: **not found** (HTTP 404). A patient asking for someone else's record is refused from the ids alone, before anything is looked up, so a patient cannot find out which records exist.
- Every decision about clinical data (allowed, refused or not found) is written to the audit log: who, what action, which patient, the outcome and why. Never the clinical values themselves. Every write the FHIR server accepts is logged the same way ([ADR-0014](../adr/0014-recording-and-reading-observations.md)).

## Needs the author's confirmation

- Annex A of the plan says the rules engine may record observations "(simulator, `dev` only)". The api grants `rules-engine` the `:observation/record` action with scope `:all`, but the simulator writes to FHIR directly and not through the api ([ADR-0023](../adr/0023-device-simulator-core-async-pipeline-to-a-fhir-transaction.md)). I found no caller that uses that permission; whether it should stay is the author's call.
- `pulse.api.authz/rules` has an `:alert/write` action for `rules-engine`, but no api route uses it (the engine writes alerts to FHIR itself). It looks like a leftover of Annex A ("Creates and updates `Flag`").
- The administrator has no permission to record a reading (`:observation/record` lists no `clinic-admin`). That matches Annex A ("No"), but it is worth being ready to explain.
