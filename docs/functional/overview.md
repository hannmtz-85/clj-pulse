# Pulse: functional overview

> For a reader who does not write code. What Pulse is for, who uses it, what it does and what it deliberately does not do. The engineering view is in [../technical/index.md](../technical/index.md); everything is linked from [../index.md](../index.md).

## Intent

Pulse is a portfolio project: a small but complete clinical system written mainly in Clojure and ClojureScript, built to show how such a system is designed, secured and tested ([02-implementation-plan.md](../02-implementation-plan.md), §1 "Goals"). It is **not** a medical device and must not be used with real patient data: every person and every reading in it is synthetic, and the clinical thresholds are didactic, not advice ([README.md](../../README.md), "Disclaimer"; plan §1 "Out of scope").

## The problem it models

Remote patient monitoring and appointment coordination (plan §2):

- Patients with a long-term condition (high blood pressure, diabetes, COPD, heart failure) take measurements at home, or a device takes them: blood pressure, heart rate, oxygen saturation (SpO₂), glucose, weight and temperature.
- A **rules engine** looks at those readings and raises an **alert** when they cross a rule (for example, blood pressure at stage 2 in two of the last three readings). See [clinical-rules.md](clinical-rules.md).
- The **care team** sees the alerts in a workspace, acknowledges them, and handles appointment requests: confirming, moving or cancelling them.
- The **patient** sees their own trends, records readings, asks for a visit, cancels it if needed, and looks at what the clinic has on file about them.
- Shortly before a confirmed appointment the system writes a **reminder** for the patient.

Everything is stored in a standard clinical format, **FHIR R4**, on a standard server (HAPI FHIR), and everyone signs in once through a single sign-on service (**Keycloak**) ([ADR-0002](../adr/0002-fhir-r4-on-hapi.md), plan §2).

## The four applications

| App | What it is | Who uses it | Functional description |
|---|---|---|---|
| `web` | Patient portal (web pages) | Patients | [web.md](web.md) |
| `spa` | Clinical workspace (an application in the browser) | Practitioners and the clinic administrator | [spa.md](spa.md) |
| `api` | The clinical gateway every other part goes through | The other apps; developers through its documentation page | [api.md](api.md) |
| `scheduler` | Background jobs: alert rules, reminders, a device simulator | Nobody directly; the administrator sees its status | [scheduler.md](scheduler.md) |

Source: [README.md](../../README.md) "The four apps"; plan §2.

## Who the users are

The demo realm has these people ([02-implementation-plan.md](../02-implementation-plan.md) Annex D; `tools/realm.clj`):

| Person | Role | Notes |
|---|---|---|
| Sam Admin (`admin@pulse.test`) | Clinic administrator | Sees every patient, and the status of the scheduler's jobs |
| Dr. Elena Rivera (`dr.rivera@pulse.test`) | Practitioner (internal medicine) | Her panel: Ana, Liam, Sofía and Omar |
| Dr. Chidi Okafor (`dr.okafor@pulse.test`) | Practitioner (cardiology) | His panel: Maya and Noah |
| Ana García, Liam Chen, Sofía Rossi, Omar Haddad, Maya Patel, Noah Kim | Patients | Each sees only their own record |

The demo patients each tell a story (plan Annex C; `libs/fhir/resources/pulse/seed/cohort.edn`):

| Patient | Profile | What the data shows | Expected alert |
|---|---|---|---|
| Ana García, 58 | Hypertension | Blood pressure rising over the last 10 days | R1 "BP stage 2" |
| Liam Chen, 46 | Type 2 diabetes | Post-meal highs and one low glucose | R3 "Glucose out of range" |
| Sofía Rossi, 67 | COPD | Repeatedly low oxygen saturation | R2 "Low SpO₂" |
| Omar Haddad, 35 | Healthy | Stable values | None |
| Maya Patel, 52 | Heart failure | Weight gain over a few days | R4 "Rapid weight gain" |
| Noah Kim, 29 | Newly registered | No readings at all | None (shows the empty states) |

There are also two **service accounts**, which are programs rather than people: the scheduler's (`rules-engine` role) and the api's own (`api-service` role). See [roles-and-permissions.md](roles-and-permissions.md).

## Scope: what is built

As of the last commit on the branch, **all the phases of the plan (0 to 10) are done** (plan, status line; [README.md](../../README.md) "Status"):

- The local infrastructure (PostgreSQL, Keycloak 26.0.0, HAPI FHIR) and the synthetic cohort.
- The `api` with its endpoints, authorization rules and documentation page.
- The `scheduler` with the alert rules, the device simulator and the reminders.
- The design system and the mock-ups, which the owner approved, and the themed sign-in page.
- The patient portal (`web`) and its acceptance in a real browser (62 checks).
- The clinical workspace (`spa`) and its acceptance in a real browser (225 checks, with an automated accessibility checker).
- **Packaging** (Phase 9): a Docker image for each app and one command (`bb apps`) that starts the four applications with the infrastructure, a check of the running containers (`bb smoke`: 24 checks, a patient and a practitioner signing in through a real browser among them), and a second workflow in GitHub Actions that runs the integration tests, the browser journeys and the containers. Everything still binds to the developer's own machine; the data is the synthetic cohort.

## Not built yet

Nothing from [02-implementation-plan.md](../02-implementation-plan.md) is left. **Phase 10 · Polish and publication** is done: the final README with its diagram, a check of the third-party notices against their licences, a scan of the whole history for secrets and the first tagged release, `v0.1.0`, published on 2026-11-05. What was left out on purpose is in [03-extras-backlog.md](../03-extras-backlog.md) (for example HTTPS and a hardened deployment, X-50, and the full SMART App Launch standard, X-02).

## Deliberately out of scope

The plan lists what is not a goal (§1 "Out of scope"): a real health record, anything production-grade (HTTPS, high availability, backups, multi-tenancy), a different Keycloak version, and the full SMART App Launch standard.

Everything else that was considered and left out is recorded in [03-extras-backlog.md](../03-extras-backlog.md). The items a non-technical reader is most likely to ask about:

| Not done | Backlog item |
|---|---|
| Real notifications (email, push) for reminders and alerts; today a reminder is only a record | X-21, X-84 |
| The patient seeing their reminders in the portal | X-82 |
| Telling the clinic when a patient cancels; a late-cancellation policy | X-90 |
| Marking a visit as having taken place or as a no-show | X-83 |
| Correcting or withdrawing a recorded reading | X-25 |
| Detecting double bookings of a doctor | X-29 |
| Alerts that close themselves when readings return to normal | X-65 |
| Thresholds set per clinic or per patient | X-68 |
| A Spanish interface (English only today) | X-30 |
| Alerts pushed to the screen instead of checked every 30 seconds | X-23, X-127 |
| A clinician recording a reading from the workspace (the api allows it; no screen does) | X-81 |
| The workspace on a phone (designed for tablet and desk widths) | X-111 |
| A light/dark theme switch (both follow the browser's preference) | X-93, X-112 |
| Production hardening (HTTPS, Keycloak production mode, backups) | X-50 |
| A persisted audit trail in FHIR instead of logs only | X-06 |

Options that were rejected outright (Polylith, SNOMED CT, Aidbox, FHIR R5 as the main version, `ring-oauth2`, and others) are in section H of the backlog.

## Needs the author's confirmation

- The status describes the branch this documentation was written on, whose latest commit is Phase 8, step 9. Nothing here was checked by running the stack (the task was documentation only).
