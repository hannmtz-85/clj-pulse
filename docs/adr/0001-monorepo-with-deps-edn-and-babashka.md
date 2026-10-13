# ADR-0001: Monorepo with deps.edn and Babashka tasks

- **Status:** accepted
- **Date:** 2026-10-13

## Context

Four apps (`api`, `web`, `spa`, `scheduler`) share FHIR and platform code. The original proof of concept used four unrelated projects that each had to be started from their own directory and repeated the same `app.core` namespace. The development machine is Windows, where `make` is not available.

## Decision

One repository with `apps/` and `libs/`. Every app and lib has its own `deps.edn`; the root `deps.edn` only holds tooling aliases (`:dev`, `:test`, `:lint`, `:fmt`) and pulls the libs in with `:local/root`. Tasks are Babashka (`bb.edn`), so they behave the same on Windows, Linux and CI. Each app uses its own namespace prefix (`pulse.api`, `pulse.web`, ...).

## Alternatives considered

- **Polylith:** more structure than four apps justify.
- **Separate repositories:** shared code would need publishing; harder to demo as one project.
- **Makefile or shell scripts:** not portable to the Windows workstation.

## Consequences

- One REPL can load every app (`:dev`), which also saves RAM on the 16 GB machine.
- Tasks do not depend on the current directory.
- Directories such as `apps/` appear when their first file does; tools that take paths (clj-kondo) list only existing ones.
