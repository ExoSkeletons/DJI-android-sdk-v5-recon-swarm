package com.kcg.dr.controller

import android.util.Log
import androidx.lifecycle.MutableLiveData
import com.kcg.dr.LocationUtils
import com.kcg.dr.LocationUtils.distanceTo
import com.kcg.dr.toDegrees
import dji.sampleV5.aircraft.models.BasicAircraftControlVM
import dji.sampleV5.aircraft.models.IntelligentFlightVM
import dji.sampleV5.aircraft.models.VirtualStickVM
import dji.sampleV5.aircraft.models.WayPointV3VM
import dji.sampleV5.aircraft.util.ToastUtils
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
import dji.v5.common.error.DJICoreError
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.sign

open class AircraftController(
    private var flightScope: CoroutineScope,

    private val stickVM: VirtualStickVM,
    private val acVM: BasicAircraftControlVM,
    private val intFlVM: IntelligentFlightVM,
    private val wayPointV3VM: WayPointV3VM,
) {
    companion object {
        private const val TAG: String = "AircraftController"

        /** virtual stick controller requires constant sending of updates to move aircraft.
         * Sending freq. range per docs is 10-22hz iirc.
         **/
        private const val FLIGHT_PARAM_SEND_FREQUENCY_HZ = 18L

        private val DEFAULT_CALLBACK = object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                //Log.d(TAG, "Success")
            }

            override fun onFailure(error: IDJIError) {
                //Log.w(TAG, "Error: ${error.errorCode()}")
            }
        }
        private val DEFAULT_CALLBACK_PARAM = object :
            CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
            override fun onSuccess(msg: EmptyMsg) {
            }

            override fun onFailure(error: IDJIError) {
                //Log.w("Controller", "Error: ${error.errorCode()}")
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
            stickVM.enableVirtualStick(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    callback?.onSuccess()
                }

                override fun onFailure(error: IDJIError) {
                    callback?.onFailure(error)
                }
            })
    }

    private fun areOnScreenSticksAttached() = onScLeftStk != null || onScRightStk != null

    fun init() {
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

    fun activate(callback: CommonCallbacks.CompletionCallback = DEFAULT_CALLBACK) {
        Log.d(TAG, "enabling virtual stick...")
        if (isVirtualStickEnabled()) {
            Log.d(TAG, "virtual stick already enabled")
            callback.onSuccess()
            return
        }
        stickVM.enableVirtualStick(callback)
    }

    fun destroy() {
        stop(true)
        intFlVM.cleanListener()
        IntelligentFlightManager.getInstance()
            .removeIntelligentFlightInfoListener(intelFlightInfoListener)
        KeyManager.getInstance().cancelListen(this)
    }

    fun isVirtualStickEnabled() =
        stickVM.currentVirtualStickStateInfo.value?.state?.isVirtualStickEnable == true

    fun isVirtualStickAdvancedModeEnabled() =
        stickVM.currentVirtualStickStateInfo.value?.state?.isVirtualStickAdvancedModeEnabled == true

    fun isMissionSupported(mission: MissionType): Boolean =
        supportedIntelligentFeatures.contains(mission)


    suspend fun requireVirtualStick(
        onFailure: (IDJIError) -> Unit = { }
    ) {
        if (!isVirtualStickEnabled()) {
            Log.d(TAG, "virtual stick not enabled")
            suspendCancellableCoroutine { cont ->
                Log.i(TAG, "suspendCancellableCoroutine")
                val callback = object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        Log.d(TAG, "virtual stick enabled")
                        cont.resumeWith(Result.success(null))
                    }

                    override fun onFailure(error: IDJIError) {
                        onFailure(error)
                        cont.resumeWithException(IllegalStateException(error.description()))
                    }
                }
                activate(callback)
            }
            Log.i(TAG, "suspendCancellableCoroutine resumed")
            Log.i(TAG, "virtual stick enabled?=${isVirtualStickEnabled()}")
        }
    }

    suspend fun requireVirtualStickAdvancedMode(
        waitForMillis: Long = 200,
        onFailure: () -> Unit = { },
    ) {
        requireVirtualStick()

        if (!isVirtualStickAdvancedModeEnabled()) {
            Log.d(TAG, "virtual stick advanced mode not enabled")
            Log.d(TAG, "enabling virtual stick advanced mode...")
            stickVM.enableVirtualStickAdvancedMode()
            delay(waitForMillis) // Give some time for DJI to enable the advanced mode
            if (!isVirtualStickAdvancedModeEnabled()) {
                Log.d(TAG, "virtual stick advanced mode failed to enable")
                onFailure()
                throw IllegalStateException("virtual stick advanced mode failed to enable")
            }
            Log.d(TAG, "virtual stick advanced mode enabled")
        }
    }


    var flightJob: Job? = null

    fun fly(
        callback: CommonCallbacks.CompletionCallback = DEFAULT_CALLBACK,
        scope: CoroutineScope = flightScope,
        block: suspend AircraftController.(CommonCallbacks.CompletionCallback) -> Unit
    ) {
        flightJob?.cancel()
        val flightJob = scope.launch {
            runCatching {
                requireVirtualStickAdvancedMode()
                block(object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {} // we'll trigger success ourselves later
                    override fun onFailure(error: IDJIError) = callback.onFailure(error)
                })
            }.onFailure { e ->
                when (e) {
                    is CancellationException -> {
                        Log.w(TAG, "mission cancelled")
                        brake()
                    }

                    else -> {
                        Log.w(TAG, "mission flight caught exception: ${e.message}")
                        brake(true)
                        callback.onFailure(DJICoreError().build(e.message))
                    }
                }
            }.onSuccess {
                if (!coroutineContext.job.isCancelled) {
                    Log.d(TAG, "mission success")
                    brake()
                    callback.onSuccess()
                }
            }
            // Clear job
            if (flightJob == this.coroutineContext.job)
                flightJob = null
        }
        this.flightJob = flightJob
    }

    private suspend fun sendStickParamForDuration(
        durationSec: Double,
        flightControlParam: VirtualStickFlightControlParam
    ) {
        requireVirtualStickAdvancedMode()

        val intervalMs = (1000.0 / FLIGHT_PARAM_SEND_FREQUENCY_HZ).toLong()
        val iterations = ((durationSec * 1000) / intervalMs).toInt()

        repeat(iterations) {
            if (!coroutineContext.isActive) return@repeat
            stickVM.sendVirtualStickAdvancedParam(flightControlParam)
            delay(intervalMs)
        }
    }

    fun brake(returnStickControl: Boolean = false) {
        Log.d(TAG, "braking")
        stickVM.setLeftPosition(0, 0)
        stickVM.setRightPosition(0, 0)
        if (returnStickControl) {
            Log.d(TAG, "returning stick control")
            Log.d(TAG, "disabling virtual stick...")
            stickVM.disableVirtualStick(DEFAULT_CALLBACK)
        }
    }

    fun stopMissions() {
        Log.d(TAG, "stopping missions")
        IntelligentFlightManager.getInstance().flyToMissionManager.stopMission(DEFAULT_CALLBACK)
        IntelligentFlightManager.getInstance().spotLightManager.stopMission(DEFAULT_CALLBACK)
        IntelligentFlightManager.getInstance().poiMissionManager.stopMission(DEFAULT_CALLBACK)

        Log.d(TAG, "stopping flight mission job...")
        flightJob?.takeIf { it.isActive }
            ?.cancel() ?: Log.d(TAG, "no flight job to cancel")
    }

    fun stop(returnStickControl: Boolean = true) {
        Log.d(TAG, "stopping")

        stopMissions()

        if (returnStickControl) {
            Log.d(TAG, "emergency stop")
            FlightControllerKey.KeyStopAutoLanding.create().action()
            FlightControllerKey.KeyEmergencyStop.create().action()
        }
        FlightControllerKey.KeyStopTakeoff.create().action()

        brake(returnStickControl)
    }

    suspend fun flyBySticks(
        direction: LocationUtils.RelativeDirection, distance: Double,
        velocity: Double = 0.5, maxVelocity: Double = 1.0,
        coordinateSystem: FlightCoordinateSystem = FlightCoordinateSystem.BODY,
        callback: CommonCallbacks.CompletionCallback = DEFAULT_CALLBACK
    ) = coroutineScope {
        var v = minOf(velocity, maxVelocity)
        require(v > 0) { "Speed must be positive" }

        val durationSec = abs(distance / v)

        val signDist = sign(distance)
        val signDir = direction.sign

        v *= signDir * signDist

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
                LocationUtils.RelativeDirection.FORWARD -> roll = v
                LocationUtils.RelativeDirection.BACKWARD -> roll = v
                LocationUtils.RelativeDirection.RIGHT -> pitch = v
                LocationUtils.RelativeDirection.LEFT -> pitch = v
                LocationUtils.RelativeDirection.UP -> verticalThrottle = v
                LocationUtils.RelativeDirection.DOWN -> verticalThrottle = v
            }
        }

        Log.i(TAG, "flying by sticks ${direction.name} for $durationSec seconds")

        sendStickParamForDuration(durationSec, flightControlParam)
        callback.onSuccess()
    }

    suspend fun spinBy(
        angleDegrees: Double,
        velocity: Double = 50.0,
        minVelocity: Double = 5.0,
        targetToleranceDegrees: Double = 1.0,
        callback: CommonCallbacks.CompletionCallback = DEFAULT_CALLBACK
    ) {
        require(velocity > 0) { "velocity must be positive" }
        require(minVelocity >= 0) { "min velocity must be non-negative" }
        require(velocity >= minVelocity) { "min velocity cannot be greater than target velocity" }
        require(targetToleranceDegrees > 0) { "target tolerance must be positive" }

        val intervalMs = (1000.0 / FLIGHT_PARAM_SEND_FREQUENCY_HZ).toLong()

        val spinSign = sign(angleDegrees)
        var cumulativeYaw = 0.0
        var lastYaw = attitude.value?.yaw ?: 0.0
        val totalAngle = abs(angleDegrees)

        while (coroutineContext.isActive) {
            val currentYaw = attitude.value?.yaw ?: continue

            // Compute deltaYaw with wrapping
            var deltaYaw = currentYaw - lastYaw
            if (deltaYaw > 180) deltaYaw -= 360
            if (deltaYaw < -180) deltaYaw += 360

            // Accumulate deltaYaw
            cumulativeYaw += deltaYaw * spinSign
            lastYaw = currentYaw

            val remaining = totalAngle - cumulativeYaw
            if (remaining <= targetToleranceDegrees) break

            // Smooth ramping using fixed ramp angle
            val rampAngleDegrees = 20.0
            val rampUpFraction = (cumulativeYaw / rampAngleDegrees).coerceIn(0.0, 1.0)
            val rampDownFraction = (remaining / rampAngleDegrees).coerceIn(0.0, 1.0)
            val velocityFactor = minOf(rampUpFraction, rampDownFraction)
            val currentVelocity =
                (minVelocity + (velocity - minVelocity) * velocityFactor).coerceAtLeast(minVelocity)

            val flightParam = VirtualStickFlightControlParam().apply {
                pitch = 0.0
                roll = 0.0
                yaw = currentVelocity * spinSign
                verticalThrottle = 0.0

                rollPitchCoordinateSystem = FlightCoordinateSystem.BODY

                rollPitchControlMode = RollPitchControlMode.VELOCITY
                verticalControlMode = VerticalControlMode.VELOCITY
                yawControlMode = YawControlMode.ANGULAR_VELOCITY
            }

            stickVM.sendVirtualStickAdvancedParam(flightParam)
            delay(intervalMs)
        }

        callback.onSuccess()
    }

    suspend fun flyCircleSticks(
        radius: Double, count: Double = 1.0, velocity: Double,
        clockwise: Boolean = true,
        callback: CommonCallbacks.CompletionCallback = DEFAULT_CALLBACK
    ) = coroutineScope {
        require(velocity > 0) { "Speed must be positive" }


        val durationSec = 2 * Math.PI * count * radius / velocity
        val yawSign = if (clockwise) 1 else -1
        val angularVelocityRad = velocity / radius

        Log.i(
            TAG,
            "flying $count circle(s) of radius $radius by sticks at velocity ${velocity}m/s for ${durationSec}s"
        )

        val circleMotionParam = VirtualStickFlightControlParam().apply { // motion to do a circle.
            pitch = 0.0
            roll = velocity
            yaw = angularVelocityRad.toDegrees() * yawSign
            verticalThrottle = 0.0

            rollPitchCoordinateSystem = FlightCoordinateSystem.BODY

            rollPitchControlMode = RollPitchControlMode.VELOCITY
            verticalControlMode = VerticalControlMode.VELOCITY
            yawControlMode = YawControlMode.ANGULAR_VELOCITY
        }

        flyBySticks(LocationUtils.RelativeDirection.FORWARD, radius, velocity)
        delay(1000)
        spinBy(90.0 * yawSign, 60.0)
        sendStickParamForDuration(durationSec, circleMotionParam)
        spinBy(-90.0 * yawSign, 60.0)
        flyBySticks(LocationUtils.RelativeDirection.BACKWARD, radius, velocity)
        callback.onSuccess()
    }


    suspend fun takeoff(
        callback: CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> = DEFAULT_CALLBACK_PARAM,
        takeStickControl: Boolean = true
    ) {
        if (takeStickControl)
            requireVirtualStick()
        suspendCancellableCoroutine { cont ->
            acVM.startTakeOff(object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                override fun onSuccess(msg: EmptyMsg?) {
                    Log.d(TAG, "takeoff success")
                    if (takeStickControl) {
                        Log.d(TAG, "post takeoff, taking stick control...")
                        activate(object : CommonCallbacks.CompletionCallback {
                            override fun onSuccess() {
                                Log.d(TAG, "took control")
                                callback.onSuccess(msg)
                            }

                            override fun onFailure(error: IDJIError) = callback.onFailure(error)
                        })
                        return
                    }
                    callback.onSuccess(msg)
                    cont.resumeWith(Result.success(Unit))
                }

                override fun onFailure(error: IDJIError) {
                    Log.d(TAG, "takeoff fail ${error.description()}")
                    callback.onFailure(error)
                    cont.cancel()
                }
            })
        }
    }

    fun land(
        callback: CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> = DEFAULT_CALLBACK_PARAM
    ) {
        stop(false)
        acVM.startLanding(callback)
    }

    fun flyToIntelligent(
        target: LocationCoordinate3D,
        callback: CommonCallbacks.CompletionCallbackWithParam<LocationCoordinate3D?>? = null,
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
        callback: CommonCallbacks.CompletionCallbackWithParam<LocationCoordinate3D?>? = null
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

    suspend fun flyToSticks(
        target: MutableLiveData<LocationCoordinate3D>,
        callback: CommonCallbacks.CompletionCallbackWithParam<LocationCoordinate3D?>? = null,
        maxVelocity: Double = 0.1,
        positionTolerance: Double = 12.0,
        coordinateSystem: FlightCoordinateSystem = FlightCoordinateSystem.BODY,
    ) = coroutineScope {
        val intervalMs = 1000L * 1 / FLIGHT_PARAM_SEND_FREQUENCY_HZ

        try {
            while (isActive) {
                val cur = location.value ?: continue
                val curYaw = attitude.value?.yaw ?: continue

                // Adjust to live target
                val curTarget = target.value ?: continue

                // Distance check (3D)
                val dist3D = cur.distanceTo(curTarget)
                if (dist3D <= positionTolerance) {
                    // Stop
                    ToastUtils.showShortToast("distance within tolerance.\nMade it")
                    break
                }

                // Calculate velocities
                val (vx, vy, vz) = LocationUtils.calculateVelocityToTarget(
                    cur,
                    curTarget,
                    curYaw,
                    maxVelocity,
                    coordinateSystem
                )

                val convergeParam = VirtualStickFlightControlParam()
                convergeParam.apply {
                    pitch = vy
                    roll = vx
                    yaw = 0.0
                    verticalThrottle = vz
                    rollPitchControlMode = RollPitchControlMode.VELOCITY
                    verticalControlMode = VerticalControlMode.VELOCITY
                    yawControlMode = YawControlMode.ANGULAR_VELOCITY
                    rollPitchCoordinateSystem = coordinateSystem
                }
                stickVM.sendVirtualStickAdvancedParam(convergeParam)

                delay(intervalMs)
            }

            callback?.onSuccess(location.value)
        } catch (e: Exception) {
            ToastUtils.showToast(e.message.toString())
            Log.e(TAG, e.message.toString())
        }
    }

    fun flyTo(
        location: MutableLiveData<LocationCoordinate3D>,
        callback: CommonCallbacks.CompletionCallbackWithParam<LocationCoordinate3D?>? = null
    ) {
        when {
            isMissionSupported(MissionType.FLY_TO) -> flyToIntelligent(location.value!!, callback)
            /*
            FlightControllerKey.KeyIsWaypointSupport.create().get() == true
                    && FlightControllerKey.KeyIsGoHomePathSupport.create().get() == true ->
                flyToWaypoint(
                    LocationCoordinate2D(location.latitude, location.longitude),
                    kmzDirPath = "",
                    callback = callback
                )
            */

            else -> CoroutineScope(Dispatchers.Main).launch {
                fly {
                    flyToSticks(
                        location,
                        callback
                    )
                }
            }
        }
    }

    suspend fun ascendBy(distance: Double) = flyBySticks(LocationUtils.RelativeDirection.UP, distance)

    suspend fun forwardBy(distance: Double) = flyBySticks(LocationUtils.RelativeDirection.FORWARD, distance)

    suspend fun leftBy(distance: Double) = flyBySticks(LocationUtils.RelativeDirection.LEFT, distance)
}