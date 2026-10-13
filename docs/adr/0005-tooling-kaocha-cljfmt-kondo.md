# ADR-0005: Tooling: Kaocha, cljfmt and clj-kondo through deps aliases

- **Status:** accepted
- **Date:** 2026-10-13

## Context

The original proof of concept had no tests, installed clj-kondo (2020) and zprint through npm, and had orphaned test scripts. Calva already bundles clj-kondo and clojure-lsp for the editor, but CI and `bb` need the same tools without npm.

## Decision

- **Tests:** Kaocha (`tests.edn`) with `clojure.test`, test.check for property tests and matcher-combinators. Verified working with Clojure 1.12.6 on 2026-10-13 even though Kaocha has had no release since 2024-05.
- **Lint:** clj-kondo 2026.08.04 as a `:lint` alias.
- **Format:** cljfmt 0.16.6 as a `:fmt` alias (`check` / `fix`), configured in `cljfmt.edn`.
- All three are driven through `bb` tasks (`test`, `lint`, `fmt`, `fmt:check`, `check`) and run by CI.

## Alternatives considered

- **Cognitect test-runner:** simpler and maintained, but fewer features (no watch mode, plugins); it stays the fallback.
- **zprint:** the original formatter; cljfmt is more common in the community and in Calva.

## Consequences

- No Node is needed for Clojure tooling.
- cljfmt needs `:extra-indents` for project macros such as `defrule` and test.check's `for-all`.
