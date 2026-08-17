package com.kcg.dr.flight.dji

import android.util.Log
import com.kcg.dr.flight.AircraftController.ICamera
import com.kcg.dr.utils.CoroutineUtils.awaitCallback
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.common.error.IDJIError
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.datacenter.livestream.LiveStreamSettings
import dji.v5.manager.datacenter.livestream.LiveStreamStatus
import dji.v5.manager.datacenter.livestream.LiveStreamStatusListener
import dji.v5.manager.datacenter.livestream.LiveStreamType
import dji.v5.manager.datacenter.livestream.LiveVideoBitrateMode
import dji.v5.manager.datacenter.livestream.StreamQuality
import dji.v5.manager.datacenter.livestream.settings.RtmpSettings
import dji.v5.manager.interfaces.ICameraStreamManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

class DJICamera : ICamera<ComponentIndexType, LiveStreamStatus> {
    companion object {
        private val streamManager get() = MediaDataCenter.getInstance().liveStreamManager
        private val cameraManager get() = MediaDataCenter.getInstance().cameraStreamManager
        const val TAG = "DJI-Camera"
    }

    private val _isStreaming = MutableStateFlow(false)
    override val isStreaming: StateFlow<Boolean> = _isStreaming

    private val _liveStreamStatus = MutableStateFlow<LiveStreamStatus?>(null)
    override val liveStreamStatus: StateFlow<LiveStreamStatus?> = _liveStreamStatus

    private val _availableCameras = MutableStateFlow<List<ComponentIndexType>>(emptyList())
    override val availableCameras: StateFlow<List<ComponentIndexType>> = _availableCameras

    private val liveStreamStatusListener = object : LiveStreamStatusListener {
        override fun onLiveStreamStatusUpdate(status: LiveStreamStatus?) {
            _liveStreamStatus.value = status
            _isStreaming.value = status?.isStreaming == true
        }

        override fun onError(error: IDJIError?) {
            Log.w(TAG, "onError: $error")
        }
    }

    private val availableCameraUpdatedListener =
        ICameraStreamManager.AvailableCameraUpdatedListener { list ->
            _availableCameras.value = list
        }

    override suspend fun init() {
        streamManager.addLiveStreamStatusListener(liveStreamStatusListener)
        cameraManager.addAvailableCameraUpdatedListener(availableCameraUpdatedListener)
        _isStreaming.value = streamManager.isStreaming
    }

    override suspend fun destroy() {
        streamManager.removeLiveStreamStatusListener(liveStreamStatusListener)
        cameraManager.removeAvailableCameraUpdatedListener(availableCameraUpdatedListener)
    }

    override suspend fun startStream(url: String) {
        val settings = LiveStreamSettings.Builder()
            .setLiveStreamType(LiveStreamType.RTMP)
            .setRtmpSettings(
                RtmpSettings.Builder()
                    .setUrl(url)
                    .build()
            )
            .build()

        streamManager.apply {
            liveStreamSettings = settings
            liveStreamQuality = StreamQuality.ORIGINAL
            liveVideoBitrateMode = LiveVideoBitrateMode.AUTO
        }

        awaitCallback { streamManager.startStream(it) }
    }

    override suspend fun stopStream() {
        awaitCallback { streamManager.stopStream(it) }
    }

    override suspend fun setCameraIndex(index: ComponentIndexType) {
        streamManager.cameraIndex = index
    }
}
