package com.egostreamer.app

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.IceCandidate

class SignalingClient(
    private val url: String,
    private val listener: Listener
) {

    interface Listener {
        fun onConnected()
        fun onAnswerReceived(sdp: String)
        fun onCandidateReceived(candidate: IceCandidate)
        fun onDisconnected()
        fun onError(message: String)
    }

    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null

    fun connect() {
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                listener.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    when (json.optString("type")) {
                        "answer" -> listener.onAnswerReceived(json.getString("sdp"))
                        "candidate" -> {
                            val cand = json.getJSONObject("candidate")
                            val candidate = IceCandidate(
                                cand.optString("sdpMid"),
                                cand.optInt("sdpMLineIndex"),
                                cand.optString("candidate")
                            )
                            listener.onCandidateReceived(candidate)
                        }
                    }
                } catch (exc: Exception) {
                    listener.onError("Bad signaling message")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                listener.onDisconnected()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                listener.onError(t.message ?: "WebSocket failure")
            }
        })
    }

    fun sendOffer(sdp: String) {
        val json = JSONObject()
        json.put("type", "offer")
        json.put("sdp", sdp)
        webSocket?.send(json.toString())
    }

    fun sendCandidate(candidate: IceCandidate) {
        val cand = JSONObject()
        cand.put("candidate", candidate.sdp)
        cand.put("sdpMid", candidate.sdpMid)
        cand.put("sdpMLineIndex", candidate.sdpMLineIndex)

        val json = JSONObject()
        json.put("type", "candidate")
        json.put("candidate", cand)
        webSocket?.send(json.toString())
    }

    fun close() {
        webSocket?.close(1000, "bye")
        webSocket = null
    }
}
