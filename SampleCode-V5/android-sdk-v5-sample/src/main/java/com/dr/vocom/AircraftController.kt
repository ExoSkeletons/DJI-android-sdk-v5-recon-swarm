package com.dr.vocom

import android.util.Log
import androidx.lifecycle.MutableLiveData
import dji.sampleV5.aircraft.models.BasicAircraftControlVM
import dji.sampleV5.aircraft.models.IntelligentFlightVM
import dji.sampleV5.aircraft.models.VirtualStickVM
import dji.sampleV5.aircraft.models.WayPointV3VM
import dji.sampleV5.aircraft.util.ToastUtils
import dji.sampleV5.aircraft.utils.LocationUtils
import dji.sampleV5.aircraft.utils.LocationUtils.RelativeDirection
import dji.sampleV5.aircraft.utils.LocationUtils.distanceTo
import dji.sampleV5.aircraft.virtualstick.OnScreenJoystick
import dji.sampleV5.aircraft.virtualstick.OnScreenJoystickListener
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.value.common.Attitude
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import dji.sdk.keyvalue.value.flightcontroller.FlightCoordinateSystem
import dji.sdk.keyvalue.value.flightcontroller.FlyToMode
import dji.sdk.keyvalue.value.flightcontroller.RollPitchControlMode
import dji.sdk.keyvalue.value.flightcontroller.VerticalControlMode
import dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam
import dji.sdk.keyvalue.value.flightcontroller.YawControlMode
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.et.action
import dji.v5.et.create
import dji.v5.et.listen
import dji.v5.manager.KeyManager
import dji.v5.manager.aircraft.virtualstick.Stick
import dji.v5.manager.intelligent.IntelligentFlightInfo
import dji.v5.manager.intelligent.IntelligentFlightInfoListener
import dji.v5.manager.intelligent.IntelligentFlightManager
import dji.v5.manager.intelligent.MissionType
import dji.v5.manager.intelligent.flyto.FlyToParam
import dji.v5.manager.intelligent.flyto.FlyToTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

open class AircraftController(
    private val stickVM: VirtualStickVM,
    private val acVM: BasicAircraftControlVM,
    private val intFlVM: IntelligentFlightVM,
    private val wayPointV3VM: WayPointV3VM,
) {
    companion object {
        /** virtual stick controller requires constant sending of updates to move aircraft.
         * Sending freq. range per docs is 10-22hz iirc.
         **/
        private const val FLIGHT_PARAM_SEND_FREQUENCY_HZ = 18L

        private val DEFAULT_CALLBACK = object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
            }

            override fun onFailure(error: IDJIError) {
                Log.e("Controller", "Error: ${error.errorCode()}")
            }
        }
        private val DEFAULT_CALLBACK_PARAM = object :
            CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
            override fun onSuccess(msg: EmptyMsg) {
            }

            override fun onFailure(error: IDJIError) {
                Log.e("Controller", "Error: ${error.errorCode()}")
            }
        }
    }

    private var onScRightStk: OnScreenJoystick? = null
    private var onScLeftStk: OnScreenJoystick? = null

    val location = MutableLiveData(LocationCoordinate3D())
    val attitude = MutableLiveData(Attitude())

    val intelFlightInfoListener = object : IntelligentFlightInfoListener {
        override fun onIntelligentFlightInfoUpdate(info: IntelligentFlightInfo) {
            info.supportedMissions?.let { supportedIntelligentFeatures = it }
        }

        override fun onIntelligentFlightErrorUpdate(error: IDJIError) {
            ToastUtils.showToast("intel-fl error: ${error.description()}")
        }
    }
    var supportedIntelligentFeatures: List<MissionType> = listOf()


    var flightJob: Job? = null


    fun attachOnScreenSticks(
        leftStk: OnScreenJoystick,
        rightStk: OnScreenJoystick,
        callback: CommonCallbacks.CompletionCallback? = null,
        deviation: Double = 0.02,
        activate: Boolean = true,
    ) {
        this.onScLeftStk = leftStk
        this.onScRightStk = rightStk
        leftStk.setJoystickListener(object : OnScreenJoystickListener {
            override fun onTouch(joystick: OnScreenJoystick?, pX: Float, pY: Float) {
                var leftPx = 0F
                var leftPy = 0F

                if (abs(pX) >= deviation) leftPx = pX
                if (abs(pY) >= deviation) leftPy = pY

                stickVM.setLeftPosition(
                    (leftPx * Stick.MAX_STICK_POSITION_ABS).toInt(),
                    (leftPy * Stick.MAX_STICK_POSITION_ABS).toInt()
                )
            }
        })
        rightStk.setJoystickListener(object : OnScreenJoystickListener {
            override fun onTouch(joystick: OnScreenJoystick?, pX: Float, pY: Float) {
                var rightPx = 0F
                var rightPy = 0F

                if (abs(pX) >= deviation) rightPx = pX
                if (abs(pY) >= deviation) rightPy = pY

                stickVM.setRightPosition(
                    (rightPx * Stick.MAX_STICK_POSITION_ABS).toInt(),
                    (rightPy * Stick.MAX_STICK_POSITION_ABS).toInt()
                )
            }
        })
        if (activate)
            activate(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    callback?.onSuccess()
                }

                override fun onFailure(error: IDJIError) {
                    callback?.onFailure(error)
                }
            })
    }

    fun activate(callback: CommonCallbacks.CompletionCallback = DEFAULT_CALLBACK) {
        if (!stickVMActive()) {
            stickVM.enableVirtualStick(callback)
        }
        intFlVM.initListener()
        FlightControllerKey.KeyAircraftLocation3D.create().listen(this) {
            it?.let { updated -> location.postValue(updated) }
        }
        FlightControllerKey.KeyAircraftAttitude.create().listen(this) {
            it?.let {
                attitude.postValue(it)
            }
        }
        IntelligentFlightManager.getInstance()
            .addIntelligentFlightInfoListener(intelFlightInfoListener)
    }

    fun stop(
        callback: CommonCallbacks.CompletionCallback = DEFAULT_CALLBACK,
        returnStickControl: Boolean = false
    ) {
        flightJob?.cancel()
        stickVM.enableVirtualStickAdvancedMode()
        stickVM.sendVirtualStickAdvancedParam(VirtualStickFlightControlParam())
        stickVM.disableVirtualStickAdvancedMode()
        if (returnStickControl) stickVM.disableVirtualStick(callback)
        FlightControllerKey.KeyStopTakeoff.create().action()
        if (returnStickControl) FlightControllerKey.KeyStopAutoLanding.create().action()
        FlightControllerKey.KeyEmergencyStop.create().action()
        IntelligentFlightManager.getInstance().flyToMissionManager.stopMission(callback)
        IntelligentFlightManager.getInstance().spotLightManager.stopMission(callback)
        IntelligentFlightManager.getInstance().poiMissionManager.stopMission(callback)
    }

    fun disable(callback: CommonCallbacks.CompletionCallback = DEFAULT_CALLBACK) {
        stop(callback, true)
        if (stickVMActive()) {
            stickVM.disableVirtualStickAdvancedMode()
            stickVM.disableVirtualStick(callback)
        }
        intFlVM.cleanListener()
        IntelligentFlightManager.getInstance()
            .removeIntelligentFlightInfoListener(intelFlightInfoListener)
        KeyManager.getInstance().cancelListen(this)
    }

    fun destroy() {
        disable()
    }

    private fun stickVMActive() =
        stickVM.currentVirtualStickStateInfo.value?.state?.isVirtualStickEnable == true

    private fun isAttached() = onScLeftStk != null && onScRightStk != null

    fun isActive() = stickVMActive() && isAttached()

    fun isMissionSupported(mission: MissionType): Boolean =
        supportedIntelligentFeatures.contains(mission)


    private suspend fun CoroutineScope.sendStickParamForDuration(
        durationSec: Double,
        flightControlParam: VirtualStickFlightControlParam
    ) {
        val intervalMs = 1000L * 1 / FLIGHT_PARAM_SEND_FREQUENCY_HZ

        val iterations = (durationSec * FLIGHT_PARAM_SEND_FREQUENCY_HZ).toInt()

        stickVM.enableVirtualStickAdvancedMode()
        delay(100)
        repeat(iterations) {
            if (!this.isActive) return@repeat

            stickVM.sendVirtualStickAdvancedParam(flightControlParam)
            delay(intervalMs)
        }
        stickVM.disableVirtualStickAdvancedMode()
    }


    fun takeoff(
        callback: CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> = DEFAULT_CALLBACK_PARAM,
        prep: Boolean = true, takeStickControl: Boolean = false
    ) {
        if (prep) {
            if (!stickVMActive()) {
                activate(object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() = takeoff(callback)
                    override fun onFailure(error: IDJIError) = callback.onFailure(error)
                })
                return
            }
        }
        val activateCallback = object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
            override fun onSuccess(msg: EmptyMsg?) {
                callback.onSuccess(msg)
                activate()
            }

            override fun onFailure(error: IDJIError) = callback.onFailure(error)
        }
        acVM.startTakeOff(if (takeStickControl) activateCallback else callback)
    }

    fun land(
        callback: CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> = DEFAULT_CALLBACK_PARAM
    ) {
        stop()
        acVM.startLanding(callback)
    }

    fun flyToIntelligent(
        target: LocationCoordinate3D,
        callback: CommonCallbacks.CompletionCallbackWithParam<LocationCoordinate3D>? = null,
    ) {
        if (!isMissionSupported(MissionType.FLY_TO)) {
            ToastUtils.showToast("FlyTo unsupported")
            return
        }

        val flyToTarget = FlyToTarget()
        flyToTarget.apply {
            maxSpeed = 1
            securityTakeoffHeight = 2
            targetLocation = target
        }
        val flyToParam = FlyToParam()
        flyToParam.apply { flyToMode = FlyToMode.SMART_HEIGHT }

        ToastUtils.showToast("pre fly to (intelli)")
        IntelligentFlightManager.getInstance().flyToMissionManager.startMission(
            flyToTarget, flyToParam,
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    ToastUtils.showToast("flyTo success @${target.toJson()}")
                    callback?.onSuccess(target)
                }

                override fun onFailure(error: IDJIError) {
                    ToastUtils.showToast("flyTo fail ${error.errorType()} , ${error.errorCode()}:${error.innerCode()}")
                    callback?.onFailure(error)
                }
            }
        )
        /*intFlVM.setFlyToMode(FlyToMode.SMART_HEIGHT)
        intFlVM.startFlyTo(flyToTarget)*/
    }

    fun flyToWaypoint(
        target: LocationCoordinate2D,
        altitudeMeters: Int = 30,
        speedMps: Double = 5.0,
        kmzDirPath: String,
        callback: CommonCallbacks.CompletionCallbackWithParam<LocationCoordinate3D>? = null
    ) {
        val current = location.value
        if (current == null || current.latitude == 0.0 && current.longitude == 0.0) {
            ToastUtils.showToast("No valid aircraft GPS fix")
            return
        }

        /*val waylineMissionForKmz = WaylineMission().apply {
            createTime = System.currentTimeMillis().toDouble()
            updateTime = System.currentTimeMillis().toDouble()
            author = "AircraftController.flyToWaypoint"
        }

        val waypointStart = WaylineExecuteWaypoint().apply {
            location = WaylineLocationCoordinate2D().apply {
                latitude = current.latitude
                longitude = current.longitude
            }
            height = current.altitude.toInt()
            speed = speedMps
        }
        val waypointEnd = WaylineExecuteWaypoint().apply {
            location = WaylineLocationCoordinate2D().apply {
                latitude = target.latitude
                longitude = target.longitude
            }
            height = altitudeMeters
            speed = speedMps
        }

        val wayline = Wayline().apply {
            waypoints = mutableListOf(waypointStart, waypointEnd)
            autoFlightSpeed = speedMps
        }

        val missionConfig = WaylineMissionConfig().apply {
            flyToWaylineMode = WaylineFlyToWaylineMode.SAFELY
            finishAction = WaylineFinishedAction.GOTO_FIRST_WAYPOINT // Or GO_HOME, LAND
            droneInfo = WaylineDroneInfo() // Basic drone info, might need more specific setup
            exitOnRCLostBehavior = WaylineExitOnRCLostBehavior.EXCUTE_RC_LOST_ACTION
            exitOnRCLostType = WaylineExitOnRCLostAction.GO_BACK // Or HOVER, LAND
            globalTransitionalSpeed = speedMps
        }

        val missionName = "flyToWaypoint_${System.currentTimeMillis()}.kmz"
        val kmzOutPath = kmzDirPath + "/${missionName}"

        WPMZManager.getInstance()
            .generateKMZFile(
                kmzOutPath,
                waylineMissionForKmz,
                missionConfig,
                wayline
            )
        wayPointV3VM.pushKMZFileToAircraft(kmzOutPath)*/
    }

    fun flyToSticks(
        target: LocationCoordinate3D,
        callback: CommonCallbacks.CompletionCallbackWithParam<LocationCoordinate3D>? = null,
        maxVelocity: Double = 0.1,
        positionTolerance: Double = 10.0,
        coordinateSystem: FlightCoordinateSystem = FlightCoordinateSystem.BODY,
        prep: Boolean = true,
    ) {
        if (prep) {
            if (!stickVMActive()) {
                activate(object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() =
                        flyToSticks(
                            target,
                            callback,
                            maxVelocity,
                            positionTolerance,
                            coordinateSystem,
                            false
                        )

                    override fun onFailure(error: IDJIError) {
                        callback?.onFailure(error)
                    }
                })
                return
            }
        }

        val intervalMs = 1000L * 1 / FLIGHT_PARAM_SEND_FREQUENCY_HZ

        flightJob?.cancel()
        flightJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                while (isActive) {
                    val cur = location.value ?: continue
                    val curYaw = attitude.value?.yaw ?: continue

                    // --- Distance check (3D) ---
                    val dist3D = cur.distanceTo(target)
                    if (dist3D <= positionTolerance) {
                        // Stop
                        ToastUtils.showShortToast("distance within tolerance")
                        break
                    }

                    val (vx, vy, vz) = LocationUtils.calculateVelocityToTarget(
                        cur,
                        target,
                        curYaw,
                        maxVelocity,
                        coordinateSystem
                    )

                    val param = VirtualStickFlightControlParam()
                    param.apply {
                        pitch = vy
                        roll = vx
                        yaw = 0.0
                        verticalThrottle = vz
                        rollPitchControlMode = RollPitchControlMode.VELOCITY
                        verticalControlMode = VerticalControlMode.VELOCITY
                        yawControlMode = YawControlMode.ANGULAR_VELOCITY
                        rollPitchCoordinateSystem = coordinateSystem
                    }
                    ToastUtils.showToast("d: $dist3D, vx: $vx, vy: $vy, vz: $vz")
                    // stickVM.sendVirtualStickAdvancedParam(param)

                    delay(intervalMs)
                }

                callback?.onSuccess(location.value!!)
            } catch (e: Exception) {
                ToastUtils.showToast(e.message.toString())
            } finally {
                stickVM.disableVirtualStickAdvancedMode()
                stop()
            }
        }
    }

    fun flyTo(
        location: LocationCoordinate3D,
        callback: CommonCallbacks.CompletionCallbackWithParam<LocationCoordinate3D>? = null
    ) {
        stop()
        when {
            isMissionSupported(MissionType.FLY_TO) -> flyToIntelligent(location, callback)
            /*
            FlightControllerKey.KeyIsWaypointSupport.create().get() == true
                    && FlightControllerKey.KeyIsGoHomePathSupport.create().get() == true ->
                flyToWaypoint(
                    LocationCoordinate2D(location.latitude, location.longitude),
                    kmzDirPath = "",
                    callback = callback
                )
            */

            else -> flyToSticks(location, callback)
        }
    }

    fun flyTo(
        location: LocationCoordinate2D,
        callback: CommonCallbacks.CompletionCallbackWithParam<LocationCoordinate3D>? = null
    ) = flyTo(
        LocationCoordinate3D(
            location.latitude,
            location.longitude,
            this.location.value!!.altitude
        ),
        callback
    )

    fun flyBySticks(
        direction: RelativeDirection,
        distance: Double,
        velocityMps: Double = 0.5,
        maxVelocity: Double = 1.0,
        coordinateSystem: FlightCoordinateSystem = FlightCoordinateSystem.BODY,
        callback: CommonCallbacks.CompletionCallback = DEFAULT_CALLBACK,
        prep: Boolean = true,
    ) {
        val velocity = minOf(velocityMps, maxVelocity)
        require(velocity > 0) { "Speed must be positive" }

        if (prep && !stickVMActive()) {
            activate(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() =
                    flyBySticks(
                        direction,
                        distance,
                        velocityMps,
                        maxVelocity,
                        coordinateSystem,
                        callback
                    )

                override fun onFailure(error: IDJIError) = callback.onFailure(error)
            })
            return
        }
        stop()

        val durationSec = abs(distance / velocity)

        val signDist = sign(distance)
        val signDir = direction.sign
        val v = signDir * signDist * velocity

        val flightControlParam = VirtualStickFlightControlParam().apply {
            pitch = 0.0
            roll = 0.0
            yaw = 0.0
            verticalThrottle = 0.0

            rollPitchCoordinateSystem = coordinateSystem

            rollPitchControlMode = RollPitchControlMode.VELOCITY
            verticalControlMode = VerticalControlMode.VELOCITY
            yawControlMode = YawControlMode.ANGULAR_VELOCITY

            when (direction) {
                RelativeDirection.FORWARD -> roll = v
                RelativeDirection.BACKWARD -> roll = v
                RelativeDirection.RIGHT -> pitch = v
                RelativeDirection.LEFT -> pitch = v
                RelativeDirection.UP -> verticalThrottle = v
                RelativeDirection.DOWN -> verticalThrottle = v
            }
        }

        flightJob?.cancel()
        flightJob = CoroutineScope(Dispatchers.Main).launch {
            sendStickParamForDuration(durationSec, flightControlParam)
            stop()
        }
    }

    fun ascendBy(distance: Double) = flyBySticks(RelativeDirection.UP, distance)
    fun forwardBy(distance: Double) = flyBySticks(RelativeDirection.FORWARD, distance)
    fun leftBy(distance: Double) = flyBySticks(RelativeDirection.LEFT, distance)

}