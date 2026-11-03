# Build and tooling

> The monorepo, the tasks, the configuration and the local stack. Sources: [ADR-0001](../adr/0001-monorepo-with-deps-edn-and-babashka.md), [ADR-0003](../adr/0003-configuration-and-lifecycle.md), [ADR-0005](../adr/0005-tooling-kaocha-cljfmt-kondo.md), [ADR-0006](../adr/0006-keycloak-realm-as-clojure-data.md), [01-stack-review.md](../01-stack-review.md), `bb.edn`, `deps.edn`, `compose.yaml`, CLAUDE.md "Commands".

## Repository layout

```
apps/      api, web, spa, scheduler   (each with deps.edn, src, resources, test)
libs/      fhir, platform, ui, view
design/    tokens.edn, theme.css, css/, fonts/, src/ (mock-ups, generators), test/
tools/     Babashka tasks: doctor, env, realm, kc, seed, design, spa, spa_journey, cdp.mjs
dev/       repl/ (user.clj), src/ (pulse.dev), test/
infra/     keycloak/ (realm JSON, login theme), hapi/ (application.yaml), postgres/ (init.sql)
http/      REST Client files, no secrets
docs/      plan, stack review, backlog, ADRs, functional and technical documentation
```

One repository, each app and lib with its own `deps.edn`; the root `deps.edn` holds only tooling aliases (`:dev`, `:test`, `:lint`, `:fmt`) and pulls the libs with `:local/root`. One namespace prefix per part (`pulse.api`, `pulse.web`, ...). Polylith and separate repositories were rejected (ADR-0001). New source directories must be added to the lint and format paths in `deps.edn`, `cljfmt.edn` and `.lsp/config.edn`, and new test directories to `tests.edn`.

## Versions

From [01-stack-review.md](../01-stack-review.md) (data as of 2026-10-13) and CLAUDE.md "Fixed decisions": Clojure 1.12.x on JDK 21, ClojureScript 1.12, Babashka, Ring 1.15 on Jetty 12, Reitit 0.11, malli 0.20, Integrant 1.0, Aero 1.1.6, Telemere 1.4.0, Nimbus JOSE+JWT 10, core.async 1.9, Hiccup 2.0, htmx 2.0.11, shadow-cljs 3.5.7, Reagent 2.0.1, React 19, re-frame 1.4.7, keycloak-js 26.0.8, Tailwind 4.3.3, daisyUI 5.7.50, Keycloak 26.0.0, HAPI `v8.12.0-2`, PostgreSQL 16. npm is the only Node package manager; versions are exact and installed with `npm ci`.

## Tasks (`bb.edn`)

Babashka tasks behave the same on Windows, Linux and CI and do not depend on the current directory (ADR-0001). The main ones, as `bb tasks` documents them:

| Area | Tasks |
|---|---|
| Machine | `doctor` (JDK, Clojure CLI, bb, Node, Docker, free RAM, ports) |
| Quality | `test`, `test:it`, `lint` (clj-kondo), `fmt` / `fmt:check` (cljfmt), `check` |
| Stack | `env:init`, `up`, `down`, `ps`, `logs`, `reset` (**destroys data**) |
| Realm | `realm:build`, `realm:check`, `realm:smoke`, `token` |
| Data | `seed`, `seed:validate [--all]` |
| Run | `dev [dev\|lite]`, `scheduler`, `web`, `spa`, `spa:build`, `spa:serve` |
| Acceptance | `web:journey`, `spa:journey`, `spa:test` |
| Design | `design:theme`, `design:check`, `design:contrast`, `design:icons`, `design:fonts`, `design:css`, `design:keycloak`, `design:web`, `design:spa`, `design:build`, `design:shots` |

Several generators write files that are **committed** and checked against their generator by a test (realm JSON, theme, Keycloak theme, the portal's and workspace's static files), so a clean clone starts without Node.

## Configuration and lifecycle

[ADR-0003](../adr/0003-configuration-and-lifecycle.md), CLAUDE.md (libs/platform):

- One EDN file per app read by Aero, validated by a malli schema at start-up (closed maps); the validation never prints the offending values.
- Platform readers: `#csv` (comma-separated → set) and `#secret` (a `Secret` that prints as `#secret[REDACTED]`, opened with `reveal`).
- Integrant starts and stops components; `integrant.repl` 0.5 (`go`, `reset`, `halt`, reloading with clj-reload).
- Every external address is a key (issuer separate from the JWKS URI and the token URI), which is what a container profile will need.
- Secrets come only from `.env` (ignored by git); `.env.example` is committed. `bb env:init` creates `.env` with random secrets or adds missing variables to an existing one without changing values (ADR-0025).

## Local stack (`compose.yaml`)

Everything binds to `127.0.0.1`: PostgreSQL 5432 (databases `keycloak` and `hapi`, `init.sql`), Keycloak 8080 (management 9000; `start-dev --import-realm`; the `pulse` theme mounted read-only), HAPI 8090 (`/fhir`). Healthchecks and `depends_on: service_healthy` for PostgreSQL and Keycloak; **HAPI has no container healthcheck** (distroless JRE), so `bb up` polls `/actuator/health` from the host. About 1.5 GB of RAM in total (plan Phase 2). `bb reset` rebuilt everything in about two minutes.

## Running the apps

- `bb dev [dev|lite]`: api (4000), scheduler (5000) and portal (3300) in **one JVM** (`pulse.dev`); `lite` uses an in-memory FHIR server shared by api and scheduler, but still needs Keycloak.
- `bb spa`: shadow-cljs dev server on 3000 with hot reload, no CSP. `bb spa:build`: the release (about 0.9 MB, ~90 s), fails on any warning. `bb spa:serve`: the release with its policy.
- REPL: alias `:dev`, `(go)`, `(reset)`, `(halt)`.
- Logs: readable text by default; `LOG_FORMAT=json` for one JSON object per line, `LOG_LEVEL=debug` ([ADR-0010](../adr/0010-structured-logging-with-telemere.md)).

## CI

`.github/workflows/ci.yml`: on push to `main` and on pull requests, Ubuntu, Temurin 21, Clojure CLI and Babashka, a Maven/gitlibs cache, then `bb fmt:check`, `bb lint`, `bb test`.

**Known gap, to close in Phase 9.** CI runs only `bb fmt:check`, `bb lint` and `bb test`, while `bb check` also runs `bb realm:check`, `bb design:check` and `bb spa:test`. `bb.edn` nevertheless describes `bb check` as "everything CI runs": that is the inconsistency to fix. Closing it means adding those three checks to `.github/workflows/ci.yml` (or running `bb check` there) and the integration job that Phase 9 of [02-implementation-plan.md](../02-implementation-plan.md) lists ("CI: an optional integration job with services"). The plan names the integration job; the three missing checks are not listed there and are part of the same fix.

## Not built yet

[02-implementation-plan.md](../02-implementation-plan.md) Phases 9 and 10:

- Phase 9: multi-stage Dockerfile per app (tools.build → JRE 21), the SPA served by nginx, `docker compose --profile apps up`, the issuer/back-channel split inside Docker, integration tests in an optional CI job. There is no Dockerfile, no `apps` compose profile and no `:build` alias today.
- Phase 10: README with diagram and quick start, licence check of `NOTICE`, `gitleaks` secret scan, publication and release `v0.1.0` (only with the owner's confirmation).

## Windows workstation notes

The project was built on Windows 10 with PowerShell and Git Bash (CLAUDE.md "Workstation"): the Clojure CLI is the `clj-msi` build (deps.clj); shadow-cljs needs `powershell` on the `PATH` (the `bb spa*` tasks add it); Git Bash rewrites paths starting with `/` (`MSYS_NO_PATHCONV=1`); the project path contains a space; Docker Desktop is capped at 6 GB; free RAM was the main constraint, which is why the `lite` profile and the one-JVM development system exist ([01-stack-review.md](../01-stack-review.md) §8).

## Needs the author's confirmation

- Whether the CI workflow has run green on GitHub; the plan's Phase 1 says it had not run yet.
