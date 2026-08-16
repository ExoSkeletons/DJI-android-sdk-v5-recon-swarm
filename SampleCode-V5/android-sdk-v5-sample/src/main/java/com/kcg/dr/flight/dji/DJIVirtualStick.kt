package com.kcg.dr.flight.dji

import android.util.Log
import com.kcg.dr.flight.AircraftController.Companion.TAG
import com.kcg.dr.flight.AircraftController.FlightParam
import com.kcg.dr.flight.AircraftController.IVirtualStick
import com.kcg.dr.utils.CoroutineUtils.awaitCallback
import com.kcg.dr.utils.CoroutineUtils.awaitOrNull
import com.kcg.dr.utils.CoroutineUtils.ifConnected
import dji.sampleV5.aircraft.models.VirtualStickVM
import dji.sdk.keyvalue.value.flightcontroller.FlightCoordinateSystem
import dji.sdk.keyvalue.value.flightcontroller.RollPitchControlMode
import dji.sdk.keyvalue.value.flightcontroller.VerticalControlMode
import dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam
import dji.sdk.keyvalue.value.flightcontroller.YawControlMode
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class DJIVirtualStick(private val stickVM: VirtualStickVM) : IVirtualStick {
    val isVirtualStickEnabled: Boolean
        get() = stickVM.currentVirtualStickStateInfo.value?.state?.isVirtualStickEnable == true

    val isVirtualStickAdvancedModeEnabled: Boolean
        get() = stickVM.currentVirtualStickStateInfo.value?.state?.isVirtualStickAdvancedModeEnabled == true

    suspend fun acquireStickControl() {
        Log.d(TAG, "enabling virtual stick...")
        if (isVirtualStickEnabled) {
            Log.d(TAG, "virtual stick already enabled")
            return
        }
        awaitCallback {
            stickVM.enableVirtualStick(it)
        }
        Log.d(TAG, "virtual stick enabled")
    }

    suspend fun acquireVirtualStickAdvancedMode(
        onFailure: () -> Unit = { },
        waitFor: Duration = 300.milliseconds,
    ) {
        acquireStickControl()

        if (!isVirtualStickAdvancedModeEnabled) {
            Log.d(TAG, "virtual stick advanced mode not enabled")
            Log.d(TAG, "enabling virtual stick advanced mode...")
            stickVM.enableVirtualStickAdvancedMode()
            delay(waitFor) // Give some time for DJI to enable the advanced mode
            if (!isVirtualStickAdvancedModeEnabled) {
                Log.d(TAG, "virtual stick advanced mode failed to enable")
                onFailure()
                throw IllegalStateException("virtual stick advanced mode failed to enable")
            }
            Log.d(TAG, "virtual stick advanced mode enabled")
        }
    }

    override suspend fun takeControl() = ifConnected {
        acquireStickControl()
        acquireVirtualStickAdvancedMode()
    }

    override suspend fun relinquishControl() = ifConnected {
        Log.d(TAG, "returning stick control")
        Log.d(TAG, "disabling virtual stick...")
        if (!isVirtualStickEnabled) {
            Log.d(TAG, "virtual stick already disabled")
            return@ifConnected
        }
        awaitOrNull { stickVM.disableVirtualStick(it) }
        Log.d(TAG, "virtual stick disabled")
    }

    override val ownsControl: Boolean get() = isVirtualStickEnabled && isVirtualStickAdvancedModeEnabled

    override fun setSpeedLevel(speedLevel: Double) = stickVM.setSpeedLevel(speedLevel)

    override fun setLeftPosition(horizontal: Int, vertical: Int) =
        stickVM.setLeftPosition(horizontal, vertical)

    override fun setRightPosition(horizontal: Int, vertical: Int) =
        stickVM.setRightPosition(horizontal, vertical)

    fun FlightParam.build(): VirtualStickFlightControlParam {
        return VirtualStickFlightControlParam().apply {
            pitch = vy ?: 0.0
            roll = vx ?: 0.0
            yaw = yaw ?: 0.0
            verticalThrottle = vz ?: 0.0

            rollPitchCoordinateSystem = FlightCoordinateSystem.BODY

            rollPitchControlMode = RollPitchControlMode.VELOCITY
            verticalControlMode = VerticalControlMode.VELOCITY
            yawControlMode = YawControlMode.ANGULAR_VELOCITY
        }
    }

    override suspend fun sendStickParam(param: FlightParam) =
        stickVM.sendVirtualStickAdvancedParam(param.build())
}