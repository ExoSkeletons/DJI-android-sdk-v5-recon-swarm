package com.kcg.dr.flight.dji

import android.util.Log
import com.kcg.dr.djiutils.await
import com.kcg.dr.flight.AircraftController.Companion.TAG
import com.kcg.dr.flight.AircraftController.IAircraft
import dji.sampleV5.aircraft.util.ToastUtils
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.value.common.Attitude
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import dji.sdk.keyvalue.value.common.Velocity3D
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.et.action
import dji.v5.et.cancelListen
import dji.v5.et.create
import dji.v5.et.listen
import dji.v5.manager.KeyManager
import dji.v5.manager.intelligent.IntelligentFlightInfo
import dji.v5.manager.intelligent.IntelligentFlightInfoListener
import dji.v5.manager.intelligent.IntelligentFlightManager
import dji.v5.manager.intelligent.MissionType
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlin.time.Duration.Companion.milliseconds

class DJIAircraft : IAircraft {
    private val _isFlying = MutableStateFlow(false)
    private val _height = MutableStateFlow(0.0)
    private val _location: MutableStateFlow<LocationCoordinate3D?> = MutableStateFlow(null)
    private val _velocity: MutableStateFlow<Velocity3D> = MutableStateFlow(Velocity3D())
    private val _batteryPercent = MutableStateFlow(0)
    private val _attitude = MutableStateFlow(Attitude())
    private val _heading = MutableStateFlow(0.0)
    override val isFlying = _isFlying
    val areMotorsOn = MutableStateFlow(false)
    override val height = _height
    override val location = _location
    override val velocity = _velocity
    override val batteryPercent = _batteryPercent
    override val attitude = _attitude
    override val heading = _heading

    override suspend fun takeoff() {
        await { onSuccess: (EmptyMsg) -> Unit, onFailure ->
            FlightControllerKey.KeyStartTakeoff.create().action(onSuccess, onFailure)
        }
    }

    override suspend fun land() = coroutineScope {
        await { onSuccess: (EmptyMsg) -> Unit, onFailure ->
            FlightControllerKey.KeyStartAutoLanding.create().action(onSuccess, onFailure)
        }

        var isConfirmNeeded = false
        var isLandingConfirmed = false
        val confirmNeededKey = FlightControllerKey.KeyIsLandingConfirmationNeeded.create()
        confirmNeededKey.listen(this) { isConfirmNeeded = it == true }
        while (isActive && (isFlying.value || areMotorsOn.value)) {
            if (!isLandingConfirmed)
                if (isConfirmNeeded) {
                        FlightControllerKey.KeyConfirmLanding.create().action(onSuccess, onFailure)
                    }?.let { isLandingConfirmed = true }
                    await { onSuccess: (EmptyMsg) -> Unit, onFailure ->
                }
            delay(500.milliseconds)
        }
        confirmNeededKey.cancelListen(this)
    }

    override suspend fun stop(emergency: Boolean) {
        Log.d(TAG, "stopping" + if (emergency) " (emergency)" else "")

        stopIntelligentMissions()

        if (emergency) {
            Log.d(TAG, "emergency stopping")
            FlightControllerKey.KeyStopAutoLanding.create().action()
            FlightControllerKey.KeyEmergencyStop.create().action()
        }
        FlightControllerKey.KeyStopTakeoff.create().action()
    }

    val intelFlightInfoListener = object : IntelligentFlightInfoListener {
        override fun onIntelligentFlightInfoUpdate(info: IntelligentFlightInfo) {
            info.supportedMissions?.let {
                supportedIntelligentFeatures = it
                Log.i(TAG, "supported missions: ${it.joinToString(", ")}")
            }
        }

        override fun onIntelligentFlightErrorUpdate(error: IDJIError) {
            ToastUtils.showToast("intel-fl error: ${error.description()}")
        }
    }
    var supportedIntelligentFeatures: List<MissionType> = listOf()

    fun isMissionSupported(mission: MissionType): Boolean =
        supportedIntelligentFeatures.contains(mission)

    private fun stopIntelligentMissions() {
        Log.d(TAG, "stopping missions")
        val callback = object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {}
            override fun onFailure(error: IDJIError) {}
        }
        IntelligentFlightManager.getInstance().flyToMissionManager.stopMission(callback)
        IntelligentFlightManager.getInstance().spotLightManager.stopMission(callback)
        IntelligentFlightManager.getInstance().poiMissionManager.stopMission(callback)
    }

    override suspend fun init() {
        FlightControllerKey.KeyIsFlying.create().listen(this) {
            isFlying.value = it == true
        }
        FlightControllerKey.KeyAreMotorsOn.create().listen(this) {
            areMotorsOn.value = it == true
        }
        FlightControllerKey.KeyAircraftLocation3D.create().listen(this) {
            location.value = it?.apply { altitude = height.value }
        }
        FlightControllerKey.KeyAircraftVelocity.create().listen(this) {
            it?.let { velocity.value = it } ?: Velocity3D()
        }
        FlightControllerKey.KeyAltitude.create().listen(this) {
            it?.let {
                height.value = it
                location.value = location.value?.apply { altitude = it }
            }
        }
        FlightControllerKey.KeyBatteryPowerPercent.create().listen(this) {
            it?.let { batteryPercent.value = it }
        }
        FlightControllerKey.KeyAircraftAttitude.create().listen(this) {
            it?.let { attitude.value = it }
        }
        FlightControllerKey.KeyCompassHeading.create().listen(this) {
            it?.let { heading.value = it }
        }
        IntelligentFlightManager.getInstance()
            .addIntelligentFlightInfoListener(intelFlightInfoListener)
    }

    override suspend fun destroy() {
        IntelligentFlightManager.getInstance()
            .removeIntelligentFlightInfoListener(intelFlightInfoListener)
        KeyManager.getInstance().cancelListen(this)
    }
}