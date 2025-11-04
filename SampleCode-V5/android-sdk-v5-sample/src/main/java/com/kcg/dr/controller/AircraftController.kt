package com.kcg.dr.controller

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import com.kcg.dr.LocationUtils
import com.kcg.dr.LocationUtils.bearingTo
import com.kcg.dr.LocationUtils.distanceTo
import com.kcg.dr.LocationUtils.translate
import com.kcg.dr.as2D
import com.kcg.dr.normalizeAngle
import com.kcg.dr.toDegrees
import com.kcg.dr.wrap180
import dji.sampleV5.aircraft.models.BasicAircraftControlVM
import dji.sampleV5.aircraft.models.CameraGimbalVM
import dji.sampleV5.aircraft.models.IntelligentFlightVM
import dji.sampleV5.aircraft.models.VirtualStickVM
import dji.sampleV5.aircraft.models.WayPointV3VM
import dji.sampleV5.aircraft.util.ToastUtils
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.GimbalKey
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
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotationMode
import dji.sdk.keyvalue.value.gimbal.GimbalMode
import dji.v5.common.callback.CommonCallbacks.CompletionCallback
import dji.v5.common.callback.CommonCallbacks.CompletionCallbackWithParam
import dji.v5.common.error.DJICoreError
import dji.v5.common.error.IDJIError
import dji.v5.et.action
import dji.v5.et.create
import dji.v5.et.listen
import dji.v5.manager.KeyManager
import dji.v5.manager.intelligent.IntelligentFlightInfo
import dji.v5.manager.intelligent.IntelligentFlightInfoListener
import dji.v5.manager.intelligent.IntelligentFlightManager
import dji.v5.manager.intelligent.MissionType
import dji.v5.manager.intelligent.flyto.FlyToParam
import dji.v5.manager.intelligent.flyto.FlyToTarget
import kotlinx.coroutines.CoroutineScope
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
import kotlin.math.pow
import kotlin.math.sign

open class AircraftController(
    private var flightScope: CoroutineScope,

    private val stickVM: VirtualStickVM,
    private val acVM: BasicAircraftControlVM,
    private val camGimbalVM: CameraGimbalVM,
    private val intFlVM: IntelligentFlightVM,
    private val wayPointV3VM: WayPointV3VM,
) {
    companion object {
        private const val TAG: String = "AircraftController"

        /** virtual stick controller requires constant sending of updates to move aircraft.
         * Sending freq. range per docs is 10-22hz iirc.
         **/
        private const val TRANSMISSION_FREQUENCY_HZ = 18L
        private const val TRANSMISSION_INTERVAL = (1000.0 / TRANSMISSION_FREQUENCY_HZ).toLong()

        private val DEFAULT_CALLBACK = object : CompletionCallback {
            override fun onSuccess() {
                //Log.d(TAG, "Success")
            }

            override fun onFailure(error: IDJIError) {
                Log.d(TAG, "error: ${error.errorCode()} ${error.description()}")
                ToastUtils.showToast("error: ${error.errorCode()}")
            }
        }
        private val DEFAULT_CALLBACK_PARAM = object :
            CompletionCallbackWithParam<EmptyMsg> {
            override fun onSuccess(msg: EmptyMsg) {
            }

            override fun onFailure(error: IDJIError) {
                Log.d(TAG, "error: ${error.errorCode()} ${error.description()}")
                ToastUtils.showToast("error: ${error.errorCode()}")
            }
        }
    }

    val location = MutableLiveData(LocationCoordinate3D())
    val attitude = MutableLiveData(Attitude())
    val heading = MutableLiveData(0.0)
    val height = intFlVM.aircraftHeight
    val gimbalAttitude = MutableLiveData(Attitude())

    val intelFlightInfoListener = object : IntelligentFlightInfoListener {
        override fun onIntelligentFlightInfoUpdate(info: IntelligentFlightInfo) {
            info.supportedMissions?.let { supportedIntelligentFeatures = it }
        }

        override fun onIntelligentFlightErrorUpdate(error: IDJIError) {
            ToastUtils.showToast("intel-fl error: ${error.description()}")
        }
    }
    var supportedIntelligentFeatures: List<MissionType> = listOf()

    fun init() {
        intFlVM.initListener()
        FlightControllerKey.KeyAircraftLocation3D.create().listen(this) {
            it?.let { updated -> location.postValue(updated) }
        }
        FlightControllerKey.KeyAircraftAttitude.create().listen(this) {
            it?.let { attitude.postValue(it) }
        }
        FlightControllerKey.KeyCompassHeading.create().listen(this) {
            it?.let { heading.postValue(it) }
        }
        GimbalKey.KeyGimbalAttitude.create().listen(this) {
            it?.let { gimbalAttitude.postValue(it) }
        }
        IntelligentFlightManager.getInstance()
            .addIntelligentFlightInfoListener(intelFlightInfoListener)
    }

    fun activate(callback: CompletionCallback = DEFAULT_CALLBACK) {
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
                val callback = object : CompletionCallback {
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
        callback: CompletionCallback = DEFAULT_CALLBACK,
        scope: CoroutineScope = flightScope,
        block: suspend AircraftController.(CompletionCallback) -> Unit
    ) {
        flightJob?.cancel()
        val flightJob = scope.launch {
            runCatching {
                requireVirtualStickAdvancedMode()
                block(object : CompletionCallback {
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
                        Log.w(
                            TAG,
                            "mission flight caught exception: ${e.toString()}: ${e.message.toString()}"
                        )
                        e.printStackTrace()
                        brake(true)
                        callback.onFailure(DJICoreError().build("${e.toString()}: ${e.message.toString()}"))
                        delay(1000)
                        land()
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

        val iterations = ((durationSec * 1000) / TRANSMISSION_INTERVAL).toInt()

        repeat(iterations) {
            if (!coroutineContext.isActive) return@repeat
            stickVM.sendVirtualStickAdvancedParam(flightControlParam)
            delay(TRANSMISSION_INTERVAL)
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

    suspend fun takeoff(
        callback: CompletionCallbackWithParam<EmptyMsg> = DEFAULT_CALLBACK_PARAM,
        takeStickControl: Boolean = true
    ) {
        if (takeStickControl)
            requireVirtualStick()
        suspendCancellableCoroutine { cont ->
            acVM.startTakeOff(object : CompletionCallbackWithParam<EmptyMsg> {
                override fun onSuccess(msg: EmptyMsg?) {
                    Log.d(TAG, "takeoff success")
                    if (takeStickControl) {
                        Log.d(TAG, "post takeoff, taking stick control...")
                        activate(object : CompletionCallback {
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
        callback: CompletionCallbackWithParam<EmptyMsg> = DEFAULT_CALLBACK_PARAM
    ) {
        stop(false)
        acVM.startLanding(callback)
    }

    fun flyToIntelligent(
        target: LocationCoordinate3D,
        callback: CompletionCallbackWithParam<LocationCoordinate3D?>? = null,
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
            object : CompletionCallback {
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
        callback: CompletionCallbackWithParam<LocationCoordinate3D?>? = null
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
        target: LocationCoordinate3D,
        callback: CompletionCallbackWithParam<LocationCoordinate3D?>? = null,
        maxVelocity: Double = 1.0,
        approachTolerance: Double = 4.0,
    ) = coroutineScope {
        while (isActive) {
            delay(TRANSMISSION_INTERVAL)

            val cur = location.value ?: continue
            val curYaw = attitude.value?.yaw ?: continue

            val curTarget = target

            // Distance check (3D)
            val dist3D = cur.distanceTo(curTarget)
            if (dist3D <= approachTolerance) break

            // Calculate velocity towards target
            val (vx, vy, vz) = LocationUtils.calculateVelocityToTarget(
                cur,
                curTarget,
                curYaw,
                maxVelocity
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
                rollPitchCoordinateSystem = FlightCoordinateSystem.BODY
            }
            stickVM.sendVirtualStickAdvancedParam(convergeParam)
        }

        callback?.onSuccess(location.value)
    }

    suspend fun followSticks(
        target: LiveData<LocationCoordinate3D>,
        maxVelocity: Double = 1.0,
        approachTolerance: Double = 3.0,
        escapeBuffer: Double = 1.0
    ) = coroutineScope {
        var curTarget: LocationCoordinate3D? = target.value
        var withinBuffer = false

        while (isActive) {
            delay(TRANSMISSION_INTERVAL)

            val cur = location.value ?: continue
            val curYaw = attitude.value?.yaw ?: continue

            // Adjust to live target
            curTarget = target.value ?: curTarget ?: continue

            // Distance check (3D)
            val dist3D = cur.distanceTo(curTarget)
            // Range check
            if (!withinBuffer && dist3D <= approachTolerance) withinBuffer = true
            if (withinBuffer && dist3D > approachTolerance + escapeBuffer) withinBuffer = false

            // If we're within range, don't move (minimise jitter)
            if (withinBuffer) {
                delay(1000)
                continue
            }

            // Calculate velocity towards target
            val (vx, vy, vz) = LocationUtils.calculateVelocityToTarget(
                cur,
                curTarget,
                curYaw,
                maxVelocity
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
                rollPitchCoordinateSystem = FlightCoordinateSystem.BODY
            }
            stickVM.sendVirtualStickAdvancedParam(convergeParam)
        }
    }

    suspend fun flyBySticks(
        direction: LocationUtils.RelativeDirection, distance: Double,
        velocity: Double = 0.5, maxVelocity: Double = 1.0,
        coordinateSystem: FlightCoordinateSystem = FlightCoordinateSystem.BODY,
        callback: CompletionCallback = DEFAULT_CALLBACK
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

    fun flyTo(
        location: LocationCoordinate3D,
        callback: CompletionCallbackWithParam<LocationCoordinate3D?>? = null
    ) {
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

            else -> fly { flyToSticks(location, callback) }
        }
    }

    suspend fun ascendBy(distance: Double) =
        flyBySticks(LocationUtils.RelativeDirection.UP, distance)

    suspend fun forwardBy(distance: Double) =
        flyBySticks(LocationUtils.RelativeDirection.FORWARD, distance)

    suspend fun leftBy(distance: Double) =
        flyBySticks(LocationUtils.RelativeDirection.LEFT, distance)

    suspend fun spinBy(
        angleDegrees: Double,
        velocity: Double = 70.0,
        minVelocity: Double = 5.0,
        targetToleranceDegrees: Double = 1.0,
        callback: CompletionCallback = DEFAULT_CALLBACK
    ) = coroutineScope {
        require(velocity > 0) { "velocity must be positive" }
        require(minVelocity >= 0) { "min velocity must be non-negative" }
        require(velocity >= minVelocity) { "min velocity cannot be greater than target velocity" }
        require(targetToleranceDegrees > 0) { "target tolerance must be positive" }

        val spinSign = sign(angleDegrees)
        var cumulativeYaw = 0.0
        var lastYaw = attitude.value?.yaw ?: 0.0
        val totalAngle = abs(angleDegrees)

        Log.i(TAG, "spinning by $angleDegrees degrees")

        while (isActive) {
            delay(TRANSMISSION_INTERVAL)

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
                // TODO: test if this works still without full param specification,
                //  and if this stops conflicts with other params
                yaw = currentVelocity * spinSign
                yawControlMode = YawControlMode.ANGULAR_VELOCITY
            }
            stickVM.sendVirtualStickAdvancedParam(flightParam)
        }

        callback.onSuccess()
    }

    suspend fun flyCircle(
        radius: Double, velocity: Double, count: Double = 1.0,
        clockwise: Boolean = true,
        fromCenter: Boolean = true,
        faceCenter: Boolean = true,
        faceForward: Boolean = true,
        callback: CompletionCallback = DEFAULT_CALLBACK
    ) = coroutineScope {
        require(velocity > 0) { "velocity must be positive" }

        val durationSec = 2 * Math.PI * count * radius / velocity
        val rotationSign = if (clockwise) 1 else -1
        val direction = if (faceForward) 1 else -1

        val circleMotionParam = VirtualStickFlightControlParam().apply {
            when (faceCenter) {
                true -> {
                    pitch = velocity * -rotationSign * direction
                    roll = 0.0
                }

                else -> {
                    pitch = 0.0
                    roll = velocity * rotationSign
                }
            }

            yaw = (velocity / radius).toDegrees() * rotationSign

            rollPitchControlMode = RollPitchControlMode.VELOCITY
            yawControlMode = YawControlMode.ANGULAR_VELOCITY

            rollPitchCoordinateSystem = FlightCoordinateSystem.BODY

            verticalThrottle = 0.0
            verticalControlMode = VerticalControlMode.VELOCITY
        }

        val posDelay = 1000L
        if (fromCenter) {
            flyBySticks(
                LocationUtils.RelativeDirection.FORWARD,
                radius,
                velocity
            ) // fly out from center
            if (faceForward) {
                delay(posDelay)
                spinBy(180.0) // spin to face center
            }
        }
        if (!faceCenter) spinBy(90.0 * direction) // spin to face travel
        sendStickParamForDuration(durationSec, circleMotionParam) // send circle motion
        if (!faceCenter) spinBy(-90.0 * direction) // spin back to face center
        if (fromCenter) {
            if (faceForward) {
                spinBy(180.0) // spin back to face outside circle
                delay(posDelay)
            }
            flyBySticks(
                LocationUtils.RelativeDirection.BACKWARD,
                radius,
                velocity
            ) // return to center
        }
        callback.onSuccess()
    }

    suspend fun scanGround(
        scanRadius: Double,
        velocity: Double,
        faceCenter: Boolean = true,
        clockwise: Boolean = true,
        callback: CompletionCallback = DEFAULT_CALLBACK
    ) = coroutineScope {
        camGimbalVM.setCameraGimbalMode(GimbalMode.YAW_FOLLOW)
        camGimbalVM.lookAt(scanRadius, -height.value!! * .75)
        flyCircle(
            scanRadius,
            velocity,
            clockwise = clockwise,
            fromCenter = true,
            faceCenter = true,
            callback = callback
        )
    }

    suspend fun flyRectangle(
        width: Double,
        height: Double,
        velocity: Double,
        clockwise: Boolean = true,
        callback: CompletionCallback = DEFAULT_CALLBACK
    ) = coroutineScope {
        require(velocity > 0) { "velocity must be positive" }

        val direction = if (clockwise) 1 else -1

        forwardBy(height / 2) // fly out from center
        spinBy(90.0 * direction) // face travel direction

        forwardBy(width / 2)
        spinBy(90.0 * direction)
        forwardBy(height)
        spinBy(90.0 * direction)
        forwardBy(width)
        spinBy(90.0 * direction)
        forwardBy(height)
        spinBy(90.0 * direction)
        forwardBy(width / 2)

        spinBy(-90.0 * direction) // spin back to face out
        forwardBy(-height / 2) // return to center

        callback.onSuccess()
    }

    suspend fun flySquare(
        width: Double,
        velocity: Double,
        clockwise: Boolean = true,
        callback: CompletionCallback = DEFAULT_CALLBACK
    ) = flyRectangle(width, width, velocity, clockwise, callback)


    fun pitchCamera(angle: Double) = camGimbalVM.pitch(angle)
    fun setCameraGimbalMode(mode: GimbalMode) = camGimbalVM.setCameraGimbalMode(mode)


    suspend fun lookAtWithSpin(target: LocationCoordinate2D, height: Double) {
        val currentLocation =
            this.location.value?.as2D() ?: throw IllegalStateException("can't get location")
        val heading = this.heading.value ?: throw IllegalStateException("can't get heading")
        val currentHeight = this.height.value ?: throw IllegalStateException("can't get height")

        // turn the aircraft to face the location (look straight ahead at it)
        val headingDiff = (currentLocation.bearingTo(target)
                - heading.normalizeAngle()
                ).wrap180()
        Log.d(TAG, "spinning to look at target, headingDiff: $headingDiff")
        spinBy(headingDiff)

        // aim the camera at the location
        camGimbalVM.lookAt(currentLocation.distanceTo(target), height - currentHeight)
    }

    suspend fun lookAtAndFollow(
        liveTargetLocation: LiveData<LocationCoordinate3D>,
        updateInterval: Long = 1000,
    ) = coroutineScope {
        while (isActive) {
            delay((1.0 / updateInterval).toLong())

            val targetLocation = liveTargetLocation.value ?: continue

            lookAtWithSpin(
                targetLocation.as2D(),
                targetLocation.altitude
            )
        }
    }

    suspend fun wave(waves: Int = 2) {
        val t = 0.2
        val tm = (t * 1000).toLong()
        val rollAngle = 15.0
        val waveAngle = 40.0

        camGimbalVM.reset()

        camGimbalVM.roll(rollAngle, t / 2, GimbalAngleRotationMode.RELATIVE_ANGLE)
        delay(tm)
        camGimbalVM.roll(-rollAngle * 2, t / 2, GimbalAngleRotationMode.RELATIVE_ANGLE)
        delay(tm)

        delay(400)

        repeat(waves) {
            camGimbalVM.pitch(-waveAngle, t)
            delay(tm)
            camGimbalVM.pitch(waveAngle * .5, t)
            delay(tm)
        }

        camGimbalVM.roll(rollAngle, t / 2, GimbalAngleRotationMode.RELATIVE_ANGLE)
        delay(tm)

        delay(100)

        camGimbalVM.reset()
    }

    suspend fun gimbalFan() = coroutineScope {
        val scanDuration = 3.0
        val scanDurationMs = (scanDuration * 1000).toLong()
        camGimbalVM.pitch(0.0, 0.5)
        delay(1000)
        camGimbalVM.pitch(-90.0, scanDuration)
        delay(scanDurationMs)
        delay(500)
        camGimbalVM.pitch(0.0, scanDuration)
        delay(scanDurationMs)
    }

    suspend fun perchShoulder(
        targetLocation: LiveData<LocationCoordinate3D>,
        targetHeading: LiveData<Double>,
        perchHeight: Double,
        perchDistance: Double
    ) = coroutineScope {
        val perchLocation = MediatorLiveData<LocationCoordinate3D>().apply {
            fun update() {
                val loc = targetLocation.value
                val heading = targetHeading.value
                if (loc != null && heading != null) {
                    value = loc.translate(
                        perchDistance,
                        LocationUtils.RelativeDirection.BACKWARD,
                        heading
                    ).apply { altitude = perchHeight }
                }
            }

            addSource(targetLocation) { update() }
            addSource(targetHeading) { update() }
            update()
        }

        // Follow perch location
        launch { followSticks(perchLocation) }
        // Keep Eyes on target location
        launch { lookAtAndFollow(targetLocation) }
    }

    suspend fun trailShoulder(
        targetLocation: LiveData<LocationCoordinate3D>,
        perchHeight: Double,
        tailDistance: Double,
        maxVelocity: Double = 1.0,
        approachTolerance: Double = 1.0,
        verticalTolerance: Double = 0.5,
    ) = coroutineScope {
        require(perchHeight > 0) { "perchHeight must be positive" }
        require(tailDistance > 0) { "tailDistance must be positive" }
        require(maxVelocity > 0) { "maxVelocity must be positive" }
        require(approachTolerance > 0 && verticalTolerance > 0) { "tolerances must be positive" }

        while (isActive) {
            delay(TRANSMISSION_INTERVAL)
            val target = targetLocation.value ?: continue
            val current = location.value ?: continue

            // face target
            lookAtWithSpin(target.as2D(), target.altitude)

            // signed deltas
            val dz = perchHeight - current.altitude
            val vSign = if (dz >= 0) 1 else -1
            val dh = current.as2D().distanceTo(target.as2D()) - tailDistance

            // Vertical: smooth to zero at perch height
            val vz =
                if (abs(dz) <= verticalTolerance) 0.0
                else vSign * (abs(dz).pow(2) / (abs(dz) + 1.0)).coerceAtMost(maxVelocity)

            // Forward: smooth to zero at perch distance
            val vf =
                if (abs(dh) <= approachTolerance) 0.0
                else (dh.pow(2) / (abs(dh) + 1.0)).coerceAtMost(maxVelocity)

            val param = VirtualStickFlightControlParam().apply {
                pitch = 0.0
                roll = vf
                yaw = 0.0
                verticalThrottle = vz
                rollPitchControlMode = RollPitchControlMode.VELOCITY
                verticalControlMode = VerticalControlMode.VELOCITY
                yawControlMode = YawControlMode.ANGULAR_VELOCITY
                rollPitchCoordinateSystem = FlightCoordinateSystem.BODY
            }

            stickVM.sendVirtualStickAdvancedParam(param)
        }
    }
}