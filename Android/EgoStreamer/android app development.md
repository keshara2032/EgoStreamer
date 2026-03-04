# Android App Development (Step‑by‑Step)

This guide assumes:
- You will scan a QR code printed by the Python server.
- The QR encodes the WebSocket URL (e.g., `ws://<lan-ip>:8080/ws`).
- WebRTC is used for video/audio streaming.
- The Python server will send back **object detection metadata** (bounding boxes) in real time.

## 1) Project setup
1. Create a new Android Studio project (Empty Activity).
1. Set min SDK to 24+ (WebRTC works lower too, but 24 is a safe baseline).
1. Enable Java 8+ desugaring if needed (Gradle default is usually fine).

## 2) Add dependencies
You need:
- A WebRTC library (prebuilt binaries)
- A QR scanner
- A WebSocket client (for signaling)
- Camera permissions (if you also do local preview)

Suggested approach:
- WebRTC: use a well‑maintained `org.webrtc` package artifact from Maven Central.
- QR: ML Kit Barcode Scanning or ZXing.
- WebSocket: OkHttp WebSocket (simple and reliable).

Note: I’m intentionally not pinning versions here. If you want, tell me your Android Gradle plugin version and I’ll provide exact versions.

## 3) Permissions
Add to `AndroidManifest.xml`:
- `android.permission.CAMERA`
- `android.permission.RECORD_AUDIO`
- `android.permission.INTERNET`

At runtime, request camera + mic permissions.

## 4) QR scanning flow
1. Show a “Scan QR” screen on app start.
1. Scan the QR code printed by the Python server.
1. Parse the WebSocket URL from the QR (example: `ws://192.168.1.10:8080/ws`).
1. Save it in memory and move to the streaming screen.

## 5) WebSocket signaling (single client)
Use the QR URL to connect:
1. Open WebSocket to the server URL.
1. Create a WebRTC `PeerConnection`.
1. Create local audio + video tracks.
1. Add tracks to the `PeerConnection`.
1. Create an SDP offer and send:
   ```json
   { "type": "offer", "sdp": "..." }
   ```
1. Receive SDP answer:
   ```json
   { "type": "answer", "sdp": "..." }
   ```
1. Exchange ICE candidates both ways:
   ```json
   {
     "type": "candidate",
     "candidate": {
       "candidate": "candidate:...",
       "sdpMid": "0",
       "sdpMLineIndex": 0
     }
   }
   ```

Once that’s done, media flows directly between phone and server.

## 6) Camera capture and preview
Typical flow with WebRTC:
1. Initialize `PeerConnectionFactory`.
1. Create `VideoCapturer` (Camera2 or CameraX).
1. Create `VideoSource` + `VideoTrack`.
1. Attach `VideoTrack` to a local preview `SurfaceViewRenderer`.
1. Add the track to the `PeerConnection`.

This gives you a live preview on the phone while streaming to the server.

## 7) How the server sends detections back to Android
You have two good options:

### Option A (recommended): WebRTC DataChannel
Pros: low latency, goes through same NAT traversal as media, simple once set up.

Flow:
1. Android creates a **DataChannel** on the `PeerConnection` with label `detections`.
1. Server (Python) listens for a data channel and uses it to send JSON messages.
1. Android receives JSON and overlays boxes on the preview.

Suggested message format (normalized coordinates 0‑1):
```json
{
  "type": "bbox",
  "frameId": 12345,
  "boxes": [
    { "x": 0.12, "y": 0.18, "w": 0.30, "h": 0.40, "label": "person", "score": 0.92 }
  ]
}
```

Notes:
- Use **normalized** coords so it doesn’t matter what resolution you capture.
- Use `frameId` or timestamp so you can align with the preview frame.


If you choose this, the server should expose `/meta` WebSocket and send the same JSON.

## 8) Drawing bounding boxes on Android
1. Create an overlay view (custom `View`) on top of the preview.
1. Store the latest list of boxes in that view.
1. On each update, call `invalidate()` and draw rectangles in `onDraw()`.

Important: map normalized coords to view coordinates:
```text
left   = x * viewWidth
top    = y * viewHeight
right  = (x + w) * viewWidth
bottom = (y + h) * viewHeight
```

Handle rotation/mirroring:
- If you use front camera, you’ll likely need to mirror horizontally.
- If the preview is rotated, rotate or swap axes accordingly.

## 9) NAT traversal (STUN/TURN)
For local LAN testing, you may not need TURN.
For real networks, you will need:
- STUN server (public is fine)
- TURN server (for reliability)

These are configured in the WebRTC `PeerConnection` ICE servers list.

## 10) Minimum viable milestones
1. Scan QR → connect WebSocket → send offer → receive answer.
1. Stream video/audio to server.
1. Draw local preview.
1. Receive mock bbox JSON and draw overlay.
1. Hook server object detection to real bbox messages.

## When you’re ready
Tell me which option you want for server‑to‑Android metadata (DataChannel or extra WebSocket).
I can then:
- Add the Python server DataChannel support.
- Provide concrete Android code skeletons for signaling + streaming + overlay.
