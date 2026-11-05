# Build and tooling

> The monorepo, the tasks, the configuration, the local stack and the images. Sources: [ADR-0001](../adr/0001-monorepo-with-deps-edn-and-babashka.md), [ADR-0003](../adr/0003-configuration-and-lifecycle.md), [ADR-0005](../adr/0005-tooling-kaocha-cljfmt-kondo.md), [ADR-0006](../adr/0006-keycloak-realm-as-clojure-data.md), [ADR-0030](../adr/0030-packaging-the-apps-compiled-uberjars-two-dockerfiles-nginx-for-the-workspace-and-a-compose-profile.md), [01-stack-review.md](../01-stack-review.md), `bb.edn`, `deps.edn`, `compose.yaml`, CLAUDE.md "Commands".

## Repository layout

```
apps/      api, web, spa, scheduler   (each with deps.edn, src, resources, test)
libs/      fhir, platform, ui, view
design/    tokens.edn, theme.css, css/, fonts/, src/ (mock-ups, generators), test/
tools/     Babashka tasks: doctor, env, realm, kc, seed, design, spa, spa_journey, build (tools.build), smoke, cdp.mjs
dev/       repl/ (user.clj), src/ (pulse.dev), test/
infra/     keycloak/ (realm JSON, login theme), hapi/ (application.yaml), postgres/ (init.sql),
           docker/ (jvm.Dockerfile, spa.Dockerfile), spa/ (nginx.conf, generated)
http/      REST Client files, no secrets
docs/      plan, stack review, backlog, ADRs, functional and technical documentation
```

One repository, each app and lib with its own `deps.edn`; the root `deps.edn` holds only tooling aliases (`:dev`, `:test`, `:lint`, `:fmt`, `:build`, `:spa-release`) and pulls the libs with `:local/root`. One namespace prefix per part (`pulse.api`, `pulse.web`, ...). Polylith and separate repositories were rejected (ADR-0001). New source directories must be added to the lint and format paths in `deps.edn`, `cljfmt.edn` and `.lsp/config.edn`, and new test directories to `tests.edn`.

## Versions

From [01-stack-review.md](../01-stack-review.md) (data as of 2026-10-13) and CLAUDE.md "Fixed decisions": Clojure 1.12.x on JDK 21, ClojureScript 1.12, Babashka, Ring 1.15 on Jetty 12, Reitit 0.11, malli 0.20, Integrant 1.0, Aero 1.1.6, Telemere 1.4.0, Nimbus JOSE+JWT 10, core.async 1.9, Hiccup 2.0, htmx 2.0.11, shadow-cljs 3.5.7, Reagent 2.0.1, React 19, re-frame 1.4.7, keycloak-js 26.0.8, Tailwind 4.3.3, daisyUI 5.7.50, Keycloak 26.0.0, HAPI `v8.12.0-2`, PostgreSQL 16. For the images ([ADR-0030](../adr/0030-packaging-the-apps-compiled-uberjars-two-dockerfiles-nginx-for-the-workspace-and-a-compose-profile.md)): tools.build 0.10.14, `clojure:temurin-21-tools-deps-1.12.6.1673-bookworm-slim` as the builder, `eclipse-temurin:21.0.13_9-jre-alpine-3.24` as the JVM runtime, `node:22.23.4-bookworm-slim` (Node, copied into the workspace's builder) and `nginxinc/nginx-unprivileged:1.30.6-alpine3.24`; clj-yaml 1.0.30 reads `compose.yaml` in the tests. npm is the only Node package manager; versions are exact and installed with `npm ci`.

## Tasks (`bb.edn`)

Babashka tasks behave the same on Windows, Linux and CI and do not depend on the current directory (ADR-0001). The main ones, as `bb tasks` documents them:

| Area | Tasks |
|---|---|
| Machine | `doctor` (JDK, Clojure CLI, bb, Node, Docker, free RAM, ports) |
| Quality | `test`, `test:it`, `lint` (clj-kondo), `fmt` / `fmt:check` (cljfmt), `check` |
| Stack | `env:init`, `up`, `down`, `ps`, `logs`, `reset` (**destroys data**); `down`, `ps`, `logs` and `reset` see the applications too |
| Packaging | `build [api\|scheduler\|web]` (the jars), `apps` (the images and the containers), `smoke`, `spa:nginx` |
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
- Every external address is a key (issuer separate from the JWKS URI and the token URI), which is what the container profile uses (ADR-0030): inside the network the services ask `keycloak:8080` for keys and tokens while the issuer stays `http://localhost:8080/realms/pulse`.
- Secrets come only from `.env` (ignored by git); `.env.example` is committed. `bb env:init` creates `.env` with random secrets or adds missing variables to an existing one without changing values (ADR-0025).

## Local stack (`compose.yaml`)

Everything binds to `127.0.0.1`: PostgreSQL 5432 (databases `keycloak` and `hapi`, `init.sql`), Keycloak 8080 (management 9000; `start-dev --import-realm`; the `pulse` theme mounted read-only), HAPI 8090 (`/fhir`). Healthchecks and `depends_on: service_healthy` for PostgreSQL and Keycloak; **HAPI has no container healthcheck** (distroless JRE), so `bb up` polls `/actuator/health` from the host. About 1.5 GB of RAM in total (plan Phase 2). `bb reset` rebuilt everything in about two minutes.

### The applications as containers (profile `apps`)

[ADR-0030](../adr/0030-packaging-the-apps-compiled-uberjars-two-dockerfiles-nginx-for-the-workspace-and-a-compose-profile.md). The same file has four more services behind the profile `apps`, so `bb up` and the integration tests are what they were: `api` (127.0.0.1:4000), `scheduler` (not published: the administrator reaches it through the api), `web` (3300) and `spa` (3000; nginx listens on 8080 inside). They run as a user that is not root, with a read-only file system and a `tmpfs` for `/tmp`, no capability and a limit of memory; each has a health check on its `/readyz` (`/healthz` for nginx) and starts when what it needs is ready. Secrets come from the `.env` that `bb up` makes; there is no new variable.

- **Jars.** `bb build [app ...]` (`tools/build.clj`, tools.build) makes `target/<app>.jar` for the api, the scheduler and the portal, compiled ahead of time from `pulse.<app>.main`: not compiled they took 33 to 43 s to answer their first request in the spike, compiled 6 to 10 s. The build looks into the jar it made (`tools/build_plan.clj`) before it says it is done.
- **Images.** `infra/docker/jvm.Dockerfile` (build argument `APP`) makes the three JVM images; `infra/docker/spa.Dockerfile` builds the workspace's release inside the image (Node, shadow-cljs, `clojure -M:spa-release`) and serves it with nginx. `.dockerignore` keeps `.env`, `.git`, installed packages and build output out of the context.
- **nginx.** `infra/spa/nginx.conf` is made by `pulse.spa.nginx` (`bb spa:nginx`) from `pulse.spa.security` and `pulse.spa.files` and committed; a test fails when it is stale. One table of requests (`tools/spa_requests.clj`) is answered by the Ring server in the unit tests and by the container in `bb smoke`.
- **Issuer and back channel.** `KEYCLOAK_JWKS_URI` and `KEYCLOAK_TOKEN_URI` point to `keycloak:8080`; `KEYCLOAK_ISSUER`, the authorization and end-session addresses and `WEB_BASE_URL` stay at `localhost`. A token asked at `keycloak:8080` says `iss` `http://localhost:8080/realms/pulse`, which `bb smoke` checks from inside the containers.
- **Run.** `bb up`, `bb seed`, `bb apps` (builds the four images one at a time, then starts them) and `bb smoke`. The profile needs the ports 3000, 3300 and 4000: stop `bb dev`, `bb web` and `bb spa:serve` first. The containers write to HAPI (the simulator's readings and the alerts raised from them; `bb web:journey` leaves a reading and requests for a visit), and the contract tests of `bb test:it` compare HAPI with the cohort as it was seeded, so run `bb test:it` before the containers or `bb reset` and `bb seed` after them. Measured with everything up: about 2.2 GB in the containers (HAPI 1 GB, Keycloak 0.6 GB, the api 0.2 GB, the scheduler and the portal 0.15 GB each, nginx 12 MB).

## Running the apps

- `bb dev [dev|lite]`: api (4000), scheduler (5000) and portal (3300) in **one JVM** (`pulse.dev`); `lite` uses an in-memory FHIR server shared by api and scheduler, but still needs Keycloak.
- `bb spa`: shadow-cljs dev server on 3000 with hot reload, no CSP. `bb spa:build`: the release (about 0.9 MB, ~90 s), fails on any warning. `bb spa:serve`: the release with its policy.
- REPL: alias `:dev`, `(go)`, `(reset)`, `(halt)`.
- Logs: readable text by default; `LOG_FORMAT=json` for one JSON object per line, `LOG_LEVEL=debug` ([ADR-0010](../adr/0010-structured-logging-with-telemere.md)).

## CI

`.github/workflows/ci.yml`: on push to `main` and on pull requests, Ubuntu, Temurin 21, Node 22, Clojure CLI and Babashka, a Maven/gitlibs cache, `npm ci` in `apps/spa`, then `bb check`: the format, the lint, the committed realm and design files, the tests on the JVM and the tests of the workspace on Node. (Until Phase 9 it ran only `bb fmt:check`, `bb lint` and `bb test`, which was less than `bb.edn` said `bb check` was; ADR-0030 closed that gap.)

`.github/workflows/integration.yml` is not required to merge: it runs by hand, once a week and on a push to `main`. It starts the stack (`bb up`), seeds it, and runs `bb test:it`, `bb spa:journey`, `bb apps`, `bb smoke` and `bb web:journey` against the containers, keeps the logs of the containers as an artifact when something fails, and stops the stack at the end. `tools/test/workflows_test.clj` holds what can be known of both files without running them (pinned actions, the triggers, the order of the steps). Both ran on GitHub on 2026-11-04: `ci.yml` was green on the three pushes of the day, and `integration.yml` was green end to end on the third, in 9 min 47 s. The first two runs failed only at the journey of the workspace, which ran on Linux for the first time and found a defect of the workspace and two mistakes of its own script ([ADR-0029](../adr/0029-the-clinical-workspace-a-browser-client-of-the-api-with-keycloak-js-re-frame-and-the-pages-of-the-mock-ups.md), update of 2026-11-04). A step that fails no longer makes the ones after it skip when what they need is there.

## Secret scan

`gitleaks` (8.30.1, installed with `winget install Gitleaks.Gitleaks`) is not part of `bb check` or of CI (X-150 would put it there). To scan the whole history, every ref, with its default rules and a redacted output, from the root of the repository:

```
gitleaks git --redact --no-banner --log-opts="--all"
```

It exits with 1 when it finds something. On 2026-11-05 it scanned 48 commits and found nothing, after a control that it does find a made-up token in a throwaway repository (a scan that cannot see proves nothing). `.env` is ignored by git and was never committed. The names of the original proof of concept, which must not be anywhere, are searched for with `git grep` over `HEAD` and with `git log --all -G` over the history ([CLAUDE.md](../../CLAUDE.md) "Working agreements").

## Not built yet

The first release, `v0.1.0`, waits for the owner's confirmation ([02-implementation-plan.md](../02-implementation-plan.md) Phase 10). Everything else that was left out on purpose is in [03-extras-backlog.md](../03-extras-backlog.md).

## Windows workstation notes

The project was built on Windows 10 with PowerShell and Git Bash (CLAUDE.md "Workstation"): the Clojure CLI is the `clj-msi` build (deps.clj); shadow-cljs needs `powershell` on the `PATH` (the `bb spa*` tasks add it); Git Bash rewrites paths starting with `/` (`MSYS_NO_PATHCONV=1`); the project path contains a space; Docker Desktop is capped at 6 GB; free RAM was the main constraint, which is why the `lite` profile and the one-JVM development system exist ([01-stack-review.md](../01-stack-review.md) §8).
