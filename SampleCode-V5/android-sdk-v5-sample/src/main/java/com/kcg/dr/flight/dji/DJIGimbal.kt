package com.kcg.dr.flight.dji

import com.kcg.dr.flight.AircraftController.*
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.value.common.Attitude
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotation
import dji.sdk.keyvalue.value.gimbal.GimbalMode
import dji.v5.et.cancelListen
import dji.v5.et.create
import dji.v5.et.listen
import kotlinx.coroutines.flow.MutableStateFlow

class DJIGimbal : IGimbal {
    private val _attitude = MutableStateFlow(Attitude())
    override val attitude = _attitude

    override fun setCameraGimbalMode(mode: GimbalMode) {
        TODO("Not yet implemented")
    }

    override fun reset() {
        GimbalKey.KeyGimbalReset.create().cancelListen(this)
        GimbalKey.KeyGimbalAttitude.create().listen(this) {
            it?.let { _attitude.postValue(it) }
        }
    }

    override fun angleCamera(
        rotation: GimbalAngleRotation,
        mode: GimbalMode?
    ) {
        TODO("Not yet implemented")
    }

}