// Screenshots of the login theme as the real Keycloak serves it (ADR-0026), with headless Chrome or Edge over the
// DevTools protocol and no dependencies. The stack must be up (`bb up`): this reads pages from it and signs in with a
// wrong password, which changes nothing.
//
//   node design/keycloak-shots.mjs [--base http://localhost:8080] [--out design/dist/shots]
//                                  [--width 390] [--scheme dark] [scenario ...]
//
// The scenarios are the pages of the realm that the theme covers: login, login-error, logout-confirm, signed-out,
// error and page-expired. None named means all of them, at 390 and 1280 pixels, in the light and the dark scheme.
// The browser is the variable CHROME, or the first of the usual places. A page is read by its own ids, which the
// templates keep from Keycloak's (username, password, kc-form-login, kc-logout), so this works with any theme.
import { spawn } from "node:child_process";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const args = process.argv.slice(2);
const option = (name, fallback) => {
  const i = args.indexOf(name);
  return i >= 0 ? args[i + 1] : fallback;
};
const base = option("--base", "http://localhost:8080");
const out = option("--out", "design/dist/shots");
const only = args.filter((a, i) => !a.startsWith("--") && !(i > 0 && args[i - 1].startsWith("--")));
const widths = option("--width") ? [Number(option("--width"))] : [390, 1280];
const schemes = option("--scheme") ? [option("--scheme")] : ["light", "dark"];

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

const realm = `${base}/realms/pulse/protocol/openid-connect`;
const authUrl = `${realm}/auth?client_id=pulse-docs&redirect_uri=${encodeURIComponent("http://localhost:4000/docs/oauth2-redirect.html")}`
  + "&response_type=code&scope=openid&state=s&nonce=n&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM&code_challenge_method=S256";

const profile = mkdtempSync(join(tmpdir(), "pulse-kc-shots-"));
mkdirSync(out, { recursive: true });
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

const browser = spawn(browserPath, [
  "--headless=new", "--remote-debugging-port=0", `--user-data-dir=${profile}`, "--hide-scrollbars",
  "--no-first-run", "--no-default-browser-check", "--disable-gpu", "--force-device-scale-factor=1",
  "--disable-extensions", "about:blank",
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
const pending = new Map();
const waiting = [];
let sessionId;

function send(method, params = {}) {
  const id = nextId++;
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject, method });
    socket.send(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) }));
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
    const i = waiting.findIndex((w) => w.method === message.method && w.sessionId === message.sessionId);
    if (i >= 0) waiting.splice(i, 1)[0].resolve(message.params);
  }
}

// The load of the next page; after ten seconds it gives up and goes on, so that a page that never loads (or that comes
// from the cache of the browser, which does not fire the event again) cannot stop the rest.
const loaded = () => new Promise((resolve) => {
  const entry = { method: "Page.loadEventFired", sessionId, resolve };
  waiting.push(entry);
  setTimeout(() => { const i = waiting.indexOf(entry); if (i >= 0) { waiting.splice(i, 1); resolve(null); } }, 10000);
});

async function go(url) {
  const done = loaded();
  await send("Page.navigate", { url });
  await done;
}

async function evaluate(expression) {
  const { result, exceptionDetails } = await send("Runtime.evaluate", { expression, returnByValue: true, awaitPromise: true });
  if (exceptionDetails) throw new Error(`${expression}: ${exceptionDetails.text}`);
  return result.value;
}

// Runs something that makes the page navigate (a form that is submitted) and waits for the new page.
async function navigating(expression) {
  const done = loaded();
  await evaluate(expression);
  await done;
}

const scenarios = {
  login: () => go(authUrl),
  "login-error": async () => {
    await go(authUrl);
    await navigating(`document.getElementById("username").value = "ana.garcia@pulse.test";
      document.getElementById("password").value = "this is not the password";
      document.getElementById("kc-form-login").submit(); true`);
  },
  "logout-confirm": () => go(`${realm}/logout`),
  "signed-out": async () => {
    await go(`${realm}/logout`);
    await navigating(`document.getElementById("kc-logout").click(); true`);
  },
  error: () => go(`${realm}/auth?client_id=there-is-no-such-client&redirect_uri=${encodeURIComponent("http://localhost:4000/")}&response_type=code`),
  // The address of a step of the flow that is not the one the flow is at: what the Back button of a browser leads to
  "page-expired": async () => {
    await go(authUrl);
    await navigating(`location.href = document.getElementById("kc-form-login").action
      .replace(/execution=[^&]*/, "execution=00000000-0000-0000-0000-000000000000"); true`);
  },
};

const names = only.length ? only : Object.keys(scenarios);
for (const name of names) if (!scenarios[name]) throw new Error(`No scenario called ${name}: ${Object.keys(scenarios).join(", ")}`);

async function main() {
  socket = new WebSocket(await endpoint());
  await new Promise((resolve, reject) => { socket.onopen = resolve; socket.onerror = reject; });
  socket.onmessage = (m) => onMessage(m.data);
  const { targetId } = await send("Target.createTarget", { url: "about:blank" });
  ({ sessionId } = await send("Target.attachToTarget", { targetId, flatten: true }));
  await send("Page.enable");

  for (const name of names) {
    for (const scheme of schemes) {
      for (const width of widths) {
        const metrics = (height) => ({ width, height, deviceScaleFactor: 1, mobile: width < 600 });
        await send("Emulation.setDeviceMetricsOverride", metrics(900));
        await send("Emulation.setEmulatedMedia", { features: [{ name: "prefers-color-scheme", value: scheme }] });
        await scenarios[name]();
        await evaluate("document.fonts.ready.then(() => true)");
        await sleep(200);
        const height = await evaluate("document.documentElement.scrollHeight");
        await send("Emulation.setDeviceMetricsOverride", metrics(Math.max(700, Math.min(height, 6000))));
        await sleep(100);
        const { data } = await send("Page.captureScreenshot", { format: "png" });
        const file = join(out, `keycloak-${name}-${scheme}-${width}.png`);
        writeFileSync(file, Buffer.from(data, "base64"));
        console.log(`Wrote ${file} (${width} x ${height})`);
      }
    }
  }
}

try {
  await main();
} finally {
  try { socket?.close(); } catch { /* already closed */ }
  browser.kill();
  await sleep(300);
  try { rmSync(profile, { recursive: true, force: true }); } catch { /* the browser may still hold a file */ }
}
