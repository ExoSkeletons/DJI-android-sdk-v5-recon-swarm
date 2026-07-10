package com.kcg.dr.flight.dji

import com.kcg.dr.CoroutineUtils.await0
import com.kcg.dr.flight.AircraftController.IGimbal
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.value.common.Attitude
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotation
import dji.sdk.keyvalue.value.gimbal.GimbalMode
import dji.v5.et.cancelListen
import dji.v5.et.create
import dji.v5.et.listen
import dji.v5.et.set
import kotlinx.coroutines.flow.MutableStateFlow

class DJIGimbal : IGimbal {
    private val _attitude = MutableStateFlow(Attitude())
    override val attitude = _attitude

    override suspend fun setCameraGimbalMode(mode: GimbalMode) {
        await0 { onSuccess, onFailure ->
            GimbalKey.KeyGimbalMode.create().set(mode, onSuccess, onFailure)
        }
    }

    override fun reset() {
        GimbalKey.KeyGimbalReset.create().cancelListen(this)
        GimbalKey.KeyGimbalAttitude.create().listen(this) {
            it?.let { _attitude.value = it }
        }
    }

    override suspend fun angleCamera(
        rotation: GimbalAngleRotation,
        mode: GimbalMode?
    ) {
        mode?.let { setCameraGimbalMode(it) }
        await0 { onSuccess, onFailure ->
            GimbalKey.KeyRotateByAngle.create().set(rotation, onSuccess, onFailure)
        }
    }
}