# EgoStreamer Server + Python Simulator (WebRTC)

This folder contains:
- `server.py`: WebRTC server that receives audio/video and shows the video in a window.
- `client_simulator.py`: macOS simulator that captures webcam + mic and streams to the server.
- `requirements.txt`: Python dependencies.

The goal is to validate the streaming pipeline on a laptop before building the Android client.

## What WebRTC is (plain English)
WebRTC is a real‑time media protocol. It lets two peers exchange audio/video with very low latency.
Key ideas you need for this project:
- **PeerConnection**: the main WebRTC object that manages media transport.
- **Tracks**: audio/video streams (camera/mic).
- **SDP Offer/Answer**: the negotiation step to agree on codecs and network details.
- **ICE candidates**: the network addresses the peers can use to connect (helps with NAT traversal).
- **Signaling**: the side‑channel used to exchange the offer/answer and ICE candidates. WebRTC does not define signaling; you build it (HTTP, WebSocket, etc).

In this repo:
- We implement **signaling** via a simple HTTP POST to `/offer`.
- The server responds with an **answer** and then media flows directly between peers.

## Prerequisites (macOS)
- Python 3.10+ recommended
- Homebrew
- A webcam + microphone

We use `aiortc` which depends on **PyAV**, which in turn depends on **FFmpeg 7**.

## Setup (first time)

1) Go to the server folder:
```bash
cd /Volumes/Workspace/repos/EgoStreamer/Server
```

2) Create and activate a virtual environment:
```bash
python3 -m venv .venv
source .venv/bin/activate
```

3) Install system dependencies:
```bash
brew install ffmpeg@7 pkg-config
```

4) Ensure `pkg-config` can find FFmpeg 7:
```bash
export PKG_CONFIG_PATH="$(brew --prefix ffmpeg@7)/lib/pkgconfig"
```

5) Install Python build tools:
```bash
python -m pip install -U pip setuptools wheel cython
```

6) Install PyAV (required by aiortc):
```bash
pip install "av==14.4.0"
```

7) Install the rest of the dependencies:
```bash
pip install -r requirements.txt
```

## Run the server
```bash
python server.py
```
The server listens on `http://0.0.0.0:8080` and accepts WebSocket signaling at `ws://<host>:8080/ws`.
It also prints a QR code that encodes the WebSocket URL so your phone can scan it.

If the QR code shows the wrong IP (multiple network interfaces), set:
```bash
SERVER_BASE_URL=http://<your-lan-ip>:8080 python server.py
```
You can disable the QR code with:
```bash
PRINT_QR=0 python server.py
```

## Run the simulator (macOS capture)
In a second terminal (with the same venv activated):
```bash
python client_simulator.py --server ws://localhost:8080/ws --video 0 --audio 0
```

If you get the wrong device, list devices:
```bash
ffmpeg -f avfoundation -list_devices true -i ""
```
Use the device indexes in `--video` and `--audio`.

## Permissions (macOS)
macOS will ask for camera and microphone permissions for your terminal or Python.
If you blocked it, go to:
`System Settings -> Privacy & Security -> Camera/Microphone`
and enable your terminal app.

## What the server is doing
`server.py`:
- Creates a WebRTC `RTCPeerConnection`.
- Receives tracks from the client.
- For video: converts frames to OpenCV images and shows them in a window.
- For audio: just consumes frames (no playback yet).

You can disable the GUI window:
```bash
DISPLAY_VIDEO=0 python server.py
```

## Optional: play audio on the server
If you want to hear the incoming audio on the server, enable playback:
```bash
PLAY_AUDIO=1 python server.py
```

This requires `sounddevice` + PortAudio:
```bash
brew install portaudio
pip install sounddevice
```

## What the simulator is doing
`client_simulator.py`:
- Uses `MediaPlayer` with `avfoundation` (macOS capture).
- Adds the audio/video tracks to a WebRTC `RTCPeerConnection`.
- Creates a WebRTC **DataChannel** named `detections` and prints messages.
- Sends an SDP offer over WebSocket and applies the SDP answer.
- Keeps streaming until you press Ctrl+C.

## Server -> client metadata (DataChannel)
The server can send detection metadata back to the client over the same WebRTC
connection using a DataChannel.

- DataChannel label: `detections` (configurable via `DETECTION_CHANNEL_LABEL`)
- Message format (JSON, normalized coordinates):
  ```json
  {
    "type": "bbox",
    "frameId": 12345,
    "timestampMs": 1710000000000,
    "boxes": [
      { "x": 0.12, "y": 0.18, "w": 0.30, "h": 0.40, "label": "person", "score": 0.92 }
    ]
  }
  ```

To send mock boxes from the server for testing:
```bash
SEND_MOCK_BBOX=1 python server.py
```

## WebSocket signaling protocol (current)
Messages are JSON with a `type` field:
- `offer`: `{ "type": "offer", "sdp": "..." }`
- `answer`: `{ "type": "answer", "sdp": "..." }`
- `candidate`:
  ```json
  {
    "type": "candidate",
    "candidate": {
      "candidate": "candidate:... UDP ...",
      "sdpMid": "0",
      "sdpMLineIndex": 0
    }
  }
  ```
- `bye`: `{ "type": "bye" }`

## How Android will find and connect to the server
WebRTC requires **signaling** (exchange of offer/answer + ICE candidates).
There are common approaches:

1) **Hardcode an IP** (quick, but fragile)
- Works only on the same LAN and breaks when IP changes.

2) **Config file / QR code**
- The server shows a QR code with its address.
- Android app scans it to connect.
- Simple and reliable for local testing.

3) **mDNS / Bonjour (local discovery)**
- The server advertises itself on the LAN.
- Android can discover services automatically.
- Good for local networks, no external server needed.

4) **Signaling server with a fixed hostname**
- Use a stable domain (e.g., `signal.example.com`).
- Android always connects to that for signaling.
- The media can be P2P (best case) or relay via TURN if needed.

For production use, you’ll typically run:
- A **signaling server** (WebSocket or HTTP)
- A **STUN** server (public is OK)
- A **TURN** server for NAT traversal (critical for reliability)

## Next steps (planned)
- Add a frame processing hook in `server.py` for object detection.
- Add WebSocket signaling for the Android client.
