# ADR-0015: Appointments: a request, a lifecycle as data, and what "panel" means for an appointment

- **Status:** accepted
- **Date:** 2026-10-14

## Context

`GET` and `POST /api/appointments` and `PATCH /api/appointments/{id}` complete the appointment half of Annex B. Several things were open. How does a patient ask for an appointment, and what does the server decide? What may a clinician do to one, and from which states? Where do those rules live, so that the SPA and the rules engine can use them too? What does the api answer when the state of the appointment does not allow what was asked? What is a clinician's "panel" for an appointment, which has two sides, a patient and a practitioner? And how is an update written when the FHIR client has no conditional update?

## Decision

- **A lifecycle as data** (`pulse.fhir.appointment`, portable). A patient's request is `proposed`. A clinician `confirm`s it with a time (`booked`), may `reschedule` a booked one, and may `cancel` a proposed or a booked one (`cancelled`, for good). `transitions` is the table (from, to, what the action needs). `available-actions` says what an appointment allows, so the SPA can enable its buttons from it. `change` applies an action as a pure function of the appointment, the action and the time, and answers `{:ok appointment}`, `{:conflict message}` when the status does not allow the action, or `{:errors {field [message]}}` when the values cannot be right.
- **What a patient asks**, `POST /api/appointments` with `{description, requestedStart, requestedEnd}`. The patient is the caller, and the doctor is the patient's general practitioner: a request to a doctor who is not theirs would be outside that doctor's panel everywhere else, and a patient with no doctor gets a 409. The result is a `proposed` Appointment (the patient `accepted`, the doctor `needs-action`) with a new `appt-<uuid>` id, and the answer is 201. The window must still be open (its end in the future), be at most 30 days long, start within 180 days, and end after it starts; the description is 1 to 200 characters. Times are instants with their offset and never bare dates, because whether "to 25 October" includes the 25th is not for the server to guess.
- **What a clinician does**, `PATCH /api/appointments/{id}` with a command: `{"action": "confirm" | "reschedule", "start", "end"}` or `{"action": "cancel", "reason"?}`. A time must not be in the past nor more than a year ahead, and lasts from 5 minutes to 4 hours. Confirming sets the doctor's participation to `accepted` and `minutesDuration`; a reason is kept as `cancelationReason` (R4 spells it with one l). Besides 200 there are three outcomes: **400** for the shape of the body; **422** for times that cannot be right, with the same `errors` member as ADR-0014; and **409** when the status of the appointment does not allow the action, such as confirming a cancelled one or rescheduling a proposal. The status is checked before the values, and the message names it.
- **The panel of an appointment** is the appointments the practitioner takes part in. The descriptor that `authorize` judges is the appointment's patient and its practitioners, so a decision costs one read (the appointment) and no Patient read, and the list is one query: `actor=` for a practitioner, `patient=` for a patient, nothing for the administrator. Every appointment the api creates is with the patient's own doctor, and so is every one in the demo data, so this is the same set as "the patients whose general practitioner is the caller" of the other endpoints.
- **One read for the decision and the change.** The authorization step now hands the descriptor to the handler (`:descriptor` on the request), and the appointment's resolver puts the appointment in it. `PATCH` reads the appointment once, and the decision is about the very version that is changed.
- **Written by read-modify-write with PUT.** Two clinicians changing the same appointment at the same moment: the last write wins. The right tool is a conditional update (`If-Match` with the version), which the `FhirClient` protocol does not offer yet (X-28).
- **Audit.** A request is audited like a recorded reading: `:pulse.audit/write` with `:created` and the reference of the appointment. A change is `:updated` and carries `:change`, the action, which is a verb and not a value.
- **The list** has a `status` filter, a `sort` (`start` or `-start`; a request that has no time yet is placed by its requested start), a `limit` (50, at most 100) and an `offset`, and answers with the `total`, as the panel does. An appointment is shown as in the summary, with `patient` and `reason` added.

## Alternatives considered

- **A partial update (`{status, start, end}`).** It is closer to the word PATCH, but then the table of what may follow what hides inside checks across fields, and "confirm" has to be inferred. The commands are the three words of Annex B.
- **One endpoint per action** (`POST /appointments/{id}/confirm`): clear, but Annex B says PATCH.
- **The panel by the patient's general practitioner.** It needs a Patient read for every decision, and a chained search (`patient.general-practitioner`) that the in-memory client would have to learn, to give the same answer for every appointment the api can create.
- **Letting the patient choose the doctor.** The appointment could then lie outside that doctor's patients.
- **Detecting double bookings.** A real clinic needs it. It is a search for the doctor's booked appointments that overlap, and it is left for later (X-29).

## Consequences

- Annex A gives patients no way to cancel, while the plan's page for the portal says "appointments (request/cancel)". The api follows Annex A. Letting a patient withdraw their own proposed request would be a new action with the `:own` scope, to be decided before Phase 7.
- Nothing stops two booked appointments of one doctor from overlapping (X-29), and an update can overwrite a concurrent one (X-28).
- There is no `GET /api/appointments/{id}` and no `Location` header: the answer to a request or a change is the appointment itself.
- The lifecycle is reusable: rule R5 of the scheduler reads `booked` and `start`, and the SPA gets its buttons from `available-actions`.
