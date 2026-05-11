package dji.sampleV5.aircraft.models

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.DJIKey
import dji.sdk.keyvalue.key.DJIKeyInfo
import dji.sdk.keyvalue.value.camera.CameraMode
import dji.sdk.keyvalue.value.camera.CameraShootPhotoMode
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.v5.common.callback.CommonCallbacks.CompletionCallbackWithParam
import dji.v5.et.action
import dji.v5.et.cancelListen
import dji.v5.et.createCamera
import dji.v5.et.listen
import dji.v5.et.set

class CameraControlsVM(application: Application) : AndroidViewModel(application) {
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

    fun startRecord(callback: CompletionCallbackWithParam<EmptyMsg>? = null) {
        val cameraIndex = cameraIndex.value ?: return
        CameraKey.KeyCameraMode.createCamera(cameraIndex).set(
            CameraMode.VIDEO_NORMAL,
            {
                CameraKey.KeyStartRecord.createCamera(cameraIndex).action(
                    EmptyMsg(),
                    { callback?.onSuccess(it) },
                    { callback?.onFailure(it) }
                )
            },
            { callback?.onFailure(it) }
        )
    }

    fun stopRecord(callback: CompletionCallbackWithParam<EmptyMsg>? = null) {
        val cameraIndex = cameraIndex.value ?: return
        CameraKey.KeyStopRecord.createCamera(cameraIndex).action(
            EmptyMsg(),
            { callback?.onSuccess(it) },
            { callback?.onFailure(it) }
        )
    }

    fun takePhoto(callback: CompletionCallbackWithParam<EmptyMsg>? = null) {
        val cameraIndex = cameraIndex.value ?: return
        CameraKey.KeyCameraMode.createCamera(cameraIndex).set(
            CameraMode.PHOTO_NORMAL,
            {
                CameraKey.KeyShootPhotoMode.createCamera(cameraIndex).set(
                    CameraShootPhotoMode.NORMAL,
                    {
                        CameraKey.KeyStartShootPhoto.createCamera(cameraIndex).action(
                            EmptyMsg(),
                            {
                                CameraKey.KeyStopShootPhoto.createCamera(cameraIndex).action()
                                callback?.onSuccess(it)
                            },
                            { callback?.onFailure(it) }
                        )
                    },
                    { callback?.onFailure(it) }
                )
            },
            { callback?.onFailure(it) }
        )
    }
}