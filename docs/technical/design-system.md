# Design system (`design/`, `libs/ui`, `libs/view`)

> One look for the portal, the workspace and the Keycloak login, defined as data. Source: [ADR-0026](../adr/0026-design-system-as-data-tokens-theme-components-and-contrast-by-test.md) and its updates; ADR-0028 and ADR-0029 for how the apps use it; CLAUDE.md (design, libs/ui, libs/view).

## Tokens as data

- `design/tokens.edn` holds the palette (**teal**, chosen by the owner on 2026-10-16 from teal, blue and indigo; the others were deleted in their own commit), light and dark schemes (`pulse`, `pulse-dark`), neutrals, the tones of a reading's band, chart colours, type, radii, shadows, and **the list of colour pairs that must meet a contrast** (4.5 for text, 3 for controls and graphics, WCAG 2.2 AA).
- `tools/design.clj` (pure; Babashka and JVM) validates the tokens with malli, measures every pair in both schemes (a ratio is truncated at two decimals, never rounded, so 4.4994 shows as 4.49), and writes `design/theme.css`: daisyUI themes, `--pulse-*` custom properties, `@theme inline` Tailwind colours, `@font-face` for Inter.
- `bb design:check` (part of `bb check`) fails if a pair is below its minimum or the theme is stale. A review found a missing pair (brand text inside a critical-tone message, 4.48:1); the soft critical background was changed from `#fee2e2` to `#fee7e7` (4.63:1).

## CSS build

Tailwind CSS 4.3.3 and daisyUI 5.7.50 pinned in `design/package.json` (Node for development only). Entries: `design/css/input.css` (mock-ups), `web.css` (portal), `spa.css` (workspace), `keycloak.css` (login theme). `design/css/base.css` is unlayered so it wins over daisyUI: the border of a field is the token `control-border` (3:1; daisyUI's default is about 1.5:1), the focus ring, reduced motion, forced colours.

**A class is always a whole string literal** (Tailwind reads the sources): never `(str "btn-" k)`, never `:div.foo`. Builds fail when a page uses a class with no rule in the compiled stylesheet.

Inter (OFL) is committed in `design/fonts`; Lucide icons (ISC) are copied into `libs/ui` as Clojure data by `bb design:icons`. **No page asks a CDN for anything.**

## Components: `libs/ui` (`.cljc`)

Presentational functions that return hiccup: no state, no handlers, depending only on Clojure. The first argument may be an attribute map; a component takes its own keys and passes the rest through, so `hx-*` and `:on-click` both work. Rendered by Hiccup 2 in `web` and by React (through `->reagent`) in `spa`.

- `component`: button, badge, tone-badge, band-badge, card, vital-tile, alert, empty-state, spinner, skeleton, avatar, form fields (label, hint, error tied by ids/ARIA), data-table, tab-nav, stat, list-row, pill-nav, breadcrumbs, search-field, user-menu, dialog, logo and more.
- `tone`: five tones; the eight bands mapped to them by severity, each with icon and word (**never colour alone**); a test checks the mapping against `ranges/severity`.
- `chart`: SVG as data. `line-chart` has a title, a description, a dashed/marked series, the normal band, named thresholds with labels that move apart (`dodge`), and a table of the same numbers; `nice-ticks` (the 1-2-5 rule). Drawn for a width (compact or wide).
- `layout`: `document` (no inline script or style), `skip-link`, `portal-shell` (top bar, bottom dock on a phone, 44 px targets), `workspace-shell` (sidebar → icon rail, 14 px type), and the Keycloak pieces (`login-card`, `login-frame`, `login-shell`).

## Shared view code: `libs/view` (`.cljc`)

`pulse.view.format` (times, numbers, units in the clinic's zone, Madrid), `views` (words of a reading and an appointment, `appointment-sections`, `next-appointment`), `charts`, `alerts` (rule titles, tones and words, held to `pulse.scheduler.rules` by a JVM-only test), `refusal` (the api's errors beside form fields), `jobs` (schedule and duration words).

`format` differs by platform only in **reading the calendar**: java.time on the JVM, `Intl.DateTimeFormat#formatToParts` (numbers only, `hourCycle: "h23"`) in the browser; all text is written by shared code from tables of English month and day names. A generated **parity table** (`pulse.view.parity-table`, 568 instants around every clock change 2024–2030 and more; 578 texts for `local->instant`) runs on both the JVM and Node and must agree (ADR-0029 point 2, CLAUDE.md libs/view). A date library for ClojureScript was rejected.

## Mock-ups are the served pages

- `bb design:build` renders the review pages to `design/dist` (git-ignored) from the components and the synthetic cohort at a fixed moment (14:00, Tuesday 13 October 2026). A mock-up is two documents: its frame (the interface itself) and the review page with an iframe of 390, 820 or 1280 px.
- Since Phase 7, the portal's mock-ups are calls of `pulse.web.pages.*/content`; since Phase 8, the workspace's are calls of `pulse.spa.pages.*/content`. **What is reviewed is what is served**, and tests hold the served page to the mock-up's.
- What a page offers is held to what the system can do: buttons from `appointment/available-actions`, limits from `measurement`, alert words from the rules; no editing of a profile, no "your care team has been told", no visits that "took place".
- The owner approved the style tile on 2026-10-16 and the 21 mock-ups on 2026-10-17 (24 after step 8 of Phase 8).
- `bb design:shots` screenshots the frames with headless Chrome over the DevTools protocol, no dependency.

## The Keycloak login theme

`pulse.design.keycloak` generates the theme `pulse` (parent `base`, which has markup and no stylesheet; not `keycloak`, whose PatternFly CSS would have to be fought) into `infra/keycloak/themes/pulse/login`: `template.ftl` and the five pages the realm uses (`login`, `error`, `info`, `logout-confirm`, `login-page-expired`), `messages_en.properties`, the icon, the fonts, and a 41 KB stylesheet. Templates are **FreeMarker made from hiccup** (`ftl`, `msg`, `when-ftl`, `either-ftl`). Tested four ways: committed files equal the generator's output; templates filled in by FreeMarker 2.3.32 (the version Keycloak 26.0.0 ships) configured as Keycloak does, read back by `pulse.ui.test.html` and checked by the a11y rules; the tools' login-form reader; and (`bb test:it`) the real Keycloak's pages and files byte for byte.

## Accessibility

WCAG 2.2 AA as the target: contrast by test; a visible focus ring from a token; 44 px targets in the portal; meaning never by colour alone; reduced motion. `pulse.ui.test.a11y/problems` checks what a program can see in hiccup (labels, names, svg, unique ids and references, table captions and scopes, heading levels, language, title, viewport, one h1, a main), with a test case for each rule. Since Phase 8 the workspace journey also runs axe-core in a real browser.

## Known limits

X-76 theme choice and high contrast, X-77 keyboard exploration of a chart, X-78 colour-blind simulation, X-86–X-88 more Keycloak pages and options, X-30 Spanish.
