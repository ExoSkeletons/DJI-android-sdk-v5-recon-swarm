package dji.sampleV5.aircraft.models

import androidx.lifecycle.MutableLiveData
import dji.sdk.keyvalue.key.CameraKey
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

class CameraActionVM : DJIViewModel() {
    private var cameraIndex: ComponentIndexType = ComponentIndexType.LEFT_OR_MAIN
    val isRecording: MutableLiveData<Boolean> = MutableLiveData(false)

    init {
        setCameraIndex(ComponentIndexType.LEFT_OR_MAIN)
    }

    fun setCameraIndex(cameraIndex: ComponentIndexType) {
        CameraKey.KeyCameraMode.createCamera(this.cameraIndex).cancelListen(this)
        this.cameraIndex = cameraIndex
        CameraKey.KeyIsRecording.createCamera(cameraIndex).listen(this)
        { isRecording.postValue(it) }
    }

    fun cameraIndex(): ComponentIndexType = cameraIndex

    fun startRecord(callback: CompletionCallbackWithParam<EmptyMsg>? = null) {
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
        CameraKey.KeyStopRecord.createCamera(cameraIndex).action(
            EmptyMsg(),
            { callback?.onSuccess(it) },
            { callback?.onFailure(it) }
        )
    }

    fun takePhoto(callback: CompletionCallbackWithParam<EmptyMsg>? = null) {
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