# ADR-0023: The device simulator: a core.async pipeline from readings to a FHIR transaction

- **Status:** accepted
- **Date:** 2026-10-15

## Context

Nothing produces readings after the seed. The cohort is 30 days of history that ends when it was seeded, and its alerts leave the windows of the rules within three days, so a demo that has been up for a while is quiet. The acceptance of Phase 5 is that with `bb dev` new readings appear and the abnormal ones raise alerts, without duplicates, in under a minute. The plan asks for the simulator to be a core.async pipeline (producer, transducers, batches, Bundle) with backpressure and an orderly shutdown, in the dev profile only. Four things have to be decided:

- **How it runs.** The executor of the scheduler already has the status, the manual run, the rule that a job never overlaps itself, and the stop with a grace period.
- **Where the readings go.** The api records a reading for a person: the performer is the caller's own FHIR resource. A device is not one, and other systems write to the same FHIR server.
- **What the readings are.** A reading every 30 seconds is 2,880 a day for each signal, so every tail of a noise model gets visited: a value that is `0.6 %` likely to be below 92 is below 92 seventeen times a day. If only the readings that are meant to be abnormal are to be abnormal, the rest must not be able to cross a threshold of Annex C.
- **How the apps run together.** The acceptance says `bb dev`, which today starts the api alone, and the alerts the engine writes to FHIR have to be seen by the api.

## Decision

**The simulator is a job.** `simulate-devices` is in the catalogue (every 15 seconds by default) and one run is one tick of the simulated world. It has the status, the manual run and the stop of every job.

**The plan of a tick is a pure function.** `(plan scenario tick at)` gives the readings that are due: the devices are the signals of the patients in `cohort.edn`, each is due every second tick (phased, so the load is even: about 28 readings a minute for the demo cohort), and a reading is the baseline of its signal at that hour of the day, with the noise cut at two standard deviations. A generator seeded with the patient, the signal and the tick gives the noise, so a tick is reproducible. The ramps and events of the cohort describe its 30 days and are left out. What is abnormal is an *episode*: a short run of readings, one for each rule of Annex C, written as data (patient, signal, the tick of a cycle of 32 ticks at which it starts, the readings), and during one the signal is due on every tick. The cycle begins when the process does, so the first alert is there in the first minute, and the four episodes follow each other every two minutes. An episode lasts longer than a run of the engine (30 seconds), because R4 only looks at the latest weight.

**A reading becomes an Observation the way the api makes one.** `measurement/normalize` checks and rounds it, `measurement/observation` builds it. It has no performer, a `device` that says what it is, and the tag `https://pulse.example/tag|simulated`. Its id is made of the patient, the signal and the second it was taken, so writing it twice is writing it once, and a restart does not meet the readings of the last run.

**The pipeline is generic** (`pulse.scheduler.pipeline`) and every channel in it is bounded:

- a producer thread puts the readings of the plan, a lazy sequence, on a channel;
- `pipeline` runs the transducers that make the Observations, keeping the order;
- a `partition-all` transducer on a channel makes the batches, and flushes the last one when the source ends;
- one writer thread, not a `go` block since it does blocking I/O, writes each batch.

A slow writer slows the producer and nothing grows. A batch that cannot be written is logged and counted and the others go on; a run with failures is a failed run. For an **orderly shutdown**, an interruption (the executor's stop, after its grace) closes the source so that nothing new starts, gives what is in flight a moment to be written and rethrows; a run that takes longer than its timeout ends the same way.

**The simulator writes to FHIR, not through the api.** Each batch is a transaction Bundle of `PUT`s, which is how a device gateway would send it.

**It is for development.** `:enabled?` is `true` in the `dev` profile unless `SCHEDULER_SIMULATOR` says `false`, and `false` in the tests. A profile for a deployment (Phase 10) must say `false`.

**`bb dev` runs the api and the scheduler in one JVM** (`pulse.dev`). The two configurations are merged into one Integrant system, with the scheduler's client of the api after the api's server, so that it starts after it and stops before it. When the api's FHIR is in memory (`lite`), the scheduler's FHIR is the api's, as a reference, since an alert written to another one would never be seen. The REPL (`(go)`) starts the same system. The dependency is core.async 1.9.865, as `docs/01-stack-review.md` says.

## Alternatives considered

- **A pipeline of its own, running as long as the process does:** no status, no manual run, and its own rules for overlap and stop, which the executor has.
- **Writing through the api:** it would need the token of a person or a role that records on behalf of a device, one request for each reading, and a performer that is not one.
- **Random values with no plan:** a failure cannot be repeated, and an alert at a random moment cannot be asserted. With the plan, a test runs a whole cycle and asks the rules themselves.
- **The events and ramps of the cohort:** they end abnormal, so after every acknowledgement a new alert would follow at once.
- **A device gateway over MQTT:** the backlog has it (X-22); an in-process simulator is simpler.
- **`go` blocks for the writer:** an HTTP call in a `go` block holds one of the few threads they share.

## Consequences

- While `bb dev` runs, about 28 readings a minute (some 1,700 an hour) accumulate in the HAPI of development until a `bb reset`. They carry the tag, so a task can remove them (backlog X-70).
- An alert for a rule and a patient appears once for each episode and, while it is active, no other: after an acknowledgement the next episode raises a new one.
- A threshold of Annex C that a normal simulated reading could cross fails a test: a property over thousands of ticks applies the rules to them. A change to an episode that stops its rule from firing fails another.
- The pipeline is tested without FHIR: backpressure with a source that cannot be pulled faster than the buffers allow, and the shutdown with a writer that waits.
- `bb dev` needs `SCHEDULER_CLIENT_SECRET` from `.env`, as `bb scheduler` does, and starts two servers: the api on 4000 and the scheduler on 5000.
