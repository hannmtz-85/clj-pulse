# ADR-0010: Structured logging with Telemere

- **Status:** accepted
- **Date:** 2026-10-14

## Context

Phase 4 needs structured logs: an audit event for every access to clinical data, a request id on every line of a request, and errors with their cause. The apps run Jetty, which logs through SLF4J. Tests must be able to assert on what was logged. Locally a person reads the output; in a container a machine does, one JSON object per line. The plan left the library open (mulog or Telemere, stack review section 3.2).

## Decision

**Telemere 1.4.0**, plus `telemere-slf4j` so that SLF4J consumers such as Jetty log through it.

- Application code emits **events as data**: `(t/event! :pulse.api/something {:level :info :data {...}})`, an id and a map, not formatted strings. The request id travels in the signal context (`t/with-ctx+`), so every line of a request carries it.
- Output is configuration: `:text` (Telemere's human format) for development and `:json` for containers, with a minimum level and per-namespace levels. The JSON line is made by `t/pr-signal-fn` with jsonista as its `:pr-fn`: Telemere's own `:json` option exists only in ClojureScript.
- `pulse.platform.log` is the only namespace that touches handlers, and an Integrant component starts and stops it. Telemere marks its handler utilities as alpha, so this wrapper contains any change.
- Tests capture signals with `t/with-signals` (which can trap them, so nothing reaches the console) and read `:signals`.

## Alternatives considered

- **mulog:** events as data too, with a smaller API and a lighter dependency tree. It has no SLF4J bridge, so Jetty would need a second logging backend with its own configuration.
- **clojure.tools.logging with Logback:** the common default, but message strings; structure would need encoders and MDC.

## Consequences

- One logging system for our events, Jetty and any later SLF4J library; one place to change the format.
- A larger dependency (`com.taoensso/encore`) than mulog needs.
- Verified in a throwaway script before adopting it: a JSON line carries `inst`, `level`, `id`, `ns`, `data` and `ctx`; an error becomes a cause chain without stack trace, which suits a JSON line; `with-signals` traps and returns signals; the level filter drops what it should.
