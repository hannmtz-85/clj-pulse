# Testing strategy

> How Pulse is tested and why each kind of test exists. Sources: [ADR-0005](../adr/0005-tooling-kaocha-cljfmt-kondo.md), the ADRs of each part, `tests.edn`, `bb.edn`, CLAUDE.md, and the acceptance sections of [02-implementation-plan.md](../02-implementation-plan.md).

## Size

The README states about **4,180 tests** (3,014 on the JVM and 1,170 on Node) and two acceptance journeys in a real browser: the portal's (62 checks) and the workspace's (225 checks, with axe-core) ([README.md](../../README.md) "Status"). These numbers were not re-counted for this document.

## Runners and suites

| Command | What runs | Notes |
|---|---|---|
| `bb test` | Kaocha on the JVM, every suite, skipping `^:integration` | Suites in `tests.edn`: `:fhir`, `:platform`, `:api`, `:scheduler`, `:web`, `:spa`, `:ui`, `:view`, `:tools`, `:design`, `:dev`; `bb test --focus :api` or a single var |
| `bb spa:test` | shadow-cljs `:node-test` on Node | The workspace's `.cljs` and the `.cljc` again; fails on any compiler warning |
| `bb test:it` | `^:integration` tests against the running stack (`bb up`) | Real Keycloak and HAPI; the portal's needs port 3300 free |
| `bb web:journey` | `apps/web/journey.mjs`, headless Chrome/Edge over CDP, no dependencies | Needs `bb up` and `bb dev lite` |
| `bb spa:journey` | `apps/spa/journey.mjs` via `tools/spa_journey.clj` | Builds the release, starts its own api and server on 3000/3300/4000/5000, runs eight sections, stops what it started |
| `bb check` | `fmt:check`, `lint`, `realm:check`, `design:check`, `test`, `spa:test` | Described in `bb.edn` as "everything CI runs", which is not true yet (see "CI" below) |

Libraries: Kaocha, `clojure.test`, test.check (properties), matcher-combinators, `day8.re-frame/test` (Node), axe-core 4.14.0 (journey only), FreeMarker 2.3.32 (`:test` alias only).

## Kinds of test, and where they are used

**Pure functions with tables and properties.** Everything that can be decided without I/O is a pure function, often `.cljc`, so it is tested in milliseconds: `next-run` against an oracle that walks the minutes, across every clock change in the JDK's database (ADR-0019); the rules' boundaries and property tests over shuffled readings (ADR-0022); unit round trips and band coverage (Phase 3); `return-to` never leaves the site (ADR-0028); the workspace's machines (panel, chart, inbox, appointments) answered in any order.

**Fakes instead of mocks.** The FHIR client is a protocol with an in-memory implementation that behaves like a server; tests do not mock HTTP, because mocks would encode assumptions about HAPI (ADR-0007). Other fakes over real sockets:

- `pulse.platform.test.idp`: a fake identity provider with throwaway RSA keys and a local JWKS server; `pulse.platform.test.oidc` adds `/authorize`, code and refresh grants and `/logout`, and is itself held to what Keycloak refuses.
- `pulse.platform.test.stub`: a stand-in for another service; `:concurrent?` exposes whether a caller makes calls at once.
- `pulse.platform.test.clock` and `pulse.scheduler.test.time`: time moved by hand, so overruns, skipped ticks and stops with a handler in flight are reachable.
- `pulse.web.test.cohort`: an api stand-in that answers from the cohort and judges posts as the api does.

**Contract tests keep the fakes honest.** With `bb test:it`, the same queries run against HAPI and against `client.memory` and must answer alike (to the second at period edges, OR lists, conditional creates); every search parameter the fake declares must exist on HAPI; everything the api can build must be valid on HAPI (ADR-0007, ADR-0016, ADR-0022, ADR-0024).

**Tables written apart from the code.** The Annex A matrix exists as `rules` in code and as a table in `authz-test`; the OpenAPI document's operations and statuses are a table in `docs-test` (ADR-0012, ADR-0017).

**Generated artefacts are checked against their generator.** The realm JSON (`realm:check`), `design/theme.css` (`design:check`), the Keycloak theme, the portal's and the workspace's static files, and the `libs/view` parity table: a test fails when a committed file is not what the generator makes.

**Cross-checks between parts.** Role lists against the committed realm; `pulse.view.alerts` against `pulse.scheduler.rules`; the mock-up data against the api's handlers; the workspace's `goog-define` defaults against the server's config and the realm; every page held to its mock-up.

**Accessibility.** `pulse.ui.test.a11y/problems` on every component, page and state (with a test case per rule, "since a rule that never fires passes every page"); axe-core and a keyboard walk in `bb spa:journey`; four widths in both schemes, reduced motion and forced colours (ADR-0026, ADR-0029 step 9).

**Rendering on Node.** `render_test.cljs` renders every page and every state of the inventory with React's development build and fails on any console message, with a control showing what React says without the adapter (ADR-0029).

**The inventory of states.** `pulse.spa.test.states` lists, per route, examples of each state; a test holds the route table to it and each example to its words, on the visible text with the live region removed (ADR-0029, update of 2026-11-02).

**Acceptance in a real browser.** Both journeys drive headless Chrome over the DevTools protocol against the real Keycloak: the sign-in through the themed login page, no horizontal scroll, no target under 24 px, no console error, no CSP violation **with a control that must be blocked** (an inline script, `eval`, an inline style, a request to another server), no token in any storage. The workspace's adds failure injection with the Fetch domain (fail, refuse, hang), a sign-in that never ends, and a fault injected into `Intl.DateTimeFormat.prototype.formatToParts` to reach the error boundary.

**One-JVM system tests.** `pulse.dev-test` runs the real api, scheduler and portal systems together with the fake IdP: simulator readings seen by the api, an alert raised and found in the practitioner's inbox, Ana's appointment reminded once, Ana signing in at the portal and recording a reading (ADR-0023, ADR-0025, ADR-0028).

**Mutation analysis.** Done by hand at several steps (about 250 mutations in Phase 5 step 4; 430 for the login theme; the pure parts of the workspace on the JVM and on Node). Each surviving mutant became a test, often in a `*-details-test` namespace. The runner is not in the repository (X-69).

## Lessons recorded

From the plan and CLAUDE.md:

- **A generator lies until its output is counted**: 97 % of the first random bodies were 400s, and a `PATCH` property produced 80 % 409s. Tally the statuses before trusting a property.
- A check that looks for violations must first be shown one (the CSP controls).
- A property that shuffles must generate the permutation (`gen/shuffle`) so a failure can be shrunk and repeated.
- Rare races were found by looping one test: `executor/stop!` failed about once in 80 runs, and the rules engine once in 60 (two readings of one instant).
- A coverage threshold is set from the measured mean and spread, not a round number.
- Mutation analysis cannot see layout: a 100-letter name pushing columns off screen was found by looking.

## CI

**Known gap, to close in Phase 9.** CI runs only `bb fmt:check`, `bb lint` and `bb test`, while `bb check` also runs `bb realm:check`, `bb design:check` and `bb spa:test`. `bb.edn` nevertheless describes `bb check` as "everything CI runs": that is the inconsistency to fix. Closing it means adding those three checks to `.github/workflows/ci.yml` (or running `bb check` there) and the integration job that Phase 9 of [02-implementation-plan.md](../02-implementation-plan.md) lists ("CI: an optional integration job with services"). The plan names the integration job; the three missing checks are not listed there and are part of the same fix. No browser journey runs in CI (X-110).

## Needs the author's confirmation

- Whether the CI workflow has run green on GitHub; the plan (Phase 1) says it had not run yet because there was no remote at the time.
- The test counts are the README's, not re-counted.
