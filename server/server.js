// CarMirror signaling server.
//
// - Serves the car web client (public/) and the APK download.
// - Pairs a car browser with a phone using a 6-digit code shown in the phone app,
//   then hands the car a long-lived token (kept in the car's localStorage).
// - Relays WebRTC signaling (SDP + ICE) between a paired car and phone. Media never
//   goes through here: it flows car <-> phone directly over the hotspot.
// - Collects car-side logs (/api/log) so problems seen in the car can be debugged later.

import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { WebSocketServer } from 'ws';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PORT = Number(process.env.PORT || 8080);
const DATA_DIR = process.env.DATA_DIR || path.join(__dirname, 'data');
const APK_DIR = process.env.APK_DIR || path.join(__dirname, 'apk');
const PUBLIC_DIR = path.join(__dirname, 'public');
const STATE_FILE = path.join(DATA_DIR, 'state.json');
const LOG_FILE = path.join(DATA_DIR, 'car.log');
const LOG_MAX_BYTES = 5 * 1024 * 1024;

fs.mkdirSync(DATA_DIR, { recursive: true });

// ---------------------------------------------------------------- state

/** devices: deviceId -> { secretHash, name, created }; cars: tokenHash -> { deviceId, created, lastSeen } */
let state = { devices: {}, cars: {} };
try {
  state = JSON.parse(fs.readFileSync(STATE_FILE, 'utf8'));
} catch {
  // first run
}
let saveTimer = null;
function saveState() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => {
    fs.writeFileSync(STATE_FILE + '.tmp', JSON.stringify(state, null, 2));
    fs.renameSync(STATE_FILE + '.tmp', STATE_FILE);
  }, 200);
}

const sha256 = (s) => crypto.createHash('sha256').update(s).digest('hex');
const randomToken = () => crypto.randomBytes(24).toString('base64url');

// ---------------------------------------------------------------- logging

function appendLog(line) {
  try {
    const st = fs.existsSync(LOG_FILE) ? fs.statSync(LOG_FILE) : null;
    if (st && st.size > LOG_MAX_BYTES) fs.renameSync(LOG_FILE, LOG_FILE + '.1');
    fs.appendFileSync(LOG_FILE, line + '\n');
  } catch (e) {
    console.error('log write failed', e);
  }
}

function log(...args) {
  console.log(new Date().toISOString(), ...args);
}

// ---------------------------------------------------------------- http

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.json': 'application/json',
  '.webmanifest': 'application/manifest+json',
  '.apk': 'application/vnd.android.package-archive',
};

function clientIp(req) {
  const xff = req.headers['x-forwarded-for'];
  if (xff) return String(xff).split(',')[0].trim();
  return req.socket.remoteAddress || '?';
}

function readBody(req, limit) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > limit) {
        reject(new Error('too large'));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

function sendFile(res, file, extraHeaders = {}) {
  fs.stat(file, (err, st) => {
    if (err || !st.isFile()) {
      res.writeHead(404, { 'content-type': 'text/plain' });
      res.end('not found');
      return;
    }
    res.writeHead(200, {
      'content-type': MIME[path.extname(file)] || 'application/octet-stream',
      'content-length': st.size,
      'cache-control': 'no-cache',
      ...extraHeaders,
    });
    fs.createReadStream(file).pipe(res);
  });
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://x');
  const p = url.pathname;

  if (p === '/healthz') {
    res.writeHead(200, { 'content-type': 'text/plain' });
    res.end('ok');
    return;
  }

  if (p === '/api/log' && req.method === 'POST') {
    try {
      const body = await readBody(req, 64 * 1024);
      const entries = JSON.parse(body);
      const ip = clientIp(req);
      for (const e of Array.isArray(entries) ? entries.slice(0, 200) : [entries]) {
        appendLog(JSON.stringify({ at: new Date().toISOString(), ip, ...e }).slice(0, 4000));
      }
      res.writeHead(204);
      res.end();
    } catch {
      res.writeHead(400);
      res.end();
    }
    return;
  }

  if (p === '/api/version') {
    const apk = path.join(APK_DIR, 'carmirror.apk');
    let apkInfo = null;
    try {
      apkInfo = JSON.parse(fs.readFileSync(path.join(APK_DIR, 'version.json'), 'utf8'));
    } catch {
      // no version file
    }
    res.writeHead(200, { 'content-type': 'application/json', 'cache-control': 'no-cache' });
    res.end(JSON.stringify({ apk: fs.existsSync(apk) ? apkInfo || {} : null }));
    return;
  }

  if (p === '/download/carmirror.apk' || p === '/carmirror.apk') {
    sendFile(res, path.join(APK_DIR, 'carmirror.apk'), {
      'content-disposition': 'attachment; filename="carmirror.apk"',
    });
    return;
  }

  // static
  let rel = p === '/' ? '/index.html' : p;
  if (rel === '/diag') rel = '/diag.html';
  if (rel === '/get') rel = '/get.html';
  const file = path.normalize(path.join(PUBLIC_DIR, rel));
  if (!file.startsWith(PUBLIC_DIR)) {
    res.writeHead(403);
    res.end();
    return;
  }
  sendFile(res, file);
});

// ---------------------------------------------------------------- signaling

/** deviceId -> phone ws */
const phones = new Map();
/** deviceId -> Set of car ws */
const carsByDevice = new Map();
/** pair code -> deviceId */
const pairCodes = new Map();
/** ip -> { count, resetAt } failed pairing attempts */
const pairFailures = new Map();

const PAIR_CODE_TTL_MS = 10 * 60 * 1000;
const MAX_PAIR_FAILURES = 20;

function send(ws, msg) {
  if (ws && ws.readyState === ws.OPEN) ws.send(JSON.stringify(msg));
}

function newPairCode(deviceId) {
  for (const [code, entry] of pairCodes) {
    if (entry.deviceId === deviceId) pairCodes.delete(code);
  }
  let code;
  do {
    code = String(crypto.randomInt(0, 1_000_000)).padStart(6, '0');
  } while (pairCodes.has(code));
  pairCodes.set(code, { deviceId, expires: Date.now() + PAIR_CODE_TTL_MS });
  return code;
}

setInterval(() => {
  const now = Date.now();
  for (const [code, entry] of pairCodes) {
    if (entry.expires < now) {
      pairCodes.delete(code);
      const phone = phones.get(entry.deviceId);
      if (phone) send(phone, { t: 'pairCode', code: newPairCode(entry.deviceId) });
    }
  }
  for (const [ip, f] of pairFailures) if (f.resetAt < now) pairFailures.delete(ip);
}, 30_000);

function carsOf(deviceId) {
  let set = carsByDevice.get(deviceId);
  if (!set) {
    set = new Set();
    carsByDevice.set(deviceId, set);
  }
  return set;
}

function notifyCars(deviceId, msg) {
  for (const car of carsOf(deviceId)) send(car, msg);
}

function bindCar(ws, deviceId, tokenHash) {
  ws.deviceId = deviceId;
  ws.tokenHash = tokenHash;
  carsOf(deviceId).add(ws);
  const dev = state.devices[deviceId];
  send(ws, { t: 'paired', name: dev?.name || 'Phone', online: phones.has(deviceId) });
}

function handlePhone(ws, msg) {
  switch (msg.t) {
    case 'hello': {
      const { deviceId, deviceSecret, name } = msg;
      if (typeof deviceId !== 'string' || typeof deviceSecret !== 'string' || deviceId.length < 8 || deviceSecret.length < 16) {
        send(ws, { t: 'error', msg: 'bad hello' });
        ws.close();
        return;
      }
      const known = state.devices[deviceId];
      if (known && known.secretHash !== sha256(deviceSecret)) {
        send(ws, { t: 'error', msg: 'device secret mismatch' });
        ws.close();
        return;
      }
      if (!known) {
        state.devices[deviceId] = { secretHash: sha256(deviceSecret), name: String(name || 'Phone').slice(0, 60), created: Date.now() };
      } else if (name) {
        known.name = String(name).slice(0, 60);
      }
      saveState();
      const prev = phones.get(deviceId);
      if (prev && prev !== ws) prev.close(4000, 'replaced');
      ws.role = 'phone';
      ws.deviceId = deviceId;
      phones.set(deviceId, ws);
      const pairedCars = Object.values(state.cars).filter((c) => c.deviceId === deviceId).length;
      send(ws, { t: 'welcome', pairCode: newPairCode(deviceId), pairedCars });
      notifyCars(deviceId, { t: 'phone', online: true });
      log('phone online', deviceId.slice(0, 8), state.devices[deviceId].name);
      break;
    }
    case 'newCode':
      if (ws.deviceId) send(ws, { t: 'pairCode', code: newPairCode(ws.deviceId) });
      break;
    case 'unpairAll':
      if (ws.deviceId) {
        for (const [h, c] of Object.entries(state.cars)) if (c.deviceId === ws.deviceId) delete state.cars[h];
        saveState();
        for (const car of carsOf(ws.deviceId)) {
          send(car, { t: 'unpaired' });
          car.close();
        }
        send(ws, { t: 'unpaired', pairedCars: 0 });
      }
      break;
    case 'signal': {
      // to a specific car connection
      if (!ws.deviceId) return;
      for (const car of carsOf(ws.deviceId)) {
        if (car.connId === msg.to) send(car, { t: 'signal', data: msg.data });
      }
      break;
    }
    default:
      break;
  }
}

function handleCar(ws, msg, ip) {
  switch (msg.t) {
    case 'hello': {
      ws.role = 'car';
      ws.connId = crypto.randomBytes(8).toString('hex');
      if (typeof msg.carToken === 'string') {
        const h = sha256(msg.carToken);
        const entry = state.cars[h];
        if (entry && state.devices[entry.deviceId]) {
          entry.lastSeen = Date.now();
          saveState();
          bindCar(ws, entry.deviceId, h);
          return;
        }
      }
      send(ws, { t: 'needPair' });
      break;
    }
    case 'pair': {
      const f = pairFailures.get(ip) || { count: 0, resetAt: Date.now() + 15 * 60 * 1000 };
      if (f.count >= MAX_PAIR_FAILURES) {
        send(ws, { t: 'pairFailed', msg: 'Too many attempts. Wait 15 minutes.' });
        return;
      }
      const entry = pairCodes.get(String(msg.code || ''));
      if (!entry || entry.expires < Date.now()) {
        f.count++;
        pairFailures.set(ip, f);
        send(ws, { t: 'pairFailed', msg: 'Wrong or expired code' });
        return;
      }
      pairCodes.delete(String(msg.code));
      const token = randomToken();
      const h = sha256(token);
      state.cars[h] = { deviceId: entry.deviceId, created: Date.now(), lastSeen: Date.now() };
      saveState();
      send(ws, { t: 'token', carToken: token });
      bindCar(ws, entry.deviceId, h);
      const phone = phones.get(entry.deviceId);
      if (phone) {
        const pairedCars = Object.values(state.cars).filter((c) => c.deviceId === entry.deviceId).length;
        send(phone, { t: 'carPaired', pairedCars });
        send(phone, { t: 'pairCode', code: newPairCode(entry.deviceId) });
      }
      log('car paired', entry.deviceId.slice(0, 8));
      break;
    }
    case 'signal': {
      if (!ws.deviceId) return;
      const phone = phones.get(ws.deviceId);
      if (!phone) {
        send(ws, { t: 'phone', online: false });
        return;
      }
      send(phone, { t: 'signal', from: ws.connId, data: msg.data });
      break;
    }
    case 'forget': {
      if (ws.tokenHash) {
        delete state.cars[ws.tokenHash];
        saveState();
      }
      ws.close();
      break;
    }
    default:
      break;
  }
}

const wss = new WebSocketServer({ server, path: '/ws', maxPayload: 1024 * 1024 });

wss.on('connection', (ws, req) => {
  const ip = clientIp(req);
  ws.isAlive = true;
  ws.on('pong', () => {
    ws.isAlive = true;
  });
  ws.on('message', (raw) => {
    let msg;
    try {
      msg = JSON.parse(raw.toString());
    } catch {
      return;
    }
    if (!msg || typeof msg.t !== 'string') return;
    if (msg.t === 'ping') {
      send(ws, { t: 'pong', ts: msg.ts });
      return;
    }
    const role = ws.role || msg.role;
    if (role === 'phone') handlePhone(ws, msg);
    else if (role === 'car') handleCar(ws, msg, ip);
  });
  ws.on('close', () => {
    if (ws.role === 'phone' && phones.get(ws.deviceId) === ws) {
      phones.delete(ws.deviceId);
      for (const [code, entry] of pairCodes) if (entry.deviceId === ws.deviceId) pairCodes.delete(code);
      notifyCars(ws.deviceId, { t: 'phone', online: false });
      log('phone offline', ws.deviceId.slice(0, 8));
    } else if (ws.role === 'car' && ws.deviceId) {
      carsOf(ws.deviceId).delete(ws);
      const phone = phones.get(ws.deviceId);
      if (phone) send(phone, { t: 'carGone', from: ws.connId });
    }
  });
});

// keepalive: drop dead sockets (mobile networks love half-open TCP)
setInterval(() => {
  for (const ws of wss.clients) {
    if (!ws.isAlive) {
      ws.terminate();
      continue;
    }
    ws.isAlive = false;
    ws.ping();
  }
}, 20_000);

server.listen(PORT, () => log(`carmirror server on :${PORT}`));
