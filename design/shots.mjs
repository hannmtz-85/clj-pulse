// Screenshots of the mock-ups with headless Chrome or Edge, over the DevTools protocol. No dependencies: Node
// has a WebSocket client of its own. `bb design:shots` writes the specification and runs this.
//
//   node design/shots.mjs <specification.json>
//
// The specification: { "browser": "<path>", "dist": "<directory of the pages>", "out": "<directory of the images>",
//   "shots": [ { "page": "index.html", "theme": "pulse-teal", "width": 1280, "mobile": false, "name": "index-teal.png" } ] }
// Each image is the whole page at that width, or the slice of it that "clip": { "y": 0, "height": 1200 } says. The theme is chosen with `?theme=`, which the page's review script
// reads, and the fonts are loaded before the picture is taken.
import { spawn } from "node:child_process";
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync, existsSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { pathToFileURL } from "node:url";

const spec = JSON.parse(readFileSync(process.argv[2], "utf8"));
const profile = mkdtempSync(join(tmpdir(), "pulse-shots-"));
mkdirSync(spec.out, { recursive: true });

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

const browser = spawn(spec.browser, [
  "--headless=new", "--remote-debugging-port=0", `--user-data-dir=${profile}`, "--hide-scrollbars",
  "--no-first-run", "--no-default-browser-check", "--disable-gpu", "--force-device-scale-factor=1",
  "--disable-extensions", "--allow-file-access-from-files", "about:blank",
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

function send(method, params = {}, sessionId) {
  const id = nextId++;
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject, method });
    socket.send(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) }));
  });
}

function event(method, sessionId) {
  return new Promise((resolve) => waiting.push({ method, sessionId, resolve }));
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

async function main() {
  socket = new WebSocket(await endpoint());
  await new Promise((resolve, reject) => { socket.onopen = resolve; socket.onerror = reject; });
  socket.onmessage = (m) => onMessage(m.data);

  const { targetId } = await send("Target.createTarget", { url: "about:blank" });
  const { sessionId } = await send("Target.attachToTarget", { targetId, flatten: true });
  await send("Page.enable", {}, sessionId);

  for (const shot of spec.shots) {
    const url = pathToFileURL(join(spec.dist, shot.page)).href + (shot.theme ? `?theme=${shot.theme}` : "");
    const metrics = (height) => ({ width: shot.width, height, deviceScaleFactor: 1, mobile: Boolean(shot.mobile) });
    await send("Emulation.setDeviceMetricsOverride", metrics(900), sessionId);
    const loaded = event("Page.loadEventFired", sessionId);
    await send("Page.navigate", { url }, sessionId);
    await loaded;
    await send("Runtime.evaluate", { expression: "document.fonts.ready.then(() => true)", awaitPromise: true }, sessionId);
    await sleep(150);
    const { result } = await send("Runtime.evaluate", { expression: "document.documentElement.scrollHeight", returnByValue: true }, sessionId);
    await send("Emulation.setDeviceMetricsOverride", metrics(Math.min(result.value, 16000)), sessionId);
    await sleep(100);
    const clip = shot.clip ? { x: 0, y: shot.clip.y, width: shot.width, height: shot.clip.height, scale: 1 } : undefined;
    const { data } = await send("Page.captureScreenshot", { format: "png", ...(clip ? { clip } : {}) }, sessionId);
    writeFileSync(join(spec.out, shot.name), Buffer.from(data, "base64"));
    console.log(`Wrote ${join(spec.out, shot.name)} (${shot.width} x ${result.value})`);
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
