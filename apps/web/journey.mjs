// The acceptance of the patient portal in a real browser (Phase 7, ADR-0028): headless Chrome or Edge over the DevTools protocol, with
// no dependencies (Node has a WebSocket client of its own), against the real Keycloak and the running portal. `bb web:journey` runs it.
//
//   node apps/web/journey.mjs [--portal http://localhost:3300] [--keycloak http://localhost:8080] [--shots <directory>]
//
// A patient signs in through the login page of the Pulse theme, is brought back to the page they asked for, goes through every page of the
// portal at 390 and 1280 px in both schemes (no horizontal scroll, no target under 24 px, no error in the console, no violation of the
// content security policy), sees only their own data, records a reading (refused in the api's words, then saved), asks for a visit and
// withdraws it (with and without a script), signs out and is not let back in. A doctor is told that the portal is for patients and gets
// no session. Last, the controls: what the policy must block (an inline script, an inline style, eval) is blocked, so that "no violation"
// above means something.
//
// Needs the stack (`bb up`) and the portal at the address the realm registers: `bb dev lite` (an in-memory FHIR server, which leaves
// nothing behind) or `bb dev` (HAPI: the journey leaves one reading and one cancelled request of Ana's). The password of the demo users
// is DEMO_USER_PASSWORD of `.env`. The browser is the variable CHROME, or the first of the usual places. The exit code is 1 when a
// check fails. With `--shots` it also writes a picture of each page.
import { spawn } from "node:child_process";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const args = process.argv.slice(2);
const option = (name, fallback) => {
  const i = args.indexOf(name);
  return i >= 0 ? args[i + 1] : fallback;
};
const portal = option("--portal", "http://localhost:3300");
const keycloak = option("--keycloak", "http://localhost:8080");
const shots = option("--shots");
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

const candidates = [
  process.env.CHROME,
  "C:/Program Files/Google/Chrome/Application/chrome.exe",
  "C:/Program Files (x86)/Google/Chrome/Application/chrome.exe",
  "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
  "C:/Program Files/Microsoft/Edge/Application/msedge.exe",
  "/usr/bin/google-chrome",
  "/usr/bin/chromium",
  "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
].filter(Boolean);
const browserPath = candidates.find((p) => existsSync(p));
if (!browserPath) throw new Error("No Chrome or Edge found: set CHROME to its path");

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const results = [];
const check = (label, ok, detail = "") => {
  results.push(Boolean(ok));
  console.log(`${ok ? "PASS" : "FAIL"}  ${label}${!ok && detail ? "  -- " + detail : ""}`);
};

const profile = mkdtempSync(join(tmpdir(), "pulse-journey-"));
if (shots) mkdirSync(shots, { recursive: true });
const browser = spawn(browserPath, [
  "--headless=new", "--remote-debugging-port=0", `--user-data-dir=${profile}`, "--hide-scrollbars",
  "--no-first-run", "--no-default-browser-check", "--disable-gpu", "--force-device-scale-factor=1", "--disable-extensions", "about:blank",
], { stdio: "ignore" });

async function endpoint() {
  const file = join(profile, "DevToolsActivePort");
  for (let i = 0; i < 100; i++) {
    if (existsSync(file)) {
      const [port, path] = readFileSync(file, "utf8").trim().split("\n");
      if (port && path) return `ws://127.0.0.1:${port}${path}`;
    }
    await sleep(100);
  }
  throw new Error("The browser did not say where to connect");
}

let socket;
let nextId = 1;
let session;
const pending = new Map();
let events = [];

function send(method, params = {}) {
  const id = nextId++;
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject, method });
    socket.send(JSON.stringify({ id, method, params, ...(session ? { sessionId: session } : {}) }));
  });
}
function onMessage(data) {
  const message = JSON.parse(data);
  if (message.id) {
    const call = pending.get(message.id);
    pending.delete(message.id);
    if (message.error) call.reject(new Error(`${call.method}: ${message.error.message}`));
    else call.resolve(message.result);
  } else if (message.method) {
    events.push(message);
  }
}

const evaluate = async (expression, extra = {}) => {
  const r = await send("Runtime.evaluate", { expression, returnByValue: true, awaitPromise: true, ...extra });
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description || "evaluation failed");
  return r.result.value;
};
async function waitFor(predicate, ms = 10000) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    try { const v = await predicate(); if (v) return v; } catch { /* the page is changing */ }
    await sleep(100);
  }
  return null;
}
const here = () => evaluate("location.href");
const loaded = () => waitFor(async () => (await evaluate("document.readyState")) === "complete");
const go = async (url) => { events = []; await send("Page.navigate", { url }); await sleep(400); await loaded(); };
const violations = () => evaluate("(window.__violations || []).map(String)");
const text = () => evaluate("document.body.innerText.replace(/\\s+/g, ' ')");
const status = (path) => evaluate(`fetch(${JSON.stringify(path)}, { redirect: 'manual' }).then(r => r.status)`);

/** What went wrong in the console since the page was asked for: an uncaught exception, or a message of level error. */
const consoleErrors = () => events.flatMap((e) => {
  if (e.method === "Runtime.exceptionThrown") return [e.params.exceptionDetails.text + " " + (e.params.exceptionDetails.exception?.description || "")];
  if (e.method === "Log.entryAdded" && e.params.entry.level === "error") return [e.params.entry.text + " " + (e.params.entry.url || "")];
  if (e.method === "Runtime.consoleAPICalled" && e.params.type === "error") return [e.params.args.map((a) => a.value ?? a.description).join(" ")];
  return [];
});

async function viewport(width, scheme) {
  await send("Emulation.setDeviceMetricsOverride", { width, height: 900, deviceScaleFactor: 1, mobile: width < 500 });
  await send("Emulation.setEmulatedMedia", { features: [{ name: "prefers-color-scheme", value: scheme }] });
  await sleep(250);
}
async function shot(name, width, scheme) {
  if (!shots) return;
  await viewport(width, scheme);
  const height = await evaluate("document.documentElement.scrollHeight");
  await send("Emulation.setDeviceMetricsOverride", { width, height: Math.min(height, 3500), deviceScaleFactor: 1, mobile: width < 500 });
  await sleep(200);
  const s = await send("Page.captureScreenshot", { format: "png" });
  writeFileSync(join(shots, `${name}-${width}-${scheme}.png`), Buffer.from(s.data, "base64"));
}

/** Signs in through the login page. `from` is where the browser goes first. Resolves to whether it came back to the portal. */
async function signInAs(username, from = portal + "/sign-in") {
  await go(from);
  const onLogin = await waitFor(async () => (await here()).startsWith(keycloak + "/") && await evaluate("!!document.getElementById('kc-form-login')"));
  if (!onLogin) return false;
  await evaluate(`document.getElementById('username').value=${JSON.stringify(username)}; document.getElementById('password').value=${JSON.stringify(password)}; document.getElementById('kc-form-login').submit(); true`);
  const ok = await waitFor(async () => (await here()).startsWith(portal + "/") && (await evaluate("document.readyState")) === "complete", 15000);
  await sleep(300);
  return Boolean(ok);
}

const click = async (selector) => {
  await evaluate(`document.querySelector(${JSON.stringify(selector)}).click(); true`);
  await sleep(500);
  await loaded();
};
const clickText = async (label, within = "main") => {
  await evaluate(`(() => { const e = [...document.querySelectorAll(${JSON.stringify(within)} + ' a, ' + ${JSON.stringify(within)} + ' button')].find(b => b.textContent.trim() === ${JSON.stringify(label)}); if (!e) throw new Error('no button ' + ${JSON.stringify(label)}); e.click(); return true; })()`);
  await sleep(500);
  await loaded();
};
const setValue = (id, value) => evaluate(`(() => { const e = document.getElementById(${JSON.stringify(id)}); e.value = ${JSON.stringify(value)}; e.dispatchEvent(new Event('input', { bubbles: true })); return e.value; })()`);
const hasButton = (label) => evaluate(`[...document.querySelectorAll('main a, main button')].some(b => b.textContent.trim() === ${JSON.stringify(label)})`);

/** What a page is, as a person and a program see it. */
const facts = () => evaluate(`JSON.stringify({
  url: location.pathname + location.search,
  title: document.title,
  h1: [...document.querySelectorAll('h1')].map(e => e.textContent.trim()),
  mains: document.querySelectorAll('main').length,
  bar: [...document.querySelectorAll('nav[aria-label="Main"] a')].map(a => a.textContent.trim() + (a.getAttribute('aria-current') ? '*' : '')),
  notices: [...document.querySelectorAll('main [role=status]')].map(e => e.innerText.replace(/\\s+/g, ' ').trim()),
  alerts: [...document.querySelectorAll('main [role=alert]')].map(e => e.innerText.replace(/\\s+/g, ' ').trim()),
  errors: [...document.querySelectorAll('main p[id$="-error"]')].map(e => e.id + ': ' + e.textContent.trim()),
  active: document.activeElement && (document.activeElement.id || document.activeElement.textContent.trim().slice(0, 30)),
  rows: [...document.querySelectorAll('main ul[aria-label] li')].map(li => li.innerText.replace(/\\s+/g, ' ').trim()),
  dialog: (() => { const d = document.querySelector('dialog'); return d ? { open: d.open, title: d.querySelector('h2').textContent.trim() } : null; })(),
  scrollW: document.documentElement.scrollWidth, clientW: document.documentElement.clientWidth,
  small: [...document.querySelectorAll('main a, main button, header a, header summary, nav a')].filter(e => { const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0 && r.height < 24; }).map(e => e.textContent.trim().slice(0, 30) + ' ' + Math.round(e.getBoundingClientRect().height))
})`).then(JSON.parse);

const isoDate = (d) => d.toISOString().slice(0, 10);
const others = /Liam|Sofía|Maya|Omar|Noah|metformin|empagliflozin|tiotropium|albuterol|Penicillin|furosemide|carvedilol|Okafor|PULSE-000[2-6]/;

const pages = [
  { name: "Home", path: "/", title: "Home · Pulse", h1: /^Good (morning|afternoon|evening), Ana$|^Welcome, Ana$/ },
  { name: "Readings", path: "/readings", title: "Readings · Pulse", h1: /^My readings$/ },
  { name: "Appointments", path: "/appointments", title: "Appointments · Pulse", h1: /^Appointments$/ },
  { name: "My record", path: "/record", title: "My record · Pulse", h1: /^My record$/ },
  { name: "Profile", path: "/profile", title: "Profile · Pulse", h1: /^Profile$/ },
];

async function main() {
  // ---- is there a portal to look at?
  const ready = await fetch(portal + "/readyz").then(async (r) => ({ status: r.status, body: await r.text() })).catch((e) => ({ status: 0, body: String(e) }));
  if (ready.status !== 200) {
    throw new Error(`The portal is not ready at ${portal} (${ready.status} ${ready.body.slice(0, 120)}). Start the stack (bb up) and the portal (bb dev lite) first.`);
  }

  socket = new WebSocket(await endpoint());
  await new Promise((resolve, reject) => { socket.onopen = resolve; socket.onerror = reject; });
  socket.onmessage = (m) => onMessage(m.data);
  const { targetId } = await send("Target.createTarget", { url: "about:blank" });
  ({ sessionId: session } = await send("Target.attachToTarget", { targetId, flatten: true }));
  await send("Page.enable"); await send("Log.enable"); await send("Network.enable"); await send("Runtime.enable");
  await send("Page.addScriptToEvaluateOnNewDocument", {
    source: "window.__violations = []; document.addEventListener('securitypolicyviolation', (e) => window.__violations.push(e.violatedDirective + ' ' + e.blockedURI));",
  });
  await viewport(1280, "light");

  // ---- a visitor is sent to sign in, and brought back to the page they asked for
  await go(portal + "/profile");
  const atLogin = await waitFor(async () => (await here()).startsWith(keycloak + "/realms/pulse/") && await evaluate("!!document.getElementById('kc-form-login')"));
  check("1. a visitor who asks for a page of a patient is sent to the login page of the Pulse theme", atLogin, await here());
  check("1b. and that page is the portal's own client's", (await here()).includes("client_id=pulse-web") && (await here()).includes("code_challenge_method=S256"), await here());
  check("1c. no violation of the policy at the login page", (await violations()).length === 0, JSON.stringify(await violations()));
  await shot("login", 390, "light");
  const signedIn = await signInAs("ana.garcia@pulse.test", portal + "/profile");
  check("2. Ana signs in with the password of the demo and is brought back to the profile she asked for", signedIn && (await here()) === portal + "/profile", await here());

  // ---- every page, reached from the bar
  for (const p of pages) {
    await viewport(1280, "light");
    await click(`nav[aria-label="Main"] a[href="${p.path}"]`);
    const f = await facts();
    const mark = `${p.name}*`;
    check(`3. ${p.name}: the bar leads to ${p.path}, and the page says which it is`, f.url === p.path && f.title === p.title && f.h1.length === 1 && p.h1.test(f.h1[0]), JSON.stringify([f.url, f.title, f.h1]));
    check(`3b. ${p.name}: one main, the five pages in the bar, and this one marked`, f.mains === 1 && JSON.stringify(f.bar.map((x) => x.replace("*", ""))) === JSON.stringify(["Home", "Readings", "Appointments", "My record", "Profile"]) && f.bar.includes(mark) && f.bar.filter((x) => x.endsWith("*")).length === 1, JSON.stringify(f.bar));
    check(`3c. ${p.name}: no error in the console and no violation of the policy`, consoleErrors().length === 0 && (await violations()).length === 0, JSON.stringify([consoleErrors(), await violations()]));
    for (const scheme of ["light", "dark"]) {
      for (const width of [390, 1280]) {
        await viewport(width, scheme);
        const g = await facts();
        check(`4. ${p.name} at ${width} px, ${scheme}: no horizontal scroll and no target under 24 px`, g.scrollW <= g.clientW && g.small.length === 0, JSON.stringify([g.scrollW, g.clientW, g.small]));
        await shot(p.name.toLowerCase().replace(" ", "-"), width, scheme);
      }
    }
  }
  await viewport(1280, "light");

  // ---- her own data, and nobody else's
  await go(portal + "/record");
  const record = await text();
  check("5. her record is hers: her medication and her condition, and no code", /Lisinopril/.test(record) && /Amlodipine/.test(record) && record.includes("Essential (primary) hypertension") && !/\bI10\b/.test(record), record.slice(0, 200));
  await go(portal + "/profile");
  const profileText = await text();
  check("5b. her profile is hers: her name, her email, her number and her doctor", ["Ana García", "ana.garcia@pulse.test", "PULSE-0001", "Valencia", "14 March 1968", "Dr. Elena Rivera"].every((t) => profileText.includes(t)), profileText.slice(0, 300));
  let everything = "";
  for (const p of pages) { await go(portal + p.path); everything += " " + (await text()); }
  check("5c. nothing of the other patients is in any page she can see", !others.test(everything), (everything.match(others) || [])[0]);
  await go(portal + "/record?patient=pt-liam&id=Patient/pt-liam");
  check("5d. no address names a patient: asking for another one's record gives her own", (await text()).includes("Lisinopril") && !others.test(await text()));
  check("5e. an appointment that is not hers is not found, and neither is one that does not exist", (await status("/appointments/appt-liam-1/cancel")) === 404 && (await status("/appointments/nothing/cancel")) === 404);

  // ---- recording a reading: refused, then saved
  await go(portal + "/readings/new?measurement=heart-rate");
  await setValue("value", "400");
  await click("form[method=post] button[type=submit]");
  let f = await facts();
  check("6. a heart rate that cannot be one is refused in the api's own words beside the field, with the cursor there", f.errors.length === 1 && /^value-error: Must be between 20 and 300/.test(f.errors[0]) && f.active === "value", JSON.stringify([f.errors, f.active]));
  await shot("reading-refused", 390, "light");
  await setValue("value", "72");
  await click("form[method=post] button[type=submit]");
  await waitFor(async () => (await here()).includes("/readings?"));
  f = await facts();
  check("6b. a heart rate that can be one is saved, and her readings say so", f.notices.length === 1 && /^Reading saved\s*Heart rate, 72 bpm, today, \d\d:\d\d\.$/.test(f.notices[0]), JSON.stringify(f.notices));
  await go(portal + "/readings?measurement=heart-rate");
  check("6c. and it is said once", (await facts()).notices.length === 0);

  // ---- asking for a visit, and withdrawing the request
  await go(portal + "/appointments");
  const today = isoDate(new Date());
  await setValue("reason", "Journey of the acceptance");
  await setValue("from", today);
  await setValue("to", isoDate(new Date(Date.parse(today) + 7 * 86400000)));
  await click("#ask form button[type=submit]");
  await waitFor(async () => (await here()) === portal + "/appointments");
  f = await facts();
  check("7. a request for a visit is sent, and the page says so with the doctor who will answer", f.notices.length === 1 && /^Request sent\s*Journey of the acceptance\. Dr\. Elena Rivera will confirm a time/.test(f.notices[0]), JSON.stringify(f.notices));
  check("7b. and it is among what is coming, as a request that can be withdrawn", f.rows.some((r) => /^Journey of the acceptance Asked for a visit between .* Requested Cancel request$/.test(r)), JSON.stringify(f.rows));
  await shot("visit-sent", 390, "light");
  await clickText("Cancel request");
  f = await facts();
  check("7c. withdrawing it asks first, in a dialog over the page, and keeping it is a way back", f.dialog && f.dialog.open && f.dialog.title === "Cancel this request?" && (await hasButton("Keep it")) , JSON.stringify(f.dialog));
  await shot("question", 390, "light");
  await clickText("Cancel the request", "dialog");
  await waitFor(async () => (await here()) === portal + "/appointments");
  f = await facts();
  check("7d. the request is withdrawn, and it is among the cancelled", f.notices.length === 1 && /^Request cancelled/.test(f.notices[0]) && f.rows.some((r) => /^Journey of the acceptance .* Cancelled/.test(r)), JSON.stringify([f.notices, f.rows]));
  if (await hasButton("Cancel appointment")) {
    await clickText("Cancel appointment");
    f = await facts();
    check("7e. the question before the confirmed appointment is cancelled has the cursor on Keep it, and keeping it changes nothing", f.dialog && f.dialog.title === "Cancel this appointment?" && f.active === "Keep it", JSON.stringify([f.dialog, f.active]));
    await clickText("Keep it", "dialog");
    check("7f. and she is back at the list with the appointment still there to cancel", (await here()) === portal + "/appointments" && (await hasButton("Cancel appointment")), await here());
  } else {
    console.log("SKIP  7e. the confirmed appointment of Ana has begun (the data was seeded some days ago): there is nothing to cancel");
  }

  // ---- the same with no script
  await send("Emulation.setScriptExecutionDisabled", { value: true });
  await go(portal + "/appointments");
  await evaluate("document.getElementById('reason').value = 'Journey with no script'; document.querySelector('#ask form').submit(); true");
  await sleep(800);
  await loaded();
  f = await facts();
  check("8. with the script off the form still sends the request, and the page says so", f.url === "/appointments" && f.notices.length === 1 && f.notices[0].startsWith("Request sent"), JSON.stringify([f.url, f.notices]));
  await evaluate(`(() => { const row = [...document.querySelectorAll('main ul[aria-label] li')].find(li => li.innerText.includes('Journey with no script')); row.querySelector('a.btn').click(); return true; })()`);
  await sleep(600);
  await loaded();
  f = await facts();
  const question = f.dialog && f.dialog.open;
  await evaluate("document.getElementById('cancel-form').submit(); true");
  await sleep(800);
  await loaded();
  f = await facts();
  await send("Emulation.setScriptExecutionDisabled", { value: false });
  check("8b. and so does the question, which withdraws it", question && f.url === "/appointments" && f.notices.length === 1 && f.notices[0].startsWith("Request cancelled"), JSON.stringify([question, f.url, f.notices]));

  // ---- the controls: what the policy must block is blocked
  await go(portal + "/profile");
  await evaluate(`(() => { window.__violations = []; const s = document.createElement('script'); s.textContent = 'window.__ran = true'; document.head.appendChild(s); const st = document.createElement('style'); st.textContent = 'body{outline:3px solid red}'; document.head.appendChild(st); try { eval('window.__evaled = true'); } catch (e) { window.__evalError = String(e); } return true; })()`, { allowUnsafeEvalBlockedByCSP: false });
  await sleep(300);
  const blocked = await violations();
  check("9. the control: an inline script, an inline style and eval are blocked by the policy", blocked.length >= 3 && !(await evaluate("!!window.__ran")) && !(await evaluate("!!window.__evaled")), JSON.stringify(blocked));

  // ---- signing out, properly
  await go(portal + "/profile");
  await evaluate("document.querySelector('details summary').click(); true");
  await sleep(200);
  check("10. the menu has the profile and the way out, which is a button of a form", (await evaluate("[...document.querySelectorAll('details li')].map(li => li.textContent.trim()).join('|')")) === "Profile|Sign out" && (await evaluate("!!document.querySelector('details button[form]')")));
  await evaluate("document.querySelector('details button[form]').click(); true");
  const out = await waitFor(async () => (await here()).startsWith(portal + "/signed-out") && (await evaluate("document.readyState")) === "complete", 15000);
  check("10b. signing out ends the session and the identity provider sends her back to the portal, which says so", out && (await text()).includes("You have signed out"), await here());
  const cookies = (await send("Network.getCookies", { urls: [portal] })).cookies.map((c) => c.name);
  check("10c. and the browser has no cookie of a session", !cookies.includes("pulse-session"), JSON.stringify(cookies));
  await go(portal + "/record");
  const again = await waitFor(async () => (await here()).startsWith(keycloak + "/") && await evaluate("!!document.getElementById('kc-form-login')"));
  check("10d. and the page of a patient is not hers any more: she is asked to sign in again", again, await here());

  // ---- somebody from the clinic
  const doctor = await signInAs("dr.rivera@pulse.test");
  const t = await text();
  check("11. a doctor who signs in is told that the portal is for patients", doctor && t.includes("This portal is for patients") && (await evaluate("document.querySelectorAll('h1').length")) === 1, t.slice(0, 160));
  check("11b. and gets no session: the page of a patient is still not theirs", (await status("/record")) !== 200);
  await shot("not-for-patients", 390, "light");

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
  try { socket?.close(); } catch { /* already closed */ }
  browser.kill();
  await sleep(300);
  try { rmSync(profile, { recursive: true, force: true }); } catch { /* the browser may still hold a file */ }
}
