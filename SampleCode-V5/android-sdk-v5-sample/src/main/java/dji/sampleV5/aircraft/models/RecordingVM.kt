package dji.sampleV5.aircraft.models

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kcg.dr.djiutils.actionOrExcept
import com.kcg.dr.djiutils.setOrExcept
import dji.sampleV5.aircraft.util.ToastUtils
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.DJIKey
import dji.sdk.keyvalue.key.DJIKeyInfo
import dji.sdk.keyvalue.value.camera.CameraMode
import dji.sdk.keyvalue.value.camera.CameraShootPhotoMode
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.v5.et.cancelListen
import dji.v5.et.createCamera
import dji.v5.et.listen
import kotlinx.coroutines.launch

class RecordingVM : ViewModel() {
    private class CameraKeyObserver<T>(
        val liveData: MediatorLiveData<T>,
        val camKeyInfo: DJIKeyInfo<T>
    ) {
        private var mSource: LiveData<ComponentIndexType>? = null
        var camKey: DJIKey<T>? = null

        fun setSource(cameraIndex: MutableLiveData<ComponentIndexType>?) {
            mSource?.let { liveData.removeSource(it) }
            mSource = cameraIndex
            mSource?.let { src ->
                liveData.addSource(src) { newIndex ->
                    camKey?.cancelListen(this)
                    camKey = null
                    if (newIndex == null) return@addSource
                    camKey = camKeyInfo.createCamera(newIndex)
                    camKey?.listen(this) { t -> t?.let { liveData.postValue(it) } }
                }
            }
        }
    }

    val cameraIndex = MutableLiveData(ComponentIndexType.LEFT_OR_MAIN)

    val isRecording: MediatorLiveData<Boolean> = MediatorLiveData(false)
    val isTakingPhoto: MediatorLiveData<Boolean> = MediatorLiveData(false)
    val mode: MediatorLiveData<CameraMode> = MediatorLiveData(CameraMode.PHOTO_NORMAL)

    private val liveDataToKeyListens = listOf(
        CameraKeyObserver(isRecording, CameraKey.KeyIsRecording),
        CameraKeyObserver(isTakingPhoto, CameraKey.KeyIsTakingPhoto),
        CameraKeyObserver(mode, CameraKey.KeyCameraMode),
    )

    init {
        for (key in liveDataToKeyListens)
            key.setSource(cameraIndex)
    }

    fun startRecord() {
        val cameraIndex = cameraIndex.value ?: return
        viewModelScope.launch {
            runCatching {
                CameraKey.KeyCameraMode.createCamera(cameraIndex)
                    .setOrExcept(CameraMode.VIDEO_NORMAL)
                CameraKey.KeyStartRecord.createCamera(cameraIndex).actionOrExcept(EmptyMsg())
            }.onFailure {
                ToastUtils.showToast("start recording failed: ${it.message}")
                Log.e(TAG, "start recording failed", it)
            }
        }
    }

    fun stopRecord() {
        val cameraIndex = cameraIndex.value ?: return
        viewModelScope.launch {
            runCatching {
                CameraKey.KeyStopRecord.createCamera(cameraIndex).actionOrExcept(EmptyMsg())
            }.onFailure {
                ToastUtils.showToast("stop recording failed: ${it.message}")
                Log.e(TAG, "stop recording failed", it)
            }
        }
    }

    fun takePhoto() {
        val cameraIndex = cameraIndex.value ?: return
        viewModelScope.launch {
            runCatching {
                CameraKey.KeyCameraMode.createCamera(cameraIndex)
                    .setOrExcept(CameraMode.PHOTO_NORMAL)
                CameraKey.KeyShootPhotoMode.createCamera(cameraIndex)
                    .setOrExcept(CameraShootPhotoMode.NORMAL)
                CameraKey.KeyStartShootPhoto.createCamera(cameraIndex).actionOrExcept(EmptyMsg())
                CameraKey.KeyStopShootPhoto.createCamera(cameraIndex).actionOrExcept(EmptyMsg())
            }.onFailure {
                ToastUtils.showToast("take photo failed: ${it.message}")
                Log.e(TAG, "take photo failed", it)
            }
        }
    }
}