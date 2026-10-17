# ADR-0027: A patient cancels their own appointment: the lifecycle says who may do what, and `me` names the doctor

- **Status:** accepted (validated point by point by the project owner on 2026-10-17; Phase 7)
- **Date:** 2026-10-17

## Context

The owner decided on 2026-10-16 that a patient can cancel their own appointment. Annex A did not allow it (a patient could only request one) and ADR-0015 left it "to be decided before Phase 7". The mock-ups of the patient portal, which the owner approved on 2026-10-17, have the button: "Cancel appointment" on a confirmed appointment and "Cancel request" on a request nobody has answered, with an optional reason. Several things are open:

- **Which appointments a patient may cancel, and until when.**
- **Where "who may do what" lives**, since the portal and the workspace both have to know which buttons to show.
- **How a patient reaches the endpoint that only clinicians use**, without opening the rest of it to them.
- **What a cancellation does for the clinician.**
- **How the portal learns the name of the patient's doctor.** The api gives only a reference (`Practitioner/pr-rivera`), and the portal shows the name beside every appointment and on the profile.

## Decision

- **What a patient may cancel.** Their own appointment when it is `proposed` (withdrawing a request) or `booked` and has not begun (its `start` is after the moment of the request). The reason is optional, 1 to 200 characters, as it is for a clinician, and a cancellation is for good. A patient may do nothing else to an appointment: confirming and rescheduling are the clinic's. A clinician keeps what they had: cancelling a `proposed` or `booked` appointment of their panel at any time.
- **The lifecycle says who** (`pulse.fhir.appointment`, portable). Each entry of `transitions` has a `:by`: the kinds of actor that may take the action, and what restricts them. It is `{:clinician {}}` for a confirmation or a move and `{:clinician {} :patient {:until-start? true}}` for a cancellation. The kinds are `:patient` and `:clinician` (a practitioner or the administrator). `available-actions` takes the kind of actor and the time, so that the portal shows its buttons from it and the workspace its own. `change` takes the kind too, and answers a fourth thing besides `:ok`, `:conflict` and `:errors`: `{:forbidden message}` when that kind of actor may not take that action. It is checked before the status, so that a patient who tries to confirm a cancelled appointment is told no, and not what state it is in. A kind that is not listed may do nothing (deny by default). The rule about time is a `:conflict`, a 409, with a message of its own: "It has already begun, so only the clinic can cancel it."
- **The api.** `PATCH /api/appointments/{id}` stays the one endpoint, and `:appointment/manage` gives the patient `:own`. Annex A answers whose appointments a caller may act on (their own, their panel's, all of them), and the lifecycle answers what each kind of person may do to them. The handler derives the kind from the roles of the caller: a practitioner or the administrator is a `:clinician`, and the patient the route let in is a `:patient`. A `:forbidden` is a 403 (`You are not allowed to do this.`, with the reason only in the log) and an audit event of outcome `:denied` that names the command. The shape of the body is still judged first, so a patient's `{"action": "confirm"}` with no times is a 400, and with times a 403. An appointment of someone else is a 403 as before, and one that does not exist a 404, now for a patient too.
- **What the clinician sees.** A cancelled appointment, with its reason, in `GET /api/appointments?status=cancelled` and in the summary, as one that a clinician cancelled. Nobody is notified: there is no notification system yet (X-90), so the dialog of the portal says "Your care team will see it" and not "will be told", as the mock-up said. The audit trail has the write (`:updated`, `:change :cancel`) with the patient as the actor.
- **The doctor's name.** `GET /api/me` answers, for a patient, `generalPractitioner`: `{reference, name, specialty}` or null. The name is written as the clinic writes it ("Dr. Elena Rivera") and the specialty is the one the Practitioner has. It is read from FHIR with the Patient. Every appointment a patient can have is with their general practitioner (ADR-0015), so the portal needs nothing else to say "with Dr. Elena Rivera". For anyone who is not a patient it is null.

## Alternatives considered

- **A separate `POST /api/appointments/{id}/cancel` for the patient.** The route would carry its own action and nothing else would change, but a clinician would have two ways to cancel, or the commands would be split between two endpoints, and Annex B says PATCH.
- **One rule for each command in `authz/rules`** (`:appointment/cancel`, `:appointment/confirm`). Annex A would then be the one table of everything. But an endpoint is authorized before its body is read, so the command would have to be checked a second time in the handler anyway, and the table is not something the portal and the SPA can ask when they decide whether to show a button. The lifecycle is portable and already says what a status allows.
- **Letting a patient cancel at any time,** a begun appointment included. A visit that has begun is no longer a cancellation but a visit that was missed, which the clinic records (X-83).
- **A coded cancellation reason** (the HL7 code for "the patient cancelled"), so that a clinician can tell who cancelled. A code in a public repository has to be checked against its code system first, and the text of the reason and the audit trail say who; it can come with X-90.
- **An endpoint to read a Practitioner** (`GET /api/practitioners/{id}`), or the doctor inside the summary. A new endpoint is a new row in Annex A and a new kind of descriptor for one name, and the summary is the clinical record. `me` already answers who the caller is, and who their doctor is belongs with it.

## Consequences

- Annex A has one row more: confirming or rescheduling is the clinic's, and cancelling is also the patient's own appointment, until it begins. The note in ADR-0015 that Annex A gives patients no way to cancel is resolved.
- `available-actions` and `change` have a new parameter, the kind of actor (and `available-actions` the time). The mock-ups and the tests were changed with them, and from now on the buttons of the workspace and the portal come from the same function.
- A patient who asks for an appointment by its id is told 403 if it is someone else's and 404 if it does not exist, as a clinician is. The ids the api makes are random, so there is nothing to learn from the difference, and the fixed ids of the demo are public.
- An update still reads the appointment and writes it back (ADR-0015, X-28): a patient and a clinician acting at the same moment are the same race as two clinicians.
- A reminder (ADR-0024) that was already sent for an appointment that is then cancelled stays in FHIR (X-72), and none is sent afterwards, since the job reads `booked`.
- `GET /api/me` costs a patient one more read, and fails as the read of the Patient does.
- Backlog: X-90, to tell the clinic when a patient cancels and to have a policy for late cancellations.
