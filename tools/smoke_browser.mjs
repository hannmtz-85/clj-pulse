// The part of `bb smoke` that needs a browser (ADR-0030): with the four applications running in their containers, a patient signs in at the
// portal and a practitioner at the clinical workspace, through the real Keycloak, in headless Chrome or Edge (tools/cdp.mjs). It is
// where the things that a server cannot see could differ between the containers and the machine: the policy of nginx against the page
// that calls the api and the identity provider, the CORS of the api, and the redirects of the sign-in.
//
//   node tools/smoke_browser.mjs [--portal http://localhost:3300] [--spa http://localhost:3000] [--api http://localhost:4000]
//                                [--keycloak http://localhost:8080]
//
// The password of the demo users is DEMO_USER_PASSWORD of `.env`. The exit code is 1 when a check fails.
import { existsSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { Browser } from "./cdp.mjs";

const args = process.argv.slice(2);
const option = (name, fallback) => {
  const i = args.indexOf(name);
  return i >= 0 ? args[i + 1] : fallback;
};
const portal = option("--portal", "http://localhost:3300");
const spa = option("--spa", "http://localhost:3000");
const api = option("--api", "http://localhost:4000");
const keycloak = option("--keycloak", "http://localhost:8080");
const repo = join(dirname(fileURLToPath(import.meta.url)), "..");

function demoPassword() {
  if (process.env.DEMO_USER_PASSWORD) return process.env.DEMO_USER_PASSWORD;
  const file = join(repo, ".env");
  if (!existsSync(file)) throw new Error("No .env: run `bb up` (or `bb env:init`) first, or set DEMO_USER_PASSWORD");
  const line = readFileSync(file, "utf8").split(/\r?\n/).find((l) => l.startsWith("DEMO_USER_PASSWORD="));
  if (!line) throw new Error("DEMO_USER_PASSWORD is not in .env");
  return line.slice("DEMO_USER_PASSWORD=".length);
}
const password = demoPassword();

const results = [];
const check = (label, ok, detail = "") => {
  results.push(Boolean(ok));
  console.log(`${ok ? "PASS" : "FAIL"}  ${label}${!ok && detail ? "  -- " + detail : ""}`);
};
const squash = (s) => String(s ?? "").replace(/\s+/g, " ").trim();
const json = (value) => JSON.stringify(value);
const notFailedToLoad = (lines) => lines.filter((l) => !/Failed to load resource|net::ERR_/.test(l));

const onLoginPage = async (page) => (await page.here()).startsWith(keycloak + "/") && await page.evaluate("!!document.getElementById('kc-form-login')");

/** Opens `start`, signs in through the login page of the Pulse theme and waits for `expect` to be said by a page of `origin`. */
async function signIn(page, { start, origin, username, expect, label }) {
  await page.go(start);
  const asked = await page.waitFor(() => onLoginPage(page), 30000);
  check(`${label}: a visitor is sent to the login page of Keycloak`, asked, (await page.here()).slice(0, 100));
  if (!asked) return false;
  await page.evaluate(`document.getElementById('username').value=${json(username)}; document.getElementById('password').value=${json(password)}; document.getElementById('kc-form-login').submit(); true`);
  const back = await page.waitFor(async () => (await page.here()).startsWith(origin + "/") && (await page.text()).includes(expect), 30000);
  check(`${label}: back at ${origin}, which says "${expect}"`, back, squash(await page.text()).slice(0, 160));
  return Boolean(back);
}

/** What every page of a person who is signed in must be: clean of violations of the policy and of console problems. */
async function clean(page, label) {
  const violations = await page.violations();
  check(`${label}: no violation of the content security policy`, violations.length === 0, json(violations));
  const problems = notFailedToLoad(page.consoleProblems());
  check(`${label}: nothing in the console`, problems.length === 0, json(problems).slice(0, 400));
}

const browser = await Browser.launch();
try {
  // A patient, at the portal: a page of the server, made from what the api answers with the token the portal keeps
  {
    const page = await browser.newPage({ origin: portal });
    const label = "Ana at the portal";
    if (await signIn(page, { start: portal + "/", origin: portal, username: "ana.garcia@pulse.test", expect: "Ana", label })) {
      const h1 = await page.evaluate("[...document.querySelectorAll('h1')].map((h) => h.textContent.trim())");
      check(`${label}: the home is hers and made from the api`, h1.length === 1 && /^Good (morning|afternoon|evening), Ana$|^Welcome, Ana$/.test(h1[0]), json(h1));
      const text = await page.text();
      check(`${label}: it says something of her readings or her appointments`, /reading|appointment/i.test(text), squash(text).slice(0, 160));
      await clean(page, label);
    }
    await page.close();
  }

  // A practitioner, at the workspace: files from nginx, and calls from the browser to the api and to Keycloak
  {
    const page = await browser.newPage({ origin: spa });
    const label = "Dr. Rivera at the workspace";
    if (await signIn(page, { start: spa + "/", origin: spa, username: "dr.rivera@pulse.test", expect: "Your panel", label })) {
      const rows = await page.waitFor(() => page.evaluate("document.querySelectorAll('main a[href^=\"/patients/\"]').length"), 30000);
      check(`${label}: the panel lists patients, which the api answered to a call from the browser`, rows > 0, String(rows));
      const calls = page.callsTo(api).map((r) => `${r.request.method} ${r.request.url.slice(api.length)}`);
      check(`${label}: the page called the api (/api/me and the patients)`, calls.some((c) => c.startsWith("GET /api/me")) && calls.some((c) => c.startsWith("GET /api/patients")), json(calls.slice(0, 6)));
      const storage = await page.storage();
      check(`${label}: no token in either storage or a cookie it can read`, !/eyJ/.test(storage), storage.slice(0, 160));
      await clean(page, label);
    }
    await page.close();
  }
} finally {
  await browser.close();
}

const failed = results.filter((ok) => !ok).length;
console.log(`\n${results.length - failed} of ${results.length} checks passed`);
process.exit(failed ? 1 : 0);
