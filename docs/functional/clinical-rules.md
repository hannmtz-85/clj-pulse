# Clinical rules and reminders

> What makes Pulse raise an alert or send a reminder, in plain words. **The thresholds are didactic, not clinical advice** ([02-implementation-plan.md](../02-implementation-plan.md) Annex C; [README.md](../../README.md) "Disclaimer").

The rules are written in `apps/scheduler/src/pulse/scheduler/rules.clj` and decided in [ADR-0022](../adr/0022-rules-engine-rules-as-data-alerts-raised-once.md). The reminders are in `apps/scheduler/src/pulse/scheduler/reminders.clj` and [ADR-0024](../adr/0024-appointment-reminders-a-communication-sent-once-from-what-fhir-holds.md).

## The four alert rules

| Rule | Title shown | Raised when | Demo patient |
|---|---|---|---|
| R1 | BP stage 2 | In **at least 2 of the last 3** blood-pressure readings of the last 3 days, the systolic is 160 or more **or** the diastolic is 100 or more | Ana García |
| R2 | Low SpO₂ | **At least 2** oxygen-saturation readings below 92 % in the last 24 hours | Sofía Rossi |
| R3 | Glucose out of range | **At least 1** glucose reading above 250 or below 70 mg/dL in the last 24 hours | Liam Chen |
| R4 | Rapid weight gain | For a patient with an active **heart failure** diagnosis (ICD-10-CM I50): the latest weight, taken in the last 24 hours, is **2 kg or more** above the lowest weight of the 72 hours before it | Maya Patel |

Notes from the code and ADR-0022:

- R1 reads "BP ≥ 160/100" as *either* number being high, which is how the demo data's tests read it.
- Annex C gives R3 no time window; 24 hours is the window the demo data is built for.
- R4 is only evaluated for patients with a heart-failure condition. A patient whose latest weight is older than a day is not alerted on.
- Readings are first converted to the unit Pulse stores (for example mg/dL for glucose); a reading in a unit that cannot be converted is left out rather than breaking the evaluation.
- Readings taken at the same instant are put in a fixed order, so that "the last three" does not depend on the order in which a device sent them.

## When an alert is raised, and when it is not raised again

From ADR-0022:

- The rules run every **30 seconds** in development (configurable; ADR-0022 suggests five minutes for a deployment), so an alert appears within a minute of the reading that causes it.
- An alert is tied to a **rule, a patient and the newest reading that supports it**. If that patient already has an **active** alert for the rule, nothing new is raised, however many readings keep arriving. One active alert per rule and patient.
- Running the rules again over the same readings never creates a duplicate, even if two runs overlap or the scheduler restarts in the middle of one (the FHIR server itself checks "create only if not there").
- Once a clinician **acknowledges** an alert it is not raised again for the same readings. A **newer** reading that still breaks the rule is new evidence and raises a new alert.
- Alerts do not close by themselves when the readings return to normal; that is backlog item X-65.

## What acknowledging means

A clinician acknowledges an alert to say they have seen it. The alert becomes inactive and the time it ended is recorded ([ADR-0016](../adr/0016-alerts-inbox-and-acknowledgement.md)). If a colleague acknowledged it first, the second person is told so rather than having it silently accepted. Who acknowledged is in the audit log; the screens do not show it yet (X-80).

## How each reading is classified (the "band")

Separately from the alert rules, every reading is put in a didactic **band** that the screens show with a colour, an icon and a word (`libs/fhir/src/pulse/fhir/ranges.cljc`; [ADR-0026](../adr/0026-design-system-as-data-tokens-theme-components-and-contrast-by-test.md)):

| Measurement | Bands (lower bound inclusive) |
|---|---|
| Systolic pressure (mmHg) | low < 90 · normal 90–120 · elevated 120–140 · high 140–160 · very high ≥ 160 |
| Diastolic pressure (mmHg) | low < 60 · normal 60–80 · elevated 80–90 · high 90–100 · very high ≥ 100 |
| Heart rate (/min) | low < 50 · normal 50–100 · high 100–120 · very high ≥ 120 |
| SpO₂ (%) | very low < 90 · low 90–92 · normal ≥ 92 |
| Glucose (mg/dL) | very low < 54 · low 54–70 · normal 70–140 · high 140–250 · very high ≥ 250 |
| Temperature (°C) | low < 36.0 · normal 36.0–37.8 · fever 37.8–39.0 · high fever ≥ 39.0 |

Weight has no band. A blood-pressure reading takes the worse band of its two numbers ([ADR-0013](../adr/0013-patient-summary-fan-out.md)).

## What counts as a believable reading

When a reading is recorded, the api refuses numbers that cannot be real (a typo or a wrong unit), but **not** unusual ones, which are what the app is for ([ADR-0014](../adr/0014-recording-and-reading-observations.md); `libs/fhir/src/pulse/fhir/measurement.cljc`): heart rate 20–300 /min, SpO₂ 50–100 %, glucose 10–1000 mg/dL, weight 1–500 kg, temperature 25–45 °C, systolic 40–300 and diastolic 20–200 mmHg (and the diastolic must be below the systolic). A reading may not be more than 5 minutes in the future or more than 30 days in the past.

## The simulated devices

In development, the scheduler pretends to be the patients' home devices ([ADR-0023](../adr/0023-device-simulator-core-async-pipeline-to-a-fhir-transaction.md)): about 28 readings a minute for the demo cohort. Normal simulated readings are kept within two standard deviations so that they can never break a rule by chance (a test checks 70,000 of them). Abnormal readings come only from planned **episodes**, one for each rule, in a cycle: Sofía at the start, Ana after 2 minutes, Liam after 4, Maya after 6, repeating every 8 minutes (`CLAUDE.md`, `bb dev`). The simulated readings are tagged as simulated.

## Reminders (rule R5)

From ADR-0024:

- **When:** a **confirmed** (booked) appointment that starts within the next **24 hours** (configurable from 1 to 168 hours) gets a reminder on the first run after it enters that window. The job runs every minute in development. An appointment that has already started is not reminded.
- **Once:** one reminder per appointment and start time. Running the job again sends nothing new. If the appointment is **moved** to another time inside the window, it gets a new reminder; the old one is not withdrawn (X-72).
- **What it says** (always in English, in the clinic's time zone, Europe/Madrid):

  > Reminder: your appointment with Elena Rivera is on Wednesday 14 October at 06:00 (Hypertension follow-up).

  The doctor's name is written without a title. If it cannot be read, the reminder leaves out "with ...". The reason is added in brackets only when the appointment has one.
- **Where it goes:** it is stored as a record (a FHIR `Communication`) addressed to the patient. **Nobody is actually notified**: there is no email or push channel (X-21), and no screen shows the reminders to the patient yet (X-82).
- A cancelled appointment gets no further reminders, but one already written stays (X-72).

## Needs the author's confirmation

- The rule interval for a deployment ("every five minutes") comes from ADR-0022. The containers of Phase 9 keep the development interval (every 30 seconds, with the simulator and the reminders on, because that is what the demonstration shows); `SCHEDULER_EVALUATE_EVERY_MS` and `SCHEDULER_SIMULATOR` change them. There is no production profile (X-50).
