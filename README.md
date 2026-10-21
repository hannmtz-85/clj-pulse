# Pulse

Remote patient monitoring and appointment coordination on **FHIR R4**, written in **Clojure** and **ClojureScript**, with single sign-on through **Keycloak**.

A portfolio project: four applications of different kinds share one identity provider and one FHIR server.

> **Status: early development.** Done: the workstation, repository foundations, the local infrastructure (PostgreSQL, Keycloak 26.0.0 with the `pulse` realm and demo users, HAPI FHIR R4), the FHIR core library with a deterministic synthetic cohort, and the `api` (token verification, authorization by role and patient compartment, the clinical endpoints, OpenAPI with Swagger UI, CORS and security headers). The `scheduler` is done: it knows when a job is due and runs it, has a protected interface for its status that the clinic administrator reads through the api (which asks it with a service account of its own), and its jobs are the rules engine, which reads the readings through the api and raises each alert once in FHIR, a simulator of devices that sends readings, some of them abnormal, so that alerts appear while the apps run, and the appointment reminders, which leave one `Communication` in FHIR, once, for each confirmed appointment that is about to begin. The design system is done: the tokens (with the contrast of every pair of colours measured by a test), the components shared by the interfaces, a style tile with teal as its palette, and the mock-ups of the sign-in, the patient portal and the clinical workspace, each as a phone, a tablet or a desk shows it, in light and dark (`bb design:build`, then open `design/dist/index.html`). The sign-in of Keycloak already has that look: its login theme is generated from the same components and checked against FreeMarker and against the real Keycloak (open <http://localhost:4000/docs/> and Authorize). The patient portal (`web`) has begun: it runs beside the api, serves its own files, and a patient signs in through Keycloak with a flow of its own (PKCE, a session kept on the server, a keeper that renews the five-minute token, CSRF), sees who they are signed in as and signs out; the pages of a patient come next (`bb web`, then open <http://localhost:3300>); the clinical workspace (`spa`) is not written yet. Progress is tracked in [docs/02-implementation-plan.md](docs/02-implementation-plan.md).
>
> Try it (needs Docker, Babashka and the Clojure CLI): `bb doctor`, `bb up`, `bb realm:smoke`, `bb seed`, then `bb dev` and open <http://localhost:4000/docs/>: Authorize signs you in as a demo user and the same request is answered differently for a patient, a practitioner and the administrator. Within a minute the alerts of the simulated devices are in `GET /api/alerts` for Dr. Rivera, and the reminder of the appointment that Ana has tomorrow is in FHIR (<http://localhost:8090/fhir/Communication>). Signed in as the administrator, `GET /api/admin/jobs` shows what the scheduler is doing.

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
