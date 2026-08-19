package com.kcg.dr.flight.dji

import android.util.Log
import com.kcg.dr.flight.AircraftController.Companion.TAG
import com.kcg.dr.flight.AircraftController.FlightParam
import com.kcg.dr.flight.AircraftController.IVirtualStick
import com.kcg.dr.utils.CoroutineUtils.awaitCallback
import com.kcg.dr.utils.CoroutineUtils.awaitOrNull
import com.kcg.dr.utils.CoroutineUtils.ifConnected
import dji.sdk.keyvalue.value.flightcontroller.*
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager
import dji.v5.manager.aircraft.virtualstick.VirtualStickState
import dji.v5.manager.aircraft.virtualstick.VirtualStickStateListener
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class DJIVirtualStick : IVirtualStick {
    private val stickManager get() = VirtualStickManager.getInstance()

    private val _ownsControl = MutableStateFlow(false)
    override val ownsControl: StateFlow<Boolean> = _ownsControl

    private val _stickState = MutableStateFlow<VirtualStickState?>(null)
    private val _authorityReason = MutableStateFlow<FlightControlAuthorityChangeReason?>(null)

    private val stateListener = object : VirtualStickStateListener {
        override fun onVirtualStickStateUpdate(stickState: VirtualStickState) {
            _stickState.value = stickState
            updateOwnership()
        }

        override fun onChangeReasonUpdate(reason: FlightControlAuthorityChangeReason) {
            _authorityReason.value = reason
            updateOwnership()
        }
    }

    private fun updateOwnership() {
        val state = _stickState.value ?: return
        val isEnabled = state.isVirtualStickEnable
        val isAdvanced = state.isVirtualStickAdvancedModeEnabled
        val isOwner = state.currentFlightControlAuthorityOwner == FlightControlAuthority.MSDK
        
        _ownsControl.value = isEnabled && isAdvanced && isOwner
    }

    override suspend fun listen() {
        stickManager.setVirtualStickStateListener(stateListener)
    }

    override suspend fun stopListening() {
        stickManager.clearAllVirtualStickStateListener()
    }

    val isVirtualStickEnabled: Boolean
        get() = _stickState.value?.isVirtualStickEnable == true

    val isVirtualStickAdvancedModeEnabled: Boolean
        get() = _stickState.value?.isVirtualStickAdvancedModeEnabled == true

    suspend fun acquireStickControl() {
        Log.d(TAG, "enabling virtual stick...")
        if (isVirtualStickEnabled) {
            Log.d(TAG, "virtual stick already enabled")
            return
        }
        awaitCallback { stickManager.enableVirtualStick(it) }
        Log.d(TAG, "virtual stick enabled")
    }

    suspend fun acquireVirtualStickAdvancedMode(
        waitFor: Duration = 300.milliseconds,
        onFailure: () -> Unit = { },
    ) {
        acquireStickControl()

        if (!isVirtualStickAdvancedModeEnabled) {
            Log.d(TAG, "virtual stick advanced mode not enabled")
            Log.d(TAG, "enabling virtual stick advanced mode...")
            stickManager.setVirtualStickAdvancedModeEnabled(true)
            delay(waitFor)
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
        awaitOrNull { stickManager.disableVirtualStick(it) }
        Log.d(TAG, "virtual stick disabled")
    }

    override fun setSpeedLevel(speedLevel: Double) {
        stickManager.speedLevel = speedLevel
    }

    override fun setLeftPosition(horizontal: Int, vertical: Int) {
        stickManager.leftStick.horizontalPosition = horizontal
        stickManager.leftStick.verticalPosition = vertical
    }

    override fun setRightPosition(horizontal: Int, vertical: Int) {
        stickManager.rightStick.horizontalPosition = horizontal
        stickManager.rightStick.verticalPosition = vertical
    }

    private fun FlightParam.build(): VirtualStickFlightControlParam {
        return VirtualStickFlightControlParam().apply {
            // Note: we tested and *when in velocity mode* the pitch/roll axes become the axes of motion, not tilt.
            //  thus x (forward) is pitch and y (left) is roll.
            pitch = vy ?: 0.0
            roll = vx ?: 0.0
            yaw = this@build.yaw ?: 0.0
            verticalThrottle = vz ?: 0.0

            rollPitchCoordinateSystem = FlightCoordinateSystem.BODY

            rollPitchControlMode = RollPitchControlMode.VELOCITY
            verticalControlMode = VerticalControlMode.VELOCITY
            yawControlMode = YawControlMode.ANGULAR_VELOCITY
        }
    }

    override suspend fun sendStickParam(param: FlightParam) =
        stickManager.sendVirtualStickAdvancedParam(param.build())
}