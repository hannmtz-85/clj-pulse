# The scheduler (`scheduler`)

> The background worker: it runs the alert rules, writes appointment reminders and, in development, simulates the patients' devices. Technical detail: [../technical/scheduler.md](../technical/scheduler.md).

## Purpose and users

No person uses the scheduler directly. It runs jobs on a clock, as its own service account ([ADR-0021](../adr/0021-service-tokens-client-credentials-cached-and-renewed-early.md)). The **administrator** sees what it is doing on the Jobs page of the workspace, which asks the api, which asks the scheduler ([ADR-0025](../adr/0025-job-status-through-the-api-its-own-service-account-and-a-role-for-it.md)).

## Its three jobs

From `apps/scheduler/src/pulse/scheduler/jobs.clj` and `CLAUDE.md`:

| Job | Every (development default) | What it does | Decided in |
|---|---|---|---|
| `evaluate-rules` | 30 seconds | Reads each patient's recent readings and raises the alerts of rules R1–R4 | [ADR-0022](../adr/0022-rules-engine-rules-as-data-alerts-raised-once.md) |
| `simulate-devices` | 15 seconds | Writes simulated readings of the cohort's devices, with abnormal episodes in turn | [ADR-0023](../adr/0023-device-simulator-core-async-pipeline-to-a-fhir-transaction.md) |
| `send-reminders` | 1 minute | Writes a reminder for each confirmed appointment of the next 24 hours, once (rule R5) | [ADR-0024](../adr/0024-appointment-reminders-a-communication-sent-once-from-what-fhir-holds.md) |

The rules and the reminders are explained in plain words in [clinical-rules.md](clinical-rules.md).

The simulator is for development only and can be switched off (`SCHEDULER_SIMULATOR=false`); so can the reminders (`SCHEDULER_REMINDERS=false`). In tests all jobs are off and run by hand.

## How the jobs behave

From [ADR-0019](../adr/0019-scheduler-jobs-as-data-and-a-pure-next-run.md) and [ADR-0020](../adr/0020-scheduler-executor-and-http-surface.md):

- **A job never runs twice at the same time.** If a run is still going when the next one is due, the next one is skipped and counted.
- **No catch-up bursts.** After a pause (a laptop that slept) a job runs once, not once for every slot it missed.
- **A failing or hanging job does not stop the schedule.** It keeps being scheduled, and its status shows the failures or how long it has been running.
- **Jobs are safe to repeat.** Alerts and reminders are created "only if not already there", so a repeated run creates nothing twice.
- **A daily job** (none of the three is daily today) would run once per local day, at its time, even on the days the clocks change.
- **Stopping is orderly**: no new runs, running ones get a grace period, then they are interrupted.

## What the status shows

For each job (ADR-0020, ADR-0025; workspace page `pulse.spa.pages.jobs`): its schedule, whether it is running and since when, the next run, the last run (when, how long, outcome: ok, failed, interrupted) with a summary of what it did (for the rules: patients, alerts raised, already raised...; for reminders: appointments found, sent, already sent, failed), and counts of runs ok, failed, interrupted and skipped. For a failure, only the **type** of the error is shown, never its message (which goes to the log).

The status is kept in memory, so it starts again from nothing when the scheduler restarts (X-36).

## What happens when something it needs is down

- **The api is down**: the rules job fails each run, with a reason in the log, until the api is back; the schedule continues. The scheduler reports itself as not ready (ADR-0022).
- **Keycloak is down or the secret is wrong**: the scheduler cannot get its token and reports `token: down` as not ready, rather than jobs failing silently (ADR-0021).
- **The FHIR server is down**: the rules, simulator and reminders jobs fail their runs. The reminders job needs only FHIR, so it keeps working when the api is down (ADR-0024).
- **One patient or one appointment fails**: it is counted and logged, and the run goes on with the others; the run as a whole is reported as failed.

## Limits

- No calendar schedules (weekdays, "last Friday") (X-34), no timeout per job (X-35), no history after a restart (X-36).
- Jobs cannot be started from the workspace; the scheduler has its own `POST /jobs/{id}/run` for the administrator, not reachable through the api (X-74).
- Rules evaluate every patient every run, not only those with new readings (X-67).
- Simulated readings accumulate in the development FHIR server until `bb reset` (X-70).
