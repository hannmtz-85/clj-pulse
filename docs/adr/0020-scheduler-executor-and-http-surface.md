# ADR-0020: The scheduler's executor, and who may call it

- **Status:** accepted
- **Date:** 2026-10-15

## Context

ADR-0019 says when a job is due. Something has to run it: keep the clock, start the handlers, survive a handler that fails or hangs, stop in order, say what it is doing, and let an administrator ask for a run now. Three things make that harder than it looks:

- **A tick must not wait for a job.** If one pool both keeps time and runs handlers, a slow handler delays every other job's tick, and sizing the pool becomes part of the schedule.
- **Time has to be testable.** The behaviours that matter (a run that overruns its period, a tick that finds the job still running, a stop with a handler in flight) are exactly the ones a test cannot reach by sleeping.
- **The scheduler is a second resource server.** It has an HTTP surface for administrators (`GET /jobs`, `POST /jobs/{id}/run`), and the api already has everything such a surface needs: the request pipeline, the problem responses, bearer authentication, a check that no endpoint forgets to say who may call it. Writing it again would give two copies to keep alike.

## Decision

**The executor** (`pulse.scheduler.executor`) has two pools. A single `ScheduledThreadPoolExecutor` thread only fires ticks, and handlers run on virtual threads (JDK 21, as in `pulse.platform.fanout`). The clock, the tick scheduler and the workers are parameters, with real ones as the default.

- **A tick** for a job, due at `d`, first arms the next tick, for `next-run` asked from the later of now and `d` (so a tick that fires a hair early cannot arm itself again for the same slot); then it starts a run, unless the job is still running from an earlier one, in which case the tick is skipped, counted and logged. Arming comes first and is the same whatever happens next, so the chain of ticks outlives a handler that fails, one that hangs and any exception in the tick itself. A hung job shows as skipped ticks and a `runningSince` that keeps getting older; it does not silently stop being scheduled.
- **No overlap and no catch-up** follow from that, and are the properties ADR-0019 promised: one run at a time per job, and after a long pause (or a run longer than its period) one late tick and then the next slot, not a burst. A run is claimed by an atomic change of the job's state, so a scheduled tick and a manual run cannot both start it.
- **A manual run** is the same run with `:trigger :manual`. It does not move the schedule: intervals are on the epoch grid and daily runs are at their time of day, whatever has been run in between. It answers `:started`, `:already-running`, `:unknown-job` or `:stopped`. It carries the caller's log context to the worker, so the events of the run have the request id of the request that asked for it.
- **A handler** is a function of one argument, `{:job-id :trigger :due :started-at}`, that returns nil or a map of simple values (the summary shown in the status). What happened to each run is recorded: `:ok`, `:failed` (the class of the exception only; the message and the stack go to the log, as everywhere in Pulse) or `:interrupted` (the exception, or anything it was caused by, is an `InterruptedException`: a handler that catches one and throws another thing in its place has still been interrupted). An `Error` is a failure like any exception, and the job is free again afterwards. Handlers must be safe to repeat and must not swallow interruption.
- **The status** of each job is data: state, when it started running, the next tick, the last run and four counts (`ok`, `failed`, `interrupted`, `skipped`). It is in memory and starts again from nothing when the process does.
- **Stopping** is in order: no more ticks, then the handlers in flight get a grace period (default 5 s) to finish, then they are interrupted, and what was interrupted is logged.

**The HTTP surface** is a Reitit/Ring service built like the api's (request id, access log, security headers, exception handler, router; content negotiation and coercion inside). There is no CORS, because no browser calls the scheduler: the workspace will see its status through the api. It is its own resource server:

- It accepts tokens whose **audience is `pulse-scheduler`** (a configuration value) and refuses the rest. A token issued for the api does not work here, and one issued for the scheduler does not work in the api, so neither service can be made to act with a token meant for the other. Which clients' tokens carry that audience is the realm's business and comes with the api's call to the scheduler (the api's own service account), in step 7 of Phase 5; until then only tests mint such tokens.
- Authorization is by role and by **action**, as data (`rules`: `:jobs/read` and `:jobs/run`, both for `clinic-admin`), and deny by default: an endpoint with no valid `:authz` stops the start-up. Every decision is an audit event of the same kind and shape as the api's.
- `/healthz` and `/readyz` are public; readiness asks the identity provider's keys and that the executor is running.

**What the two services share moves to `libs/platform`**, now that there is a second user: `pulse.platform.http.router` (the Reitit options of a JSON service, the answers for a path, method or format the router does not know, the map from failures that carry their own meaning to problems, and the traversal that finds the endpoints with no valid `:authz`) and `pulse.platform.system` (the Integrant components for logging, the key cache, the token verifier and the Jetty server, which each service's keys `derive` from, with the schemas of what their configuration may say). The api uses them with no change in behaviour. What stays with each service is policy: who may do what, the security headers it sends, what it needs to be ready.

## Alternatives considered

- **One pool that keeps time and runs the handlers** (`scheduleWithFixedDelay`, say): the simplest thing, but a slow handler holds a thread that ticks need, and a hung one removes a thread for good.
- **Arming the next tick when a run ends:** it looks like "ask from now after each run", but it ties the timer to the life of the run: a handler that never returns stops the job for ever and says nothing.
- **Letting the tick wait for a free job** (queueing the run): that is a catch-up under another name.
- **Accepting the api's tokens in the scheduler** (same audience): one fewer realm change, but then the audience protects nothing, and forwarding the caller's token would be the design. The api's own service-account token (step 7) keeps each service's authorization its own.
- **Quartz:** see ADR-0019.
- **Copying the api's pipeline into the scheduler:** two copies of the problem responses and the router options that have to stay the same, for the sake of not touching the api.

## Consequences

- Tests drive time by hand: a fake clock and a fake tick scheduler that runs what is due when told to, with real virtual threads where concurrency is the point. A few tests use the real pools to prove that the shell around them works.
- No run history survives a restart and a run has no timeout of its own; a handler is bounded by the timeouts of what it calls (the FHIR client has them). Both are in the backlog (X-35, X-36).
- A job that is skipped many times in a row is a signal for a person, not for the program. Alerting on it belongs with the metrics extra (X-41).
- The api's code gets smaller and its behaviour does not change; its tests are the proof.
