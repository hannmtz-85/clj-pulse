// The acceptance of the clinical workspace in a real browser (Phase 8, ADR-0029): headless Chrome or Edge over the DevTools protocol
// (tools/cdp.mjs, no dependencies besides axe-core, a dev dependency of this directory), against the real Keycloak, the real api and the
// release of the workspace served with the policy of the real thing. `bb spa:journey` starts what it needs and runs it.
//
//   node apps/spa/journey.mjs [--spa http://localhost:3000] [--api http://localhost:4000] [--keycloak http://localhost:8080]
//                             [--portal http://localhost:3300] [--shots <directory>] [--only name,name]
//
// What it does, in the order of the sections:
//   practitioner    The acceptance of the phase: a clinician signs in through SSO, filters their panel, opens a chart with graphs,
//                   acknowledges an alert and confirms an appointment, and sees only what is theirs.
//   states          What the person is told when something is missing or goes wrong, with the api made to fail, refuse and stay
//                   silent: nothing is shown as empty that could not be loaded, and what can be tried again can be.
//   resilience      A sign-in that does not end says so; a page that cannot be drawn is a page and not a white one.
//   administrator   Everything of the clinic, and the jobs of the scheduler.
//   patient         A patient is told the workspace is not for them, and asks the api for nothing.
//   accessibility   Every page and state at four widths in both schemes: no scroll sideways, no target under 24 px, axe-core finds
//                   nothing; the keyboard reaches everything in order and shows where it is; reduced motion and forced colours.
//   policy          The controls: what the content security policy must block is blocked, so that "no violation" means something.
//   sign-out        Signing out through the identity provider to a page that signs nobody in, and the sign-in asks again.
//
// Needs the stack (`bb up`), the api of `bb dev lite` started with SCHEDULER_SIMULATOR=false and SCHEDULER_EVALUATE_EVERY_MS=5000 (the
// cohort does not move and the alerts are there in seconds) and FRESH (it acknowledges an alert and confirms a request, which the
// in-memory FHIR server keeps), and the release served on 3000. The password of the demo users is DEMO_USER_PASSWORD of `.env`. The
// browser is the variable CHROME, or the first of the usual places. The exit code is 1 when a check fails.
import { existsSync, mkdirSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { Browser, sleep } from "../../tools/cdp.mjs";

const args = process.argv.slice(2);
const option = (name, fallback) => {
  const i = args.indexOf(name);
  return i >= 0 ? args[i + 1] : fallback;
};
const spa = option("--spa", "http://localhost:3000");
const api = option("--api", "http://localhost:4000");
const keycloak = option("--keycloak", "http://localhost:8080");
const portal = option("--portal", "http://localhost:3300");
const shots = option("--shots");
const only = option("--only") ? option("--only").split(",") : null;
const repo = join(dirname(fileURLToPath(import.meta.url)), "..", "..");

function demoPassword() {
  if (process.env.DEMO_USER_PASSWORD) return process.env.DEMO_USER_PASSWORD;
  const file = join(repo, ".env");
  if (!existsSync(file)) throw new Error("No .env: run `bb up` (or `bb env:init`) first, or set DEMO_USER_PASSWORD");
  const line = readFileSync(file, "utf8").split(/\r?\n/).find((l) => l.startsWith("DEMO_USER_PASSWORD="));
  if (!line) throw new Error("DEMO_USER_PASSWORD is not in .env");
  return line.slice("DEMO_USER_PASSWORD=".length);
}
const password = demoPassword();
const axeSource = readFileSync(join(repo, "apps", "spa", "node_modules", "axe-core", "axe.min.js"), "utf8");
if (shots) mkdirSync(shots, { recursive: true });

const results = [];
const check = (label, ok, detail = "") => {
  results.push(Boolean(ok));
  console.log(`${ok ? "PASS" : "FAIL"}  ${label}${!ok && detail ? "  -- " + detail : ""}`);
};
const skip = (label, why) => console.log(`SKIP  ${label}  -- ${why}`);
const heading = (title) => console.log(`\n== ${title}`);
const squash = (s) => String(s ?? "").replace(/\s+/g, " ").trim();
const json = (value) => JSON.stringify(value);

let browser;

/** A page for one person, in a browser context of its own, with the api and the token endpoint open to being answered by a check. */
const newPage = (options = {}) => browser.newPage({
  origin: spa,
  axe: axeSource,
  intercept: [`${api}/api/*`, `${keycloak}/realms/pulse/protocol/openid-connect/token*`],
  ...options,
});

// ---------------------------------------------------------------------------------------------------------------- what a person does

const onLoginPage = async (page) => (await page.here()).startsWith(keycloak + "/") && await page.evaluate("!!document.getElementById('kc-form-login')");

/** Signs in through the login page of the Pulse theme. `expect` is text the page must say when the person is back. */
async function signIn(page, username, { path = "/", expect, label } = {}) {
  await page.go(spa + path);
  const asked = await page.waitFor(() => onLoginPage(page), 30000);
  check(`${label}: a visitor is sent to the login page of Keycloak`, asked, (await page.here()).slice(0, 100));
  if (!asked) return false;
  await page.evaluate(`document.getElementById('username').value=${json(username)}; document.getElementById('password').value=${json(password)}; document.getElementById('kc-form-login').submit(); true`);
  const back = await page.waitFor(async () => (await page.here()).startsWith(spa + "/") && (expect ? (await page.text()).includes(expect) : true), 30000);
  check(`${label}: back in the workspace, which says "${expect ?? "something"}"`, back, squash(await page.text()).slice(0, 160));
  return Boolean(back);
}

const click = (page, selector) => page.evaluate(`(() => { const e = document.querySelector(${json(selector)}); if (!e) throw new Error('nothing to click: ' + ${json(selector)}); e.click(); return true; })()`);
const clickText = (page, text, within = "main") => page.evaluate(`(() => {
  const e = [...document.querySelectorAll(${json(within)} + ' a, ' + ${json(within)} + ' button')].find((x) => x.textContent.trim() === ${json(text)});
  if (!e) throw new Error('nothing to click: ' + ${json(text)});
  e.click(); return true; })()`);
const clickSidebar = (page, title) => page.evaluate(`(() => {
  const e = [...document.querySelectorAll('aside nav a')].find((a) => a.getAttribute('title') === ${json(title)} || a.textContent.trim().startsWith(${json(title)}));
  if (!e) throw new Error('no item of the sidebar: ' + ${json(title)});
  e.click(); return true; })()`);
const setValue = (page, id, value) => page.evaluate(`(() => {
  const e = document.getElementById(${json(id)});
  Object.getOwnPropertyDescriptor(e.constructor.prototype, 'value').set.call(e, ${json(value)});
  e.dispatchEvent(new Event('input', { bubbles: true })); return true; })()`);

const live = (page) => page.evaluate("(document.querySelector('main [role=status]') || {}).textContent || ''");
const h1s = (page) => page.evaluate("[...document.querySelectorAll('h1')].map((h) => h.textContent.trim())");
const tabTitle = (page) => page.evaluate("document.title");
const active = (page) => page.evaluate("document.activeElement ? (document.activeElement.id || document.activeElement.tagName) : null");
const patientNames = (page) => page.evaluate("[...document.querySelectorAll('main a[href^=\"/patients/\"]')].map((a) => (a.querySelector('.font-medium') || a).textContent.trim())");

/** Records what the live region of the page says from now on, each change as a screen reader hears it; `said` gives what was recorded. */
const recordLive = (page) => page.evaluate(`(() => {
  window.__said = [];
  const note = () => { const r = document.querySelector('main [role=status]'); const t = r ? r.textContent.trim() : ''; if (t && window.__said[window.__said.length - 1] !== t) window.__said.push(t); };
  note();
  if (window.__observer) window.__observer.disconnect();
  window.__observer = new MutationObserver(note);
  window.__observer.observe(document.querySelector('main'), { subtree: true, childList: true, characterData: true });
  return true; })()`);
const said = (page) => page.evaluate("window.__said || []");
const alertCount = (page) => page.evaluate("(() => { const a = document.querySelector('aside nav a[href=\"/alerts\"]'); return a ? a.getAttribute('aria-label') : null; })()");
const apiPaths = (page) => page.callsTo(api).map((r) => r.request.url.slice(api.length));
const apiCalls = (page, method) => page.callsTo(api).filter((r) => r.request.method === method);
const notFailedToLoad = (lines) => lines.filter((l) => !/Failed to load resource|net::ERR_/.test(l));

/** What every page must be of a person who is signed in: clean of console, of violations of the policy and of tokens. */
async function clean(page, label) {
  const violations = await page.violations();
  check(`${label}: no violation of the policy`, violations.length === 0, json(violations));
  const problems = notFailedToLoad(page.consoleProblems());
  check(`${label}: nothing in the console`, problems.length === 0, json(problems).slice(0, 400));
}

/** The places a token must never be, and the only place it goes. */
async function noTokenAnywhere(page, label) {
  const storage = await page.storage();
  check(`${label}: no token in either storage, a cookie it can read or a database`, !/eyJ/.test(storage), storage.slice(0, 160));
  const spoken = json(page.events.filter((e) => e.method === "Runtime.consoleAPICalled")) + (await page.text());
  check(`${label}: no token in the console or in the text of the page`, !/eyJ/.test(spoken));
  const bearers = page.events.filter((e) => e.method === "Network.requestWillBeSent"
    && /^Bearer /i.test(e.params.request.headers.Authorization || e.params.request.headers.authorization || ""));
  check(`${label}: a token goes to the api and to nobody else`, bearers.length > 0 && bearers.every((e) => e.params.request.url.startsWith(api + "/")),
    bearers.map((e) => e.params.request.url).join(" ").slice(0, 160));
  check(`${label}: the address has no code, state or token left`, !/[#?&](code|state|session_state|access_token)=/.test(await page.here()), await page.here());
}

const smallTargets = (page, selector = "a, button, input, summary, select") => page.evaluate(`[...document.querySelectorAll(${json(selector)})].filter((e) => {
  const r = e.getBoundingClientRect(); const s = getComputedStyle(e);
  return r.width > 0 && r.height > 0 && s.visibility !== 'hidden' && (r.height < 24 || r.width < 24) && !e.closest('.sr-only') && !e.matches('.sr-only');
}).map((e) => (e.textContent || e.getAttribute('aria-label') || e.tagName).trim().slice(0, 40) + ' ' + Math.round(e.getBoundingClientRect().width) + 'x' + Math.round(e.getBoundingClientRect().height))`);
const sidewaysScroll = (page) => page.evaluate("document.documentElement.scrollWidth > document.documentElement.clientWidth");

// ---------------------------------------------------------------------------------------------------------------- practitioner

/** The people and the things of the cohort that the journey looks for. */
const rivera = "dr.rivera@pulse.test";
const panelOf = ["Ana García", "Sofía Rossi", "Liam Chen", "Omar Haddad"];
const notHers = /Maya|Noah/;

async function practitioner() {
  heading("practitioner: the acceptance of the phase");
  const page = await newPage();
  try {
    // ---- SSO
    if (!await signIn(page, rivera, { expect: "Your panel", label: "Dr. Rivera" })) return;
    await page.waitFor(async () => (await patientNames(page)).length >= 4, 30000);
    check("the tab says where the person is and the page has one heading and one main",
      (await tabTitle(page)) === "Panel · Pulse" && json(await h1s(page)) === json(["Your panel"]) && (await page.evaluate("document.querySelectorAll('main').length")) === 1,
      `${await tabTitle(page)} ${json(await h1s(page))}`);
    check("the first thing the keyboard reaches is the link that skips to the content",
      await (async () => { await page.evaluate("document.activeElement && document.activeElement.blur(); true"); await page.key("Tab"); return (await page.evaluate("document.activeElement.textContent")) === "Skip to the content"; })());
    const sidebar = await page.evaluate("[...document.querySelectorAll('aside nav a')].map((a) => a.getAttribute('title') || a.textContent.trim())");
    check("the sidebar of a practitioner has the panel, the alerts and the appointments, and not the jobs", json(sidebar) === json(["Panel", "Alerts", "Appointments"]), json(sidebar));
    check("the person in the sidebar is who the api says they are", /Elena Rivera/.test(await page.evaluate("document.querySelector('aside').innerText")) && /Internal medicine/.test(await page.evaluate("document.querySelector('aside').innerText")));
    const names = await patientNames(page);
    check("the panel is the four patients of Dr. Rivera and nobody else's", json([...names].sort()) === json([...panelOf].sort()) && !notHers.test(await page.text()), json(names));
    // The panel is put in order of urgency once, when it has settled; until then its rows are in the order of the list, by family name
    // (Chen, García, Haddad, Rossi), and the statuses of the rows are already drawn. A runner of GitHub showed that twice, when this read
    // the page as soon as the four names were on it. So the order is read from the page, from the status of each row, once it is the one
    // that must hold whatever the readings are: the worst first, none after one that is better, and the first has something wrong.
    const severity = { Normal: 0, Low: 1, Elevated: 1, "Very low": 2, High: 2, Fever: 2, "Very high": 3, "High fever": 3 };
    const statusOfRows = () => page.evaluate(`[...document.querySelectorAll('main table tbody tr')].map((tr) => ({
      name: ((tr.querySelector('th .font-medium') || tr.querySelector('th') || {}).textContent || '').trim(),
      status: ((tr.querySelector('td') || {}).textContent || '').trim() }))`);
    const inOrderOfUrgency = (rows) => {
      const worse = rows.map((r) => severity[r.status]);
      return rows.length === names.length && worse.every((s) => s !== undefined) && worse.every((s, i) => i === 0 || worse[i - 1] >= s) && worse[0] > 0;
    };
    let rows = await statusOfRows();
    // the panel gives up on what has not come after 10 s (the deadline of the panel): a little more than that is all there is to wait for
    await page.waitFor(async () => { rows = await statusOfRows(); return inOrderOfUrgency(rows); }, 15000);
    check("the panel is in order of urgency: the worst status first and the best last, and the first has something wrong", inOrderOfUrgency(rows), json(rows));
    await clean(page, "the panel");
    await noTokenAnywhere(page, "the panel");

    // ---- the calls of the panel: six at a time at most
    await clickSidebar(page, "Alerts");
    await page.waitFor(async () => /Needs a decision/.test(await page.text()), 20000);
    page.clearEvents();
    await clickSidebar(page, "Panel");
    await page.waitFor(async () => (await patientNames(page)).length >= 4 && /Active alerts/.test(await page.text()), 30000);
    await sleep(1500);
    // the figure of the alerts and the beat of the navigation ask the same address, so what is counted is the fan-out over the patients
    const fanOut = page.mostAtOnce(api, /\/api\/(me|alerts|appointments)/);
    check("the fan-out over the patients never has more than six calls on their way at once", fanOut <= 6, String(fanOut));
    check("it asks for the summary of each patient and the series of what is worst",
      apiPaths(page).filter((p) => /\/summary/.test(p)).length === 4 && apiPaths(page).some((p) => /\/observations\?/.test(p)), json(apiPaths(page)).slice(0, 400));

    // ---- a filter loads nothing, and is in the address
    page.clearEvents();
    await page.evaluate("[...document.querySelectorAll('nav[aria-label=\"Filter the panel\"] a')].find((a) => a.textContent.startsWith('With active alerts')).click(); true");
    await sleep(500);
    const narrowed = await patientNames(page);
    check("the filter is in the address, narrows the list and loads nothing", (await page.path()) === "/?show=alerts" && narrowed.length >= 1 && narrowed.length < 4
      && apiPaths(page).filter((p) => !/status=active&limit=1/.test(p)).length === 0, `${await page.path()} ${json(narrowed)} ${json(apiPaths(page))}`);
    await page.evaluate("history.back(); true");
    await sleep(500);
    check("the back button returns to the whole panel", (await page.path()) === "/" && (await patientNames(page)).length === 4, await page.path());
    await setValue(page, "search", "ana");
    await sleep(400);
    check("the search is in the address and finds one patient", (await page.path()) === "/?q=ana" && json(await patientNames(page)) === json(["Ana García"]), `${await page.path()} ${json(await patientNames(page))}`);
    await setValue(page, "search", "");
    await sleep(300);

    // ---- the alerts are there (the rules run every five seconds), and the inbox says what they are
    await clickSidebar(page, "Alerts");
    const raised = await page.waitFor(async () => {
      await page.go(spa + "/alerts");
      await page.waitFor(async () => /Needs a decision/.test(await page.text()), 15000);
      return /BP stage 2 · Ana García/.test(await page.text());
    }, 90000);
    check("the rules raised the alert of Ana's blood pressure, and the inbox says what it is", raised, squash(await page.text()).slice(0, 200));
    if (!raised) return;
    const before = await alertCount(page);
    check("the count in the navigation is the number of alerts that need a decision", /^Alerts \d+ active$/.test(before ?? ""), String(before));
    const inbox = await page.evaluate("document.querySelector('main').innerText");
    check("the alert says when it was raised, the rule in its own words and the readings behind it", /Raised /.test(inbox) && /Blood pressure at stage 2/.test(inbox) && /The readings behind it/.test(inbox), squash(inbox).slice(0, 300));
    await clean(page, "the inbox");

    // ---- a chart, with its graph
    await page.go(spa + "/patients/pt-ana");
    const chart = await page.waitFor(() => page.evaluate("document.querySelectorAll('main svg[role=\"img\"]').length > 0 && /Ana García/.test(document.querySelector('h1').textContent)"), 30000);
    check("the chart of Ana is her name, her alert and a graph of her blood pressure", chart && /BP stage 2/.test(await page.text()), squash(await page.text()).slice(0, 200));
    check("the graph can be seen, and has a table with the same numbers",
      (await page.evaluate("[...document.querySelectorAll('main figure svg[role=\"img\"]')].some((s) => s.getBoundingClientRect().width > 100)"))
      && (await page.evaluate("document.querySelectorAll('main figure details table').length")) >= 1);
    check("nothing of another patient is on her chart", !/Liam|Sofía|Omar|Maya|Noah/.test(await page.evaluate("document.querySelector('main').innerText")));
    page.clearEvents();
    await page.evaluate("[...document.querySelectorAll('nav[aria-label=\"Period\"] a')].find((a) => a.textContent === '30 days').click(); true");
    await page.waitFor(async () => /last 30 days|Last 30 days/.test(await page.text()), 15000);
    check("a period is in the address and asks for the series again, and for nothing else", (await page.path()).includes("days=30")
      && apiPaths(page).filter((p) => /observations/.test(p)).length === 1 && apiPaths(page).every((p) => /observations|alerts\?status=active&limit=1/.test(p)), `${await page.path()} ${json(apiPaths(page))}`);
    await clean(page, "the chart");

    // ---- acknowledging an alert
    const ackId = await page.evaluate("(document.querySelector('button[id^=\"ack-\"]') || {}).id");
    check("the alert has a button that acknowledges it, which says which alert it is for", Boolean(ackId)
      && /^Acknowledge BP stage 2 · Ana García$/.test(await page.evaluate(`document.getElementById(${json(ackId)}).getAttribute('aria-label')`)), String(ackId));
    page.clearEvents();
    await recordLive(page);
    await click(page, `#${ackId}`);
    const busy = await page.waitFor(() => page.evaluate(`(() => { const b = document.getElementById(${json(ackId)}); return !b || b.disabled || b.getAttribute('aria-busy') === 'true'; })()`), 3000);
    check("the button is busy from the click, so a second click is not a second command", Boolean(busy));
    const gone = await page.waitFor(async () => (await page.evaluate("document.querySelectorAll('main [id^=\"alert-\"]').length")) === 0, 15000);
    check("the alert is gone from the chart once the api has answered and the page has been read again", Boolean(gone), squash(await page.text()).slice(0, 200));
    check("exactly one command was sent, and the summary was read again", apiCalls(page, "POST").length === 1 && /\/api\/alerts\/[^/]+\/ack$/.test(apiPaths(page).find((p) => /\/ack$/.test(p)) ?? "") && apiPaths(page).some((p) => /\/summary/.test(p)), json(apiPaths(page)));
    check("a screen reader is told what happened, with the name of the alert", (await said(page)).some((t) => /^BP stage 2 · Ana García: Alert acknowledged./.test(t)), json(await said(page)));
    const after = await page.waitFor(async () => { const a = await alertCount(page); return a !== before ? a : null; }, 8000);
    check("and the count in the navigation went down", Boolean(after), `${before} -> ${after}`);
    await clean(page, "after acknowledging");

    // ---- the inbox has it as acknowledged
    await clickSidebar(page, "Alerts");
    // the heading is there before the list, which is grey shapes until it has come: wait for the words of a row
    await page.waitFor(() => page.evaluate("[...document.querySelectorAll('ul[aria-label=\"Acknowledged alerts\"] li')].some((l) => l.innerText.trim().length > 0)"), 15000);
    const done = await page.evaluate("[...document.querySelectorAll('ul[aria-label=\"Acknowledged alerts\"] li')].map((l) => l.innerText.replace(/\\s+/g, ' ').trim())");
    check("the alert is among those that were acknowledged", done.some((l) => /BP stage 2 · Ana García/.test(l) && /Acknowledged/.test(l)), json(done));

    // ---- appointments: confirm a request
    await clickSidebar(page, "Appointments");
    await page.waitFor(async () => /Requests to answer/.test(await page.text()) && /Sofía Rossi/.test(await page.text()), 20000);
    const requests = await page.evaluate("[...document.querySelectorAll('main section[aria-labelledby=requests] li')].map((l) => l.innerText.replace(/\\s+/g, ' ').trim())");
    check("the request of Sofía waits for an answer, with the window she asked for", requests.length === 1 && /COPD/.test(requests[0]) && /Asked for a visit between/.test(requests[0]), json(requests));
    const confirmId = await page.evaluate("(document.querySelector('main section[aria-labelledby=requests] a.btn[id^=\"confirm-\"]') || {}).id");
    await click(page, `#${confirmId}`);
    const opened = await page.waitFor(() => page.evaluate("!!document.querySelector('dialog[open]')"), 10000);
    check("Confirm opens a dialog over the list, with an address of its own and the cursor in its first field",
      opened && (await page.path()).endsWith("/confirm") && (await page.waitFor(async () => (await active(page)) === "day", 5000)), `${await page.path()} ${await active(page)}`);
    check("the dialog says what it is about, and proposes a day that is not past, from ten to half past ten",
      /COPD check-up/.test(await page.evaluate("document.querySelector('dialog').innerText")) && (await page.evaluate("document.getElementById('from').value")) === "10:00" && (await page.evaluate("document.getElementById('until').value")) === "10:30");
    page.clearEvents();
    await recordLive(page);
    await click(page, "#appointment-submit");
    const closed = await page.waitFor(async () => !(await page.evaluate("!!document.querySelector('dialog[open]')")) && (await page.path()) === "/appointments", 20000);
    check("the dialog closes when the api has answered and the lists have been read again", Boolean(closed), await page.path());
    const patch = apiCalls(page, "PATCH");
    check("one command was sent, to confirm, with a start and an end", patch.length === 1 && /"action":"confirm"/.test(patch[0].request.postData ?? "") && /"start":"20\d\d-/.test(patch[0].request.postData ?? ""), patch.map((r) => r.request.postData).join(" "));
    const booked = await page.evaluate("[...document.querySelectorAll('main section[aria-labelledby=booked] li')].map((l) => l.innerText.replace(/\\s+/g, ' ').trim())");
    check("the appointment is among the booked ones, confirmed, and no request waits any more", booked.some((l) => /COPD/.test(l) && /Confirmed/.test(l))
      && /No request is waiting for an answer\./.test(await page.text()), json(booked));
    check("a screen reader is told what happened, with the name of the appointment", Boolean(await page.waitFor(async () => (await said(page)).some((t) => /Appointment confirmed for/.test(t)), 5000)), json(await said(page)));
    await clean(page, "after confirming");
    await noTokenAnywhere(page, "the whole walk");
    check("nobody else's name was in any page of the walk", !notHers.test(await page.text()));
  } finally {
    await page.close();
  }
}

// ---------------------------------------------------------------------------------------------------------------- states

const panelReady = (page) => page.waitFor(async () => (await patientNames(page)).length >= 4, 30000);

async function states() {
  heading("states: what the person is told when something is missing or goes wrong");
  const page = await newPage();
  try {
    if (!await signIn(page, rivera, { expect: "Your panel", label: "Dr. Rivera, for the states" })) return;
    await panelReady(page);
    const visibleText = (p) => p.evaluate("document.querySelector('main').innerText");
    const has = (text) => async () => text.test(await page.evaluate("document.querySelector('main').innerText"));

    // ---- the list of patients fails: the panel says so, and is not empty
    page.rules = [{ test: /\/api\/patients\?/, action: "fail" }];
    page.clearEvents();
    await page.go(spa + "/");
    const failed = await page.waitFor(has(/Your panel could not be loaded/), 30000);
    const text = await visibleText(page);
    check("when the list of patients fails the panel says so, and says what else is up to date", failed && /The problem is on our side, not yours\./.test(text), squash(text).slice(0, 200));
    check("it is never shown as a panel with nobody in it", !/You have no patients yet|No patients match/.test(text));
    check("it has the button to try again, and nothing is listed", await page.evaluate("[...document.querySelectorAll('main button')].some((b) => b.textContent.trim() === 'Try again')") && (await patientNames(page)).length === 0);
    check("the tab and the page say where they are", (await tabTitle(page)) === "Panel · Pulse" && json(await h1s(page)) === json(["Your panel"]));
    await axeHere(page, "the panel that could not be loaded");
    page.rules = [];
    await clickText(page, "Try again");
    check("trying again loads it", Boolean(await page.waitFor(async () => (await patientNames(page)).length >= 4 && !/could not be loaded/.test(await visibleText(page)), 30000)));

    // ---- the summaries fail: the patients are there, and what is missing is said in its own place
    page.rules = [{ test: /\/api\/patients\/[^/]+\/summary/, action: "fail" }];
    await page.go(spa + "/");
    const partial = await page.waitFor(has(/4 patients could not be loaded/), 30000);
    check("when the summaries fail the patients are listed and the panel says how many could not be loaded", partial && (await patientNames(page)).length === 4, squash(await visibleText(page)).slice(0, 200));
    check("each row says what it could not show", (await page.evaluate("[...document.querySelectorAll('main table tbody tr')].filter((r) => /Not loaded/.test(r.innerText)).length")) >= 4);
    await axeHere(page, "the panel with patients that could not be loaded");
    page.rules = [];
    await clickText(page, "Try again");
    check("and trying again fills the rows", Boolean(await page.waitFor(async () => !/could not be loaded/.test(await visibleText(page)), 30000)));

    // ---- the inbox fails
    page.rules = [{ test: /\/api\/alerts\?/, action: "fail" }];
    await page.go(spa + "/alerts");
    const lost = await page.waitFor(has(/The alerts could not be loaded/), 30000);
    const inbox = await visibleText(page);
    check("when the alerts cannot be loaded the inbox says so for both lists and is not an empty inbox", lost && /The acknowledged alerts could not be loaded/.test(inbox) && !/No alerts|Nothing has been acknowledged yet/.test(inbox), squash(inbox).slice(0, 200));
    check("and the count of the navigation says it could not be read, and does not go away", await page.waitFor(async () => /could not be read/.test((await alertCount(page)) ?? ""), 5000), String(await alertCount(page)));
    await axeHere(page, "the inbox that could not be loaded");
    page.rules = [];
    await clickText(page, "Try again");
    check("trying again reads them", Boolean(await page.waitFor(async () => /Needs a decision/.test(await visibleText(page)) && !/could not be loaded/.test(await visibleText(page)), 30000)));

    // ---- a list of the appointments fails
    page.rules = [{ test: /\/api\/appointments\?.*status=booked/, action: "fail" }];
    await page.go(spa + "/appointments");
    const booked = await page.waitFor(has(/The booked appointments could not be loaded/), 30000);
    const list = await visibleText(page);
    check("when one list of appointments fails the others are there and it is not 'Nothing is booked'", booked && /Requests to answer/.test(list) && !/Nothing is booked\./.test(list), squash(list).slice(0, 200));
    await axeHere(page, "the appointments with a list that could not be loaded");
    page.rules = [];

    // ---- a patient who is not hers, and one who does not exist: the api says no, and the page shows nothing of them
    page.clearEvents();
    await page.go(spa + "/patients/pt-noah");
    const refused = await page.waitFor(has(/We cannot find that patient/), 30000);
    const gone = await visibleText(page);
    check("the chart of a patient of another doctor is a page that says so, with the way back", refused && /They are not in your panel, or there is no such patient\./.test(gone) && /Go to the panel/.test(gone), squash(gone).slice(0, 200));
    check("nothing of that patient is shown, and the series was not even asked for", !/Noah|Kim/.test(await page.text()) && apiPaths(page).every((p) => !/observations/.test(p)), json(apiPaths(page)));
    check("it is a heading and a title, like any page", json(await h1s(page)) === json(["We cannot find that patient"]));
    await page.go(spa + "/patients/pt-nobody");
    check("and so is the chart of a patient who does not exist", Boolean(await page.waitFor(has(/We cannot find that patient/), 30000)));

    // ---- an address that is no page, and a page that is not for the person
    await page.go(spa + "/nothing-here");
    const notFound = await page.waitFor(has(/We cannot find that page/), 20000);
    check("an address that is no page says so, inside the workspace, with the way back", notFound && (await tabTitle(page)) === "Page not found · Pulse" && /Go to the panel/.test(await visibleText(page)));
    page.clearEvents();
    await page.go(spa + "/jobs");
    const notAllowed = await page.waitFor(has(/You cannot open that page/), 20000);
    check("the jobs are for the administrator: a practitioner is told so and no call is made for them", notAllowed && apiPaths(page).every((p) => !/admin\/jobs/.test(p)), json(apiPaths(page)));
    await clickText(page, "Go to the panel");
    check("and the button leads back to the panel", Boolean(await page.waitFor(async () => (await page.path()) === "/" && /Your panel/.test(await page.text()), 20000)));

    // ---- an api that does not answer: the panel waits, and then says so
    page.rules = [{ test: /\/api\/patients\?/, action: "hang" }];
    await page.go(spa + "/");
    const waiting = await page.waitFor(async () => /Loading your panel/.test(await live(page)), 15000);
    check("while the list has not come the panel is on its way, and not empty", waiting && !/You have no patients yet/.test(await visibleText(page)));
    await axeHere(page, "the panel on its way");
    const gaveUp = await page.waitFor(has(/Your panel could not be loaded/), 20000);
    check("a call that never answers gives up, and the panel says so", Boolean(gaveUp), squash(await visibleText(page)).slice(0, 160));
    page.rules = [];

    // ---- the api refuses the token: the page says so once, and does not send the person round and round
    page.rules = [{ test: /\/api\/me$/, action: "status", status: 401, problem: { title: "Unauthorized", status: 401 } }, { test: /\/api\/patients\?/, action: "status", status: 401, problem: { title: "Unauthorized", status: 401 } }];
    page.clearEvents();
    await page.go(spa + "/");
    const refusedToken = await page.waitFor(async () => /Your sign-in was not accepted/.test(await page.text()), 45000);
    check("an api that does not accept the sign-in even after it was renewed is a page with a link, and not a loop", refusedToken && (await page.here()).startsWith(spa + "/") && /Sign in again/.test(await page.text()), (await page.here()).slice(0, 80));
    page.rules = [];
  } finally {
    await page.close();
  }
}

// ---------------------------------------------------------------------------------------------------------------- resilience

/** Runs one scenario of a section, and says so when it stopped before its end, so that what follows it is still looked at. */
async function scenario(name, run) {
  try {
    await run();
  } catch (e) {
    check(`${name}: the scenario ran to its end`, false, e.stack?.split("\n").slice(0, 3).join(" ") ?? e.message);
  }
}

/** The identity provider does not answer after the person has signed in. */
async function aSignInThatDoesNotEnd() {
  const slow = await newPage();
  try {
    await slow.go(spa + "/");
    await slow.waitFor(() => onLoginPage(slow), 30000);
    slow.rules = [{ test: /\/protocol\/openid-connect\/token/, action: "hang" }];
    await slow.evaluate(`document.getElementById('username').value=${json(rivera)}; document.getElementById('password').value=${json(password)}; document.getElementById('kc-form-login').submit(); true`);
    await slow.waitFor(async () => (await slow.here()).startsWith(spa + "/"), 30000);
    await sleep(1500);
    check("while the identity provider has not answered the page says that it is signing in", /Signing in/.test(await slow.text()) && !/taking longer/.test(await slow.text()), squash(await slow.text()).slice(0, 120));
    const longer = await slow.waitFor(async () => /This is taking longer than usual/.test(await slow.text()), 20000);
    check("a wait that does not end says so, and offers to try again", longer && /The sign-in service has not answered yet\./.test(await slow.text()) && await slow.evaluate("[...document.querySelectorAll('button')].some((b) => b.textContent.trim() === 'Try again')"), squash(await slow.text()).slice(0, 160));
    check("a screen reader is told too, in the region that already said it was waiting", /taking longer than usual/.test(await slow.evaluate("[...document.querySelectorAll('[role=status]')].map((e) => e.textContent).join(' ')")));
    check("it has one heading", json(await h1s(slow)) === json(["Signing in"]));
    await axeHere(slow, "the wait that does not end");
    slow.rules = [];
    await clickText(slow, "Try again", "body");
    const worked = await slow.waitFor(async () => (await slow.here()).startsWith(spa + "/") && /Your panel/.test(await slow.text()), 30000);
    check("and trying again, with the identity provider back, signs the person in", Boolean(worked), squash(await slow.text()).slice(0, 120));
  } finally {
    await slow.close();
  }
}

/** A fault in drawing a page: the browser's own formatter of dates fails, which every page that writes a time meets. */
async function aPageThatCannotBeDrawn() {
  const page = await newPage();
  try {
    if (!await signIn(page, rivera, { expect: "Your panel", label: "Dr. Rivera, for the fault" })) return;
    await panelReady(page);
    page.clearEvents();
    await page.evaluate("window.__realFormat = Intl.DateTimeFormat.prototype.formatToParts; Intl.DateTimeFormat.prototype.formatToParts = function () { throw new Error('a fault put here by the journey'); }; true");
    // the next time the page is drawn is when it fails: the person's own click, or the page's own (the beat of the navigation draws it too)
    let crashed = await page.waitFor(async () => /Something went wrong/.test(await page.text()), 3000);
    if (!crashed) {
      // the page may be white already, if nothing catches the fault: the check below says so
      try { await clickSidebar(page, "Alerts"); } catch { /* nothing to click */ }
      crashed = await page.waitFor(async () => /Something went wrong/.test(await page.text()), 8000);
    }
    const body = await page.text();
    check("when a page cannot be drawn the person is told so, and the page is not white", crashed && body.trim().length > 40 && /The workspace could not show this page\./.test(body),
      body.trim().length === 0 ? "the page is white" : squash(body).slice(0, 160));
    check("the page has the ways out: to load it again, to go to the panel and to sign out", ["Reload the page", "Go to the panel", "Sign out"].every((t) => body.includes(t)));
    check("it is a heading, a title and a main like any page, and the cause is in the console", json(await h1s(page)) === json(["Something went wrong"])
      && page.consoleProblems().some((l) => /The workspace could not draw a page/.test(l)), json(page.consoleProblems().map((l) => l.slice(0, 80))));
    check("nothing in the page says what the cause was", !/a fault put here/.test(body));
    await axeHere(page, "the page that cannot be drawn");
    await page.evaluate("Intl.DateTimeFormat.prototype.formatToParts = window.__realFormat; true");
    await clickText(page, "Reload the page", "body");
    // the page that is loaded again is the one the person was on: the panel, or the inbox when the fault was found by a click on Alerts
    // (the page itself draws again within seconds, but a runner may not wait for it)
    const back = await page.waitFor(async () => !/Something went wrong/.test(await page.text())
      && await page.evaluate("Boolean(document.querySelector('aside nav')) && document.querySelectorAll('main h1').length === 1"), 30000);
    check("and loading it again, with the fault gone, is the workspace", Boolean(back), `${await page.path()} ${squash(await page.text()).slice(0, 160)}`);
  } finally {
    await page.close();
  }
}

async function resilience() {
  heading("resilience: a wait that does not end, and a page that cannot be drawn");
  await scenario("a sign-in that does not end", aSignInThatDoesNotEnd);
  await scenario("a page that cannot be drawn", aPageThatCannotBeDrawn);
}

// ---------------------------------------------------------------------------------------------------------------- administrator

async function administrator() {
  heading("administrator: all of the clinic, and the jobs");
  const page = await newPage();
  try {
    if (!await signIn(page, "admin@pulse.test", { expect: "All patients", label: "the administrator" })) return;
    await page.waitFor(async () => (await patientNames(page)).length >= 6, 30000);
    const names = await patientNames(page);
    check("the panel of the administrator is every patient of the clinic", names.length === 6 && /Maya|Noah/.test(names.join(" ")), json(names));
    const sidebar = await page.evaluate("[...document.querySelectorAll('aside nav a')].map((a) => a.getAttribute('title') || a.textContent.trim())");
    check("the sidebar has a group of its own with the jobs", json(sidebar) === json(["Panel", "Alerts", "Appointments", "Jobs"]) && /Administration/i.test(await page.evaluate("document.querySelector('aside nav').innerText")), json(sidebar));
    page.clearEvents();
    await clickSidebar(page, "Jobs");
    const jobs = await page.waitFor(async () => /Last run failed/.test(await page.text()) && (await page.evaluate("document.querySelectorAll('ul[aria-label=\"Jobs of the scheduler\"] li').length")) >= 1, 30000);
    check("the jobs of the scheduler are listed, with how the last run of each went", Boolean(jobs), squash(await page.text()).slice(0, 200));
    const rows = await page.evaluate("[...document.querySelectorAll('ul[aria-label=\"Jobs of the scheduler\"] li')].map((l) => l.innerText.replace(/\\s+/g, ' ').trim())");
    check("the rules and the reminders are among them, with their schedule", rows.some((r) => /evaluate-rules/.test(r) && /Every/.test(r)) && rows.some((r) => /send-reminders/.test(r)), json(rows).slice(0, 300));
    check("the page asked the api for the jobs, once", apiPaths(page).filter((p) => /admin\/jobs/.test(p)).length === 1, json(apiPaths(page)));
    check("it has one heading and a title", json(await h1s(page)) === json(["Jobs"]) && (await tabTitle(page)) === "Jobs · Pulse");
    await clean(page, "the jobs");
    await page.go(spa + "/patients/pt-noah");
    check("the administrator can open the chart of any patient", Boolean(await page.waitFor(async () => /Noah Kim/.test(await page.text()), 30000)));
    await noTokenAnywhere(page, "the administrator's walk");
  } finally {
    await page.close();
  }
}

// ---------------------------------------------------------------------------------------------------------------- patient

async function patient() {
  heading("patient: the workspace is not for them");
  const page = await newPage();
  try {
    page.clearEvents();
    if (!await signIn(page, "ana.garcia@pulse.test", { expect: "This workspace is for the clinic", label: "Ana" })) return;
    await sleep(1500);
    const body = await page.text();
    check("a patient is told the workspace is for the clinic, with a way to the portal and a way out",
      /Go to the patient portal/.test(body) && /Sign out/.test(body) && json(await h1s(page)) === json(["This workspace is for the clinic"]), squash(body).slice(0, 160));
    check("the way to the portal leads to the portal", (await page.evaluate("(document.querySelector('a[href^=\"http\"]') || {}).href")) === portal + "/" || (await page.evaluate("(document.querySelector('a[href^=\"http\"]') || {}).href")) === portal);
    check("nothing at all was asked of the api for them", page.callsTo(api).length === 0, json(apiPaths(page)));
    check("and nothing of the clinic is on the page", !/Rivera|Garc[ií]a, Ana|Liam|Sofía/.test(body.replace(/Ana García/g, "")));
    await clean(page, "the page for a patient");
  } finally {
    await page.close();
  }
}

// ---------------------------------------------------------------------------------------------------------------- accessibility

const widths = [320, 390, 820, 1280];
const schemes = ["light", "dark"];

/** The pages and the states of the tour, as a person reaches them. `ready` says when the page has what it is about. */
const tour = (admin) => admin
  ? [{ name: "jobs", path: "/jobs", ready: (p) => p.waitFor(async () => /Last run failed/.test(await p.text()), 30000) }]
  : [
    { name: "panel", path: "/", ready: (p) => p.waitFor(async () => (await patientNames(p)).length >= 4 && /Away from normal/.test(await p.text()), 30000) },
    { name: "chart", path: "/patients/pt-ana", ready: (p) => p.waitFor(() => p.evaluate("document.querySelectorAll('main svg[role=\"img\"]').length > 0"), 30000) },
    { name: "inbox", path: "/alerts", ready: (p) => p.waitFor(async () => /Needs a decision/.test(await p.text()) && /Acknowledged/.test(await p.text()), 30000) },
    { name: "appointments", path: "/appointments", ready: (p) => p.waitFor(async () => /Requests to answer/.test(await p.text()) && /Cancelled/.test(await p.text()), 30000) },
    { name: "confirm", path: "/appointments/{REQUEST}/confirm", ready: (p) => p.waitFor(() => p.evaluate("!!document.querySelector('dialog[open] #day')"), 30000) },
    { name: "reschedule", path: "/appointments/{BOOKED}/reschedule", ready: (p) => p.waitFor(() => p.evaluate("!!document.querySelector('dialog[open] #day')"), 30000) },
    { name: "cancel", path: "/appointments/{BOOKED}/cancel", ready: (p) => p.waitFor(() => p.evaluate("!!document.querySelector('dialog[open] #why')"), 30000) },
    { name: "no-page", path: "/nothing-here", ready: (p) => p.waitFor(async () => /We cannot find that page/.test(await p.text()), 20000) },
    { name: "not-allowed", path: "/jobs", ready: (p) => p.waitFor(async () => /You cannot open that page/.test(await p.text()), 20000) },
    { name: "chart-gone", path: "/patients/pt-noah", ready: (p) => p.waitFor(async () => /We cannot find that patient/.test(await p.text()), 30000) },
    { name: "signed-out", path: "/signed-out", ready: (p) => p.waitFor(async () => /You have signed out/.test(await p.text()), 20000) },
  ];

/** What axe-core finds wrong in the page as it is, at 1280 px in the light scheme and at 390 px in the dark one: the two ends of both ranges. */
async function axeHere(page, label) {
  const found = [];
  for (const [width, scheme] of [[1280, "light"], [390, "dark"]]) {
    await page.viewport(width, scheme);
    for (const v of await page.axe()) found.push(`${width} ${scheme} ${v.id} (${v.impact}, ${v.count}): ${v.nodes.join(" ; ")}`);
  }
  await page.viewport(1280, "light");
  check(`${label}: axe-core finds nothing wrong`, found.length === 0, found.slice(0, 3).join(" | "));
}

/** The ids of the appointments a dialog can be opened for: a request, which can be confirmed, and a booked one, which can be moved or cancelled. */
async function appointmentsToOpen(page) {
  await page.go(spa + "/appointments");
  // the rows are there once the lists have come, and the headings before
  await page.waitFor(() => page.evaluate("document.querySelectorAll('main section[aria-labelledby] li[id^=\"appointment-\"]').length >= 2"), 30000);
  const idIn = (section) => page.evaluate(`(document.querySelector('main section[aria-labelledby=${section}] li') || { id: '' }).id.replace(/^appointment-/, '')`);
  return { REQUEST: await idIn("requests"), BOOKED: await idIn("booked") };
}

async function accessibility() {
  heading("accessibility: every page and state, at four widths, in both schemes");
  for (const admin of [false, true]) {
    const page = await newPage();
    try {
      const username = admin ? "admin@pulse.test" : rivera;
      if (!await signIn(page, username, { expect: admin ? "All patients" : "Your panel", label: admin ? "the administrator, for the tour" : "Dr. Rivera, for the tour" })) continue;
      const ids = admin ? {} : await appointmentsToOpen(page);
      for (const stop of tour(admin)) {
        const missing = [...stop.path.matchAll(/\{(\w+)\}/g)].map((m) => m[1]).find((k) => !ids[k]);
        if (missing) { skip(`${stop.name}: the dialog`, `no ${missing.toLowerCase()} to open it for`); continue; }
        await page.go(spa + stop.path.replace(/\{(\w+)\}/g, (_, k) => ids[k]));
        const there = await stop.ready(page);
        check(`${stop.name}${admin ? " (administrator)" : ""}: the page is there`, Boolean(there), squash(await page.text()).slice(0, 120));
        if (!there) continue;
        await sleep(300);
        const bad = [];
        for (const width of widths) {
          for (const scheme of schemes) {
            await page.viewport(width, scheme);
            if (await sidewaysScroll(page)) bad.push(`${width} ${scheme}: scrolls sideways`);
            const small = await smallTargets(page);
            if (small.length) bad.push(`${width} ${scheme}: ${small.join(", ")}`);
            if (shots && (width === 390 || width === 1280)) await page.shot(join(shots, `${stop.name}-${width}-${scheme}.png`), width, scheme);
          }
        }
        check(`${stop.name}: at 320, 390, 820 and 1280 px in both schemes it does not scroll sideways and no target is under 24 px`, bad.length === 0, bad.slice(0, 4).join(" | "));
        await axeHere(page, stop.name);
        const violations = await page.violations();
        check(`${stop.name}: no violation of the policy`, violations.length === 0, json(violations));
      }
    } finally {
      await page.close();
    }
  }
  await scenario("the keyboard", keyboard);
  await scenario("the preferences", preferences);
}

async function keyboard() {
  heading("accessibility: the keyboard");
  const page = await newPage();
  try {
    if (!await signIn(page, rivera, { expect: "Your panel", label: "Dr. Rivera, for the keyboard" })) return;
    for (const stop of [{ name: "the panel", path: "/", ready: (p) => p.waitFor(async () => (await patientNames(p)).length >= 4, 30000) },
      { name: "the chart", path: "/patients/pt-ana", ready: (p) => p.waitFor(() => p.evaluate("document.querySelectorAll('main svg[role=\"img\"]').length > 0"), 30000) },
      { name: "the inbox", path: "/alerts", ready: (p) => p.waitFor(async () => /Needs a decision/.test(await p.text()), 30000) },
      { name: "the appointments", path: "/appointments", ready: (p) => p.waitFor(async () => /Requests to answer/.test(await p.text()), 30000) }]) {
      await page.go(spa + stop.path);
      await stop.ready(page);
      await sleep(500);
      await page.evaluate("document.activeElement && document.activeElement.blur(); window.scrollTo(0, 0); true");
      const focusable = await page.evaluate(`[...document.querySelectorAll('a[href], button:not([disabled]), input:not([type=hidden]):not([disabled]), select, textarea, summary, [tabindex]:not([tabindex="-1"])')]
        .filter((e) => {
          const r = e.getBoundingClientRect(); const s = getComputedStyle(e);
          const closed = e.closest('details:not([open])'); // what is inside a closed disclosure is not reached, but its summary is
          const inside = closed && !(e.tagName === 'SUMMARY' && e.parentElement === closed);
          return s.visibility !== 'hidden' && s.display !== 'none' && !inside && !e.closest('[inert], [hidden]') && (r.width > 0 || e.matches('.sr-only'));
        }).length`);
      const stops = [];
      let first = null;
      let cameRound = false;
      for (let i = 0; i < focusable + 8; i++) {
        await page.key("Tab");
        const now = await page.evaluate(`(() => {
          const e = document.activeElement; if (!e || e === document.body) return null;
          // the ring may be drawn on the element, or on the box that holds it (a field is a label that has the ring while it holds the focus)
          const ring = (x) => { if (!x) return false; const t = getComputedStyle(x); return (t.outlineStyle !== 'none' && parseFloat(t.outlineWidth) >= 2) || t.boxShadow !== 'none'; };
          return { key: e.tagName + '#' + (e.id || '') + '.' + (e.getAttribute('href') || e.textContent || '').trim().slice(0, 30),
                   name: (e.getAttribute('aria-label') || e.textContent || e.value || '').trim().replace(/\\s+/g, ' ').slice(0, 40),
                   visible: ring(e) || ring(e.closest('label')),
                   skip: e.textContent.trim() === 'Skip to the content' };
        })()`);
        if (!now) { cameRound = true; break; }
        if (first === null) first = now;
        else if (now.key === first.key && stops.length > 1) { cameRound = true; break; }
        stops.push(now);
      }
      check(`${stop.name}: the first stop is the link that skips to the content`, first?.skip === true, json(first));
      check(`${stop.name}: the keyboard reaches every control (${stops.length} stops of ${focusable})`, stops.length >= focusable - 1 && stops.length <= focusable + 1, `${stops.length} of ${focusable}`);
      check(`${stop.name}: every stop has a name`, stops.every((s) => s.name.length > 0), json(stops.filter((s) => !s.name.length).map((s) => s.key)));
      check(`${stop.name}: every stop shows that the focus is there`, stops.every((s) => s.visible), json(stops.filter((s) => !s.visible).map((s) => s.key).slice(0, 5)));
      check(`${stop.name}: the keyboard is not trapped: it comes round to the beginning`, cameRound);
      // the skip link takes the next tab into the content
      await page.evaluate("document.activeElement && document.activeElement.blur(); window.scrollTo(0, 0); true");
      await page.key("Tab");
      await page.key("Enter");
      await page.key("Tab");
      check(`${stop.name}: after the skip link the next stop is in the content`, await page.evaluate("!!(document.activeElement && document.activeElement.closest('main'))"), String(await active(page)));
    }
    // ---- the dialog: Escape closes it and the focus goes back to what opened it
    await page.go(spa + "/appointments");
    await page.waitFor(async () => /Requests to answer/.test(await page.text()), 30000);
    const reschedule = await page.evaluate("(document.querySelector('main section[aria-labelledby=booked] a.btn[id^=\"reschedule-\"]') || {}).id");
    await page.evaluate(`document.getElementById(${json(reschedule)}).focus(); true`);
    await page.key("Enter");
    check("Enter on a button of a row opens the dialog, with the cursor in its first field", Boolean(await page.waitFor(async () => (await active(page)) === "day", 10000)), String(await active(page)));
    await page.key("Escape");
    check("Escape closes the dialog and the focus goes back to the button that opened it", Boolean(await page.waitFor(async () => (await active(page)) === reschedule && (await page.path()) === "/appointments", 10000)), `${await active(page)} ${await page.path()}`);
  } finally {
    await page.close();
  }
}

async function preferences() {
  heading("accessibility: reduced motion and forced colours");
  const page = await newPage();
  try {
    if (!await signIn(page, rivera, { expect: "Your panel", label: "Dr. Rivera, for the preferences" })) return;
    // a page that stays on its way: the shapes that stand for what has not come are animated, and are not when the person asks
    page.rules = [{ test: /\/api\/patients\?/, action: "hang" }];
    await page.go(spa + "/");
    await page.waitFor(async () => /Loading your panel/.test(await live(page)), 15000);
    const running = () => page.evaluate("document.getAnimations().filter((a) => a.playState === 'running').length");
    await page.viewport(1280, "light", { reducedMotion: false });
    await sleep(300);
    const normal = await running();
    check("the control: while a page is on its way something moves", normal > 0, String(normal));
    await page.viewport(1280, "light", { reducedMotion: true });
    await sleep(300);
    const reduced = await running();
    check("when the person asks for less motion nothing moves", reduced === 0, String(reduced));
    page.rules = [];
    await page.go(spa + "/");
    await page.waitFor(async () => (await patientNames(page)).length >= 4, 30000);
    await page.viewport(1280, "light", { forcedColors: true });
    await sleep(300);
    const forced = await page.evaluate(`(() => { const h = document.querySelector('h1'); const s = getComputedStyle(h);
      const link = document.querySelector('main a[href^="/patients/"]'); const l = getComputedStyle(link);
      return { text: s.color, back: getComputedStyle(document.body).backgroundColor, link: l.color }; })()`);
    check("in forced colours the text is not the colour of its background, and the links are not either", forced.text !== forced.back && forced.link !== forced.back, json(forced));
    check("and nothing scrolls sideways", !(await sidewaysScroll(page)));
    // the backgrounds are dropped in forced colours, and with them what told the current page and the current filter from the others
    const marks = await page.evaluate(`(() => {
      const line = (e) => getComputedStyle(e).textDecorationLine;
      const current = document.querySelector('aside nav a[aria-current="page"]');
      const pill = document.querySelector('nav[aria-label="Filter the panel"] a[aria-current="page"]');
      return { current: line(current), others: [...document.querySelectorAll('aside nav a:not([aria-current])')].map(line),
               pill: pill && line(pill), pills: [...document.querySelectorAll('nav[aria-label="Filter the panel"] a:not([aria-current])')].map(line) };
    })()`);
    check("in forced colours the page and the filter that are current are told from the others by more than a colour",
      /underline/.test(marks.current) && marks.others.every((l) => !/underline/.test(l)) && /underline/.test(marks.pill) && marks.pills.every((l) => !/underline/.test(l)), json(marks));
    if (shots) await page.shot(join(shots, "panel-forced-colors.png"), 1280, "light", { forcedColors: true });
    await page.viewport(1280, "light");
  } finally {
    await page.close();
  }
}

// ---------------------------------------------------------------------------------------------------------------- policy

async function policy() {
  heading("policy: what must be blocked is blocked");
  const page = await newPage();
  try {
    if (!await signIn(page, rivera, { expect: "Your panel", label: "Dr. Rivera, for the controls" })) return;
    await panelReady(page);
    check("until the controls, the pages of the journey broke no rule of the policy", (await page.violations()).length === 0, json(await page.violations()));
    await page.evaluate(`(() => {
      window.__violations = [];
      const s = document.createElement('script'); s.textContent = 'window.__ran = true'; document.head.appendChild(s);
      const st = document.createElement('style'); st.textContent = 'body{outline:3px solid red}'; document.head.appendChild(st);
      const a = document.createElement('p'); a.setAttribute('style', 'color:red'); document.body.appendChild(a);
      try { eval('window.__evaled = true'); } catch (e) { window.__evalError = String(e); }
      fetch('http://example.com/').catch(() => { window.__fetchBlocked = true; });
      const f = document.createElement('iframe'); f.src = 'http://example.com/'; document.body.appendChild(f);
      const form = document.createElement('form'); form.action = 'http://example.com/'; form.method = 'post'; document.body.appendChild(form);
      try { form.requestSubmit ? form.requestSubmit() : form.submit(); } catch (e) { /* blocked */ }
      return true; })()`, { allowUnsafeEvalBlockedByCSP: false });
    await sleep(800);
    const seen = (await page.violations()).join(" | ");
    for (const [what, pattern] of [["an inline script", /script-src-elem/], ["an inline style", /style-src-elem/], ["a style attribute", /style-src-attr/],
      ["eval", /script-src /], ["a request to another server", /connect-src/], ["a frame of another server", /frame-src|child-src|default-src/], ["a form posted to another server", /form-action/]]) {
      check(`the control: ${what} is blocked by the policy`, pattern.test(seen), seen.slice(0, 400));
    }
    check("and none of them ran", !(await page.evaluate("!!window.__ran")) && !(await page.evaluate("!!window.__evaled")), "");
  } finally {
    await page.close();
  }
}

// ---------------------------------------------------------------------------------------------------------------- sign-out

async function signOut() {
  heading("sign-out: through the identity provider, to a page that signs nobody in");
  const page = await newPage();
  try {
    if (!await signIn(page, rivera, { expect: "Your panel", label: "Dr. Rivera, for signing out" })) return;
    await panelReady(page);
    await page.evaluate("document.querySelector('header details summary').click(); true");
    await sleep(200);
    check("the menu of the person has a button that signs out, and not a link", await page.evaluate("[...document.querySelectorAll('header details ul button')].some((b) => b.textContent.trim() === 'Sign out') && !document.querySelector('header details ul a[href*=\"logout\"]')"));
    await page.evaluate("[...document.querySelectorAll('header details ul button')].find((b) => b.textContent.trim() === 'Sign out').click(); true");
    // Keycloak may ask first; with the id token it does not
    await page.waitFor(async () => (await page.here()).startsWith(spa + "/signed-out") || await page.evaluate("!!document.getElementById('kc-logout')"), 20000);
    if (await page.evaluate("!!document.getElementById('kc-logout')")) await page.evaluate("document.getElementById('kc-logout').click(); true");
    const out = await page.waitFor(async () => (await page.here()).startsWith(spa + "/signed-out") && /You have signed out/.test(await page.text()), 20000);
    check("signing out ends the session at the identity provider and brings the person back to the page that says so", Boolean(out), (await page.here()).slice(0, 100));
    await sleep(2500);
    check("that page does not ask the identity provider to sign anybody in", (await page.here()).startsWith(spa + "/signed-out") && /You have signed out/.test(await page.text()), (await page.here()).slice(0, 100));
    check("and it has one heading, and a link to sign in", json(await h1s(page)) === json(["You have signed out"]) && /Sign in/.test(await page.text()));
    await clickText(page, "Sign in", "main");
    const asked = await page.waitFor(() => onLoginPage(page), 30000);
    check("signing in again asks for the password: the session of the identity provider is over", Boolean(asked), (await page.here()).slice(0, 100));
    await page.go(spa + "/appointments");
    check("and so does the address of a page", Boolean(await page.waitFor(() => onLoginPage(page), 30000)));
  } finally {
    await page.close();
  }
}

// ---------------------------------------------------------------------------------------------------------------- main

// the accessibility tour only looks (it opens dialogs and sends nothing), so it goes first, while the cohort is as it was seeded
const sections = { accessibility, practitioner, states, resilience, administrator, patient, policy, "sign-out": signOut };

async function main() {
  const status = async (url) => fetch(url).then((r) => r.status).catch(() => 0);
  const needs = [[`${spa}/healthz`, "the workspace (bb spa:serve, after bb spa:build)"], [`${api}/readyz`, "the api (bb dev lite)"],
    [`${keycloak}/realms/pulse/.well-known/openid-configuration`, "Keycloak (bb up)"]];
  for (const [url, what] of needs) {
    if ((await status(url)) !== 200) throw new Error(`${what} is not ready at ${url}. \`bb spa:journey\` starts it; to start it by hand see the head of apps/spa/journey.mjs.`);
  }
  browser = await Browser.launch();
  for (const [name, run] of Object.entries(sections)) {
    if (only && !only.includes(name)) continue;
    try {
      await run();
    } catch (e) {
      check(`${name}: the section ran to its end`, false, e.stack?.split("\n").slice(0, 3).join(" ") ?? e.message);
    }
  }
  const failed = results.filter((ok) => !ok).length;
  console.log(`\n${results.length - failed} of ${results.length} checks passed${shots ? "; pictures in " + shots : ""}`);
  if (failed) process.exitCode = 1;
}

try {
  await main();
} catch (e) {
  console.error("FAILED:", e.message);
  process.exitCode = 1;
} finally {
  await browser?.close();
}
