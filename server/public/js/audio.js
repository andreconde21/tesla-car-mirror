// Plays the phone's sound in the car browser. The Tesla switches its media source to the
// browser while this page is open, so Bluetooth audio from the phone isn't heard; the phone
// sends it here instead.
//
// Opus packets (20 ms) arrive on an unordered, no-retransmit data channel, are decoded with
// WebCodecs and scheduled back to back on a Web Audio clock with a small, self-correcting
// buffer: late packets are dropped rather than letting delay build up.
//
// Wire format: [pts µs: u64][opus packet]

import { log, warn } from './remotelog.js';

const TARGET = 0.08; // seconds of buffer we aim for
const MAX = 0.3; // beyond this, skip ahead (latency would grow otherwise)

export class AudioPlayer {
  constructor() {
    this.ctx = null;
    this.decoder = null;
    this.next = 0;
    this.gain = null;
    this.packets = 0;
    this.enabled = true;
    // browsers only start audio after a user gesture: any tap on the page will do
    const unlock = () => this.resume();
    document.addEventListener('pointerdown', unlock, { capture: true });
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
      if (++this.packets === 1) log('audio: first packet');
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
    if (this.next > now + MAX) return; // too far behind: drop this packet to catch up
    const src = ctx.createBufferSource();
    src.buffer = buffer;
    src.connect(this.gain);
    src.start(this.next);
    this.next += buffer.duration;
  }

  setEnabled(on) {
    this.enabled = on;
    if (!on) this.next = 0;
  }
}
