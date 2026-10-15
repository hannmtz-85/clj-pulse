# ADR-0022: The rules engine: rules as data, and an alert raised once

- **Status:** accepted
- **Date:** 2026-10-15

## Context

Annex C names four rules (R1 to R4) that turn readings into alerts, with thresholds that are didactic and not clinical advice. An alert is a FHIR `Flag`. The api lists and acknowledges alerts and leaves two things to the engine (ADR-0016): who raises them, and whether a condition that persists raises a new alert once the first one has been acknowledged. Four things have to be decided:

- **How a rule is written.** The clinical condition ("at least 2 of the last 3 readings have a systolic pressure of 160 or more, or a diastolic one of 100 or more") should be written once, read like Annex C, and shown to a clinician exactly as it ran. A rule is mostly data (which measurement, how far back, how many), and its condition is code.
- **When two evaluations are the same alert.** The engine runs every few minutes over data that changes every few hours. An alert per run would bury the inbox; an alert raised once and never again would silence a patient whose pressure goes on rising after the doctor acknowledged it.
- **What stops a duplicate**, including when two runs overlap or the process restarts in the middle of one.
- **Where the engine gets its data and puts its alerts.** The api already normalizes readings (units, bands, bounded series) and authorizes the engine's service account for them (Annex A: identifiers and observations for `rules-engine`). It gives that role no way to read conditions, and no endpoint to write a `Flag`.

## Decision

**A rule is a map made by `defrule`.** The macro takes the rule's data and its condition, written in terms of the numbers of a reading (`value`, `systolic`, `diastolic`; for a rise, `value` and `baseline`), and defines a var holding `{:id :title :type ... :test (fn ...) :condition '(or (>= systolic 160) (>= diastolic 100))}`. The function runs; the quoted form is the same text, kept as data to explain a decision, so the condition that is shown and the condition that runs cannot differ. The macro also checks the rule against a schema when the namespace loads, so a mistake in a rule stops the start-up and not the first evaluation. That is all it does; the catalogue of rules is a plain vector, and a test fails for a rule that is defined and not in it.

**Rules are evaluated by type.** `evaluate` is a multimethod on `:type`, pure, from `(rule readings now)` to nil or the evidence, and independent of the order of the readings. A server does not promise an order for readings of the same instant, which devices that send in batches produce, so readings are put in a total order, by time and then by their numbers; which of four readings of one instant are "the last three", and which is the latest weight (the highest), no longer depends on how they arrived. A property test found this: shuffling two readings of one instant gave different evidence, in about one run of the test in sixty.

- `:recent-count`: at least `n` of the last `m` readings within the look-back satisfy the condition. R1.
- `:window-count`: at least `n` readings within a window satisfy it. R2 (2 readings of SpO₂ below 92 % in 24 hours) and R3 (1 glucose above 250 or below 70 mg/dL in 24 hours; Annex C gives no window, and 24 hours is the one the demo cohort is built for).
- `:rise`: the latest reading, if it is from the last 24 hours, is above the lowest of the 72 hours before it by at least a delta. R4 (2 kg), for a patient with an active condition coded I50 (heart failure).

R1 reads "BP ≥ 160/100" as the cohort's tests do, a systolic of 160 or more *or* a diastolic of 100 or more. Readings are first put in the unit the measurement is stored in, or left out if they cannot be (another system may write mmol/L or pounds to the same server).

**An alert is one for a rule, a patient and the newest reading that supports it.** Its identifier is `R1/pt-ana/2026-10-13T08:00:00Z`, in the system `https://pulse.example/identifier/alert`. The parts are joined with a slash because an id of FHIR cannot hold one, so the identifier of one patient's alert is never taken for another's (`pt-ana-2` and `pt-ana`), and a slash needs no escaping in a search.

- If the patient already has an *active* alert for the rule, nothing is raised: one active alert at a time for each rule and patient, however many readings keep arriving.
- Otherwise the `Flag` is created with a **conditional create** (`If-None-Exist: identifier=...`), in a one-entry transaction. If a `Flag` with that identifier exists, active or acknowledged, nothing is created. So a run that is repeated, two runs that overlap and a restart in the middle of one raise nothing twice, and an alert that a clinician has acknowledged is not raised again for the same readings. A *newer* reading that still satisfies the rule raises a new alert, because it is new evidence.
- The Flag is `active`, with `period.start` the moment it was raised, a rule coding and the title of the rule as its text. The server gives it its id, since a create may not name one.

A `PUT` with an id made from the same key would also be idempotent, but it would write over an acknowledged alert and make it active again.

**The engine reads through the api and writes to FHIR.** Patients (identifiers only) and series of readings come from the api with the engine's own service token (ADR-0021), so they go through its authorization and its normalization, and the engine is the first real client of the role `rules-engine`. The conditions that R4 needs, and the alerts it raises and looks up, are FHIR resources that the engine reads and writes itself, as ADR-0016 says. A run goes patient by patient. A failure of the infrastructure (no token, the api unreachable) ends the run, since the next patient would fail the same way; any other failure is counted and the run goes on, and a run with failures ends as a failed run, so it shows as one. What the handler returns is a summary of simple numbers (`patients`, `raised`, `already-raised`, `still-active`, `not-applicable`, `failed`), which the status of the scheduler shows with camelCase names. The job runs every five minutes in a deployment, `SCHEDULER_EVALUATE_EVERY_MS`, and every 30 seconds by default, so that locally an alert appears within a minute of a reading; the tests switch it off and run it by hand.

**The client of the api lives in the platform**, as `pulse.platform.http.client`, because step 7 needs the other half: the api calling the scheduler. A client sends the caller's own token from its keeper (ADR-0021) with each request, and what comes back is data: `request` answers `{:status :body}` whatever the status, and `get-json` and `post-json` throw when the answer is not a success, in one of three ways that a caller can tell apart (the service answered with a problem, the service could not be reached or was too slow, there was no token to send) and with nothing secret in them. It is a component of every service that needs one (`:pulse.platform/service-client`), with timeouts, and is stopped with the system.

**The in-memory FHIR client learns conditional create**, as HAPI has it (no match: created; one match: left as it is and reported as a `200`; more than one: `412`), with a contract test against HAPI. Each alert raised is an audit event of the same kind as the api's writes (`:pulse.audit/write`), with the engine's client as the actor.

## Alternatives considered

- **Rules as plain functions:** nothing to learn, but the text that explains a decision is a string written next to the code and can say something else.
- **A rules library** (Clara, Drools): a rete network for four conditions over a handful of readings.
- **Rules in the configuration:** a condition is an expression; EDN with `eval` is the worst way to have one.
- **One alert per rule and patient, for ever:** a patient who goes on worsening after an acknowledgement is not told about again.
- **An alert per run:** the inbox fills with copies.
- **Looking for an existing alert and then creating one:** a race between two runs; the conditional create is the server doing the same check atomically.
- **Reading everything from FHIR:** it works, and it would make the engine repeat the api's unit conversion and bands, and leave the role `rules-engine` of Annex A unused.
- **Writing the alerts through a new api endpoint:** an endpoint that exists for one caller, to write what the caller could write itself.

## Consequences

- A rule is read at a glance, tested without a server, and its condition can be shown to whoever asks why an alert exists.
- The inbox has at most one active alert for each rule and patient; reopening one means a new reading.
- An acknowledged alert and a restart cost one conditional create each, which is the server's question and not a search followed by a write.
- A patient whose conditions are not coded I50 never has R4 evaluated, and one whose latest weight is older than a day is not alerted on it; both are in the rule.
- The engine depends on the api (to be up, and to accept its token) and on FHIR. Its readiness says so: `/readyz` of the scheduler also asks the api whether it is ready, which includes that it can check tokens and reach FHIR, and asks FHIR for a Patient. A scheduler whose api is down is unready and keeps its schedule, and its jobs fail with a reason that is logged, one run after another, until the api is back.
