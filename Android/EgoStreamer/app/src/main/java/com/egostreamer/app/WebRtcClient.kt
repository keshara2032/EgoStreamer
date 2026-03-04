package com.egostreamer.app

import android.content.Context
import android.util.Log
import android.media.AudioManager
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

class WebRtcClient(
    private val context: Context,
    private val previewView: SurfaceViewRenderer,
    private val onIceCandidateReady: (IceCandidate) -> Unit,
    private val onDataMessage: (String) -> Unit,
    private val onConnectionState: (PeerConnection.PeerConnectionState) -> Unit
) {

    private val eglBase = EglBase.create()
    private val peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null

    private var videoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var videoTrack: VideoTrack? = null
    private var audioTrack: AudioTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    init {
        initializePeerConnectionFactory(context.applicationContext)

        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase.eglBaseContext,
            true,
            true
        )
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        setupPreview()
        createPeerConnection()
        startLocalMedia()
    }

    companion object {
        @Volatile
        private var initialized = false

        private fun initializePeerConnectionFactory(appContext: Context) {
            if (initialized) return
            synchronized(this) {
                if (initialized) return
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(appContext)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions()
                )
                initialized = true
            }
        }
    }

    private fun setupPreview() {
        previewView.init(eglBase.eglBaseContext, null)
        previewView.setEnableHardwareScaler(true)
        previewView.setMirror(false)
    }

    private fun createPeerConnection() {
        val rtcConfig = PeerConnection.RTCConfiguration(emptyList())
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN

        peerConnection = peerConnectionFactory.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    onIceCandidateReady(candidate)
                }

                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                    onConnectionState(newState)
                }

                override fun onDataChannel(dataChannel: DataChannel) {
                    setupDataChannel(dataChannel)
                }

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
                override fun onAddStream(stream: org.webrtc.MediaStream) {}
                override fun onRemoveStream(stream: org.webrtc.MediaStream) {}
                override fun onSignalingChange(state: PeerConnection.SignalingState) {}
                override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: org.webrtc.RtpReceiver, streams: Array<out org.webrtc.MediaStream>) {}
            }
        )

        val dataChannel = peerConnection?.createDataChannel("detections", DataChannel.Init())
        if (dataChannel != null) {
            setupDataChannel(dataChannel)
        }
    }

    private fun setupDataChannel(channel: DataChannel) {
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}

            override fun onStateChange() {
                Log.d("WebRtcClient", "DataChannel state: ${channel.state()}")
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.data == null) return
                val data = buffer.data
                val bytes = ByteArray(data.remaining())
                data.get(bytes)
                val message = String(bytes, Charsets.UTF_8)
                onDataMessage(message)
            }
        })
    }

    private fun startLocalMedia() {
        logDeviceAudioSampleRate()
        videoCapturer = createCameraCapturer()
        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        videoSource = peerConnectionFactory.createVideoSource(false)
        videoCapturer?.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)
        videoTrack = peerConnectionFactory.createVideoTrack("video_track", videoSource)
        videoTrack?.addSink(previewView)

        audioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
        audioTrack = peerConnectionFactory.createAudioTrack("audio_track", audioSource)

        peerConnection?.addTrack(videoTrack, listOf("stream"))
        peerConnection?.addTrack(audioTrack, listOf("stream"))

        try {
            videoCapturer?.startCapture(1280, 720, 30)
        } catch (exc: Exception) {
            Log.e("WebRtcClient", "Failed to start capture: ${exc.message}")
        }
    }

    private fun logDeviceAudioSampleRate() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val rate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            val frames = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
            Log.i(
                "WebRtcClient",
                "DEVICE_AUDIO_RATE: sampleRate=$rate, framesPerBuffer=$frames"
            )
        } catch (exc: Exception) {
            Log.w("WebRtcClient", "DEVICE_AUDIO_RATE: failed to query (${exc.message})")
        }
    }

    private fun createCameraCapturer(): VideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        val deviceNames = enumerator.deviceNames

        for (name in deviceNames) {
            if (enumerator.isBackFacing(name)) {
                val capturer = enumerator.createCapturer(name, null)
                if (capturer != null) return capturer
            }
        }

        for (name in deviceNames) {
            val capturer = enumerator.createCapturer(name, null)
            if (capturer != null) return capturer
        }
        return null
    }

    fun createOffer(onOfferReady: (String) -> Unit) {
        val constraints = MediaConstraints()
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        onOfferReady(sdp.description)
                    }
                    override fun onSetFailure(error: String) {
                        Log.e("WebRtcClient", "setLocalDescription failed: $error")
                    }
                    override fun onCreateSuccess(sdp: SessionDescription) {}
                    override fun onCreateFailure(error: String) {}
                }, sdp)
            }
            override fun onCreateFailure(error: String) {
                Log.e("WebRtcClient", "createOffer failed: $error")
            }
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String) {}
        }, constraints)
    }

    fun setRemoteDescription(sdp: String) {
        val desc = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String) {
                Log.e("WebRtcClient", "setRemoteDescription failed: $error")
            }
            override fun onCreateSuccess(sdp: SessionDescription) {}
            override fun onCreateFailure(error: String) {}
        }, desc)
    }

    fun addIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    fun close() {
        try {
            videoCapturer?.stopCapture()
        } catch (_: Exception) {
        }
        videoCapturer?.dispose()
        videoSource?.dispose()
        audioSource?.dispose()
        surfaceTextureHelper?.dispose()
        previewView.release()
        peerConnection?.close()
        peerConnection?.dispose()
        eglBase.release()
    }
}
