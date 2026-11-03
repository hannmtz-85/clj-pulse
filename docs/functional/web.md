# The patient portal (`web`)

> What a patient can do in the portal, step by step, and what they see when something goes wrong. Technical detail: [../technical/web.md](../technical/web.md). Design record: [ADR-0028](../adr/0028-the-patient-portal-a-server-side-client-of-the-api-with-its-own-sign-in-and-a-token-keeper-for-each-session.md).

## Purpose and users

The portal is where a **patient** follows their own care: their latest readings and trends, recording a new reading, their appointments, what the clinic has on file about them, and their profile. Only people with the `patient` role are let in ([roles-and-permissions.md](roles-and-permissions.md)). It runs locally at <http://localhost:3300> (`CLAUDE.md`, "Commands").

The pages are made on the server and work with or without JavaScript ([README.md](../../README.md)). Every page has five places in its bar: **Home, Readings, Appointments, My record** and **Profile** (`pulse.web.pages/nav`). On a phone the bar is a dock at the bottom; buttons are at least 44 px high ([ADR-0026](../adr/0026-design-system-as-data-tokens-theme-components-and-contrast-by-test.md)).

## Signing in and out

1. A visitor opens any page of the portal and is sent to the Pulse sign-in page (Keycloak, in the Pulse theme).
2. After signing in they are brought back to the page they asked for.
3. A doctor or the administrator who signs in here is told **"This portal is for patients"**, with a link that signs them out; no session is created.
4. To sign out, the patient uses **Sign out** in the menu (or on the Profile page). The portal ends the session first and then signs the person out of Keycloak, and they land on a page that says they have signed out.

A session ends after **30 minutes without activity** or **10 hours** after the sign-in ([ADR-0028](../adr/0028-the-patient-portal-a-server-side-client-of-the-api-with-its-own-sign-in-and-a-token-keeper-for-each-session.md) point 2). The person's sign-in is renewed in the background while they use the portal.

## Home

What the patient sees (ADR-0028, update of 2026-10-22; `pulse.web.pages.home`):

- Who they are, their **next appointment** (the soonest confirmed one that has not begun, else the soonest request, else "No appointment coming up"), with a button to cancel it when that is allowed.
- **Latest readings**: one tile per measurement they have, each with its band (normal, elevated...) shown by colour, icon and word.
- **Recent readings**: the newest readings across measurements.
- Buttons to record a reading, see the readings, look at the record and ask for a visit.

A **new patient with no readings** (Noah Kim in the demo) sees a welcome and first steps instead.

## Readings

- **The trend** (`/readings`): pills to choose the measurement and the period (**7, 14 or 30 days**), a chart with the normal band and the thresholds marked, three figures, and a table of the last eight readings. A chart is drawn for a phone and one for a desk. If more than 500 readings matched, the page says it is showing the latest 500 (ADR-0028, update of 2026-10-23).
- **Recording a reading** (`/readings/new`):
  1. Choose what was measured (blood pressure, heart rate, SpO₂, glucose, weight or temperature).
  2. Type the value(s) and, optionally, when it was taken (on the clinic's clock; empty means now).
  3. Save. On success the patient is taken to the trend of that measurement and told once that it was saved.
  4. A value that cannot be real is refused with the reason **beside the field**, in the api's own words (for example a diastolic of 310). See the limits in [clinical-rules.md](clinical-rules.md).
  5. If the service failed **before** anything was sent, the page says the reading was not saved; if it failed **while** the reading was on its way, it says "We could not confirm that your reading was saved", and keeps what was typed.

## Appointments

From ADR-0028, update of 2026-10-24 (`pulse.web.appointments`, `pulse.web.visits`):

- **The list** shows three groups: **coming** (confirmed visits not yet begun, and requests), **earlier** (visits that have begun; the system does not know whether a visit took place, X-83) and **cancelled**. If there are more than the latest 100, the page says so.
- **Asking for a visit**: a reason and a first and a last day on which the patient could come. The request always goes to the patient's own doctor. A patient with no doctor on file has no form. A request that is sent is shown in the list.
- **Cancelling**: a question appears over the page, with an optional reason, **"Keep it"** (which has the focus) and a button to cancel. A request can be withdrawn at any time; a confirmed appointment only **until it begins**. If the appointment was already cancelled, has begun or changed meanwhile, the page says why and nothing is sent. The care team sees the cancellation in their list; nobody is notified (X-90). The question works without JavaScript.

## My record

Three cards ([ADR-0028](../adr/0028-the-patient-portal-a-server-side-client-of-the-api-with-its-own-sign-in-and-a-token-keeper-for-each-session.md), update of 2026-10-25): **Medication** (name, dose, "Since Nov 2025"), **Conditions** (name, state, since) and **Allergies** (name, reaction). No codes are shown. Nothing can be edited.

## Profile

"Who you are" (name, email, date of birth, patient number, city, each only when known), "Your care team" (the doctor and their specialty, or "We do not know who your doctor is. Tell your clinic.") and a Sign out button. Nothing can be edited, and the page says so.

## What the patient sees when something goes wrong

The portal's rule is that **something that could not be loaded is said, never shown as an empty list** ([ADR-0013](../adr/0013-patient-summary-fan-out.md); ADR-0028):

| Situation | What the patient sees |
|---|---|
| Part of a page could not be loaded | A message in its place with its own title and **Try again**, e.g. "Some of your readings could not be loaded"; the rest of the page is shown |
| A list of the record could not be loaded | A message under that list's heading, never an empty card |
| The address does not exist | "We cannot find that page" (404) |
| The api failed or was too slow | An error page (502, or 504 when too slow); details go to the log, not the page |
| The session ended (time-out, or a sign-in that is no longer valid) | "Your session has ended", with a button to sign in |
| Keycloak cannot be reached to renew the sign-in | "Sign-in is not available right now" (503); the session is kept |
| A sign-in link was used twice or is too old | "That sign-in has expired" |
| Any other sign-in failure | "We could not sign you in" |
| A doctor or administrator signs in here | "This portal is for patients" (403) |

## Limits

- English only (X-30); times are always in the clinic's zone, Madrid (X-97, X-104).
- At most the latest 500 readings of a measurement, over at most 30 days (X-102); the latest 100 appointments (X-106).
- No reminders, notifications, data export or profile editing (X-82, X-84).
- A reading cannot be corrected or withdrawn, and a double click can record it twice (X-25, X-101).
- A restart of the portal ends every session, since sessions are kept in memory (X-92).
- No theme switch; it follows the browser's light or dark preference (X-93).

## How it was accepted

`bb web:journey` walks a patient through the portal in a real browser against the real Keycloak: 62 checks, including that Ana sees nothing of another patient, a refused and a saved reading, a request sent and withdrawn with and without JavaScript, and a doctor being turned away ([02-implementation-plan.md](../02-implementation-plan.md), Phase 7 acceptance; `CLAUDE.md`).
