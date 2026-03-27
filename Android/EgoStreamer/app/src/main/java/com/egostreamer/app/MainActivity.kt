package com.egostreamer.app

import android.Manifest
import android.content.Context
import android.content.res.ColorStateList
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
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

    companion object {
        private const val PREFS_NAME = "egostreamer_prefs"
        private const val KEY_WS_URL = "saved_ws_url"
    }

    private lateinit var previewView: SurfaceViewRenderer
    private lateinit var overlayView: OverlayView
    private lateinit var statusText: TextView
    private lateinit var wsUrlInput: EditText
    private lateinit var scanButton: ImageButton
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    private var webRtcClient: WebRtcClient? = null
    private var signalingClient: SignalingClient? = null
    private var isStreaming = false
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

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
        val contents = result.contents?.trim().orEmpty()
        if (contents.isNotEmpty()) {
            if (isValidWebSocketUrl(contents)) {
                wsUrlInput.setText(contents)
                saveServerUrl(contents)
                lockToLandscape()
            } else {
                toast("QR code must contain a valid ws:// or wss:// URL")
            }
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

        restoreSavedServerUrl()
        updateStatus("Disconnected")
        updateUiState(isStreaming = false)
    }

    override fun onDestroy() {
        releaseStreamingResources()
        super.onDestroy()
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
        if (!isValidWebSocketUrl(url)) {
            toast("Please enter a valid ws:// or wss:// URL")
            return
        }

        if (!hasPermissions()) {
            requestPermissions()
            return
        }

        lockToLandscape()
        releaseStreamingResources()
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

        signalingClient = SignalingClient(url, this)
        signalingClient?.connect()
        updateStatus("Connecting")
        updateUiState(isStreaming = true)
    }

    private fun stopStreaming() {
        releaseStreamingResources()
        clearOverlay()
        updateStatus("Disconnected")
        updateUiState(isStreaming = false)
    }

    private fun handleDataMessage(payload: String) {
        try {
            val json = JSONObject(payload)
            val type = json.optString("type")
            
            when (type) {
                "bbox" -> {
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
                }
                "feedback" -> {
                    val feedbackJson = json.optJSONObject("feedback") ?: return
                    val feedback = OverlayView.Feedback(
                        protocol = feedbackJson.optString("protocol", ""),
                        action = feedbackJson.optString("action", ""),
                        assistance = feedbackJson.optString("assistance", "")
                    )
                    runOnUiThread { overlayView.setFeedback(feedback) }
                }
            }
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
            if (isStreaming) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }

            setButtonEnabledState(
                button = startButton,
                enabled = !isStreaming,
                enabledColorRes = R.color.uva_orange
            )
            setButtonEnabledState(
                button = stopButton,
                enabled = isStreaming,
                enabledColorRes = R.color.uva_blue
            )
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
        stopStreaming()
    }

    override fun onError(message: String) {
        releaseStreamingResources()
        clearOverlay()
        updateStatus("Error: $message")
        updateUiState(isStreaming = false)
    }

    private fun restoreSavedServerUrl() {
        val savedUrl = prefs.getString(KEY_WS_URL, null)?.trim().orEmpty()
        if (savedUrl.isNotEmpty()) {
            wsUrlInput.setText(savedUrl)
            lockToLandscape()
        }
    }

    private fun saveServerUrl(url: String) {
        prefs.edit().putString(KEY_WS_URL, url).apply()
    }

    private fun isValidWebSocketUrl(url: String): Boolean {
        return url.startsWith("ws://") || url.startsWith("wss://")
    }

    private fun lockToLandscape() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }

    private fun releaseStreamingResources() {
        val signaling = signalingClient
        signalingClient = null
        signaling?.close()

        val rtcClient = webRtcClient
        webRtcClient = null
        rtcClient?.close()
    }

    private fun clearOverlay() {
        runOnUiThread {
            overlayView.setBoxes(emptyList())
            overlayView.setFeedback(null)
        }
    }

    private fun setButtonEnabledState(button: Button, enabled: Boolean, enabledColorRes: Int) {
        button.isEnabled = enabled
        val colorRes = if (enabled) enabledColorRes else R.color.button_disabled
        button.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, colorRes)
        )
    }
}
