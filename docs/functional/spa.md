# The clinical workspace (`spa`)

> What practitioners and the administrator do in the workspace, step by step, and what each screen shows in each state. Technical detail: [../technical/spa.md](../technical/spa.md). Design record: [ADR-0029](../adr/0029-the-clinical-workspace-a-browser-client-of-the-api-with-keycloak-js-re-frame-and-the-pages-of-the-mock-ups.md).

## Purpose and users

The workspace is where the **care team** works: a panel of their patients in order of urgency, the chart of each patient, the inbox of alerts, and the appointments. The **administrator** sees every patient and, in addition, the status of the scheduler's jobs. Only `practitioner` and `clinic-admin` are let in ([roles-and-permissions.md](roles-and-permissions.md)). It runs in the browser at <http://localhost:3000> (`CLAUDE.md`).

The sidebar has the panel, the alerts (with a count of alerts that need a decision) and the appointments; the administrator also has an Administration group with **Jobs** (`pulse.spa.pages.shell`; ADR-0026). The workspace is designed for tablet (820 px) and desk (1280 px) widths; a phone layout is backlog item X-111.

**A doctor sees only their own patients because the api says so**: the workspace filters nothing itself ([ADR-0029](../adr/0029-the-clinical-workspace-a-browser-client-of-the-api-with-keycloak-js-re-frame-and-the-pages-of-the-mock-ups.md), update of 2026-10-29).

## Signing in and out

1. Opening the workspace sends the person to the Pulse sign-in page and back to the page they asked for.
2. A **patient** who signs in is told "This workspace is for the clinic", with a link to the patient portal; nothing is asked of the api.
3. If the sign-in service does not answer for 10 seconds, the waiting page says "This is taking longer than usual. The sign-in service has not answered yet." with **Try again**.
4. **Sign out** in the person's menu signs them out of Keycloak and lands on "You have signed out".
5. If the api keeps refusing the sign-in, the person sees "Your sign-in was not accepted" with **Sign in again**, instead of being sent round in a loop (the owner's choice of 2026-10-26).

## The panel (`/`)

From ADR-0029, update of 2026-10-28:

- A row per patient: name, the headline reading (the one furthest from normal) and its band, a 14-day trend, active alerts and the next visit. The name opens the patient's chart.
- **Order of urgency**, set **once** when everything has loaded, so rows do not jump while being read: worst latest reading first, then most active alerts, then by name.
- Figures at the top (one of them "Away from normal").
- **Filters** (all, with active alerts, away from normal, normal) and a **search** by name. Both live in the address, so a filtered panel can be bookmarked or shared, and the back button works. The counts on the filters appear only once every patient has loaded.
- The administrator sees all patients. A practitioner sees their panel. More than 100 patients: "Showing the first 100 of N patients" (X-120).

## The chart of a patient (`/patients/:id`)

From ADR-0029, update of 2026-10-29:

- Header: name, age, sex, patient number, city, and conditions.
- **Alerts**: one block per active alert, with its tone, when it was raised, the rule in words, and the latest readings behind it. Each has **Acknowledge** and a link to the inbox.
- **Latest readings** as tiles.
- **A chart** with pills to choose the measurement and the period (7, 14 or 30 days); both are in the address.
- Medication, allergies, and the patient's appointments, with buttons to confirm, reschedule or cancel (which open the dialogs of the appointments page).
- A patient outside the practitioner's panel, or one that does not exist, shows a page that says so, and none of that patient's data is shown.

## The inbox of alerts (`/alerts`)

From ADR-0029, update of 2026-10-31:

- Two lists: alerts that **need a decision** (active) and **acknowledged** ones, with a choice between them in the address.
- Each active alert shows its rule in words, the patient (a link to the chart) and the latest readings behind it, and an **Acknowledge** button.
- **Acknowledging**: the button stays busy from the click until the api has answered **and the page has been read again**, so a second click cannot send a second command. The workspace never marks an alert acknowledged by itself; the api decides. A screen reader is told what happened, for example "BP stage 2 · Ana García: Alert acknowledged."
  - If someone else acknowledged it first, it is shown as acknowledged.
  - If the answer did not come back, the page is read again to find out, and the person is told.
  - If it did not work, the focus goes back to that alert's button.
- **The list does not move on its own** (the owner's choice of 2026-10-31, following WCAG 2.2.2). Every 30 seconds, while the tab is visible, the workspace checks for changes. If the alerts have changed, it says **"The alerts have changed"** with a **Show the changes** button, so that an alert does not move under the person's hand. What the person did themselves is shown at once.
- The count in the sidebar is refreshed every 30 seconds and after each acknowledgement. If it could not be read it shows **?** ("Alerts, could not be read"), never a missing badge, which would mean no alerts.

## Appointments (`/appointments`)

From ADR-0029, update of 2026-10-31 (step 8):

- Tabs: All, **Requests**, **Booked**, **Cancelled**, each with its count, kept in the address.
- Each row has only the buttons the appointment's status allows for a clinician: **Confirm** (a request), **Reschedule** (a booked one), **Cancel** (a request or a booked one).
- Each button opens a **dialog over the list**, with its own address (`/appointments/<id>/confirm`, `/reschedule`, `/cancel`):
  1. **Confirm / Reschedule**: a day, a start and an end. A request's default is the first day of the patient's window that is not past (tomorrow at the earliest), 10:00–10:30.
  2. **Cancel**: an optional reason.
  3. The form is checked before sending ("Enter a date.", "Enter a time.", and the lifecycle's own rules); what the api refuses is shown **beside the field in the api's words**.
  4. The button is busy until the api has answered and the three lists have been read again. Then the dialog closes and the focus goes to the appointment's row.
  5. If the status changed meanwhile (someone else acted), the dialog becomes a message with the reason.
  6. Escape closes the dialog and returns the focus to the button that opened it.
- The list is read when the page is opened and is not refreshed by itself (X-132).

## Jobs (`/jobs`, administrator only)

- Three figures, an alert for each job whose last run failed (the type of error, how long it took and when the next run is), and a row per job: schedule, last run with its outcome, next run, number of runs.
- A practitioner who opens the address is told "You cannot open that page" and no call is made.
- The list is read once when the page is opened.

## What each page shows in each state

Every route is held by a test to an inventory of states (ADR-0029, update of 2026-11-02; `pulse.spa.test.states`): loading, empty, partly loaded, failed, gone, forbidden.

| State | What the person sees |
|---|---|
| Loading | Skeleton rows, and a screen-reader message ("Loading the patient") |
| Empty | Its own words, e.g. "No alerts" or "None in 14 days" |
| Part of the page failed | A message in its place ("Not loaded", "Some readings not loaded") with **Try again**; never "None" for something that failed |
| The patient is not there, or not theirs | A page that says so; nothing of the patient is shown |
| A page for another role | "You cannot open that page" |
| An address that is no page | "We cannot find that page" with **Go to the panel** |
| The workspace itself could not draw the page | "Something went wrong", with **Reload the page**, **Go to the panel** and **Sign out**, never a blank screen |
| Sign-in slow | "This is taking longer than usual..." with **Try again** |

## Accessibility

Checked by programs, not yet by a screen-reader user (X-140): the project's own rules on every state, axe-core in a real browser on every page and state, the keyboard on the panel, chart, inbox and appointments, four widths (320, 390, 820, 1280 px) in both schemes, reduced motion, and forced colours. The checks found and fixed a "skip to content" link that did nothing, among other defects (ADR-0029, update of 2026-11-02).

## Limits

- No phone layout (X-111), no theme switch (X-112), English only (X-30).
- The panel is as of the moment it was opened (X-121); appointments and jobs are not refreshed (X-132).
- The patient's name is not in the browser tab title (X-126).
- One alert acknowledged per click (X-128).
- Dialogs do not trap the keyboard focus (X-133).
- Clinicians cannot record a reading from the workspace (X-81).
- After a command started from a chart, the person stays on the appointments list (X-134).

## How it was accepted

`bb spa:journey`: a clinician signs in, filters the panel, opens a chart, acknowledges an alert and confirms an appointment, in a real browser against the real Keycloak and api, 225 checks, including that a doctor sees only their own patients, that no token is anywhere but the request header, and that axe-core finds nothing on any page or state ([02-implementation-plan.md](../02-implementation-plan.md), Phase 8 acceptance).
