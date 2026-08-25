package com.kcg.dr.flight.dji

import com.kcg.dr.djiutils.await
import com.kcg.dr.djiutils.ifConnected
import com.kcg.dr.flight.AircraftController.IGimbal
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.value.common.Attitude
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotation
import dji.sdk.keyvalue.value.gimbal.GimbalAttitudeRange
import dji.sdk.keyvalue.value.gimbal.GimbalMode
import dji.sdk.keyvalue.value.gimbal.GimbalResetType
import dji.v5.et.action
import dji.v5.et.cancelListen
import dji.v5.et.create
import dji.v5.et.listen
import dji.v5.et.set
import kotlinx.coroutines.flow.MutableStateFlow

class DJIGimbal : IGimbal {
    private val _attitudeRange = MutableStateFlow<GimbalAttitudeRange?>(null)
    private val _attitude = MutableStateFlow(Attitude())
    override val attitude = _attitude

    override suspend fun setCameraGimbalMode(mode: GimbalMode) = ifConnected {
        await { onSuccess, onFailure ->
            GimbalKey.KeyGimbalMode.create().set(mode, onSuccess, onFailure)
        }
    }

    override suspend fun reset() = ifConnected {
        GimbalKey.KeyGimbalAttitude.create().apply {
            cancelListen(this)
            listen(this) { it?.let { _attitude.value = it } }
        }
        GimbalKey.KeyGimbalAttitudeRange.create().apply {
            cancelListen(this)
            listen(this) { _attitudeRange.value = it }
        }

        await { onSuccess: ((EmptyMsg?) -> Unit), onFailure ->
            GimbalKey.KeyGimbalReset.create().action(GimbalResetType.RECENTER, onSuccess, onFailure)
        }
        await { onSuccess, onFailure ->
            GimbalKey.KeyGimbalMode.create().set(GimbalMode.YAW_FOLLOW, onSuccess, onFailure)
        }
    }

    override suspend fun angleCamera(
        rotation: GimbalAngleRotation,
        mode: GimbalMode?
    ) {
        mode?.let { setCameraGimbalMode(it) }
        _attitudeRange.value?.let {
            rotation.apply {
                pitch = pitch.coerceIn(it.pitch.min, it.pitch.max)
                yaw = yaw.coerceIn(it.yaw.min, it.yaw.max)
                roll = roll.coerceIn(it.roll.min, it.roll.max)
            }
        }
        await { onSuccess: ((EmptyMsg?) -> Unit), onFailure ->
            GimbalKey.KeyRotateByAngle.create().action(rotation, onSuccess, onFailure)
        }
    }
}