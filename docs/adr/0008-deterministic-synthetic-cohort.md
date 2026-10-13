# ADR-0008: The demo data is a deterministic, scenario-driven synthetic cohort

- **Status:** accepted
- **Date:** 2026-10-13

## Context

The portfolio needs realistic clinical data without any real patient information, and the demo must show specific stories: a hypertensive patient getting worse, a diabetic with out-of-range glucose, a COPD patient with low oxygen saturation, a heart-failure patient gaining weight, a healthy control and a brand-new patient. Synthea can generate realistic histories, but it is a 100 MB Java download, its output is large and random, and it does not produce the scenarios above.

## Decision

- The cohort is described as data in `libs/fhir/resources/pulse/seed/cohort.edn` (people, conditions, medication, allergies, appointments and one measurement *signal* per series), and loaded from the **classpath**, not from a working-directory path.
- `pulse.fhir.synthetic/generate` is a pure function of the cohort, the end instant and the number of days. Noise comes from a per-patient `java.util.Random` seeded from the patient id; it is shaped with a sum of twelve uniform samples (Irwin-Hall), which only uses addition and therefore gives bit-identical results on every JVM and in Babashka, which has no `StrictMath`.
- Draws are always consumed in the same order (day, then signal, then slot), even for readings after the end instant, so the values of a day do not change when the seeding moment moves.
- Resource ids are derived from patient, signal and hour (`obs-ana-bp-2026101308`), so seeding is idempotent with `PUT`.
- Each scenario is encoded so that its rule fires whatever the time of day the cohort is seeded (events are placed so that 24-hour windows always contain them). Tests sweep all 24 seeding hours for rules R1-R4.
- Readings are produced by a multimethod on the signal kind, so a new kind of measurement is one `defmethod`. The scheduler's device simulator will reuse it.
- Terminologies are LOINC, ICD-10-CM, RxNorm and UCUM only; every code was checked against the NLM services (see `pulse.fhir.terminology`).

## Alternatives considered

- **Synthea:** realistic but heavy, random and unable to target scenarios; remains possible as an import in the extras.
- **Random data per run:** not reproducible, and tests could not assert on exact scenarios.

## Consequences

- Seeding on a later day keeps the earlier observations and adds new ones (the ids carry the time); `bb reset` starts clean.
- Changing the generator changes the data: tests that assert on exact values must be updated deliberately.
