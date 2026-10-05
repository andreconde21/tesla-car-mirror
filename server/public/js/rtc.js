// Signaling (WebSocket to the CarMirror server) and the WebRTC link to the phone.
//
// The server only relays SDP/ICE. Once connected, everything (app list, touch input,
// video) flows directly between the car and the phone over the phone's hotspot.

import { log, warn, error } from './remotelog.js';

export class Signaling {
  constructor(onMessage, onOpenChange) {
    this.onMessage = onMessage;
    this.onOpenChange = onOpenChange;
    this.ws = null;
    this.backoff = 1000;
    this.open = false;
    this.connect();
  }

  connect() {
    const proto = location.protocol === 'https:' ? 'wss' : 'ws';
    const ws = new WebSocket(`${proto}://${location.host}/ws`);
    this.ws = ws;
    ws.onopen = () => {
      this.backoff = 1000;
      this.open = true;
      this.onOpenChange(true);
    };
    ws.onmessage = (e) => {
      let msg;
      try {
        msg = JSON.parse(e.data);
      } catch {
        return;
      }
      this.onMessage(msg);
    };
    ws.onclose = () => {
      const wasOpen = this.open;
      this.open = false;
      if (wasOpen) this.onOpenChange(false);
      setTimeout(() => this.connect(), this.backoff);
      this.backoff = Math.min(this.backoff * 2, 15000);
    };
    ws.onerror = () => {
      // onclose follows
    };
  }

  send(msg) {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) this.ws.send(JSON.stringify(msg));
  }
}

const CONNECT_TIMEOUT_MS = 20000;

/**
 * One WebRTC session with the phone. The car is always the offerer and creates every
 * data channel (control + one video channel per mirrored app).
 */
export class PhoneLink {
  /**
   * @param {Signaling} sig
   * @param {{ onOpen: () => void, onClose: (reason: string) => void, onCtl: (msg: object) => void }} hooks
   */
  constructor(sig, hooks) {
    this.sig = sig;
    this.hooks = hooks;
    this.pendingCandidates = [];
    this.remoteSet = false;
    this.closed = false;
    this.videoChannels = new Map();

    const pc = new RTCPeerConnection({
      iceServers: [{ urls: 'stun:stun.l.google.com:19302' }],
      bundlePolicy: 'max-bundle',
    });
    this.pc = pc;

    pc.onicecandidate = (e) => {
      if (e.candidate) log('car candidate ' + e.candidate.candidate.split(' ').slice(4, 8).join(' '));
      this.sig.send({ t: 'signal', data: { type: 'candidate', candidate: e.candidate ? e.candidate.toJSON() : null } });
    };
    pc.onconnectionstatechange = () => {
      const st = pc.connectionState;
      log('pc state ' + st);
      if (st === 'failed') this.close('connection failed');
      if (st === 'closed') this.close('closed');
      if (st === 'disconnected') {
        clearTimeout(this.discTimer);
        this.discTimer = setTimeout(() => {
          if (pc.connectionState === 'disconnected') this.close('disconnected');
        }, 4000);
      }
    };

    this.ctl = pc.createDataChannel('ctl', { ordered: true });
    this.ctl.onopen = () => {
      clearTimeout(this.connectTimer);
      log('ctl open');
      this.hooks.onOpen();
    };
    this.ctl.onclose = () => this.close('control channel closed');
    this.ctl.onmessage = (e) => {
      if (typeof e.data !== 'string') return;
      let msg;
      try {
        msg = JSON.parse(e.data);
      } catch {
        return;
      }
      this.hooks.onCtl(msg);
    };

    this.connectTimer = setTimeout(() => this.close('timeout'), CONNECT_TIMEOUT_MS);
    this.start().catch((e) => {
      error('offer failed: ' + e);
      this.close('offer failed');
    });
  }

  async start() {
    const offer = await this.pc.createOffer();
    await this.pc.setLocalDescription(offer);
    this.sig.send({ t: 'signal', data: { type: 'offer', sdp: offer.sdp } });
  }

  async onSignal(data) {
    if (this.closed) return;
    try {
      if (data.type === 'answer') {
        await this.pc.setRemoteDescription({ type: 'answer', sdp: data.sdp });
        this.remoteSet = true;
        for (const c of this.pendingCandidates) await this.pc.addIceCandidate(c);
        this.pendingCandidates = [];
      } else if (data.type === 'candidate') {
        if (!data.candidate) return;
        log('phone candidate ' + String(data.candidate.candidate).split(' ').slice(4, 8).join(' '));
        if (this.remoteSet) await this.pc.addIceCandidate(data.candidate);
        else this.pendingCandidates.push(data.candidate);
      } else if (data.type === 'bye') {
        this.close(data.reason || 'phone closed');
      }
    } catch (e) {
      warn('signal handling failed: ' + e, data.type);
    }
  }

  sendCtl(msg) {
    if (this.ctl.readyState === 'open') this.ctl.send(JSON.stringify(msg));
  }

  /** Create the channel the phone will push one app's video into. */
  openVideoChannel(sid, onMessage) {
    const ch = this.pc.createDataChannel('v:' + sid, { ordered: true });
    ch.binaryType = 'arraybuffer';
    ch.onmessage = (e) => onMessage(e.data);
    this.videoChannels.set(sid, ch);
    return ch;
  }

  closeVideoChannel(sid) {
    const ch = this.videoChannels.get(sid);
    if (ch) {
      this.videoChannels.delete(sid);
      try {
        ch.close();
      } catch {
        // ignore
      }
    }
  }

  /** Describe the network path: "direct" (same Wi-Fi) or "internet". */
  async pathInfo() {
    try {
      const stats = await this.pc.getStats();
      let pair = null;
      stats.forEach((r) => {
        if (r.type === 'transport' && r.selectedCandidatePairId) pair = stats.get(r.selectedCandidatePairId);
      });
      if (!pair) {
        stats.forEach((r) => {
          if (r.type === 'candidate-pair' && r.nominated && r.state === 'succeeded') pair = r;
        });
      }
      if (!pair) return null;
      const local = stats.get(pair.localCandidateId);
      const remote = stats.get(pair.remoteCandidateId);
      const types = [local?.candidateType, remote?.candidateType];
      const direct = types.every((t) => t === 'host' || t === 'prflx');
      return {
        direct,
        rttMs: pair.currentRoundTripTime != null ? Math.round(pair.currentRoundTripTime * 1000) : null,
        local: local?.candidateType,
        remote: remote?.candidateType,
        remoteAddr: remote?.address,
      };
    } catch {
      return null;
    }
  }

  close(reason) {
    if (this.closed) return;
    this.closed = true;
    clearTimeout(this.connectTimer);
    clearTimeout(this.discTimer);
    log('link closed: ' + reason);
    try {
      this.sig.send({ t: 'signal', data: { type: 'bye', reason } });
    } catch {
      // ignore
    }
    try {
      this.pc.close();
    } catch {
      // ignore
    }
    this.hooks.onClose(reason);
  }
}
