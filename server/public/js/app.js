// CarMirror car client: pairing, connection to the phone, launcher and app panes.

import { log, warn } from './remotelog.js';
import { Signaling, PhoneLink } from './rtc.js';
import { Player, detectDecoder } from './player.js';
import { AudioPlayer } from './audio.js';

const VERSION = '1.0.0';
const MAX_LONG_SIDE = 1920; // encoder-friendly cap; Tesla screens are 1920 px wide anyway

const $ = (sel, root = document) => root.querySelector(sel);

// ------------------------------------------------------------------ storage

function load(key, fallback) {
  try {
    const v = localStorage.getItem(key);
    return v == null ? fallback : JSON.parse(v);
  } catch {
    return fallback;
  }
}
function save(key, value) {
  try {
    if (value == null) localStorage.removeItem(key);
    else localStorage.setItem(key, JSON.stringify(value));
  } catch {
    // storage unavailable: pairing will just be asked again next time
  }
}

const settings = Object.assign({ bitrate: 8, fps: 60, scale: 1.5, stats: false, focus: true, sound: true, relay: false }, load('cm.settings', {}));
const zoom = load('cm.zoom', {}); // per-app zoom factor (car-sized screens)
const audio = new AudioPlayer();
audio.setEnabled(settings.sound);
audio.onNeedGesture = () => toast('Tap the screen once to turn on the sound', 6000);

const state = {
  sig: null,
  link: null,
  paired: false,
  phoneName: 'Phone',
  phoneOnline: false,
  apps: [],
  layout: load('cm.layout', 'full'),
  panes: [],
  restore: load('cm.panes', []), // [{pkg,label}|null]
  reconnectTimer: null,
  reconnectDelay: 1000,
  rtt: null,
  path: null,
};

// ------------------------------------------------------------------ UI helpers

const screens = ['pair', 'msg', 'panes'];
function show(name) {
  for (const s of screens) $('#screen-' + s).hidden = s !== name;
  $('#btn-layout').hidden = name !== 'panes' || state.mode === 'screen';
  $('#btn-immersive').hidden = name !== 'panes';
}

function setStatus(text, level) {
  $('#status').textContent = text;
  $('#dot').className = 'dot ' + (level || '');
}

function message(title, body, { spinner = true, actions = [] } = {}) {
  $('#msg-title').textContent = title;
  $('#msg-body').innerHTML = body || '';
  $('#msg-spinner').hidden = !spinner;
  const box = $('#msg-actions');
  box.innerHTML = '';
  for (const a of actions) {
    const b = document.createElement('button');
    b.className = 'btn' + (a.primary ? ' primary' : '');
    b.textContent = a.label;
    b.onclick = a.onClick;
    box.appendChild(b);
  }
  show('msg');
}

let toastTimer = null;
function toast(text, ms = 4000) {
  const t = $('#toast');
  t.textContent = text;
  t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => (t.hidden = true), ms);
}

// ------------------------------------------------------------------ pairing

let code = '';
function renderCode() {
  $('#code-display').textContent = code.padEnd(6, '_');
}
function buildKeypad() {
  const pad = $('#keypad');
  const keys = ['1', '2', '3', '4', '5', '6', '7', '8', '9', '⌫', '0', 'OK'];
  for (const k of keys) {
    const b = document.createElement('button');
    b.textContent = k;
    b.onclick = () => {
      $('#pair-error').textContent = '';
      if (k === '⌫') code = code.slice(0, -1);
      else if (k === 'OK') submitCode();
      else if (code.length < 6) code += k;
      renderCode();
      if (code.length === 6 && k !== 'OK') submitCode();
    };
    pad.appendChild(b);
  }
}
function submitCode() {
  if (code.length !== 6) return;
  if (!state.sig?.open) {
    $('#pair-error').textContent = 'No connection to the server. Retrying…';
    log('pair submit while signaling closed');
    return;
  }
  log('pair submit');
  $('#pair-error').textContent = 'Checking…';
  state.sig.send({ t: 'pair', code });
}

// ------------------------------------------------------------------ signaling

function onSigOpen(open) {
  log('signaling ' + (open ? 'open' : 'closed'));
  if (open) {
    state.sig.send({ t: 'hello', role: 'car', carToken: load('cm.carToken', null) });
  } else {
    setStatus('Server offline, retrying…', 'warn');
  }
}

function onSig(msg) {
  if (msg.t !== 'signal') log('sig <- ' + msg.t);
  switch (msg.t) {
    case 'needPair':
      state.paired = false;
      save('cm.carToken', null);
      code = '';
      renderCode();
      setStatus('Not paired', 'warn');
      show('pair');
      break;
    case 'token':
      save('cm.carToken', msg.carToken);
      break;
    case 'pairFailed':
      code = '';
      renderCode();
      $('#pair-error').textContent = (msg.msg || 'Pairing failed') + '. Check the code on the phone and try again.';
      warn('pair failed: ' + msg.msg);
      break;
    case 'paired':
      state.paired = true;
      state.turn = msg.turn || null;
      state.phoneName = msg.name || 'Phone';
      state.phoneOnline = !!msg.online;
      onPhonePresence();
      break;
    case 'phone':
      state.phoneOnline = !!msg.online;
      onPhonePresence();
      break;
    case 'unpaired':
      save('cm.carToken', null);
      location.reload();
      break;
    case 'signal':
      state.link?.onSignal(msg.data);
      break;
    default:
      break;
  }
}

function onPhonePresence() {
  if (!state.paired) return;
  if (state.phoneOnline) {
    if (!state.link) connectPhone();
  } else if (!state.link) {
    setStatus(`${state.phoneName} is offline`, 'warn');
    message(
      `Waiting for ${state.phoneName}`,
      'Open <b>CarMirror</b> on the phone and tap <b>Start</b>. Turn on the phone\'s hotspot and connect the car to it ' +
        '(Wi-Fi in the car\'s settings). The phone needs mobile data.',
    );
  }
}

// ------------------------------------------------------------------ phone link

function connectPhone() {
  clearTimeout(state.reconnectTimer);
  if (state.link) return;
  setStatus(`Connecting to ${state.phoneName}…`, 'warn');
  if (!state.panes.length || $('#screen-panes').hidden) {
    message(`Connecting to ${state.phoneName}…`, 'Setting up a direct link over the hotspot.');
  }
  state.link = new PhoneLink(state.sig, {
    onOpen: onLinkOpen,
    onClose: onLinkClose,
    onCtl,
    onAudio: (buf) => audio.feed(buf),
    turn: settings.relay && state.turn ? state.turn : null,
  });
}

function onLinkOpen() {
  state.reconnectDelay = 1000;
  setStatus(`${state.phoneName} · connected`, 'ok');
  state.link.sendCtl({
    t: 'hello',
    version: VERSION,
    ua: navigator.userAgent,
    screen: { w: screen.width, h: screen.height, dpr: devicePixelRatio, vw: innerWidth, vh: innerHeight },
  });
  state.link.sendCtl({ t: 'apps?' });
  state.link.sendCtl({ t: 'audio', on: settings.sound && AudioPlayer.supported() });
  buildPanes();
  show('panes');
  refreshPath();
  setTimeout(async () => {
    const info = await state.link?.pathInfo();
    if (info) log('link path', info);
  }, 1500);
}

function onLinkClose(reason) {
  const hadPanes = state.panes.some((p) => p.sid);
  for (const p of state.panes) p.detach();
  state.link = null;
  if (!state.paired) return;
  warn('link down: ' + reason);
  if (reason === 'timeout' || reason === 'connection failed') {
    setStatus('No direct connection', 'bad');
    const actions = [];
    if (!settings.relay && state.turn) {
      actions.push({
        label: 'Connect through the server',
        primary: true,
        onClick: () => {
          settings.relay = true;
          save('cm.settings', settings);
          clearTimeout(state.reconnectTimer);
          connectPhone();
        },
      });
    }
    message(
      'Can\'t reach the phone directly',
      'The Tesla browser can\'t reach a normal phone hotspot. Best fix: run <b>Shizuku</b> on the phone, then CarMirror ' +
        'switches the hotspot to <b>car mode</b> by itself and the car reconnects in a few seconds. ' +
        'Or connect through the server: always works, but uses mobile data both ways (about 1.8 GB/h at 4 Mbps). ' +
        'Retrying automatically…',
      { actions },
    );
  } else if (hadPanes) {
    setStatus('Reconnecting…', 'warn');
  }
  if (state.phoneOnline) {
    state.reconnectTimer = setTimeout(connectPhone, state.reconnectDelay);
    state.reconnectDelay = Math.min(state.reconnectDelay * 2, 10000);
  } else {
    onPhonePresence();
  }
}

function onCtl(msg) {
  switch (msg.t) {
    case 'apps':
      state.apps = msg.apps || [];
      for (const p of state.panes) p.renderLauncher();
      restorePanes();
      break;
    case 'icon': {
      state.icons = state.icons || {};
      state.icons[msg.pkg] = msg.icon;
      const app = state.apps.find((a) => a.pkg === msg.pkg);
      if (app) {
        app.icon = msg.icon;
        for (const img of document.querySelectorAll(`img[data-pkg="${CSS.escape(msg.pkg)}"]`)) img.src = 'data:image/png;base64,' + msg.icon;
      }
      break;
    }
    case 'started':
      break;
    case 'ended': {
      if (msg.reason || msg.log) warn('session ended: ' + (msg.reason || ''), msg.log ? { log: msg.log } : undefined);
      const pane = state.panes.find((p) => p.sid === msg.sid);
      if (pane) {
        pane.stop(false);
        if (msg.reason) toast(msg.reason, 7000);
      }
      break;
    }
    case 'error': {
      const pane = msg.sid != null ? state.panes.find((p) => p.sid === msg.sid) : null;
      if (pane) pane.showStageMsg(msg.msg);
      else toast(msg.msg, 7000);
      break;
    }
    case 'caps':
      state.mode = msg.mode;
      log('phone mode ' + msg.mode);
      if (msg.mode === 'screen' && state.layout === 'split') setLayout('full');
      $('#btn-layout').hidden = msg.mode === 'screen' || $('#screen-panes').hidden;
      for (const p of state.panes) p.setChrome();
      break;
    case 'bars': {
      const pane = state.panes.find((p) => p.sid === msg.sid);
      if (pane) {
        pane.bars = { l: msg.l, t: msg.tp, r: msg.r, b: msg.b };
        pane.applyCrop();
      }
      break;
    }
    case 'fg': {
      log('phone foreground ' + msg.pkg + (msg.home ? ' (home)' : ''));
      // screen mode: the phone's home screen is replaced by our own app grid in focus mode
      const pane = state.panes.find((p) => p.sid);
      if (pane && state.mode === 'screen') {
        if (msg.home && settings.focus) pane.showOverlay('home');
        else if (!msg.home) {
          if (pane.overlay === 'home') pane.hideOverlay();
          const app = state.apps.find((a) => a.pkg === msg.pkg);
          if (app && pane.app && app.pkg !== pane.app.pkg) {
            pane.app = app;
            pane.setChrome();
            savePanes();
          }
        }
      }
      break;
    }
    case 'media':
      updateMediaSession(msg);
      break;
    case 'stats': {
      const pane = state.panes.find((p) => p.sid === msg.sid);
      if (pane) pane.onPhoneStats(msg);
      break;
    }
    case 'log':
      log('phone: ' + msg.msg);
      break;
    case 'pong':
      state.rtt = Math.round(performance.now() - msg.ts);
      break;
    case 'status':
      if (msg.msg) toast(msg.msg, 7000);
      break;
    default:
      break;
  }
}

// ------------------------------------------------------------------ Tesla media card

/** What's playing on the phone -> the media card the Tesla shows for the browser (app icon + title). */
function updateMediaSession(m) {
  if (!('mediaSession' in navigator) || typeof MediaMetadata === 'undefined') return;
  const app = state.apps.find((a) => a.pkg === m.pkg);
  const label = (app && app.label) || m.label || 'CarMirror';
  const icon = (app && app.icon) || state.icons?.[m.pkg];
  try {
    navigator.mediaSession.metadata = new MediaMetadata({
      title: m.title || label,
      artist: m.title ? m.artist || label : '',
      album: m.title ? label : '',
      artwork: icon ? [{ src: 'data:image/png;base64,' + icon, sizes: '96x96', type: 'image/png' }] : [],
    });
    navigator.mediaSession.playbackState = m.playing ? 'playing' : 'paused';
  } catch (e) {
    warn('media session: ' + e);
  }
}

if ('mediaSession' in navigator) {
  const key = (k) => () => state.link?.sendCtl({ t: 'mediaKey', k });
  for (const [action, k] of [['play', 'play'], ['pause', 'pause'], ['nexttrack', 'next'], ['previoustrack', 'prev']]) {
    try {
      navigator.mediaSession.setActionHandler(action, key(k));
    } catch {
      // action not supported by this browser
    }
  }
}

setInterval(() => {
  if (state.link) state.link.sendCtl({ t: 'ping', ts: performance.now() });
}, 2000);

async function refreshPath() {
  if (!state.link) return;
  const info = await state.link.pathInfo();
  if (info) {
    state.path = info;
    const rtt = info.rttMs ?? state.rtt;
    setStatus(
      `${state.phoneName} · ${info.direct ? 'direct' : info.relayed ? 'via server' : 'via internet'}${rtt != null ? ` · ${rtt} ms` : ''}`,
      info.direct ? 'ok' : 'warn',
    );
  }
}
setInterval(refreshPath, 5000);

// ------------------------------------------------------------------ panes

let sidCounter = Math.floor(Math.random() * 1e6);

class Pane {
  constructor(index) {
    this.index = index;
    this.el = $('#tpl-pane').content.firstElementChild.cloneNode(true);
    this.grid = $('.grid', this.el);
    this.launcher = $('.launcher', this.el);
    this.stage = $('.stage', this.el);
    this.canvas = $('canvas', this.stage);
    this.stageMsg = $('.stage-msg', this.stage);
    this.body = $('.pane-body', this.el);
    this.title = $('.pane-title', this.el);
    this.statsEl = $('.pane-stats', this.el);
    this.sid = null;
    this.app = null;
    this.player = null;
    this.lastSize = null;

    $('.back', this.el).onclick = () => this.sid && state.link?.sendCtl({ t: 'key', sid: this.sid, k: 'back' });
    $('.keyframe', this.el).onclick = () => this.sid && state.link?.sendCtl({ t: 'reset', sid: this.sid });
    $('.zoom-out', this.el).onclick = () => this.zoomBy(1 / 1.15);
    $('.zoom-in', this.el).onclick = () => this.zoomBy(1.15);
    $('.apps', this.el).onclick = () => {
      if (state.mode === 'screen' && this.sid) {
        if (this.overlay) this.hideOverlay();
        else this.showOverlay('apps');
      } else {
        this.stop(true);
      }
    };
    $('.close', this.el).onclick = () => {
      this.stop(true);
      if (state.layout === 'split') setLayout('full', this.index === 0 ? 1 : 0);
    };

    this.resizeObs = new ResizeObserver(() => this.onResize());
    this.resizeObs.observe(this.body);
    this.renderLauncher();
    this.setChrome();
  }

  setChrome() {
    const running = !!this.sid;
    $('.back', this.el).hidden = !running;
    $('.keyframe', this.el).hidden = !running;
    $('.zoom-out', this.el).hidden = !running || state.mode !== 'apps';
    $('.zoom-in', this.el).hidden = !running || state.mode !== 'apps';
    $('.apps', this.el).hidden = !running;
    $('.close', this.el).hidden = !running && state.layout !== 'split';
    this.title.textContent = running ? this.app.label : state.layout === 'split' ? 'Choose an app' : 'Apps on ' + state.phoneName;
    this.statsEl.hidden = !settings.stats || !running;
  }

  renderLauncher() {
    this.grid.innerHTML = '';
    if (!state.apps.length) {
      this.grid.innerHTML = '<p class="launcher-empty">No apps yet. Pick the apps to show in the car in the CarMirror app on the phone.</p>';
      return;
    }
    for (const app of state.apps) {
      const b = document.createElement('button');
      b.className = 'app-tile';
      const img = document.createElement('img');
      img.alt = '';
      img.dataset.pkg = app.pkg;
      if (app.icon) img.src = 'data:image/png;base64,' + app.icon;
      const span = document.createElement('span');
      span.textContent = app.label;
      b.append(img, span);
      b.onclick = () => {
        if (this.sid && state.mode === 'screen') {
          // one phone screen: switching apps reuses the running stream
          state.link?.sendCtl({ t: 'launch', sid: this.sid, pkg: app.pkg });
          this.app = app;
          this.hideOverlay();
          this.setChrome();
          savePanes();
        } else {
          this.open(app);
        }
      };
      this.grid.appendChild(b);
    }
  }

  applyCrop() {
    if (!this.player) return;
    const zero = { l: 0, t: 0, r: 0, b: 0 };
    this.player.setCrop(settings.focus && this.bars ? this.bars : zero);
  }

  /** Show the app grid over a running screen-mode stream (switching apps keeps the stream). */
  showOverlay(why) {
    this.overlay = why;
    this.launcher.classList.add('overlay');
    this.launcher.hidden = false;
  }

  hideOverlay() {
    this.overlay = null;
    this.launcher.classList.remove('overlay');
    if (this.sid) this.launcher.hidden = true;
  }

  /** Virtual display size in device pixels for the current stage, plus a dpi that keeps text readable. */
  targetSize() {
    // measure the pane body: the stage itself may still be hidden (0x0) when an app opens
    const r = this.body.getBoundingClientRect();
    let cssW = Math.max(200, Math.round(r.width));
    let cssH = Math.max(200, Math.round(r.height));
    const dpr = devicePixelRatio || 1;
    let w = cssW * dpr;
    let h = cssH * dpr;
    const long = Math.max(w, h);
    if (long > MAX_LONG_SIDE) {
      const k = MAX_LONG_SIDE / long;
      w *= k;
      h *= k;
    }
    w = Math.floor(w / 8) * 8;
    h = Math.floor(h / 8) * 8;
    const pxPerCss = w / cssW;
    const z = (this.app && zoom[this.app.pkg]) || 1;
    const dpi = Math.round(160 * settings.scale * pxPerCss * z);
    return { w, h, dpi };
  }

  open(app, { light = false } = {}) {
    if (!state.link) return;
    this.stop(true);
    if (!light) this.degraded = false;
    this.slowReports = 0;
    this.app = app;
    this.sid = ++sidCounter;
    const sid = this.sid;
    let { w, h, dpi } = this.targetSize();
    if (this.degraded) {
      // 2/3 resolution at 30 fps: roughly a third of the encoding work
      w = Math.floor((w * 2) / 3 / 8) * 8;
      h = Math.floor((h * 2) / 3 / 8) * 8;
      dpi = Math.round((dpi * 2) / 3);
    }
    this.lastSize = { w, h };
    this.bars = null;
    this.overlay = null;
    this.launcher.classList.remove('overlay');
    this.launcher.hidden = true;
    this.stage.hidden = false;
    this.showStageMsg(`Opening ${app.label}…`);
    clearTimeout(this.hintTimer);
    this.hintTimer = setTimeout(() => {
      if (this.sid === sid && !this.player?.gotFirstFrame) {
        this.showStageMsg(`Opening ${app.label}… If the phone asks to share its screen, tap "Start now" on the phone.`);
      }
    }, 4000);
    this.player = new Player(this.canvas, {
      onInput: (m) => state.link?.sendCtl({ ...m, sid }),
      onKeyframeRequest: (why) => {
        log('keyframe request: ' + why);
        state.link?.sendCtl({ t: 'reset', sid });
      },
      onFirstFrame: () => this.hideStageMsg(),
      onStats: (s) => {
        if (settings.stats) {
          const rtt = state.path?.rttMs ?? state.rtt;
          const lag = this.encoderLag != null ? ` enc ${this.encoderLag}ms` : '';
          this.statsEl.textContent = `${s.size} ${s.fps}fps ${(s.kbps / 1000).toFixed(1)}Mb/s q${s.queue}${rtt != null ? ` rtt ${rtt}ms` : ''}${lag}`;
        }
      },
    });
    state.link.openVideoChannel(sid, (buf) => this.player?.feed(buf));
    state.link.sendCtl({
      t: 'start',
      sid,
      pkg: app.pkg,
      w,
      h,
      dpi,
      fps: this.degraded ? 30 : settings.fps,
      bitrate: settings.bitrate * 1_000_000,
    });
    log(`open ${app.pkg} ${w}x${h}@${dpi}dpi`);
    this.setChrome();
    savePanes();
  }

  onResize() {
    if (!this.sid || !state.link) return;
    clearTimeout(this.resizeTimer);
    this.resizeTimer = setTimeout(() => {
      const { w, h } = this.streamParams();
      if (this.lastSize && Math.abs(this.lastSize.w - w) < 16 && Math.abs(this.lastSize.h - h) < 16) return;
      this.lastSize = { w, h };
      log(`resize ${this.sid} -> ${w}x${h}`);
      state.link.sendCtl({ t: 'resize', sid: this.sid, w, h });
    }, 350);
  }

  /**
   * The phone reports how far its encoder lags behind the screen. A phone that can't keep
   * up (old or hot) gets a lighter stream instead of a growing delay.
   */
  onPhoneStats(msg) {
    this.encoderLag = msg.lag;
    if (msg.lag > 1200) this.slowReports = (this.slowReports || 0) + 1;
    else this.slowReports = 0;
    if (this.slowReports >= 3 && !this.degraded && this.app) {
      this.degraded = true;
      warn(`phone encoder lagging ${msg.lag} ms: reopening ${this.app.pkg} lighter`);
      toast('The phone is struggling: switching to a lighter stream');
      this.reconfigure();
    }
  }

  /** Stream size/settings for this pane (2/3 size at 30 fps once degraded). */
  streamParams() {
    let { w, h, dpi } = this.targetSize();
    if (this.degraded) {
      w = Math.floor((w * 2) / 3 / 8) * 8;
      h = Math.floor((h * 2) / 3 / 8) * 8;
      dpi = Math.round((dpi * 2) / 3);
    }
    return { w, h, dpi, fps: this.degraded ? 30 : settings.fps, bitrate: settings.bitrate * 1_000_000 };
  }

  /** Bigger/smaller interface for this app (car-sized screens: changes the app's dpi live). */
  zoomBy(f) {
    if (!this.app) return;
    const z = Math.min(2.5, Math.max(0.4, ((zoom[this.app.pkg] || 1) * f)));
    zoom[this.app.pkg] = Math.round(z * 100) / 100;
    save('cm.zoom', zoom);
    toast(`${this.app.label}: ${Math.round(z * 100)}%`, 1500);
    this.reconfigure();
  }

  /** Apply new stream settings to the running app without reopening it. */
  reconfigure() {
    if (!this.sid || !state.link) return;
    const p = this.streamParams();
    this.lastSize = { w: p.w, h: p.h };
    log(`reconfigure ${this.sid} -> ${p.w}x${p.h} ${p.fps}fps ${p.bitrate / 1e6}Mbps`);
    state.link.sendCtl({ t: 'reconfigure', sid: this.sid, ...p });
  }

  showStageMsg(text) {
    this.stageMsg.textContent = text;
    this.stageMsg.hidden = false;
  }
  hideStageMsg() {
    this.stageMsg.hidden = true;
  }

  /** Stop the running app (if any) and show the launcher. */
  stop(notifyPhone) {
    if (this.sid != null) {
      if (notifyPhone) state.link?.sendCtl({ t: 'stop', sid: this.sid });
      state.link?.closeVideoChannel(this.sid);
    }
    this.player?.close();
    this.player = null;
    this.sid = null;
    this.app = null;
    this.stage.hidden = true;
    this.overlay = null;
    this.launcher.classList.remove('overlay');
    this.launcher.hidden = false;
    this.ctxClear();
    this.setChrome();
    savePanes();
  }

  ctxClear() {
    const c = this.canvas;
    c.width = 2;
    c.height = 2;
  }

  /** The link dropped: forget the session but remember the app so it can come back. */
  detach() {
    const app = this.app;
    this.player?.close();
    this.player = null;
    this.sid = null;
    if (app) {
      this.app = app;
      this.showStageMsg('Reconnecting…');
    }
  }

  destroy() {
    this.resizeObs.disconnect();
    this.stop(true);
    this.el.remove();
  }
}

function savePanes() {
  const list = state.panes.map((p) => (p.app ? { pkg: p.app.pkg, label: p.app.label } : null));
  save('cm.panes', list);
}

function buildPanes() {
  const count = state.layout === 'split' ? 2 : 1;
  const root = $('#screen-panes');
  // keep existing panes (their sessions survive a layout change), add or remove as needed
  while (state.panes.length > count) state.panes.pop().destroy();
  while (state.panes.length < count) {
    const p = new Pane(state.panes.length);
    state.panes.push(p);
    root.appendChild(p.el);
  }
  for (const p of state.panes) p.setChrome();
  $('#btn-layout').textContent = state.layout === 'split' ? 'Single' : 'Split';
}

function setLayout(layout, keepIndex = 0) {
  if (layout === state.layout) return;
  state.layout = layout;
  save('cm.layout', layout);
  if (layout === 'full' && state.panes.length === 2 && keepIndex === 1) {
    // keep the right pane: move it to the front
    const [left, right] = state.panes;
    state.panes = [right, left];
    right.index = 0;
    left.index = 1;
    $('#screen-panes').prepend(right.el);
  }
  buildPanes();
  savePanes();
}

/** After a (re)connect, reopen the apps that were showing. */
function restorePanes() {
  const restore = state.panes.some((p) => p.app && !p.sid)
    ? state.panes.map((p) => (p.app ? { pkg: p.app.pkg, label: p.app.label } : null))
    : state.restore;
  state.restore = [];
  if (!restore || !restore.length) return;
  restore.forEach((r, i) => {
    const pane = state.panes[i];
    if (!r || !pane || pane.sid) return;
    const app = state.apps.find((a) => a.pkg === r.pkg) || r;
    pane.open(app);
  });
}

// ------------------------------------------------------------------ chrome buttons

function setupChrome() {
  $('#btn-layout').onclick = () => setLayout(state.layout === 'split' ? 'full' : 'split');
  $('#btn-immersive').onclick = () => {
    document.body.classList.add('immersive');
    $('#btn-show-bars').hidden = false;
  };
  $('#btn-show-bars').onclick = () => {
    document.body.classList.remove('immersive');
    $('#btn-show-bars').hidden = true;
  };

  const dlg = $('#settings');
  $('#btn-settings').onclick = () => {
    $('#set-bitrate').value = String(settings.bitrate);
    $('#set-fps').value = String(settings.fps);
    $('#set-scale').value = String(settings.scale);
    $('#set-stats').checked = !!settings.stats;
    $('#set-focus').checked = !!settings.focus;
    $('#set-sound').checked = !!settings.sound;
    $('#set-relay').checked = !!settings.relay;
    $('#row-scale').hidden = state.mode === 'screen';
    $('#btn-forget').hidden = !state.paired;
    $('#about').textContent = `CarMirror ${VERSION} · ${navigator.userAgent}`;
    dlg.showModal();
  };
  dlg.addEventListener('close', () => {
    const before = JSON.stringify([settings.bitrate, settings.fps, settings.scale]);
    settings.bitrate = Number($('#set-bitrate').value);
    settings.fps = Number($('#set-fps').value);
    settings.scale = Number($('#set-scale').value);
    settings.stats = $('#set-stats').checked;
    settings.focus = $('#set-focus').checked;
    const relayChanged = settings.relay !== $('#set-relay').checked;
    settings.relay = $('#set-relay').checked;
    if (relayChanged && state.link) {
      state.link.close('relay setting changed');
    }
    const soundChanged = settings.sound !== $('#set-sound').checked;
    settings.sound = $('#set-sound').checked;
    if (soundChanged) {
      audio.setEnabled(settings.sound);
audio.onNeedGesture = () => toast('Tap the screen once to turn on the sound', 6000);
      state.link?.sendCtl({ t: 'audio', on: settings.sound });
    }
    save('cm.settings', settings);
    const streamChanged = before !== JSON.stringify([settings.bitrate, settings.fps, settings.scale]);
    for (const p of state.panes) {
      p.setChrome();
      p.applyCrop();
      // quality applies to the stream right away, without reopening the app
      if (streamChanged && p.sid) p.reconfigure();
    }
    if (dlg.returnValue === 'forget') {
      if (confirm('Unpair this car? You will need a new code from the phone.')) {
        state.sig.send({ t: 'forget' });
        save('cm.carToken', null);
        save('cm.panes', null);
        setTimeout(() => location.reload(), 300);
      }
    }
  });
}

async function keepScreenAwake() {
  try {
    if ('wakeLock' in navigator) await navigator.wakeLock.request('screen');
  } catch {
    // not available or denied
  }
}

// ------------------------------------------------------------------ boot

async function boot() {
  buildKeypad();
  renderCode();
  setupChrome();
  log('car boot', {
    v: VERSION,
    ua: navigator.userAgent,
    screen: [screen.width, screen.height, devicePixelRatio],
    viewport: [innerWidth, innerHeight],
    secure: isSecureContext,
  });

  if (typeof RTCPeerConnection === 'undefined') {
    message('This browser can\'t do WebRTC', 'Open <a href="/diag">/diag</a> and send the result to support.', { spinner: false });
    return;
  }
  const dec = await detectDecoder();
  log('decoder', dec);
  if (!dec.ok) {
    message('Video decoding not available', `${dec.reason}. Open <a href="/diag">/diag</a> for details.`, { spinner: false });
    return;
  }

  // tell the phone right away when the page goes (otherwise it waits for WebRTC to time out)
  window.addEventListener('pagehide', () => state.link?.close('page closed'));

  message('Connecting…', '');
  state.sig = new Signaling(onSig, onSigOpen);
  keepScreenAwake();
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') keepScreenAwake();
  });
}

boot();
