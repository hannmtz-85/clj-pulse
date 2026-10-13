# Pulse

Remote patient monitoring and appointment coordination on **FHIR R4**, written in **Clojure** and **ClojureScript**, with single sign-on through **Keycloak**.

A portfolio project: four applications of different kinds share one identity provider and one FHIR server.

> **Status: early development.** The workstation, repository foundations and local infrastructure (PostgreSQL, Keycloak 26.0.0 with the `pulse` realm and demo users, HAPI FHIR R4) are done; the apps are not written yet. Progress is tracked in [docs/02-implementation-plan.md](docs/02-implementation-plan.md).
>
> Try the infrastructure (needs Docker, Babashka and the Clojure CLI): `bb doctor`, then `bb up` and `bb realm:smoke`.

## The four apps

| App | Kind | Who uses it | What it does |
|---|---|---|---|
| `api` | Resource server / FHIR gateway | The other apps | Validates tokens, enforces role and patient-compartment access, aggregates FHIR data |
| `web` | Server-rendered web app | Patients | Patient portal: summary, vitals trends, medication, appointments |
| `spa` | Single-page app (ClojureScript) | Clinicians | Patient panel, charts, alert inbox, schedule |
| `scheduler` | Background worker | Service account | Clinical rules, reminders and a device simulator |

## Planned stack

Clojure 1.12 · Ring, Reitit, malli, Integrant · Hiccup + htmx · ClojureScript, shadow-cljs, Reagent 2, re-frame · Keycloak 26.0.0 · HAPI FHIR (R4) on PostgreSQL · Babashka tasks · Tailwind CSS 4 + daisyUI 5 · GitHub Actions.

## Documentation

- [Stack review](docs/01-stack-review.md): what was kept, upgraded, replaced and why.
- [Implementation plan](docs/02-implementation-plan.md): concept, architecture and phases 0-10.
- [Extras backlog](docs/03-extras-backlog.md): everything deliberately left for later.

## Disclaimer

All data is synthetic. This is not a medical device, the clinical thresholds are didactic only, and it must not be used with real patient data.

## License

To be confirmed before the first public release (MIT is the current proposal).
