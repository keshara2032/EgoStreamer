# EgoStreamer Android Client

This app scans a QR code from the Python server, connects over WebSocket signaling, streams camera + mic using WebRTC, and receives detection metadata over a WebRTC DataChannel to draw bounding boxes.

## Prerequisites
- Android Studio (recommended)
- Android SDK 34
- A physical Android device on the same LAN as the Python server

## Setup
1. Open Android Studio.
2. Select **Open** and choose the `Android/` folder in this repo.
3. Let Gradle sync and download dependencies.
4. Plug in your Android device with USB debugging enabled.

## Run
1. Start the Python server (from `Server/`):
   ```bash
   SEND_MOCK_BBOX=1 python server.py
   ```
2. Run the Android app on your device.
3. Tap **Scan QR** and scan the QR code printed by the server.
4. Tap **Connect**.
5. You should see the local preview and bounding boxes (mock data).

## Manual URL input
If QR scanning fails, enter the WebSocket URL manually:
```
ws://<server-ip>:8080/ws
```

## Troubleshooting
- If you get a black screen, check camera permissions.
- If connection fails, make sure the phone and server are on the same Wi‑Fi.
- If you used a different port or host, scan the new QR or update the URL.

## Notes
- The app expects **DataChannel** label `detections`.
- Bounding boxes are normalized [0..1] relative to the preview size.
