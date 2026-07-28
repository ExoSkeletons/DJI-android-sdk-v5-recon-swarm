package com.kcg.dr.flight.dji

import com.kcg.dr.flight.AircraftController.IGimbal
import com.kcg.dr.utils.CoroutineUtils.await
import com.kcg.dr.utils.CoroutineUtils.await0
import com.kcg.dr.utils.CoroutineUtils.runIfConnected00
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.value.common.Attitude
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotation
import dji.sdk.keyvalue.value.gimbal.GimbalMode
import dji.sdk.keyvalue.value.gimbal.GimbalResetType
import dji.v5.et.action
import dji.v5.et.cancelListen
import dji.v5.et.create
import dji.v5.et.listen
import dji.v5.et.set
import kotlinx.coroutines.flow.MutableStateFlow

class DJIGimbal : IGimbal {
    private val _attitude = MutableStateFlow(Attitude())
    override val attitude = _attitude

    override suspend fun setCameraGimbalMode(mode: GimbalMode) = runIfConnected00 {
        await0 { onSuccess, onFailure ->
            GimbalKey.KeyGimbalMode.create().set(mode, onSuccess, onFailure)
        }
    }

    override suspend fun reset() = runIfConnected00 {
        GimbalKey.KeyGimbalAttitude.create().cancelListen(this)
        GimbalKey.KeyGimbalAttitude.create().listen(this) {
            it?.let { _attitude.value = it }
        }

        await { onSuccess, onFailure ->
            GimbalKey.KeyGimbalReset.create().action(GimbalResetType.RECENTER, onSuccess, onFailure)
        }
        await0 { onSuccess, onFailure ->
            GimbalKey.KeyGimbalMode.create().set(GimbalMode.YAW_FOLLOW, onSuccess, onFailure)
        }
    }

    override suspend fun angleCamera(
        rotation: GimbalAngleRotation,
        mode: GimbalMode?
    ) {
        mode?.let { setCameraGimbalMode(it) }
        await { onSuccess, onFailure ->
            GimbalKey.KeyRotateByAngle.create().action(rotation, onSuccess, onFailure)
        }
    }
}