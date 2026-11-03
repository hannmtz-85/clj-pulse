# `apps/scheduler`: jobs, rules engine, simulator, reminders

> Functional view: [../functional/scheduler.md](../functional/scheduler.md). Sources: [ADR-0019](../adr/0019-scheduler-jobs-as-data-and-a-pure-next-run.md) to [ADR-0025](../adr/0025-job-status-through-the-api-its-own-service-account-and-a-role-for-it.md); `apps/scheduler/src/pulse/scheduler/*`; CLAUDE.md (apps/scheduler).

## Configuration

`resources/pulse/scheduler/config.edn` is the Integrant system map, read and checked like the api's (closed maps; profiles `dev` and `test`, `SCHEDULER_PROFILE`). The `dev` profile cannot load without `SCHEDULER_CLIENT_SECRET`. Job intervals: `SCHEDULER_EVALUATE_EVERY_MS` (30000), simulator 15000, `SCHEDULER_REMINDERS_EVERY_MS` (60000); `SCHEDULER_SIMULATOR` and `SCHEDULER_REMINDERS` switch jobs off.

## Schedules are data, `next-run` is pure (ADR-0019)

- A job is `{:id :description :schedule :handler}`; the id is a simple lower-case keyword (it goes into URLs); `job/check!` validates a catalogue with unique ids. The handler closes over its dependencies.
- A schedule is EDN: `{:type :interval :every-ms n :offset-ms?}` aligned to the **epoch grid** (a restart does not move it; up to a week), or `{:type :daily :at "HH:mm[:ss]" :zone "Europe/Madrid"}`, zone required.
- `(next-run schedule after)` is the first instant strictly after `after`. No clock, no state.
- Daily edge cases: a time the clocks skip runs right after the gap; a repeated time runs the first time; `next-daily` tries from one day before to two after, because Greenland's change (23:00→00:00, since 2024) and Newfoundland's (back at 00:01 until 2010) need both ends. Tests build cases from every such change in the JDK's tz database and check all ~18,000 changes 2000–2040 need no more.
- Rejected: `scheduleAtFixedRate` (drift, bursts, untestable), cron strings, Quartz, start-up-relative intervals.

## The executor (ADR-0020)

- Ticks fire on **one** daemon `ScheduledThreadPoolExecutor` thread; handlers run on **virtual threads** named `job-N`.
- A tick **first arms the next tick** (`next-run` from the later of now and its own due time), then runs the job unless it is still running (skipped, counted, logged). So: no overlap, no catch-up, and the chain survives a handler that fails or hangs.
- State is one atom with a map per job, changed by `swap!`; claiming a run is atomic, so a tick and a manual run cannot both start it.
- `run-now!` answers `:started`, `:already-running`, `:unknown-job` or `:stopped`; it does not move the schedule and carries the caller's log context.
- A handler gets `{:job-id :trigger :due :started-at}` and returns nil or a map of simple values. Outcomes: `:ok`, `:failed` (class only in the status), `:interrupted` (an `InterruptedException` anywhere in the cause chain).
- `stop!`: no more ticks, `:grace-ms` (5 s) for handlers, then interrupt; it waits for the tick thread after `shutdownNow` before shutting the workers (a race found once in ~80 runs).
- Clock, tick scheduler and workers are parameters: `pulse.scheduler.test.time` drives time by hand (`world`, `advance!`, `fire-next!`, `direct-workers`).

## HTTP surface (ADR-0020)

A second resource server built like the api's from `libs/platform` (no CORS: no browser calls it): audience `pulse-scheduler`, `/healthz`, `/readyz` (keys, executor, `token`, the api's `/readyz` asked with the scheduler's token, FHIR), `GET /jobs`, `POST /jobs/{id}/run` (202, 404, 409, 503). Rules: `:jobs/read` for `clinic-admin` and `api-service`; `:jobs/run` for `clinic-admin`. Deny by default with `check-routes!`; audit events of the api's shape. Views are camelCase with ISO strings.

## The rules engine (ADR-0022)

- **`defrule`** defines a var with the rule's data and its condition **twice**: the function that runs and the quoted form of the same text, so what is shown is what ran. The rule is checked against a malli schema when the namespace loads. The catalogue is a plain vector; a test fails for a rule defined but not in it.
- `evaluate` is a pure multimethod on `:type` (`:recent-count`, `:window-count`, `:rise`) from `(rule readings now)` to nil or `{:readings :newest-at}`. Readings get a total order (time, then their numbers) because servers do not order same-instant readings (a property test found one failure in sixty). A rise is computed in rationals (64.1 − 62.1 is 1.999999999999993 in doubles).
- The four rules and their parameters: [../functional/clinical-rules.md](../functional/clinical-rules.md).
- `alerts`: the Flag of a rule, a patient and the newest supporting reading; `raise!` is `create-once!` (conditional create in a one-entry transaction): a repeated run, overlapping runs or a restart raise nothing twice, and an acknowledged alert is not re-raised for the same evidence. `active?` finds the active alert by the prefix `R1/pt-ana/` with its slash. A `PUT` with a derived id was rejected because it would reactivate an acknowledged alert.
- `source`: `ApiSource` reads patients (identifiers) and series **through the api** with the scheduler's token (normalization and authorization are the api's, and the `rules-engine` role is used), and the active conditions from FHIR (Annex A gives `rules-engine` no patient read). `reading-from-item` converts to the stored unit and drops what cannot be used.
- `engine/run!` goes patient by patient; `failure/fatal?` ends the run for an interruption, no token, api or FHIR unreachable/failing, or the api refusing the token. Other failures are counted; a run with failures throws, so it shows as failed. Result: `patients`, `raised`, `already-raised`, `still-active`, `not-applicable`, `failed`.
- Each alert raised is a `:pulse.audit/write` event with the scheduler's client as actor.

## The device simulator (ADR-0023)

- A job (`simulate-devices`), so it inherits status, manual run, no-overlap and orderly stop.
- `plan` is pure: `(plan scenario tick at)`. Devices are the cohort's signals; each is due every second tick, phased (about 28 readings a minute); a reading is the signal's baseline at that hour with noise **redrawn beyond 2 SD**, so normal readings cannot cross a threshold of Annex C. Episodes (`resources/pulse/scheduler/simulation.edn`), one per rule in a 32-tick cycle, take over a signal; the cycle starts with the process.
- A reading is made the way the api makes one (`measurement/normalize`, `measurement/observation`), with no performer, a `device`, the `simulated` tag, and an id from patient, signal and second.
- `pipeline` (generic core.async): a producer thread puts a lazy sequence on a bounded channel; `pipeline` runs the transducers in order; `partition-all` on a channel batches and flushes the last; **one writer thread (not a `go` block, it does blocking I/O)** writes each batch as a FHIR transaction of PUTs. Backpressure: a slow writer slows the producer. An interruption or timeout closes the source, drains for `:drain-ms`, and throws `::interrupted` or `::timeout` with stats.
- Writes go to FHIR, not through the api (a device is not a performer; one request per reading would be needed).
- Tests: rules applied to 70,000 normal readings (none may fire); each episode must fire on at least two consecutive ticks.

## The reminders (ADR-0024)

- `send-reminders` reads and writes **FHIR only**: one search `Appointment?status=booked&date=gt<now>&date=le<now+window>` in whole seconds (HAPI and the fake agree to the second), then `create-once!` of a `Communication` per appointment.
- Key `<appointment id>/<start UTC>`: a moved appointment gets a new reminder. Each doctor is read once per run; the name has no title.
- `message` is English whatever the machine's locale (the formatter is made per call so a test can change the default locale), in the clinic's zone.
- Result: `appointments`, `sent`, `already-sent`, `failed`. Audited as `:pulse.audit/write` with action `:reminder/write` and rule `R5`, never the text.
- Checked on HAPI: six concurrent creators of one reminder store one; most others get 409.

## `bb dev`: api, scheduler and portal in one JVM

`pulse.dev` merges the configurations into one Integrant system; the scheduler's client of the api starts after the api's server and stops before it. With `lite`, the scheduler's FHIR is the api's in-memory one, by reference (ADR-0023). `SCHEDULER_SIMULATOR=false` stops the simulator.

## Tests

`rules-test` (the macro and its expansion, Annex C examples with their boundaries, properties), `alerts-test`, `source-test` against a stand-in api, `engine-test` (cohort seeded at each hour), `end-to-end-test` (the api's own handler served with Jetty, the engine against it: four alerts once, an acknowledgement kept, a patient's token refused with 403), `reminders-test` (window edges to the second, clock changes, locale), `stack-test` (HAPI and the fake remind the same patients), `pulse.dev-test` (the acceptance in one JVM). A mutation analysis (~250 mutations) strengthened the tests (CLAUDE.md, apps/scheduler).

## Known limits

X-34 calendar schedules, X-35 per-job timeout, X-36 history across restarts, X-65 alerts that resolve, X-66 why an alert exists in the Flag, X-67 evaluate only what changed, X-68 per-patient thresholds, X-70 cleanup of simulated readings, X-71 devices as FHIR resources, X-72 retract a reminder, X-73 cheaper reminder runs, X-74 start a job through the api.
