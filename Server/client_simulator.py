import argparse
import asyncio
import logging
import json

from aiohttp import ClientSession, WSMsgType
from aiortc import RTCPeerConnection, RTCIceCandidate, RTCSessionDescription
from aiortc.sdp import candidate_from_sdp, candidate_to_sdp
from aiortc.contrib.media import MediaPlayer

logging.basicConfig(level=logging.INFO)


async def run(server_url, video_device, audio_device):
    pc = RTCPeerConnection()
    channel = pc.createDataChannel("detections")

    @channel.on("message")
    def on_message(message):
        logging.info(f"DataChannel message: {message}")

    device = f"{video_device}:{audio_device}"
    player = MediaPlayer(
        device,
        format="avfoundation",
        options={"framerate": "30", "video_size": "640x480"},
    )

    if player.video:
        pc.addTrack(player.video)
    if player.audio:
        pc.addTrack(player.audio)

    answer_set = asyncio.Event()

    async with ClientSession() as session:
        async with session.ws_connect(server_url) as ws:

            @pc.on("icecandidate")
            async def on_icecandidate(event):
                if event is None:
                    return
                await ws.send_json(
                    {
                        "type": "candidate",
                        "candidate": {
                            "candidate": candidate_to_sdp(event),
                            "sdpMid": event.sdpMid,
                            "sdpMLineIndex": event.sdpMLineIndex,
                        },
                    }
                )

            async def ws_reader():
                async for msg in ws:
                    if msg.type != WSMsgType.TEXT:
                        continue
                    data = json.loads(msg.data)
                    msg_type = data.get("type")
                    if msg_type == "answer":
                        await pc.setRemoteDescription(
                            RTCSessionDescription(sdp=data["sdp"], type="answer")
                        )
                        answer_set.set()
                    elif msg_type == "candidate":
                        candidate = data.get("candidate")
                        if not candidate:
                            continue
                        candidate_sdp = candidate.get("candidate")
                        if not candidate_sdp:
                            continue
                        ice = candidate_from_sdp(candidate_sdp)
                        ice.sdpMid = candidate.get("sdpMid")
                        ice.sdpMLineIndex = candidate.get("sdpMLineIndex")
                        await pc.addIceCandidate(ice)
                    elif msg_type == "bye":
                        break

            reader_task = asyncio.create_task(ws_reader())

            offer = await pc.createOffer()
            await pc.setLocalDescription(offer)
            await ws.send_json(
                {"type": "offer", "sdp": pc.localDescription.sdp}
            )

            await answer_set.wait()
            logging.info("Streaming... press Ctrl+C to stop")
            await asyncio.Event().wait()
            reader_task.cancel()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--server", default="ws://localhost:8080/ws")
    parser.add_argument("--video", default="0", help="avfoundation video device index")
    parser.add_argument("--audio", default="0", help="avfoundation audio device index")
    args = parser.parse_args()

    try:
        asyncio.run(run(args.server, args.video, args.audio))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
