// One mirrored app: receives the H.264 stream from the phone over a WebRTC data channel,
// decodes it with WebCodecs (hardware) and paints it on a <canvas>.
//
// Deliberately no <video> element: the Tesla browser blocks video playback while the car
// is moving, and a canvas fed by WebCodecs is not a "video" to it. It is also the lowest-
// latency path available in a browser (no jitter buffer, no MSE).
//
// Wire format of the video channel (phone -> car), big-endian:
//   [1][w:u32][h:u32]                                   new encoder session (video size)
//   [2][flags:u8][pts:u64][size:u32][payload...]        first chunk of a packet
//   [3][payload...]                                     continuation chunk
//   flags: 1 = codec config (SPS/PPS), 2 = key frame

import { log, warn, error } from './remotelog.js';

const KIND_SESSION = 1;
const KIND_PACKET = 2;
const KIND_CONT = 3;
const F_CONFIG = 1;
const F_KEY = 2;

// Android MotionEvent actions understood by scrcpy
const ACTION_DOWN = 0;
const ACTION_UP = 1;
const ACTION_MOVE = 2;
const POINTER_ID_GENERIC_FINGER = -2;

let hwPreference = 'prefer-hardware';

/** Probe once whether hardware H.264 decode is available; fall back to software otherwise. */
export async function detectDecoder() {
  if (typeof VideoDecoder === 'undefined') return { ok: false, reason: 'WebCodecs (VideoDecoder) is not available in this browser' };
  const base = { codec: 'avc1.640028', optimizeForLatency: true };
  try {
    const hw = await VideoDecoder.isConfigSupported({ ...base, hardwareAcceleration: 'prefer-hardware' });
    if (hw.supported) {
      hwPreference = 'prefer-hardware';
      return { ok: true, hw: true };
    }
    const sw = await VideoDecoder.isConfigSupported({ ...base, hardwareAcceleration: 'no-preference' });
    if (sw.supported) {
      hwPreference = 'no-preference';
      return { ok: true, hw: false };
    }
    return { ok: false, reason: 'H.264 decoding is not supported' };
  } catch (e) {
    hwPreference = 'no-preference';
    return { ok: true, hw: false, note: String(e) };
  }
}

function hex2(n) {
  return n.toString(16).padStart(2, '0').toUpperCase();
}

/** Build an RFC 6381 codec string ("avc1.PPCCLL") from the SPS inside an Annex-B config packet. */
function codecFromConfig(data) {
  for (let i = 0; i + 4 < data.length; i++) {
    // start code 00 00 01 (also matches the tail of 00 00 00 01)
    if (data[i] === 0 && data[i + 1] === 0 && data[i + 2] === 1) {
      const nalType = data[i + 3] & 0x1f;
      if (nalType === 7 && i + 6 < data.length) {
        return `avc1.${hex2(data[i + 4])}${hex2(data[i + 5])}${hex2(data[i + 6])}`;
      }
    }
  }
  return 'avc1.42E01F';
}

function concat(a, b) {
  const out = new Uint8Array(a.length + b.length);
  out.set(a, 0);
  out.set(b, a.length);
  return out;
}

export class Player {
  /**
   * @param {HTMLCanvasElement} canvas
   * @param {{ onInput: (msg: object) => void, onKeyframeRequest: (why: string) => void, onFirstFrame?: () => void, onStats?: (s: object) => void }} hooks
   */
  constructor(canvas, hooks) {
    this.canvas = canvas;
    this.hooks = hooks;
    this.ctx = canvas.getContext('2d', { alpha: false, desynchronized: true });
    this.videoW = 0;
    this.videoH = 0;
    this.config = null;
    this.codec = null;
    this.decoder = null;
    this.waitingKey = true;
    this.cur = null;
    this.lastKeyRequest = 0;
    this.gotFirstFrame = false;
    this.closed = false;
    this.crop = { l: 0, t: 0, r: 0, b: 0 }; // fractions of the video hidden on each side (focus mode)

    // stats
    this.framesDrawn = 0;
    this.bytesIn = 0;
    this.dropped = 0;
    this.statsTimer = setInterval(() => this.emitStats(), 1000);

    this.pointers = new Map(); // browser pointerId -> small id
    this.bindInput();
    this.resizeObserver = new ResizeObserver(() => this.layout());
    this.resizeObserver.observe(canvas.parentElement);
  }

  // ------------------------------------------------------------ stream

  /** @param {ArrayBuffer} buf */
  feed(buf) {
    if (this.closed) return;
    this.bytesIn += buf.byteLength;
    const dv = new DataView(buf);
    const kind = dv.getUint8(0);
    if (kind === KIND_SESSION) {
      this.onSession(dv.getUint32(1), dv.getUint32(5));
    } else if (kind === KIND_PACKET) {
      const flags = dv.getUint8(1);
      const pts = dv.getUint32(2) * 4294967296 + dv.getUint32(6);
      const size = dv.getUint32(10);
      this.cur = { flags, pts, data: new Uint8Array(size), off: 0 };
      this.append(new Uint8Array(buf, 14));
    } else if (kind === KIND_CONT) {
      if (this.cur) this.append(new Uint8Array(buf, 1));
    }
  }

  append(part) {
    const cur = this.cur;
    if (cur.off + part.length > cur.data.length) {
      warn('packet overflow, resyncing');
      this.cur = null;
      this.requestKeyframe('overflow');
      return;
    }
    cur.data.set(part, cur.off);
    cur.off += part.length;
    if (cur.off === cur.data.length) {
      this.cur = null;
      this.onPacket(cur);
    }
  }

  /** Hide the phone's system bars: fractions of the picture to drop on each side. */
  setCrop(c) {
    const ok = (v) => (Number.isFinite(v) && v >= 0 && v < 0.4 ? v : 0);
    this.crop = { l: ok(c.l), t: ok(c.t), r: ok(c.r), b: ok(c.b) };
    this.resizeCanvas();
  }

  /** The part of the video that is shown, in video pixels. */
  region() {
    const c = this.crop;
    const x = Math.round(this.videoW * c.l);
    const y = Math.round(this.videoH * c.t);
    const w = Math.max(16, Math.round(this.videoW * (1 - c.l - c.r)));
    const h = Math.max(16, Math.round(this.videoH * (1 - c.t - c.b)));
    return { x, y, w, h };
  }

  resizeCanvas() {
    if (!this.videoW) return;
    const r = this.region();
    if (this.canvas.width !== r.w || this.canvas.height !== r.h) {
      this.canvas.width = r.w;
      this.canvas.height = r.h;
    }
    this.layout();
  }

  onSession(w, h) {
    log(`video session ${w}x${h}`);
    this.videoW = w;
    this.videoH = h;
    this.waitingKey = true;
    this.resizeCanvas();
  }

  onPacket({ flags, pts, data }) {
    if (flags & F_CONFIG) {
      this.config = data;
      const codec = codecFromConfig(data);
      if (codec !== this.codec || !this.decoder || this.decoder.state !== 'configured') {
        this.configure(codec);
      }
      return;
    }
    if (!this.decoder || this.decoder.state !== 'configured') return;

    const key = (flags & F_KEY) !== 0;
    if (this.waitingKey && !key) {
      this.dropped++;
      return;
    }
    // The decoder is falling behind (should not happen with hardware decode): skip to the next key frame
    // rather than accumulating latency.
    if (!key && this.decoder.decodeQueueSize > 8) {
      this.dropped++;
      this.waitingKey = true;
      this.requestKeyframe('decoder backlog');
      return;
    }
    let payload = data;
    if (key) {
      this.waitingKey = false;
      if (this.config) payload = concat(this.config, data);
    }
    try {
      this.decoder.decode(new EncodedVideoChunk({ type: key ? 'key' : 'delta', timestamp: pts, data: payload }));
    } catch (e) {
      error('decode() threw: ' + e);
      this.resetDecoder();
    }
  }

  configure(codec) {
    this.codec = codec;
    if (!this.decoder || this.decoder.state === 'closed') {
      this.decoder = new VideoDecoder({
        output: (frame) => this.draw(frame),
        error: (e) => {
          error('decoder error: ' + e.message, { codec: this.codec });
          this.resetDecoder();
        },
      });
    }
    try {
      this.decoder.configure({ codec, optimizeForLatency: true, hardwareAcceleration: hwPreference });
      log(`decoder configured ${codec} (${hwPreference})`);
    } catch (e) {
      error('configure failed: ' + e, { codec });
      if (hwPreference !== 'no-preference') {
        hwPreference = 'no-preference';
        this.decoder.configure({ codec, optimizeForLatency: true, hardwareAcceleration: hwPreference });
      }
    }
    this.waitingKey = true;
  }

  resetDecoder() {
    try {
      if (this.decoder && this.decoder.state !== 'closed') this.decoder.close();
    } catch {
      // ignore
    }
    this.decoder = null;
    this.waitingKey = true;
    if (this.codec && !this.closed) this.configure(this.codec);
    this.requestKeyframe('decoder reset');
  }

  requestKeyframe(why) {
    const now = performance.now();
    if (now - this.lastKeyRequest < 1000) return;
    this.lastKeyRequest = now;
    this.hooks.onKeyframeRequest?.(why);
  }

  draw(frame) {
    if (this.closed) {
      frame.close();
      return;
    }
    const w = frame.displayWidth;
    const h = frame.displayHeight;
    if (this.videoW !== w || this.videoH !== h) {
      this.videoW = w;
      this.videoH = h;
      this.resizeCanvas();
    }
    const r = this.region();
    this.ctx.drawImage(frame, r.x, r.y, r.w, r.h, 0, 0, this.canvas.width, this.canvas.height);
    frame.close();
    this.framesDrawn++;
    if (!this.gotFirstFrame) {
      this.gotFirstFrame = true;
      this.hooks.onFirstFrame?.();
    }
  }

  /** Fit the canvas inside its stage keeping the aspect ratio (CSS pixels). */
  layout() {
    const stage = this.canvas.parentElement;
    if (!stage || !this.videoW) return;
    const sw = stage.clientWidth;
    const sh = stage.clientHeight;
    if (!sw || !sh) return;
    const cw = this.canvas.width;
    const ch = this.canvas.height;
    const scale = Math.min(sw / cw, sh / ch);
    this.canvas.style.width = Math.floor(cw * scale) + 'px';
    this.canvas.style.height = Math.floor(ch * scale) + 'px';
  }

  emitStats() {
    const s = {
      fps: this.framesDrawn,
      kbps: Math.round((this.bytesIn * 8) / 1000),
      queue: this.decoder?.decodeQueueSize ?? 0,
      dropped: this.dropped,
      size: `${this.videoW}x${this.videoH}`,
    };
    this.framesDrawn = 0;
    this.bytesIn = 0;
    this.hooks.onStats?.(s);
  }

  // ------------------------------------------------------------ input

  bindInput() {
    const c = this.canvas;
    const toVideo = (e) => {
      const r = c.getBoundingClientRect();
      const reg = this.region();
      const x = Math.round(reg.x + ((e.clientX - r.left) / r.width) * reg.w);
      const y = Math.round(reg.y + ((e.clientY - r.top) / r.height) * reg.h);
      return {
        x: Math.max(0, Math.min(this.videoW - 1, x)),
        y: Math.max(0, Math.min(this.videoH - 1, y)),
      };
    };
    const idFor = (e) => {
      if (e.pointerType === 'mouse') return POINTER_ID_GENERIC_FINGER;
      let id = this.pointers.get(e.pointerId);
      if (id === undefined) {
        const used = new Set(this.pointers.values());
        id = 0;
        while (used.has(id)) id++;
        this.pointers.set(e.pointerId, id);
      }
      return id;
    };
    const send = (action, e) => {
      if (!this.videoW) return;
      const { x, y } = toVideo(e);
      this.hooks.onInput({ t: 'touch', a: action, id: idFor(e), x, y, w: this.videoW, h: this.videoH });
    };

    this.activePointers = new Set();
    c.addEventListener('pointerdown', (e) => {
      e.preventDefault();
      try {
        c.setPointerCapture(e.pointerId);
      } catch {
        // ignore
      }
      this.activePointers.add(e.pointerId);
      send(ACTION_DOWN, e);
    });
    c.addEventListener('pointermove', (e) => {
      if (!this.activePointers.has(e.pointerId)) return;
      e.preventDefault();
      send(ACTION_MOVE, e);
    });
    const up = (e) => {
      if (!this.activePointers.has(e.pointerId)) return;
      e.preventDefault();
      send(ACTION_UP, e);
      this.activePointers.delete(e.pointerId);
      this.pointers.delete(e.pointerId);
    };
    c.addEventListener('pointerup', up);
    c.addEventListener('pointercancel', up);
    c.addEventListener('contextmenu', (e) => e.preventDefault());
    c.addEventListener(
      'wheel',
      (e) => {
        e.preventDefault();
        if (!this.videoW) return;
        const { x, y } = toVideo(e);
        const k = e.deltaMode === 1 ? 1 / 3 : 1 / 100;
        this.hooks.onInput({ t: 'scroll', x, y, w: this.videoW, h: this.videoH, dx: -e.deltaX * k, dy: -e.deltaY * k });
      },
      { passive: false },
    );
  }

  close() {
    this.closed = true;
    clearInterval(this.statsTimer);
    this.resizeObserver.disconnect();
    try {
      if (this.decoder && this.decoder.state !== 'closed') this.decoder.close();
    } catch {
      // ignore
    }
    this.decoder = null;
  }
}
