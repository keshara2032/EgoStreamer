package com.egostreamer.app

import android.Manifest
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SurfaceViewRenderer

class MainActivity : AppCompatActivity(), SignalingClient.Listener {

    companion object {
        private const val TAG = "EgoStreamer"
        private const val PREFS_NAME = "egostreamer_prefs"
        private const val KEY_WS_URL = "saved_ws_url"
    }

    private lateinit var previewView: SurfaceViewRenderer
    private lateinit var overlayView: OverlayView
    private lateinit var rootLayout: View
    private lateinit var statusText: TextView
    private lateinit var scanButton: MaterialButton
    private lateinit var startButton: MaterialButton
    private lateinit var stopButton: MaterialButton

    private var webRtcClient: WebRtcClient? = null
    private var signalingClient: SignalingClient? = null
    private var isStreaming = false
    private var serverUrl = ""
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
                setServerUrl(contents, persist = true)
                lockToLandscape()
                startButton.post { startButton.requestFocus() }
            } else {
                toast("QR code must contain a valid ws:// or wss:// URL")
                scanButton.requestFocus()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rootLayout = findViewById(R.id.root_layout)
        previewView = findViewById(R.id.preview_view)
        overlayView = findViewById(R.id.overlay_view)
        statusText = findViewById(R.id.status_text)
        scanButton = findViewById(R.id.scan_button)
        startButton = findViewById(R.id.start_button)
        stopButton = findViewById(R.id.stop_button)
        applyPreviewMode()

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
            setPrompt(getString(R.string.scan_server_prompt))
            setBeepEnabled(true)
            setOrientationLocked(false)
        }
        scanLauncher.launch(options)
    }

    private fun startStreaming() {
        if (isStreaming) return

        val url = serverUrl.trim()
        if (!isValidWebSocketUrl(url)) {
            toast(getString(R.string.scan_required_message))
            scanButton.requestFocus()
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
            showLocalPreview = shouldShowLocalPreview(),
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
            Log.i(TAG, "Received message. Raw payload: $payload")


            
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
                    Log.i(TAG, "Received feedback message. Raw payload: $payload")
                    val feedbackJson = json.optJSONObject("feedback") ?: return

                    val feedback = OverlayView.Feedback(
                        protocol = feedbackJson.optString("protocol", ""),
                        action = feedbackJson.optString("action", "")
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

    private fun shouldShowLocalPreview(): Boolean {
        return resources.getBoolean(R.bool.show_local_camera_preview)
    }

    private fun applyPreviewMode() {
        val showLocalPreview = shouldShowLocalPreview()
        previewView.visibility = if (showLocalPreview) View.VISIBLE else View.INVISIBLE
        val backgroundColor = if (showLocalPreview) R.color.uva_blue else R.color.black
        rootLayout.setBackgroundColor(ContextCompat.getColor(this, backgroundColor))
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
                enabled = !isStreaming
            )
            setButtonEnabledState(
                button = stopButton,
                enabled = isStreaming
            )
            scanButton.isEnabled = !isStreaming
            focusPrimaryAction()
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
            setServerUrl(savedUrl, persist = false)
            lockToLandscape()
        } else {
            setServerUrl("", persist = false)
        }
    }

    private fun setServerUrl(url: String, persist: Boolean) {
        serverUrl = url
        if (persist) {
            prefs.edit().putString(KEY_WS_URL, url).apply()
        }
    }

    private fun isValidWebSocketUrl(url: String): Boolean {
        return url.startsWith("ws://") || url.startsWith("wss://")
    }

    private fun focusPrimaryAction() {
        val target = when {
            isStreaming -> stopButton
            serverUrl.isBlank() -> scanButton
            else -> startButton
        }
        target.post { target.requestFocus() }
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

    private fun setButtonEnabledState(button: Button, enabled: Boolean) {
        button.isEnabled = enabled
    }
}
