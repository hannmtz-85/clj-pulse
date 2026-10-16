# ADR-0024: Appointment reminders: a Communication, sent once, from what FHIR holds

- **Status:** accepted (the design was validated by the project owner on 2026-10-15; implemented in step 6 of Phase 5)
- **Date:** 2026-10-15

## Context

Rule R5 of Annex C: a confirmed appointment in under 24 hours with no reminder gets a `Communication`. It is the last job the scheduler lacks before the status of its jobs reaches the api (step 7). Four things have to be decided:

- **Where it reads the appointments.** Annex A gives the role `rules-engine` no way to read appointments through the api, and a reminder needs every booked appointment of the clinic, not one patient's.
- **What makes two reminders the same reminder**, so that one is sent once however often the job runs, and what a reschedule does.
- **What the message says and when it goes.**
- **Who reads it.** Nothing in the apps does yet.

## Decision

**A job, `send-reminders`, reads and writes FHIR, not the api.** One search finds every appointment of the clinic that is confirmed and about to begin, `Appointment?status=booked&date=gt<now>&date=le<now + window>`, and the reminders are written to FHIR, as the alerts are (ADR-0022). The api is not involved, so Annex A does not change.

**A reminder is one for an appointment and the time it starts.** Its identifier is `<appointment id>/<start as an instant in UTC>` in the system `https://pulse.example/identifier/reminder`, for example `appt-ana-1/2026-10-14T04:00:00Z`. It is created with a **conditional create** in a one-entry transaction, which the server answers with a 201 if there was no such reminder and a 200 if there was: so a repeated run and a restart send nothing twice, with no search for the reminders first. Two runs at the same time cannot send it twice either, which was checked on HAPI: of six creators of the same reminder at once, one creates it and the others are answered with a 200 or, mostly, with a 409, a conflict that is solved by asking again, and never were two stored (an integration test keeps it so). A scheduler runs a job one at a time, so this can only happen with two schedulers: the one that loses counts the reminder as failed, and the next run finds it sent. If an appointment is rescheduled to another time inside the window it gets a new reminder; the old one is not retracted, and neither is one for an appointment that is cancelled afterwards (backlog X-72).

**The message** is a `Communication` that is `completed`, of the category `reminder`, with the patient as subject and recipient, the appointment in `about`, `sent` the instant of the run, and a text in English, with the time in the zone of the clinic:

> Reminder: your appointment with Elena Rivera is on Wednesday 14 October at 06:00 (Hypertension follow-up).

The name of the doctor is read from the `Practitioner`, without a title, since a title is not for the code to assume; if it cannot be read, or has no name, the text goes without "with ..." (a doctor who is not on the server, who has no name, or whom the server will not give; a failure of the infrastructure is another matter, and ends the run), each doctor is read once for a run, and the description goes in brackets only when the appointment has one. The zone is the setting `:zone` (`Europe/Madrid`), the formatting is fixed to the English locale so that the text does not depend on the machine, and a test goes through the day the clocks change.

**When.** The first run after the appointment is within the window (`now < start <= now + window`, 24 hours by default and a setting between 1 and 168) sends the reminder; an appointment that is confirmed with less notice is reminded on the next run, and one that has started is not. The job runs every minute locally (`SCHEDULER_REMINDERS_EVERY_MS`); `SCHEDULER_REMINDERS=false` and the test profile switch it off.

**What a run says and how it fails.** Its result is `appointments` (found), `sent`, `already-sent` and `failed`. An infrastructure failure (FHIR unreachable or failing) ends the run, and so does an interruption, which a job must not swallow (ADR-0020): what ends a run is now `pulse.scheduler.failure/fatal?`, which the rules engine uses too and which had been private to it. Any other failure is about one appointment, is counted and logged and the run goes on; a run with failures is a failed run. The search is in whole seconds, which is how a time is booked, so that HAPI and the fake agree to the second at both ends of the window (an integration test checks the edges). Each reminder sent is a `:pulse.audit/write` event of the scheduler's client, with the action `:reminder/write`, the patient, the reference of the `Communication` and the rule `R5`; never the text.

**In the FHIR library.** `resource/communication` takes an `:identifier`. The in-memory client learns the `identifier` search parameter of `Communication` (which HAPI has), because a conditional create is a search, and a contract test creates one on HAPI and in memory and compares the answers, as for the `Flag`. The conditional create became a function, `pulse.fhir.client/create-once!` (it was the body of `alerts/raise!`, which now calls it), and refuses a resource with no identifier to go by, since it would create another one every time.

**Nothing reads it yet.** The portal will show a patient their reminders (Phase 7) and a real channel, an e-mail or a push, is the extra X-21. Until then a reminder is a record that the clinic would have sent one.

## Alternatives considered

- **Reading the appointments through the api:** it would need a new action in Annex A for the engine and a request for each patient, to learn what one search of the clinic gives.
- **Looking for a reminder and then creating it:** a race between two runs; the conditional create is the server doing the same check atomically.
- **One reminder for an appointment, whatever its time:** a patient whose appointment moves would never be told.
- **The text in the language of the patient:** the bilingual interface is the extra X-30.
- **Events instead of polling** (a `Subscription` on `Appointment`): the reminder is due when the clock says so and no event happens then; a run every minute is enough.
- **A `CommunicationRequest` or a `Task` that a channel completes:** the right model for a real delivery (X-21), more than a record needs.

## Consequences

- With `bb dev lite`, the appointment of Ana (20 hours ahead in the cohort) has its reminder in the first minute. On a HAPI seeded days ago there is none until it is seeded again, since the appointments of the cohort are relative to the moment of seeding.
- One reminder stays in FHIR for each booked appointment, and none is ever removed.
- A run makes a request for every appointment in the window, every minute, and nearly all find the reminder sent. It is nothing for the demo cohort and it grows with the clinic (backlog X-73).
- The zone is the clinic's, one for everybody.
- The job needs only FHIR: it works with the api down, and its readiness is the scheduler's.
