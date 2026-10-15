# ADR-0019: The scheduler: jobs as data and a pure `next-run`

- **Status:** accepted
- **Date:** 2026-10-15

## Context

The `scheduler` runs things on a clock: it evaluates the clinical rules, simulates devices in development and sends appointment reminders. The usual way to write that is `ScheduledExecutorService.scheduleAtFixedRate` with a period, and it has three problems for this project:

- **A daily job drifts.** "Every 24 hours" is not "every day at 08:00": twice a year a local day has 23 or 25 hours, and a job that was meant for 02:30 meets a time that does not exist (clocks go forward) or exists twice (clocks go back).
- **It cannot be tested without waiting.** The rule "when is this due?" is tangled with threads and sleeping, so the only test is a slow one, and the interesting cases (a daylight-saving change, a leap day) cannot be reached at all.
- **A pause becomes a burst.** A fixed-rate task that fell behind (a laptop that slept, a run that took longer than its period) runs several times in a row to catch up, which for a rules engine means evaluating the same data again and again.

Cron expressions answer the first problem but they are strings with a parser to trust, a second language for something that is two numbers and a zone here, and the libraries that run them (Quartz and its descendants) come with a job store and a thread pool of their own.

## Decision

- **A job is a map**, validated by a malli schema when the catalogue is built: `{:id :description :schedule :handler}`. The `handler` is a function that closes over its dependencies (FHIR client, token source, clock), so the catalogue is built from the configuration by a function, and the job itself holds no state.
- **A schedule is data that EDN can say**, so that the configuration states it and the same schema checks it:
  - `{:type :interval :every-ms n}`, with an optional `:offset-ms`: due at every instant `t` where `t ≡ offset (mod n)`. Intervals are aligned to the epoch, not to the moment the process started, so the schedule is the same whenever the scheduler was started, and a restart does not move it. Any whole number of milliseconds up to a week: anything rarer is a calendar, not an interval. A floor that suits operations (nobody wants the rules engine run every millisecond) is for the scheduler's own configuration to set, so that tests can still use short intervals.
  - `{:type :daily :at "HH:mm[:ss]" :zone "Europe/Madrid"}`: once per local day, at that time on the clock of the zone. The zone is required, because a time of day without one means nothing. A time that does not exist (the hour clocks skip) runs right after the gap, as `java.time` resolves it, and a time that exists twice runs the first time, so every day has exactly one run. When the gap is at the end of the day (Greenland's clocks go from 23:00 to 00:00) the run of that day comes out just after midnight, on the next date, and the next day still has its own run at its own time.
- **`pulse.scheduler.schedule/next-run` is pure**: `(next-run schedule after)` is the first instant, strictly after `after`, at which the schedule is due. It reads no clock and holds no state. The executor (the only impure part, [ADR-0020](0020-scheduler-executor-and-http-surface.md)) asks for the next run from *now* each time a run falls due. That one rule, with a job that does not start a second run while one is going, gives the other two properties: a run that overruns its period is not overlapped, and a pause or a long run is not made up for; the missed runs are skipped.
- **The tests need no clock.** The properties of `next-run` are checked against generated schedules and instants. The daily case is also checked against an oracle that does not search the way `next-run` does: it walks the minutes after `after` and asks, for each, whether the schedule is due then, straight from the definition (an instant is a run when the day's time of day resolves to it in the zone). The generator is aimed at the days on which clocks change, in zones that change them at midnight, by half an hour or by three quarters, and a test counts that it gets there. Two families of real changes need more than the usual days to be answered right, the run of the day before (Greenland, from 2024) and the day after next (Newfoundland, until 2010, put its clocks back across midnight), and cases are built from every such change in the JDK's time zone database; a last test checks, against all of its 18 000 changes between 2000 and 2040, that no change needs more than those days.

## Alternatives considered

- **`scheduleAtFixedRate` with a period:** drifts across daylight-saving changes, cannot be tested without waiting and catches up in bursts.
- **Cron expressions:** a parser to write or trust, the weekday and month fields nobody here needs, and an opaque string where a map can be checked field by field. Calendar schedules are in the backlog (X-34) for when a job needs them.
- **Quartz or a library derived from it:** a scheduler with its own threads and job store, to run six jobs in one process, and the due time stays hidden inside it.
- **Intervals counted from the start-up:** the grid depends on when the process happened to start, so the same configuration gives different run times on each run, and a test cannot know them.
- **`Duration` and `ZoneId` objects in the schedule:** they are not EDN, so the configuration could not state a schedule.

## Consequences

- When a job is due is a function of two values, and its tests run in milliseconds through leap days and the two daylight-saving changes of a year.
- A job's period is not a promise that it runs that often: after a long pause there is one run, not many. Handlers must be written to be repeated safely (the rules engine's output is a conditional create, so it is), and must not assume that every slot ran.
- No weekdays, days of the month or "last Friday": a `:daily` job that wants to skip days decides inside its handler, until X-34.
- An unknown time zone or a malformed time fails when the catalogue is built, at start-up, not when the job is first due.
