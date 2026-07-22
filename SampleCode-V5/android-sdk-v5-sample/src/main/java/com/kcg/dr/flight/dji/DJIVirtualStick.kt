package com.kcg.dr.flight.dji

import android.util.Log
import com.kcg.dr.DJIErrorException
import com.kcg.dr.flight.AircraftController.Companion.TAG
import com.kcg.dr.flight.AircraftController.FlightParam
import com.kcg.dr.flight.AircraftController.IVirtualStick
import dji.sampleV5.aircraft.models.VirtualStickVM
import dji.sdk.keyvalue.value.flightcontroller.RollPitchControlMode
import dji.sdk.keyvalue.value.flightcontroller.VerticalControlMode
import dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam
import dji.sdk.keyvalue.value.flightcontroller.YawControlMode
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class DJIVirtualStick(private val stickVM: VirtualStickVM) : IVirtualStick {
    fun isVirtualStickEnabled(): Boolean =
        stickVM.currentVirtualStickStateInfo.value?.state?.isVirtualStickEnable == true

    fun isVirtualStickAdvancedModeEnabled(): Boolean =
        stickVM.currentVirtualStickStateInfo.value?.state?.isVirtualStickAdvancedModeEnabled == true

    fun takeStickControl(callback: CommonCallbacks.CompletionCallback? = null) {
        Log.d(TAG, "enabling virtual stick...")
        if (isVirtualStickEnabled()) {
            Log.d(TAG, "virtual stick already enabled")
            callback?.onSuccess()
            return
        }
        stickVM.enableVirtualStick(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                Log.d(TAG, "virtual stick enabled")
                callback?.onSuccess()
            }

            override fun onFailure(error: IDJIError) {
                callback?.onFailure(error)
            }
        })
    }

    fun returnStickControl(callback: CommonCallbacks.CompletionCallback? = null) {
        Log.d(TAG, "returning stick control")
        Log.d(TAG, "disabling virtual stick...")
        if (!isVirtualStickEnabled()) {
            Log.d(TAG, "virtual stick already disabled")
            callback?.onSuccess()
            return
        }
        stickVM.disableVirtualStick(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                Log.d(TAG, "virtual stick disabled")
                callback?.onSuccess()
            }

            override fun onFailure(error: IDJIError) {
                callback?.onFailure(error)
            }
        })
    }

    suspend fun requireVirtualStick() {
        if (!isVirtualStickEnabled()) {
            Log.d(TAG, "virtual stick not enabled")
            suspendCancellableCoroutine { cont ->
                val callback = object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() = cont.resume(0)

                    override fun onFailure(error: IDJIError) =
                        cont.resumeWithException(DJIErrorException(error))
                }
                takeStickControl(callback)
            }
        } else Log.d(TAG, "virtual stick already enabled")
    }

    suspend fun requireVirtualStickAdvancedMode(
        onFailure: () -> Unit = { },
        waitFor: Duration = 200.milliseconds,
    ) {
        requireVirtualStick()

        if (!isVirtualStickAdvancedModeEnabled()) {
            Log.d(TAG, "virtual stick advanced mode not enabled")
            Log.d(TAG, "enabling virtual stick advanced mode...")
            stickVM.enableVirtualStickAdvancedMode()
            delay(waitFor) // Give some time for DJI to enable the advanced mode
            if (!isVirtualStickAdvancedModeEnabled()) {
                Log.d(TAG, "virtual stick advanced mode failed to enable")
                onFailure()
                throw IllegalStateException("virtual stick advanced mode failed to enable")
            }
            Log.d(TAG, "virtual stick advanced mode enabled")
        }
    }

    override suspend fun enable() {
        requireVirtualStick()
        requireVirtualStickAdvancedMode()
    }

    override suspend fun disable() {
        returnStickControl() // todo: wrap with dji callback cancelable coroutine
    }

    override fun setSpeedLevel(speedLevel: Double) {
        stickVM.setSpeedLevel(speedLevel)
    }

    override fun setLeftPosition(horizontal: Int, vertical: Int) {
        stickVM.setLeftPosition(horizontal, vertical)
    }

    override fun setRightPosition(horizontal: Int, vertical: Int) {
        stickVM.setRightPosition(horizontal, vertical)
    }

    fun FlightParam.build(): VirtualStickFlightControlParam {
        val mPitch = pitch
        val mRoll = roll
        val mYaw = yaw
        val mVerticalThrottle = verticalThrottle
        return VirtualStickFlightControlParam().apply {
            pitch = mPitch ?: 0.0
            roll = mRoll ?: 0.0
            yaw = mYaw ?: 0.0
            verticalThrottle = mVerticalThrottle ?: 0.0

            rollPitchCoordinateSystem = coordinateSystem

            rollPitchControlMode = RollPitchControlMode.VELOCITY
            verticalControlMode = VerticalControlMode.VELOCITY
            yawControlMode = YawControlMode.ANGULAR_VELOCITY
        }
    }

    override suspend fun sendStickParam(param: FlightParam) {
        stickVM.sendVirtualStickAdvancedParam(param.build())
}