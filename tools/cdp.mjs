// A small client of the Chrome DevTools Protocol, with no dependencies (Node has a WebSocket client of its own), for the acceptance
// journeys that drive a real browser: headless Chrome or Edge, one browser context for each person (so that nobody shares a cookie with
// anybody), what the page logs and the requests it makes kept for the checks to read, a listener for every violation of the content
// security policy that no policy can stop, and the means to make an api or an identity provider answer as a check wants (a failure, a
// refusal, silence). Used by `apps/spa/journey.mjs`; the portal's journey and the scripts of the design keep their own copies for now.
import { spawn } from "node:child_process";
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

export const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

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

/** The browser to drive: the variable CHROME, or the first of the usual places. */
export function browserPath() {
  const found = candidates.find((p) => existsSync(p));
  if (!found) throw new Error("No Chrome or Edge found: set CHROME to its path");
  return found;
}

/** What a page writes to its console that is a problem: an uncaught exception, a message of level error or warning. */
export function problemsIn(events) {
  const lines = [];
  for (const e of events) {
    if (e.method === "Runtime.consoleAPICalled" && ["error", "warning", "assert"].includes(e.params.type)) {
      lines.push(`${e.params.type}: ` + e.params.args.map((a) => a.value ?? a.description ?? a.type).join(" ").slice(0, 300));
    } else if (e.method === "Runtime.exceptionThrown") {
      lines.push("exception: " + (e.params.exceptionDetails.exception?.description || e.params.exceptionDetails.text).slice(0, 300));
    } else if (e.method === "Log.entryAdded" && ["error", "warning"].includes(e.params.entry.level)) {
      lines.push(`log-${e.params.entry.level}: ${e.params.entry.text} ${e.params.entry.url || ""}`.slice(0, 300));
    }
  }
  return lines;
}

/**
 * The most requests to `origin` that were on their way at once, from the network events of a page. A preflight is the browser's, and the
 * requests whose address `leave` (a RegExp) matches are not counted: the calls of something else than what is being looked at.
 */
export function mostAtOnce(events, origin, leave = null) {
  const start = new Map();
  const end = new Map();
  for (const e of events) {
    if (e.method === "Network.requestWillBeSent" && e.params.request.url.startsWith(origin) && e.params.request.method !== "OPTIONS"
      && !(leave && leave.test(e.params.request.url))) {
      start.set(e.params.requestId, e.params.timestamp);
    }
    if ((e.method === "Network.loadingFinished" || e.method === "Network.loadingFailed") && start.has(e.params.requestId)) {
      end.set(e.params.requestId, e.params.timestamp);
    }
  }
  const marks = [];
  for (const [id, t0] of start) {
    marks.push([t0, 1]);
    marks.push([end.get(id) ?? t0 + 60, -1]);
  }
  marks.sort((a, b) => a[0] - b[0] || a[1] - b[1]);
  let now = 0;
  let most = 0;
  for (const [, d] of marks) {
    now += d;
    most = Math.max(most, now);
  }
  return most;
}

/** The keys a check presses: what the protocol wants to know of each. */
const keys = {
  Tab: { key: "Tab", code: "Tab", windowsVirtualKeyCode: 9 },
  Enter: { key: "Enter", code: "Enter", windowsVirtualKeyCode: 13, text: "\r" },
  Escape: { key: "Escape", code: "Escape", windowsVirtualKeyCode: 27 },
  " ": { key: " ", code: "Space", windowsVirtualKeyCode: 32, text: " " },
};

export class Browser {
  #socket;
  #nextId = 1;
  #pending = new Map();
  #pages = new Map();

  constructor(child, profile) {
    this.child = child;
    this.profile = profile;
  }

  /** Starts the browser and connects to it. */
  static async launch() {
    const profile = mkdtempSync(join(tmpdir(), "pulse-cdp-"));
    const child = spawn(browserPath(), [
      "--headless=new", "--remote-debugging-port=0", `--user-data-dir=${profile}`, "--hide-scrollbars",
      "--no-first-run", "--no-default-browser-check", "--disable-gpu", "--force-device-scale-factor=1", "--disable-extensions", "about:blank",
    ], { stdio: "ignore" });
    const browser = new Browser(child, profile);
    await browser.#connect();
    return browser;
  }

  async #connect() {
    const file = join(this.profile, "DevToolsActivePort");
    let endpoint;
    for (let i = 0; i < 100 && !endpoint; i++) {
      if (existsSync(file)) {
        const [port, path] = readFileSync(file, "utf8").trim().split("\n");
        if (port && path) endpoint = `ws://127.0.0.1:${port}${path}`;
      }
      if (!endpoint) await sleep(100);
    }
    if (!endpoint) throw new Error("The browser did not say where to connect");
    this.#socket = new WebSocket(endpoint);
    await new Promise((resolve, reject) => { this.#socket.onopen = resolve; this.#socket.onerror = reject; });
    this.#socket.onmessage = (m) => this.#onMessage(JSON.parse(m.data));
  }

  #onMessage(message) {
    if (message.id) {
      const call = this.#pending.get(message.id);
      this.#pending.delete(message.id);
      if (message.error) call.reject(new Error(`${call.method}: ${message.error.message}`));
      else call.resolve(message.result);
    } else if (message.method && message.sessionId) {
      this.#pages.get(message.sessionId)?.onEvent(message);
    }
  }

  /** Sends a command; `sessionId` is the page's, or none for a command to the browser itself. */
  send(method, params = {}, sessionId) {
    const id = this.#nextId++;
    return new Promise((resolve, reject) => {
      this.#pending.set(id, { resolve, reject, method });
      this.#socket.send(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) }));
    });
  }

  /**
   * A page in a browser context of its own: no cookie, no storage, nothing shared with any other. Options: `width`, `height` and
   * `scheme` ("light" or "dark") of the window, `origin` (the page's own, which an answer made up by a check says it may be read by),
   * `intercept` (url patterns of the requests a check may answer itself: see `Page.rules`) and `axe` (the source of axe-core, put in
   * every document before its own scripts).
   */
  async newPage(options = {}) {
    const { browserContextId } = await this.send("Target.createBrowserContext", {});
    const { targetId } = await this.send("Target.createTarget", { url: "about:blank", browserContextId });
    const { sessionId } = await this.send("Target.attachToTarget", { targetId, flatten: true });
    const page = new Page(this, { sessionId, targetId, browserContextId, ...options });
    this.#pages.set(sessionId, page);
    await page.setup();
    return page;
  }

  forget(sessionId) {
    this.#pages.delete(sessionId);
  }

  /** Closes the browser and removes its profile. */
  async close() {
    try { this.#socket?.close(); } catch { /* already closed */ }
    this.child.kill();
    await sleep(300);
    try { rmSync(this.profile, { recursive: true, force: true }); } catch { /* the browser may still hold a file */ }
  }
}

export class Page {
  /**
   * What a check does with the requests it intercepts: a list of `{ test: RegExp on the url, method?, action, once?, ... }`, and
   * `action` is `hang` (never answered), `fail` (a 500, after `delay` ms if it says one) or `status` (the `status` and the `problem` it
   * says, as a problem document). A request no rule matches goes on, after `delayMs` if the page has one. The preflight of a request
   * across origins is never touched.
   */
  rules = [];
  delayMs = 0;
  events = [];

  constructor(browser, { sessionId, targetId, browserContextId, width = 1280, height = 900, scheme = "light", origin, intercept = [], axe }) {
    this.browser = browser;
    this.sessionId = sessionId;
    this.targetId = targetId;
    this.browserContextId = browserContextId;
    this.options = { width, height, scheme, origin, intercept, axe };
  }

  async setup() {
    const { width, height, scheme, intercept, axe } = this.options;
    for (const domain of ["Page", "Log", "Network", "Runtime"]) await this.send(`${domain}.enable`);
    if (intercept.length) await this.send("Fetch.enable", { patterns: intercept.map((urlPattern) => ({ urlPattern, requestStage: "Request" })) });
    await this.send("Page.addScriptToEvaluateOnNewDocument", {
      // every violation of the content security policy, which no policy can stop a listener from seeing
      source: "window.__violations = []; document.addEventListener('securitypolicyviolation', (e) => window.__violations.push(e.violatedDirective + ' ' + e.blockedURI));",
    });
    if (axe) await this.send("Page.addScriptToEvaluateOnNewDocument", { source: axe });
    await this.viewport(width, scheme, { height });
  }

  send(method, params = {}) {
    return this.browser.send(method, params, this.sessionId);
  }

  onEvent(message) {
    if (message.method === "Fetch.requestPaused") this.#paused(message.params);
    else this.events.push(message);
  }

  #paused({ requestId, request }) {
    const quiet = (promise) => promise.catch(() => {});
    const proceed = () => quiet(this.send("Fetch.continueRequest", { requestId }));
    if (request.method === "OPTIONS") return void proceed();
    const rule = this.rules.find((r) => r.test.test(request.url) && (!r.method || r.method === request.method));
    if (rule?.once) this.rules = this.rules.filter((r) => r !== rule);
    if (rule?.action === "hang") return;
    if (rule?.action === "status" || rule?.action === "fail") {
      const status = rule.action === "fail" ? 500 : rule.status;
      const problem = rule.problem || (rule.action === "fail" ? { title: "Internal Server Error", status: 500 } : { status });
      const answer = () => quiet(this.send("Fetch.fulfillRequest", {
        requestId,
        responseCode: status,
        responseHeaders: [
          { name: "Content-Type", value: "application/problem+json" },
          { name: "Access-Control-Allow-Origin", value: this.options.origin || "*" },
          { name: "Access-Control-Expose-Headers", value: "X-Request-Id" },
        ],
        body: Buffer.from(JSON.stringify(problem)).toString("base64"),
      }));
      return void (rule.delay ? setTimeout(answer, rule.delay) : answer());
    }
    if (this.delayMs) setTimeout(proceed, this.delayMs);
    else proceed();
  }

  async evaluate(expression, extra = {}) {
    const r = await this.send("Runtime.evaluate", { expression, returnByValue: true, awaitPromise: true, ...extra });
    if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description || r.exceptionDetails.text);
    return r.result.value;
  }

  /** Waits until `predicate` gives something that is not false, for `ms` at most: that, or null. */
  async waitFor(predicate, ms = 10000) {
    const end = Date.now() + ms;
    while (Date.now() < end) {
      try { const v = await predicate(); if (v) return v; } catch { /* the page is changing */ }
      await sleep(120);
    }
    return null;
  }

  here() { return this.evaluate("location.href"); }

  async path() {
    const u = new URL(await this.here());
    return u.pathname + u.search;
  }

  text() { return this.evaluate("document.body.innerText"); }

  async squashed() { return (await this.text()).replace(/\s+/g, " ").trim(); }

  async go(url) {
    await this.send("Page.navigate", { url });
    await sleep(400);
    await this.waitFor(async () => (await this.evaluate("document.readyState")) === "complete");
  }

  clearEvents() { this.events = []; }

  violations() { return this.evaluate("(window.__violations || []).map(String)"); }

  consoleProblems() { return problemsIn(this.events); }

  requestsTo(origin) {
    return this.events.filter((e) => e.method === "Network.requestWillBeSent" && e.params.request.url.startsWith(origin)).map((e) => e.params);
  }

  /** The requests to `origin` that are not preflights, in the order they were made. */
  callsTo(origin) {
    return this.requestsTo(origin).filter((r) => r.request.method !== "OPTIONS");
  }

  mostAtOnce(origin, leave = null) { return mostAtOnce(this.events, origin, leave); }

  /** What the page keeps in the places a token must never be: both storages, the cookies it can read and the names of its databases. */
  storage() {
    return this.evaluate(`(async () => JSON.stringify({
      local: Object.entries(localStorage), session: Object.entries(sessionStorage), cookie: document.cookie,
      databases: indexedDB.databases ? (await indexedDB.databases()).map((d) => d.name) : [],
    }))()`);
  }

  /** The size of the window and the preferences of the person that the page can ask about. */
  async viewport(width, scheme = "light", { height = 900, reducedMotion = false, forcedColors = false } = {}) {
    await this.send("Emulation.setDeviceMetricsOverride", { width, height, deviceScaleFactor: 1, mobile: width < 500 });
    await this.send("Emulation.setEmulatedMedia", {
      features: [
        { name: "prefers-color-scheme", value: scheme },
        { name: "prefers-reduced-motion", value: reducedMotion ? "reduce" : "no-preference" },
        { name: "forced-colors", value: forcedColors ? "active" : "none" },
      ],
    });
    await sleep(200);
  }

  /** A picture of the whole page at `width` in `scheme`, written to `file` (at most 3000 px high). `preferences` are those of `viewport`. */
  async shot(file, width, scheme, preferences = {}) {
    await this.viewport(width, scheme, preferences);
    const height = await this.evaluate("document.documentElement.scrollHeight");
    await this.send("Emulation.setDeviceMetricsOverride", { width, height: Math.min(Math.max(height, 400), 3000), deviceScaleFactor: 1, mobile: width < 500 });
    await sleep(150);
    const s = await this.send("Page.captureScreenshot", { format: "png" });
    writeFileSync(file, Buffer.from(s.data, "base64"));
    await this.viewport(width, scheme, preferences);
  }

  /** Presses and releases a key (`Tab`, `Enter`, `Escape` or a space), with shift held if `shift`. */
  async key(name, { shift = false } = {}) {
    const k = keys[name];
    if (!k) throw new Error(`No key ${name}`);
    const modifiers = shift ? 8 : 0;
    await this.send("Input.dispatchKeyEvent", { type: "keyDown", modifiers, ...k });
    await this.send("Input.dispatchKeyEvent", { type: "keyUp", modifiers, ...k });
  }

  /** What axe-core finds wrong in the page as it is now, as `rule: selector` lines (nothing when the page has no axe, or it is right). */
  async axe(tags = ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa", "best-practice"]) {
    return JSON.parse(await this.evaluate(`axe.run(document, { runOnly: { type: 'tag', values: ${JSON.stringify(tags)} }, resultTypes: ['violations'] })
      .then((r) => JSON.stringify(r.violations.map((v) => ({ id: v.id, impact: v.impact, nodes: v.nodes.map((n) => n.target.join(' ')).slice(0, 4), count: v.nodes.length }))))`));
  }

  async close() {
    this.browser.forget(this.sessionId);
    try { await this.browser.send("Target.closeTarget", { targetId: this.targetId }); } catch { /* already closed */ }
    try { await this.browser.send("Target.disposeBrowserContext", { browserContextId: this.browserContextId }); } catch { /* already gone */ }
  }
}
