// Plays the phone's sound in the car browser. The Tesla switches its media source to the
// browser while this page is open, so Bluetooth audio from the phone isn't heard; the phone
// sends it here instead.
//
// Opus packets (20 ms) arrive on an unordered, no-retransmit data channel, are decoded with
// WebCodecs and scheduled back to back on a Web Audio clock with a small, self-correcting
// buffer: when it has grown (a burst after a network hiccup), packets play 4% faster until it is
// back near the target, and packets that would land too far ahead are dropped, so delay never
// builds up.
//
// Wire format: [pts µs: u64][opus packet]

import { log, warn } from './remotelog.js';

const TARGET = 0.06; // seconds of buffer we aim for
const HIGH = 0.1; // above this, play slightly fast until back at TARGET
const CATCH_UP = 1.04; // playback rate while draining (a 4% pitch change, briefly)
const MAX = 0.25; // beyond this, drop packets outright
const STATS_EVERY = 30; // seconds between buffer reports in the car log

export class AudioPlayer {
  constructor() {
    this.ctx = null;
    this.decoder = null;
    this.next = 0;
    this.gain = null;
    this.packets = 0;
    this.enabled = true;
    this.draining = false;
    this.stats = { n: 0, sum: 0, max: 0, fast: 0, dropped: 0, at: 0 };
    // browsers only start audio after a user gesture: any tap on the page will do (on a touch
    // screen that's the finger lifting: pointerdown alone doesn't count)
    const unlock = () => this.enabled && this.resume();
    for (const ev of ['pointerdown', 'pointerup', 'keydown']) document.addEventListener(ev, unlock, { capture: true });
  }

  static supported() {
    return typeof AudioDecoder !== 'undefined' && typeof AudioContext !== 'undefined';
  }

  resume() {
    if (!AudioPlayer.supported()) return;
    if (!this.ctx) {
      this.ctx = new AudioContext({ latencyHint: 'interactive', sampleRate: 48000 });
      this.gain = this.ctx.createGain();
      this.gain.connect(this.ctx.destination);
    }
    if (this.ctx.state === 'suspended') this.ctx.resume().catch(() => {});
  }

  ensureDecoder() {
    if (this.decoder && this.decoder.state === 'configured') return true;
    try {
      this.decoder = new AudioDecoder({
        output: (data) => this.play(data),
        error: (e) => {
          warn('audio decoder error: ' + e.message);
          this.decoder = null;
        },
      });
      this.decoder.configure({ codec: 'opus', sampleRate: 48000, numberOfChannels: 2 });
      log('audio decoder ready');
      return true;
    } catch (e) {
      warn('audio decoder unavailable: ' + e);
      this.decoder = null;
      return false;
    }
  }

  /** @param {ArrayBuffer} buf */
  feed(buf) {
    if (!this.enabled) return;
    if (!this.ctx || this.ctx.state !== 'running') {
      if (!this.hinted) {
        this.hinted = true;
        this.onNeedGesture?.();
      }
      return;
    }
    if (!this.ensureDecoder()) return;
    const dv = new DataView(buf);
    const pts = dv.getUint32(0) * 4294967296 + dv.getUint32(4);
    try {
      this.decoder.decode(new EncodedAudioChunk({ type: 'key', timestamp: pts, data: new Uint8Array(buf, 8) }));
      if (++this.packets === 1) {
        const c = this.ctx;
        log(`audio: first packet (output latency base ${ms(c.baseLatency)} + device ${ms(c.outputLatency)})`);
      }
    } catch (e) {
      warn('audio decode failed: ' + e);
    }
  }

  play(data) {
    const ctx = this.ctx;
    if (!ctx) {
      data.close();
      return;
    }
    const frames = data.numberOfFrames;
    const channels = Math.min(2, data.numberOfChannels);
    const buffer = ctx.createBuffer(2, frames, data.sampleRate);
    for (let ch = 0; ch < 2; ch++) {
      data.copyTo(buffer.getChannelData(ch), { planeIndex: Math.min(ch, channels - 1), format: 'f32-planar' });
    }
    data.close();
    const now = ctx.currentTime;
    if (this.next < now + 0.01) this.next = now + TARGET; // underrun: rebuild a small cushion
    const ahead = this.next - now;
    this.report(ahead, now);
    if (ahead > MAX) {
      this.stats.dropped++;
      return; // far too much queued: drop this packet to catch up
    }
    const src = ctx.createBufferSource();
    src.buffer = buffer;
    if (ahead > HIGH) this.draining = true;
    else if (ahead <= TARGET) this.draining = false;
    if (this.draining) {
      src.playbackRate.value = CATCH_UP;
      this.stats.fast++;
    }
    src.connect(this.gain);
    src.start(this.next);
    this.next += buffer.duration / src.playbackRate.value;
  }

  /** Every STATS_EVERY seconds, how much sound sat in the buffer, for the car log. */
  report(ahead, now) {
    const st = this.stats;
    st.n++;
    st.sum += ahead;
    st.max = Math.max(st.max, ahead);
    if (!st.at) st.at = now;
    if (now - st.at < STATS_EVERY) return;
    log(`audio: buffer avg ${ms(st.sum / st.n)}, max ${ms(st.max)}, sped up ${st.fast}, dropped ${st.dropped} of ${st.n} packets`);
    this.stats = { n: 0, sum: 0, max: 0, fast: 0, dropped: 0, at: now };
  }

  /** Off also suspends the audio output, so the car doesn't keep the browser as its sound source. */
  setEnabled(on) {
    this.enabled = on;
    if (on) this.resume();
    else {
      this.next = 0;
      this.ctx?.suspend().catch(() => {});
    }
  }
}

function ms(seconds) {
  return seconds == null ? '?' : Math.round(seconds * 1000) + ' ms';
}
