package com.egostreamer.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SurfaceViewRenderer

class MainActivity : AppCompatActivity(), SignalingClient.Listener {

    private lateinit var previewView: SurfaceViewRenderer
    private lateinit var overlayView: OverlayView
    private lateinit var statusText: TextView
    private lateinit var wsUrlInput: EditText
    private lateinit var scanButton: Button
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    private var webRtcClient: WebRtcClient? = null
    private var signalingClient: SignalingClient? = null
    private var isStreaming = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.all { it }
        if (granted) {
            startStreaming()
        } else {
            toast("Camera and microphone permissions are required")
        }
    }

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents != null) {
            wsUrlInput.setText(contents)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.preview_view)
        overlayView = findViewById(R.id.overlay_view)
        statusText = findViewById(R.id.status_text)
        wsUrlInput = findViewById(R.id.ws_url_input)
        scanButton = findViewById(R.id.scan_button)
        startButton = findViewById(R.id.start_button)
        stopButton = findViewById(R.id.stop_button)

        scanButton.setOnClickListener { startQrScan() }
        startButton.setOnClickListener { startStreaming() }
        stopButton.setOnClickListener { stopStreaming() }

        updateStatus("Disconnected")
        updateUiState(isStreaming = false)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStreaming()
    }

    private fun startQrScan() {
        val options = ScanOptions().apply {
            setPrompt("Scan server QR")
            setBeepEnabled(true)
            setOrientationLocked(false)
        }
        scanLauncher.launch(options)
    }

    private fun startStreaming() {
        if (isStreaming) return

        val url = wsUrlInput.text.toString().trim()
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
            toast("Please enter a valid ws:// or wss:// URL")
            return
        }

        if (!hasPermissions()) {
            requestPermissions()
            return
        }

        webRtcClient?.close()
        webRtcClient = WebRtcClient(
            context = this,
            previewView = previewView,
            onIceCandidateReady = { candidate ->
                signalingClient?.sendCandidate(candidate)
            },
            onDataMessage = { payload ->
                handleDataMessage(payload)
            },
            onConnectionState = { state ->
                updateStatus(state.name)
            }
        )

        signalingClient?.close()
        signalingClient = SignalingClient(url, this)
        signalingClient?.connect()
        updateStatus("Connecting")
        updateUiState(isStreaming = true)
    }

    private fun stopStreaming() {
        if (!isStreaming) return
        signalingClient?.close()
        signalingClient = null
        webRtcClient?.close()
        webRtcClient = null
        overlayView.setBoxes(emptyList())
        updateStatus("Disconnected")
        updateUiState(isStreaming = false)
    }

    private fun handleDataMessage(payload: String) {
        try {
            val json = JSONObject(payload)
            if (json.optString("type") != "bbox") return

            val boxesJson = json.optJSONArray("boxes") ?: return
            val boxes = mutableListOf<OverlayView.Box>()
            for (i in 0 until boxesJson.length()) {
                val box = boxesJson.optJSONObject(i) ?: continue
                boxes.add(
                    OverlayView.Box(
                        x = box.optDouble("x", 0.0).toFloat(),
                        y = box.optDouble("y", 0.0).toFloat(),
                        w = box.optDouble("w", 0.0).toFloat(),
                        h = box.optDouble("h", 0.0).toFloat(),
                        label = box.optString("label", ""),
                        score = box.optDouble("score", 0.0).toFloat()
                    )
                )
            }
            runOnUiThread { overlayView.setBoxes(boxes) }
        } catch (exc: Exception) {
            updateStatus("Data parse error")
        }
    }

    private fun hasPermissions(): Boolean {
        val camera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        return camera == PackageManager.PERMISSION_GRANTED && mic == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            )
        )
    }

    private fun updateStatus(message: String) {
        runOnUiThread {
            statusText.text = message
        }
    }

    private fun updateUiState(isStreaming: Boolean) {
        this.isStreaming = isStreaming
        runOnUiThread {
            startButton.isEnabled = !isStreaming
            stopButton.isEnabled = isStreaming
            scanButton.isEnabled = !isStreaming
            wsUrlInput.isEnabled = !isStreaming
        }
    }

    private fun toast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onConnected() {
        updateStatus("Connected")
        webRtcClient?.createOffer { sdp ->
            signalingClient?.sendOffer(sdp)
        }
    }

    override fun onAnswerReceived(sdp: String) {
        webRtcClient?.setRemoteDescription(sdp)
    }

    override fun onCandidateReceived(candidate: IceCandidate) {
        webRtcClient?.addIceCandidate(candidate)
    }

    override fun onDisconnected() {
        updateStatus("Disconnected")
        updateUiState(isStreaming = false)
    }

    override fun onError(message: String) {
        updateStatus("Error: $message")
        updateUiState(isStreaming = false)
    }
}
