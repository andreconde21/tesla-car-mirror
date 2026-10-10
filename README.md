# CarMirror

Phone apps on the Tesla screen: YouTube for the passenger, Conductore or navigation for the
driver, one app full screen or two side by side. Built to be fast and to keep playing while
the car moves. Landing page: `/about`; download and setup: `/get`.

```
Android phone                                              Tesla browser (car)
┌────────────────────────────────────────┐                ┌──────────────────────────────┐
│ CarMirror app                          │                │ carmirror.outsmartis.dev     │
│  ├ foreground service                  │  WebRTC data   │  ├ WebCodecs H.264 decoder   │
│  │   (signaling WS, WebRTC peer,       │  channels over │  ├ <canvas> (no <video>)     │
│  │    H.264 + Opus encoders)           │  the hotspot   │  ├ WebCodecs Opus + WebAudio │
│  ├ accessibility service (touch)       │ ─────────────► │  └ touch -> control channel  │
│  └ Shizuku user service (shell uid):   │ ◄───────────── │                              │
│      virtual displays, input, car      │                │                              │
│      hotspot, phone audio              │                │                              │
└────────────────────────────────────────┘                └──────────────────────────────┘
          │ signaling (pairing, SDP/ICE), TURN fallback  ┌──────────────────────────┐   │
          └────────────────────────────────────────────► │ server/ (Node, Coolify)  │ ◄─┘
                                                         └──────────────────────────┘
```

## Two modes, chosen automatically

- **Per-app screens** whenever Shizuku is running: the Shizuku service (shell uid) creates a
  trusted virtual display around the app's own MediaCodec input surface, launches the app on
  it and injects input there (`InputEvent.setDisplayId`). Encoding stays in the app process.
  Each app gets the car pane's exact size and relayouts live on resize, split and fullscreen;
  split view is two displays. The phone's keyboard shows on the car display (IME policy
  LOCAL), or not at all when the car's "On-screen keyboard" setting is off (policy HIDE).
- **Screen mode** otherwise: the phone screen through MediaProjection, touches through the
  accessibility service's gestures, and focus mode cropping the system bars and replacing the
  home screen with the car's own app grid.

scrcpy is no longer used: scrcpy-server aborted natively inside MediaCodec on a Galaxy M53 /
Android 16, while plain in-app MediaCodec works there.

## Why it is built this way

- **WebRTC data channels, not a WebSocket to the phone.** A page on HTTPS can't open
  `ws://192.168.x.x` (mixed content) and WebCodecs needs a secure context. With WebRTC the page
  is served over HTTPS from the server and the video still flows directly phone to car.
- **Car hotspot mode (9.9.0.x).** The Tesla browser refuses private addresses, which every
  normal hotspot uses. With Shizuku the phone restarts its hotspot with a fixed public-looking
  subnet (`TetheringRequest.setStaticIpv4Addresses`), phone 9.9.0.1, car 9.9.0.2, so the car
  reaches the phone directly and video uses no mobile data (`CarHotspot.kt`). Only the car can
  join while it's on; it can be turned off under Advanced.
- **Hotspot relay.** libwebrtc on Android never gathers a candidate on the hotspot interface,
  so the phone listens on a UDP port on all interfaces, forwards it to libwebrtc's loopback
  candidate and advertises the hotspot's real address (`HotspotRelay.kt`).
- **TURN fallback.** When the direct link fails (normal hotspot, no Shizuku), the car can go
  through the server's coturn (car setting "Connect through the server"; offered after a
  failed connect). That uses mobile data both ways.
- **WebCodecs + canvas instead of `<video>`.** The Tesla browser stops `<video>` playback
  while driving. A canvas fed by a decoder isn't a video element, and it also has the lowest
  latency (no jitter buffer, no MSE). The decoder runs with `optimizeForLatency`.
- **Sound through the browser.** A Tesla switches its media source to the browser while the
  page is open, so Bluetooth audio from the phone isn't heard. The phone sends Opus (20 ms
  packets, unordered, no retransmits) and the car plays it with WebAudio. Source: the phone's
  output via remote submix with Shizuku (the phone goes quiet), else playback capture.
- **Tesla media card.** The car page's Media Session shows what's playing on the phone (title
  with Shizuku, else the app in front); its play, pause and skip buttons control the phone.
- **Backpressure:** if the data channel backs up, the phone drops frames up to the next key
  frame and requests one, so delay can't build up. If the phone's encoder falls behind (more
  than 1.2 s for about 6 s), the car reopens the app at 2/3 resolution and 30 fps.
- **Start with the car:** pick the car's Bluetooth in the phone app and CarMirror starts when
  the phone connects to it and stops when it disconnects.

## Repo layout

- `server/`: signaling server + car web client (vanilla JS, no build step).
  - `server.js`: pairing, signaling WebSocket, TURN credentials, APK download, car logs.
  - `public/js/app.js`: pairing, launcher, panes, split view, settings, media card, reconnect.
  - `public/js/player.js`: WebCodecs video decoding, canvas, touch to display coordinates.
  - `public/js/audio.js`: Opus decoding and playback.
  - `public/js/rtc.js`: signaling + WebRTC (the car offers and creates every channel).
  - `public/about.html`: `/about`, the landing page. `public/get.html`: `/get`, download + setup.
  - `public/diag.html`: open `/diag` in the car. It shows WebCodecs/WebRTC/codec support and a
    canvas animation test, and uploads the results to the server log.
- `android/`: the phone app (Kotlin, Compose).
  - `MainActivity.kt`: phone UI (Setup, Pair your car, Apps in the car, Advanced).
  - `MirrorService.kt`: foreground service, signaling, wake lock.
  - `CarPeer.kt`: WebRTC peer, control-channel protocol, now playing.
  - `AppSession.kt` / `ScreenSession.kt`: the two modes (one stream per car pane).
  - `PrivilegedService.kt`: Shizuku user service: virtual displays, app launch, input
    injection, audio capture, car hotspot, media sessions.
  - `TouchService.kt`: accessibility service: gestures, app in front, system bar sizes.
  - `AudioStreamer.kt`, `CarHotspot.kt`, `HotspotRelay.kt`, `CarBluetooth.kt`: see above.

### Wire formats

Video channel `v:<sid>` (phone to car), big-endian:
`[1][w u32][h u32]` new encoder session · `[2][flags u8][pts u64][size u32]payload…` packet
start (flags: 1 config, 2 key frame) · `[3]payload…` continuation (64 KB chunks).

Control channel `ctl` (JSON): car→phone `hello`, `apps?`, `start{sid,pkg,w,h,dpi,fps,bitrate}`,
`stop`, `touch{a,id,x,y,w,h}`, `scroll`, `key{k}`, `reset`, `resize{w,h}`, `keyboard{on}`, `ping`;
`audio{on}`, `mediaKey{k}`, `launch{pkg}` (screen mode);
phone→car `caps{mode}`, `apps`, `icon`, `started`, `ended{reason}`, `stats{lag}`, `bars`, `fg{pkg,home}`,
`media`, `log`, `status`, `error`, `pong`.

Audio channel `audio` (phone to car, unordered, no retransmits): `[pts µs u64][opus packet]`.

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
server over WSS. (That run predates the switch from scrcpy to in-app displays.) The emulator's software encoder is slow (seconds of lag at 1080p), which the
auto-degrade handles. Real phones encode in hardware.

## Known limits / to verify in the car

1. **Tesla browser support:** run `/diag` in the car first. Needs RTCPeerConnection, VideoDecoder
   and avc1 support. The results land in the server's `car.log`.
2. **Direct link:** the car's status bar says `direct` when the link uses the hotspot. If it
   doesn't, check that car hotspot mode is on (9.9.0.x, needs Shizuku) and look at
   Advanced → "Last link candidates" in the phone app.
3. **While driving:** the canvas approach avoids the `<video>` block. The `/diag` canvas test
   shows whether Tesla throttles canvas drawing too.
4. Apps with FLAG_SECURE (banking, Netflix) show black. Conductore doesn't set it.
5. After a phone reboot, open Shizuku and tap Start (Wireless debugging).
