# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Status

Pulse is a portfolio project built from scratch in phases. **Phases 0 (workstation) and 1 (repository foundations) are done; Phase 2 (infrastructure) is next. There is no application code yet**, only `libs/fhir` with a first namespace.

## Commands

Run from the repository root (any shell; quote the path, it has a space). Do not invent others: the remaining `bb` tasks arrive with their phase.

- `bb doctor`: checks JDK, Clojure CLI, Babashka, Node, Docker, free RAM and ports.
- `bb test`: Kaocha test suite (config in `tests.edn`). Pass Kaocha args through, for example `bb test --focus pulse.fhir.reference-test/parse-examples` to run a single test.
- `bb lint` (clj-kondo), `bb fmt` (cljfmt fix), `bb fmt:check`, and `bb check` (format + lint + test, what CI runs).
- REPL: alias `:dev` (see `dev/user.clj`), for example Calva jack-in.
- New source directories (such as `apps/`) must be added to the lint and format paths in `deps.edn` and `cljfmt.edn`.

`docs/` is the source of truth:
- `docs/02-implementation-plan.md`: concept, architecture, phases with checklists and acceptance criteria. Read the current phase before starting work and tick items as they are completed.
- `docs/01-stack-review.md`: why each dependency was chosen, with versions as of 2026-10-13.
- `docs/03-extras-backlog.md`: everything deliberately deferred. Add new ideas there instead of expanding the current phase.

## What it is

Remote patient monitoring and appointment coordination on FHIR R4 with Keycloak SSO. Four apps of different kinds share one realm (`pulse`) and one FHIR server (HAPI): `api` (resource server and FHIR gateway), `web` (server-rendered patient portal), `spa` (ClojureScript clinical workspace) and `scheduler` (rules engine and simulator).

## Working agreements

- Everything written in the repository is in English: code, comments, UI text, docs, ADRs, commit messages. The conversation with the owner is in Spanish.
- Document first: a stack or architecture change gets a short ADR (`docs/adr/`, created in Phase 1) before the code.
- The names used by the original proof of concept must not reappear anywhere in the repository (code, config, docs, comments, sample data).
- Data is synthetic only. No secrets in git: use `.env` (ignored) and commit `.env.example`.
- Do not install software, change Windows/WSL/Docker configuration, start containers, publish to GitHub, make commits or permanently delete files without the owner's explicit confirmation at that moment. Send removals to the Recycle Bin or an archive first.

## Fixed decisions

- Keycloak stays at **26.0.0** (keycloak-js on the 26.0.x line) with Keycloak's default password hashing. No custom hash provider.
- FHIR R4 on HAPI FHIR with a pinned image tag; resources use deterministic client-assigned IDs (`PUT`).
- Terminologies: LOINC, ICD-10-CM, RxNorm and UCUM. No SNOMED CT.
- Clojure 1.12.x on JDK 21 (shadow-cljs 3 requires JDK ≥ 21). UI: Tailwind CSS 4 + daisyUI 5.

## Workstation (Windows 10; PowerShell and Git Bash)

- The Clojure CLI is the `clj-msi` build (deps.clj, `clojure --version` prints "Clojure CLI version (deps.clj)") in `%LOCALAPPDATA%\Apps\clojure`. Babashka comes from winget. npm is 11.21 (npm 12 needs Node ≥ 24.15 and the machine has 24.12). `JAVA_HOME` is set at user level.
- Processes started before Phase 0 (VS Code, its terminals, agent shells) do not see the new `PATH`. If `clojure` or `bb` is not found, restart VS Code or prepend `%LOCALAPPDATA%\Apps\clojure` and the Babashka winget package folder to `PATH`.
- Docker Desktop is capped by `~/.wslconfig` at 6 GB and 6 CPUs. Free RAM is scarce (about 0.7 GB with Docker idle), so do not start the full stack casually.
- The project path contains a space (`Clojure Porfolio`): always quote paths.
- In PowerShell, `"text $var: more"` is parsed as a scope-qualified variable and fails; write `${var}:`.
