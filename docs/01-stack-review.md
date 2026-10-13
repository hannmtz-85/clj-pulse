# 01 · Stack review of the original proof of concept

> **Status:** reviewed and accepted by the project owner (2026-10-13) · **Data as of 2026-10-13** (Maven Central, Clojars, npm, GitHub, Docker Hub and official documentation) · **Origin:** the November 2024 proof of concept this repository started from. It has been archived outside the repository and removed from the working tree.
> Version numbers in the "Today" column come from public registries on the day of the query. Anything that could not be verified is marked ⚠.
> Next: [02-implementation-plan.md](02-implementation-plan.md).

## 1. Verdict

The stack was **right in spirit but out of date**. Ring + Reitit + Reagent/re-frame + Keycloak is still a current and respected combination in the Clojure ecosystem. What was not presentable in a portfolio:

1. **Versions about two years behind**: Clojure 1.11 → 1.12.6, Reitit 0.7 → 0.11, Reagent 1.2 / React 16.8 → Reagent 2 / React 19, shadow-cljs 2.28 → 3.5.
2. **Abandoned or redundant dependencies**: `clj-json` (no release since 2012), `clj-time` and `ring-cors` (2019), `chime` (2021), `clj-kondo` installed through npm (2020), and three overlapping JSON/HTTP libraries.
3. **Fragile patterns** in authentication, authorization and configuration (§4).
4. **No automated quality**: zero tests, no CI, orphaned test/e2e scripts.
5. **The domain was a toy shop CRUD** that shows nothing about Clojure; only the SSO part was interesting.

**Conclusion:** keep the idea (four apps + SSO) and the technology base; upgrade almost everything, replace about nine dependencies, and rebuild configuration, authorization and tooling.

## 2. What is kept

- Clojure/ClojureScript, `deps.edn` and shadow-cljs.
- Ring + Reitit (routes as data): still the de facto standard.
- Reagent + re-frame for the SPA; Hiccup on the server.
- **Keycloak 26.0.0**, an owner decision (§6).
- The four application kinds: `spa`, `api`, `scheduler`, `web`.
- `.http` files with the REST Client extension (already installed).

## 3. Dependencies: PoC → today → action

### 3.1 Language and tooling

| Component | PoC | Today | Action | Notes |
|---|---|---|---|---|
| Clojure | 1.11.4 | **1.12.6** (1.13.0-alpha9 in testing) | Upgrade | Stay on stable |
| ClojureScript | 1.11.132 | 1.12.145 | Upgrade | |
| JDK | Temurin 21.0.3 (installed) | Clojure supports LTS 8, 11, 17, 21 and 25 | **Keep 21** | shadow-cljs 3 needs ≥ 21; the Ring Jetty adapter needs ≥ 17 |
| Clojure CLI | not installed | 1.12.6.1673 | Install | `clj-msi` (§8). **Done in Phase 0** |
| Babashka | none | 1.13.226 (winget) | Add | Cross-platform tasks and data scripts. **Done in Phase 0** |
| tools.build | none | v0.10.14 (git dependency) | Add | Uberjars |
| clj-kondo | npm 2020.11.7 | 2026.08.04 | Replace | `deps.edn` alias (no npm); Calva already bundles it for the editor |
| zprint-clj | 0.8.0 | none | Replace | cljfmt 0.16.6, the community-standard formatter |
| Tests | none | Kaocha 1.91.1392 + test.check 1.1.3 + matcher-combinators 3.11.0 | Add | ⚠ Kaocha has had no release since 2024-05 (stable); alternative: Cognitect test-runner |
| nREPL / CIDER / refactor-nrepl | 1.1.1 / 0.47.1 / 3.10.0 | 1.8.0 / 0.63.1 / 3.14.0 | Upgrade | |
| Node and packages | Node 24.12, **npm 8.1.2**, yarn 1.22.10 (two lockfiles) | npm 11.21.0 | Unify | npm only. **Done in Phase 0**: npm 11.21.0 (npm 12.2.0 needs Node ≥ 24.15). `yarn.lock` is gone with the old SPA |

### 3.2 Backend (`api`, `web`, `scheduler`)

| Component | PoC | Today | Action | Notes |
|---|---|---|---|---|
| Ring core / Jetty adapter | 1.12.2 | **1.15.5** (2026-06-23) | Upgrade | Since 1.14: Java 11 (core) and **17** (adapter), Jetty 12. 1.15.5 fixes a ReDoS in `wrap-nested-params` |
| ring-defaults / anti-forgery | 0.5.0 / 1.3.1 | 0.7.1 / 1.4.0 | Upgrade | |
| Reitit | 0.7.1 / 0.7.2 | **0.11.0** (2026-09-18) | Upgrade | Breaking changes that touch us little: Java 11+ (0.8), file-handler redirects (0.8), `:responses <status>` vs `:default` (0.9), OpenAPI 3.2 by default (0.11) |
| malli | none | 0.20.3 | Add | Validation, coercion, generators and OpenAPI through `reitit-malli` |
| muuntaja + jsonista | none | 0.6.12 / 1.0.2 | Add | Content negotiation (JSON, EDN, Transit, `application/fhir+json`) |
| ring-json, cheshire, clj-json | 0.5.1, 5.13.0, 0.5.3 | cheshire 6.2.0; clj-json: 2012 | Remove | A single JSON stack (jsonista) |
| clj-time | 0.15.2 | last release 2019 | Remove | `java.time` (+ `tick` 1.0.2 if needed) |
| clj-http | 3.13.0 | 3.13.1 | Replace | `babashka.http-client` 0.4.25: JDK client with the same API in bb and on the JVM, so `bb seed` and the apps share one FHIR client. Alternative: hato 1.0.0 |
| buddy-auth / sign / core | 3.0.1 / 3.5.351 / none | 3.0.323 (2022-01) / 3.6.1-359 (2024-07) / 1.12.0-430 (2024-07) | Replace (JWT) | Maintenance mode. JWT/JWKS with Nimbus JOSE+JWT 10.10 (active, 2026-09) behind a thin Clojure facade |
| ring-oauth2 | 0.3.0 | 0.3.0 (2024-07) | Keep | Supports `:pkce? true`. ⚠ Token refresh is not documented: spike in Phase 7 |
| ring-cors | 0.1.13 | last release 2019 | Replace | Minimal, tested in-house middleware |
| hiccup | 2.0.0-RC3 | 2.0.0 (2025-06) | Upgrade | |
| chime | 0.3.3 | 0.3.3 (2021-06) | Replace | In-house scheduler: pure `next-run` function + `ScheduledExecutorService` |
| core.async | 1.6.681 | 1.9.865 | Upgrade | |
| Integrant (+ integrant/repl) | none | 1.0.1 / 0.5.1 | Add | Component lifecycle and REPL workflow |
| Aero | none | 1.1.6 (2020-02) | Add | ⚠ No release since 2020 (stable, no dependencies). Alternative: an in-house EDN loader of about 25 lines |
| Logging | `println` | mulog 0.10.1 (or Telemere 1.4.0) | Add | Logs as data; the final choice goes into an ADR |

### 3.3 Frontend (`spa`)

| Component | PoC | Today | Action | Notes |
|---|---|---|---|---|
| shadow-cljs | 2.28.14 | **3.5.7** (2026-10-12) | Upgrade | **3.0 requires JDK ≥ 21** (new closure-compiler). Spike before migrating |
| React | 16.8 | 19.3.0 | Upgrade | |
| Reagent | 1.2.0 | **2.0.1** (2025-11) | Upgrade | **Requires React 19** and `reagent.dom.client/create-root` (replaces `reagent.dom/render`). The default compiler is still class-based; `defc` and `reagent.hooks` are optional |
| re-frame | 1.4.3 | 1.4.7 (2026-04) | Upgrade | |
| day8 http-fx + cljs-ajax | 0.2.4 / 0.8.4 | 0.2.4 (2022-01) / 0.8.4 (0.9.0 in beta) | Replace | In-house `:http` effect over `fetch` + `AbortController` (about 40 lines, no dependencies) |
| reitit in the SPA | declared, unused | reitit-frontend 0.11.0 | Use | Real routing with controllers |
| hiccup (cljs) | 1.0.5 | none | Remove | Not used on the client |
| keycloak-js | ^25.0.4 | 26.2.4 (latest); 26.0 line → **26.0.8** | Pin to 26.0.8 | Same line as the 26.0.x server |
| Styling | minimal CSS | Tailwind 4.3.3 + daisyUI 5.7.48 | Add | Shared with `web` and the login theme |
| Charts | none | Chart.js 4.5.1 (SPA) and server-side SVG (web) | Add | |
| Dev utilities | none | re-frame-10x 1.12.3, day8.re-frame/test 0.1.7, portal 0.68.1 | Add | |

### 3.4 Infrastructure

| Component | PoC | Today | Action | Notes |
|---|---|---|---|---|
| Keycloak | 26.0.0 | 26.8.0 (the 26.0 line ends at 26.0.8) | **Keep 26.0.0** | Owner decision. Optional, without leaving the line: 26.0.8 (patches). Local development only |
| PostgreSQL | `latest` | none | Pin a major version (16) | `latest` with a persistent volume breaks on major upgrades. ⚠ Check the KC 26.0 support matrix in Phase 2 |
| Custom legacy password-hash provider | 16 MB JAR | none | **Remove** | Keycloak's default PBKDF2; the custom `passwordPolicy` goes away |
| FHIR server | none | HAPI FHIR JPA Starter `hapiproject/hapi:v8.12.0-2` (2026-10-02) | Add | §7 |
| Docker Compose | Hostname v1 options, `KEYCLOAK_ADMIN*`, no healthchecks | none | Rewrite | `KC_BOOTSTRAP_ADMIN_*` (the KC 26 mechanism; the account is temporary), hostname v2, `--import-realm` |

## 4. Problems found by `/init` and how each is resolved

| # | Problem | Root cause | Solution | Phase |
|---|---|---|---|---|
| 1 | Apps only started from their own folder | `slurp "resources/data/…"` relative to the working directory | Data lives in FHIR; resources come from the classpath; root aliases and `bb` tasks do not depend on the current directory | 1, 3 |
| 2 | The realm was not imported automatically | `compose.yaml` had no `--import-realm`; the JSON was uploaded by hand | `start-dev --import-realm` + a curated realm. The import **is skipped when the realm already exists**, so `bb reset` recreates it | 2 |
| 3 | "Script upload is disabled" | `authorizationSettings` in the export (the service client had authorization services enabled) | Hand-written realm without `authorizationSettings`; the scheduler client only has a service account | 2 |
| 4 | URLs, ports and secrets hard-coded | No configuration layer | Aero + `.env`; `${VAR}` inside the realm (Keycloak supports it); `.env.example` | 1, 2, 4 |
| 5 | The export had no human users | It only contained the service account | Demo users and roles declared in the realm | 2 |
| 6 | Public key loaded lazily, a failure stayed cached, only `keys[0]`, no `kid` | `delay` + `x5c` of the first JWK | JWKS with TTL, selection by `kid`, refresh on unknown `kid`; `/readyz` | 4 |
| 7 | Invalid or missing token → HTTP 500 | `jwt/unsign` throws and was not caught; `str/replace` on `nil` | Middleware answering 401/403 with `WWW-Authenticate`; tests | 4 |
| 8 | A new route was open to any authenticated user | Access rules kept apart from routes, ending in an "any authenticated user" rule | Authorization in the route data and **deny by default** | 4 |
| 9 | Domain data in memory with inconsistent IDs | Seed EDN in an `atom` (string key, numeric `:id`, duplicates) | FHIR server + deterministic seed with fixed IDs | 3 |
| 10 | `yarn clean` cleaned the wrong directory; `test`/`cards`/`e2e` scripts had no builds; two lockfiles; unused `keycloak.json`; duplicated dependencies | create-cljs-app template left unadapted | shadow-cljs with `:deps true`; npm only; real scripts; tests | 8 |
| 11 | Side effects in render (SPA) and the token logged to the console | `dispatch` inside views; `console.log` of the Bearer token | Route events (reitit-frontend controllers); tokens are never logged | 8 |
| 12 | Repeated `app.*` namespaces in three projects | Same root namespace in every app | One prefix per app (`pulse.api`, `pulse.web`, …) | 1 |
| 13 | `.http` samples with stale secrets and users missing from the seed data | Copied from old sessions | Regenerated; REST Client variables without secrets | 2, 4 |
| 14 | No tests, no CI | n/a | Kaocha + test.check + GitHub Actions | 1 onwards |

Minor defects that disappear with the rewrite: the web app ran `Integer/parseInt` on a price with decimals; a `:clientid` key was read as `:clientId`; anti-forgery was applied twice; the scheduler random-walked a price with no lower bound (it could go negative); the SPA started its refresh timer when the module loaded.

## 5. What was already good and is reused as an idea

- OAuth2 flows: authorization code (web), public client with PKCE (spa), client credentials (scheduler), protected resource (api).
- Role rules plus a "resource owner" rule, which becomes FHIR's *patient compartment*.
- Per-client custom login themes (`login_theme` in the realm).
- Composite roles as a pattern.

## 6. Keycloak: what stays and what changes

- **Version:** 26.0.0, unchanged. The 26.0 line ends at 26.0.8, which is the option if security patches are ever wanted without leaving the line.
- **Compose:** `KC_HOSTNAME_PORT` and `KC_HOSTNAME_STRICT_HTTPS` are dropped (they do not appear in the KC 26 hostname v2 documentation); `KC_HOSTNAME` + `KC_HTTP_ENABLED` are used. The initial admin moves to `KC_BOOTSTRAP_ADMIN_*`.
- **Realm:** named `pulse`; a small, readable JSON with `${VAR}` for secrets, no `authorizationSettings`, no custom `passwordPolicy`.
- **keycloak-js 26.0.8** in the SPA (same line as the server).
- **Themes:** same `themes/<name>/login` layout, rebuilt with the new design tokens.
- **Import:** `--import-realm` skips a realm that already exists; to reload changes the volume is wiped (`bb reset`).

## 7. FHIR server

| | **HAPI FHIR JPA Starter** (chosen) | **Blaze** |
|---|---|---|
| Language | Java | **Clojure** |
| Current version | image `v8.12.0-2` (2026-10-02), monthly releases | v1.11.0 (2026-08-23) |
| FHIR | R4 (also R4B/R5) | R4 |
| Typical use | General-purpose server: CRUD, search, transactions, validation, Subscriptions | Aimed at analytics and research (CQL, `$evaluate-measure`) according to its README |
| Configuration | Mounted `application.yaml`, PostgreSQL, `client_id_strategy`, `hapi.fhir.subscription.resthook_enabled`, `JAVA_TOOL_OPTIONS` | Environment variables, own storage (RocksDB) |
| ⚠ Not verified | none | Subscriptions and authentication are not stated in its README |

**Decision:** HAPI is the primary server (functional breadth, validation, industry recognition). Blaze stays as an optional compose profile in the extras backlog, to show that our FHIR client does not depend on the server. Aidbox (Clojure, licensed) is ruled out because of licence friction for anyone cloning the repo (from memory, not verified today).

## 8. Windows workstation

### 8.1 State before and after Phase 0

| Item | Before (2026-10-13) | After Phase 0 |
|---|---|---|
| JDK | Temurin 21.0.3; `JAVA_HOME` unset | Same JDK; `JAVA_HOME` set at user level |
| Clojure CLI | not installed | `clj-msi` 1.12.6.1673 (deps.clj + Clojure Tools), per-user, in `%LOCALAPPDATA%\Apps\clojure`; verified in PowerShell and Git Bash, including a git dependency |
| Babashka | not installed | 1.13.226 via winget |
| Node / npm / yarn | 24.12 / **8.1.2** / 1.22.10 | 24.12 / **11.21.0** / 1.22.10 (unused) |
| Docker Desktop | installed, **daemon off** | running; engine 27.5.1, Compose 2.32.4, `hello-world` OK |
| `.wslconfig` | missing (the VM could take about 50 % of RAM) | `memory=6GB`, `processors=6`, `swap=2GB`; the engine reports 6.2 GB and 6 CPUs |
| RAM | 15.9 GB total, **about 2.1 GB free** | **about 0.7 GB free** with Docker Desktop idle (the WSL VM holds about 3.3 GB) |
| Ports 3000, 3300, 3333, 4000, 5000, 5432, 8080, 8081, 9000 | free | free (HAPI will use 8090) |
| Git 2.55, gh, VS Code (Calva, REST Client, Docker) | present | present |
| PowerShell execution policy | LocalMachine = RemoteSigned | unchanged, sufficient |
| Package managers | winget, choco; no scoop | unchanged |
| Project path | contains a space (`Clojure Porfolio`) | unchanged; minor risk, keep paths quoted |
| Optional tweaks (long paths, antivirus exclusions) | not done | still not done (see the extras backlog) |

**Memory is the main constraint.** With Docker idle only about 0.7 GB is free, so the full stack (Keycloak + HAPI + PostgreSQL) will only run comfortably after closing heavy applications (Chrome alone holds about 4 GB). The plan keeps a "lite" profile (the `api` against an in-memory fake FHIR client, no HAPI) and an all-apps-in-one-REPL workflow for that reason.

### 8.2 How Clojure was installed

| Option | What it is | Pros | Cons |
|---|---|---|---|
| **A. `clj-msi`** (used) | Community MSI that installs `clojure.exe`/`clj.exe` (deps.clj-based) with Clojure Tools 1.12.6.1673; cited by the official guide | Real binaries: work in PowerShell, cmd and Git Bash; same version as the official CLI; SHA-256 matched the digest GitHub publishes | deps.clj is a reimplementation of the CLI (it reports "Clojure CLI version (deps.clj)"); the MSI is not Authenticode-signed |
| B. WSL2 + Linux install | The official guide's "recommended" option | Parity with Linux and CI | The project lives on `D:\`; slow I/O on `/mnt/d`; two environments; more RAM |
| C. scoop or choco | Package managers | none | scoop is not installed; choco offers 1.12.5.1664 (one patch behind) |

## 9. Sources

- Official Clojure install guide: <https://clojure.org/guides/install_clojure>
- `clj-msi`: <https://github.com/casselc/clj-msi> · `deps.clj`: <https://github.com/borkdude/deps.clj>
- Changelogs: [Reitit](https://github.com/metosin/reitit/blob/master/CHANGELOG.md), [Reagent](https://github.com/reagent-project/reagent/blob/master/CHANGELOG.md), [shadow-cljs](https://raw.githubusercontent.com/thheller/shadow-cljs/master/CHANGELOG.md), [Ring](https://github.com/ring-clojure/ring/blob/master/CHANGELOG.md)
- Keycloak: [import/export](https://www.keycloak.org/server/importExport), [hostname v2](https://www.keycloak.org/server/hostname), [bootstrap admin](https://www.keycloak.org/server/bootstrap-admin-recovery)
- HAPI FHIR JPA Starter: <https://github.com/hapifhir/hapi-fhir-jpaserver-starter> · Blaze: <https://github.com/samply/blaze>
- ring-oauth2: <https://github.com/weavejester/ring-oauth2> · Buddy access rules: <https://funcool.github.io/buddy-auth/latest/user-guide.html>
- Versions: Maven Central and Clojars metadata, npm registry, GitHub and Docker Hub APIs (queried on 2026-10-13).
