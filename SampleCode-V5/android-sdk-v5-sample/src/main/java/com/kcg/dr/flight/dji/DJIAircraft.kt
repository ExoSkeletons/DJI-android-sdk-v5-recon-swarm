package com.kcg.dr.flight.dji

import android.util.Log
import com.kcg.dr.DJIErrorException
import com.kcg.dr.flight.AircraftController.Companion.TAG
import com.kcg.dr.flight.AircraftController.IAircraft
import dji.sampleV5.aircraft.models.BasicAircraftControlVM
import dji.sampleV5.aircraft.models.VirtualStickVM
import dji.sampleV5.aircraft.util.ToastUtils
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.value.common.Attitude
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.et.action
import dji.v5.et.create
import dji.v5.et.get
import dji.v5.et.listen
import dji.v5.manager.KeyManager
import dji.v5.manager.intelligent.IntelligentFlightInfo
import dji.v5.manager.intelligent.IntelligentFlightInfoListener
import dji.v5.manager.intelligent.IntelligentFlightManager
import dji.v5.manager.intelligent.MissionType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class DJIAircraft(
    private val acVM: BasicAircraftControlVM,
    private val stickVM: VirtualStickVM
) : IAircraft {
    private val _isFlying = MutableStateFlow(false)
    private val _height = MutableStateFlow(0.0)
    private val _location = MutableStateFlow(LocationCoordinate3D())
    private val _batteryPercent = MutableStateFlow(0)
    private val _attitude = MutableStateFlow(Attitude())
    private val _heading = MutableStateFlow(0.0)
    override val isFlying = _isFlying
    override val height = _height
    override val location = _location
    override val batteryPercent = _batteryPercent
    override val attitude = _attitude
    override val heading = _heading

    override suspend fun takeoff() {
        val msg = suspendCancellableCoroutine { cont ->
            acVM.startTakeOff(object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                override fun onSuccess(msg: EmptyMsg?) {
                    Log.d(TAG, "takeoff success")
                    cont.resume(msg)
                }

                override fun onFailure(error: IDJIError) {
                    Log.d(TAG, "takeoff fail ${error.description()}")
                    cont.resumeWithException(DJIErrorException(error))
                }
            })
        }
    }

    override suspend fun land() {
        TODO("Not yet implemented")
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
            info.supportedMissions?.let { supportedIntelligentFeatures = it }
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
        FlightControllerKey.KeyIsFlying.create().get(false) == true
        FlightControllerKey.KeyAircraftLocation3D.create().listen(this) {
            location.postValue(it)
        }
        FlightControllerKey.KeyAltitude.create().listen(this) {
            it?.let { updated -> height.postValue(updated) }
        }
        FlightControllerKey.KeyBatteryPowerPercent.create().listen(this) {
            it?.let { batteryPercent.postValue(it) }
        }
        FlightControllerKey.KeyAircraftAttitude.create().listen(this) {
            it?.let { attitude.postValue(it) }
        }
        FlightControllerKey.KeyCompassHeading.create().listen(this) {
            it?.let { heading.postValue(it) }
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