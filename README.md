# CarMirror

> **Two modes, chosen automatically.** *Per-app screens* (1.3.0+) whenever Shizuku is running:
> the Shizuku service (shell uid) creates a trusted virtual display around the app's own
> MediaCodec input surface, launches the app on it (`am start --display`) and injects input
> (`InputEvent.setDisplayId`); encoding stays in the app process. Each app gets the car pane's
> exact size and relayouts live on resize/split/fullscreen. Otherwise *Screen* mode: the phone
> screen via MediaProjection + accessibility gestures, with focus mode cropping the system bars.
> scrcpy-server is no longer used: it aborted natively inside MediaCodec on a Galaxy M53 /
> Android 16, while plain in-app MediaCodec works there.
>
> **Hotspot gotcha:** libwebrtc on Android never offered the hotspot address as an ICE
> candidate, so the phone relays a UDP port on all interfaces to libwebrtc's loopback
> candidate and advertises it at the hotspot's .1 address (`HotspotRelay.kt`).

Phone apps on the Tesla screen: YouTube for the passenger, Conductore or navigation for the
driver, one app full screen or two side by side. Built to be fast and to keep playing while
the car moves.

```
Android phone                                              Tesla browser (car)
┌────────────────────────────────────────┐                ┌──────────────────────────────┐
│ CarMirror app                          │                │ carmirror.outsmartis.dev     │
│  ├ foreground service                  │  WebRTC data   │  ├ WebCodecs H.264 decoder   │
│  │   (signaling WS, WebRTC peer)       │  channels over │  ├ <canvas> (no <video>)     │
│  └ Shizuku user service (shell uid)    │  the hotspot   │  └ touch -> control channel  │
│      └ scrcpy server per app:          │ ─────────────► │                              │
│         own virtual display, HW H.264, │ ◄───────────── │                              │
│         touch/key injection            │                │                              │
└────────────────────────────────────────┘                └──────────────────────────────┘
          │ signaling only (pairing, SDP/ICE)   ┌──────────────────────────┐   │
          └───────────────────────────────────► │ server/ (Node, Coolify)  │ ◄─┘
                                                └──────────────────────────┘
Sound: the phone plays it to the car over normal Bluetooth.
```

## Why it is built this way

- **One virtual display per app** (scrcpy `new_display` + `flex_display`). Apps lay themselves
  out for the car screen (1920x1008 landscape, tablet UI), the phone screen stays free, and
  split view is just two displays. When the car disconnects, apps move back to the phone
  (`vd_destroy_content=false`).
- **Shizuku** gives the shell-level permission scrcpy needs (create a trusted display, launch
  any app on it, inject input) without root. scrcpy-server v4.1 is bundled unmodified (Apache
  2.0) in `android/app/src/main/assets/scrcpy-server.jar`.
- **WebRTC data channels, not a WebSocket to the phone.** A page on HTTPS can't open
  `ws://192.168.x.x` (mixed content), WebCodecs needs a secure context, and Tesla's browser may
  block private IPs. With WebRTC the page is served over HTTPS from the server and the video
  still flows directly phone to car over the hotspot. The server only relays SDP and ICE.
- **WebCodecs + canvas instead of `<video>`.** The Tesla browser stops `<video>` playback
  while driving. A canvas fed by a decoder isn't a video element, and it also has the lowest
  latency (no jitter buffer, no MSE). The decoder runs with `optimizeForLatency`.
- **H.264 hardware encode on the phone, audio left on the phone.** Audio goes to the car over
  Bluetooth, so lip sync is about as good as Bluetooth allows. Streaming audio too would add
  buffering.
- **Backpressure:** if the data channel backs up, the phone drops frames up to the next key
  frame and asks scrcpy for one (`RESET_VIDEO`), so delay can't build up. If the phone's
  encoder falls behind (more than 1.2 s for about 6 s), the car reopens the app at 2/3
  resolution and 30 fps.

## Repo layout

- `server/`: signaling server + car web client (vanilla JS, no build step).
  - `public/js/player.js`: WebCodecs decoding, canvas, touch to scrcpy coordinates.
  - `public/js/rtc.js`: signaling + WebRTC (the car offers and creates every channel).
  - `public/js/app.js`: pairing, launcher, panes, split view, settings, reconnect/restore.
  - `public/diag.html`: open `/diag` in the car. It shows WebCodecs/WebRTC/codec support and a
    canvas animation test, and uploads the results to the server log.
  - `public/get.html`: `/get`, the APK download + setup steps.
- `android/`: the phone app (Kotlin, Compose).
  - `PrivilegedService.kt`: Shizuku user service. Starts scrcpy, pipes its sockets to a
    loopback TCP port (the app can't connect to the shell's unix socket because of SELinux),
    and authenticates the app with a per-session secret.
  - `MirrorSession.kt`: scrcpy protocol (v4.1 stream framing, control messages) to data
    channel framing.
  - `CarPeer.kt`: WebRTC peer, control-channel protocol.
  - `MirrorService.kt`: foreground service, signaling, wake lock.

### Wire formats

Video channel `v:<sid>` (phone to car), big-endian:
`[1][w u32][h u32]` new encoder session · `[2][flags u8][pts u64][size u32]payload…` packet
start (flags: 1 config, 2 key frame) · `[3]payload…` continuation (64 KB chunks).

Control channel `ctl` (JSON): car→phone `hello`, `apps?`, `start{sid,pkg,w,h,dpi,fps,bitrate}`,
`stop`, `touch{a,id,x,y,w,h}`, `scroll`, `key{k}`, `reset`, `resize{w,h}`, `ping`;
phone→car `apps`, `icon`, `started`, `ended{reason}`, `stats{lag}`, `pong`.

## Deploy the server

Any host that serves HTTPS works (WebCodecs needs a secure context):

```bash
cd server && npm ci && PORT=8080 DATA_DIR=./data APK_DIR=./apk node server.js
```

Put it behind a TLS reverse proxy (WebSocket upgrade on `/ws`). Pairings live in
`DATA_DIR/state.json` and car-side logs in `DATA_DIR/car.log`. Drop the APK at
`APK_DIR/carmirror.apk` (optional `version.json`) to serve it at `/get`.
The phone app's default server is set in `android/app/build.gradle.kts` (`DEFAULT_SERVER`)
and can be changed in the app under Advanced.

## Build the APK

```bash
cd android
export ANDROID_HOME=/path/to/android-sdk CARMIRROR_KEYSTORE=/path/to/carmirror.jks CARMIRROR_KEYSTORE_PASSWORD=...
./gradlew assembleRelease -PversionCode=2 -PversionName=1.0.1
```

Keep the keystore safe and out of the repo: sideloaded updates must be signed with the same key.

## Testing without a car

An Android 14 emulator (`carmirror` AVD) + headless Chrome act as phone and car:
`adb shell am start -n dev.outsmartis.carmirror/.MainActivity --es server http://10.0.2.2:18080`
points the app at a local server. Verified on 2026-10-04: pairing, launcher with icons, YouTube
and Chrome on a 1920x1008 display, taps, swipes, the Android keyboard showing on the car
screen, split view (two displays), restore after reconnect, auto-degrade, and the production
server over WSS. The emulator's software encoder is slow (seconds of lag at 1080p), which the
auto-degrade handles. Real phones encode in hardware.

## Known limits / to verify in the car

1. **Tesla browser support:** run `/diag` in the car first. Needs RTCPeerConnection, VideoDecoder
   and avc1 support. The results land in the server's `car.log`.
2. **Hotspot candidate:** the phone app shows "Last link candidates". The hotspot address
   (often 192.168.x.x or 10.x on `swlan0`/`ap0`) must be in that list. The car's status bar says
   `direct` when the link uses the hotspot.
3. **While driving:** the canvas approach avoids the `<video>` block. The `/diag` canvas test
   shows whether Tesla throttles canvas drawing too.
4. Apps with FLAG_SECURE (banking, Netflix) show black. Conductore doesn't set it.
5. After a phone reboot, open Shizuku and tap Start (Wireless debugging).
