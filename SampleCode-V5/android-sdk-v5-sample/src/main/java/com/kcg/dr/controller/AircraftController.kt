package com.kcg.dr.controller

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import com.kcg.dr.DJIErrorException
import com.kcg.dr.LocationUtils
import com.kcg.dr.LocationUtils.bearingTo
import com.kcg.dr.LocationUtils.distanceTo
import com.kcg.dr.LocationUtils.translate
import com.kcg.dr.LocationUtils.vectorToTarget
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
import dji.v5.et.get
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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

open class AircraftController(
    val scope: CoroutineScope,

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

    data class FlightParam(
        var pitch: Double? = null,
        var roll: Double? = null,
        var yaw: Double? = null,
        var verticalThrottle: Double? = null,

        var coordinateSystem: FlightCoordinateSystem = FlightCoordinateSystem.BODY,
    ) {

        fun merge(other: FlightParam): FlightParam {
            return FlightParam().apply {
                pitch = other.pitch ?: this@FlightParam.pitch
                roll = other.roll ?: this@FlightParam.roll
                yaw = other.yaw ?: this@FlightParam.yaw
                verticalThrottle = other.verticalThrottle ?: this@FlightParam.verticalThrottle

                coordinateSystem = other.coordinateSystem
            }
        }

        fun build(): VirtualStickFlightControlParam {
            return VirtualStickFlightControlParam().apply {
                pitch = this@FlightParam.pitch ?: 0.0
                roll = this@FlightParam.roll ?: 0.0
                yaw = this@FlightParam.yaw ?: 0.0
                verticalThrottle = this@FlightParam.verticalThrottle ?: 0.0

                rollPitchCoordinateSystem = coordinateSystem

                rollPitchControlMode = RollPitchControlMode.VELOCITY
                verticalControlMode = VerticalControlMode.VELOCITY
                yawControlMode = YawControlMode.ANGULAR_VELOCITY
            }
        }

        override fun toString(): String {
            return "" +
                    (if (pitch != null) "pitch: $pitch," else "") +
                    (if (roll != null) " roll: $roll," else "") +
                    (if (yaw != null) " yaw: $yaw," else "") +
                    (if (verticalThrottle != null) " verticalThrottle: $verticalThrottle" else "")
        }
    }

    val height = MutableLiveData(0.0)
    val location = MutableLiveData<LocationCoordinate3D>(null)
    val batteryPercent = MutableLiveData(0)
    val attitude = MutableLiveData<Attitude>(null)
    val heading = MutableLiveData(0.0)
    val gimbalAttitude = MutableLiveData<Attitude>(null)

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
        GimbalKey.KeyGimbalAttitude.create().listen(this) {
            it?.let { gimbalAttitude.postValue(it) }
        }
        IntelligentFlightManager.getInstance()
            .addIntelligentFlightInfoListener(intelFlightInfoListener)
    }

    fun destroy() {
        stop(true)
        flightParamTransmissionJob?.cancel()
        intFlVM.cleanListener()
        IntelligentFlightManager.getInstance()
            .removeIntelligentFlightInfoListener(intelFlightInfoListener)
        KeyManager.getInstance().cancelListen(this)
    }

    fun isFlying(): Boolean = FlightControllerKey.KeyIsFlying.create().get(false) == true

    fun isVirtualStickEnabled() =
        stickVM.currentVirtualStickStateInfo.value?.state?.isVirtualStickEnable == true

    fun isVirtualStickAdvancedModeEnabled() =
        stickVM.currentVirtualStickStateInfo.value?.state?.isVirtualStickAdvancedModeEnabled == true

    fun isMissionSupported(mission: MissionType): Boolean =
        supportedIntelligentFeatures.contains(mission)

    fun takeStickControl(callback: CompletionCallback = DEFAULT_CALLBACK) {
        Log.d(TAG, "enabling virtual stick...")
        if (isVirtualStickEnabled()) {
            Log.d(TAG, "virtual stick already enabled")
            callback.onSuccess()
            return
        }
        stickVM.enableVirtualStick(object : CompletionCallback {
            override fun onSuccess() {
                Log.d(TAG, "virtual stick enabled")
                startFlightParamTransmission()
                callback.onSuccess()
            }

            override fun onFailure(error: IDJIError) = callback.onFailure(error)
        })
    }

    fun returnStickControl(callback: CompletionCallback = DEFAULT_CALLBACK) {
        Log.d(TAG, "returning stick control")
        stopFlightParamTransmission()
        Log.d(TAG, "disabling virtual stick...")
        if (!isVirtualStickEnabled()) {
            Log.d(TAG, "virtual stick already disabled")
            callback.onSuccess()
            return
        }
        stickVM.disableVirtualStick(callback)
    }

    suspend fun requireVirtualStick() {
        if (!isVirtualStickEnabled()) {
            Log.d(TAG, "virtual stick not enabled")
            suspendCancellableCoroutine { cont ->
                val callback = object : CompletionCallback {
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


    private var flightJob: Job? = null

    fun fly(
        callback: CompletionCallback? = DEFAULT_CALLBACK,
        scope: CoroutineScope = this@AircraftController.scope,
        block: suspend AircraftController.(CompletionCallback) -> Unit
    ) {
        flightJob?.cancel()
        val flightJob = scope.launch {
            runCatching {
                requireVirtualStickAdvancedMode()
                block(object : CompletionCallback {
                    override fun onSuccess() {} // we'll trigger success ourselves later
                    override fun onFailure(error: IDJIError) {
                        callback?.onFailure(error)
                    }
                })
            }.onFailure { e ->
                when (e) {
                    is CancellationException -> {
                        Log.w(TAG, "cancellation in flight")
                        brake()
                    }

                    is DJIErrorException -> {
                        val error = e.error
                        Log.w(
                            TAG,
                            "${error.errorType()} error in flight: ${error.errorCode()}, ${error.description()}"
                        )
                        callback?.onFailure(error)
                        brake(true)
                    }

                    else -> {
                        Log.w(
                            TAG,
                            "exception in flight: ${e.toString()}: ${e.message.toString()}"
                        )
                        e.printStackTrace()
                        brake(true)
                        callback?.onFailure(
                            DJICoreError().build(
                                e.message,
                                e.javaClass.simpleName,
                                e.toString(),
                                0,
                                e.stackTrace
                            )
                        )
                    }
                }
            }.onSuccess {
                if (!coroutineContext.job.isCancelled) {
                    Log.d(TAG, "flight mission success")
                    brake()
                    callback?.onSuccess()
                }
            }
            // Clear job
            if (flightJob == this.coroutineContext.job)
                flightJob = null
        }
        this.flightJob = flightJob
    }

    fun brake(returnStickControl: Boolean = false) {
        Log.d(TAG, "braking")
        stickVM.setLeftPosition(0, 0)
        stickVM.setRightPosition(0, 0)
        if (returnStickControl) returnStickControl()
    }

    suspend fun brakeFor(duration: Duration, returnStickControl: Boolean = false) = coroutineScope {
        brake(returnStickControl)
        delay(duration)
    }


    private fun stopIntelligentMissions() {
        Log.d(TAG, "stopping missions")
        IntelligentFlightManager.getInstance().flyToMissionManager.stopMission(DEFAULT_CALLBACK)
        IntelligentFlightManager.getInstance().spotLightManager.stopMission(DEFAULT_CALLBACK)
        IntelligentFlightManager.getInstance().poiMissionManager.stopMission(DEFAULT_CALLBACK)

        Log.d(TAG, "stopping flight mission job...")
        flightJob?.takeIf { it.isActive }
            ?.cancel() ?: Log.d(TAG, "no flight job to cancel")
    }

    fun stop(emergency: Boolean = true) {
        Log.d(TAG, "stopping" + if (emergency) " (emergency)" else "")

        stopIntelligentMissions()

        if (emergency) {
            Log.d(TAG, "emergency stopping")
            FlightControllerKey.KeyStopAutoLanding.create().action()
            FlightControllerKey.KeyEmergencyStop.create().action()
        }
        FlightControllerKey.KeyStopTakeoff.create().action()

        brake(emergency)
    }


    suspend fun takeoff(
        callback: CompletionCallbackWithParam<EmptyMsg> = DEFAULT_CALLBACK_PARAM,
        takeStickControl: Boolean = true,
        awaitStabilisation: Boolean = true,
    ) {
        Log.d(TAG, "takeoff")

        if (takeStickControl) requireVirtualStick()
        if (FlightControllerKey.KeyIsFlying.create().get(false)) {
            Log.d(TAG, "already flying")
            callback.onSuccess(EmptyMsg())
            return
        }

        try {
            // Start takeoff
            Log.d(TAG, "starting takeoff")
            val msg = suspendCancellableCoroutine { cont ->
                acVM.startTakeOff(object : CompletionCallbackWithParam<EmptyMsg> {
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
            Log.d(TAG, "post takeoff")
            // Take stick control if required
            if (takeStickControl) requireVirtualStick()
            // Wait for aircraft stabilisation
            if (awaitStabilisation) {
                val takeoffStabilisationDelay = 6.seconds
                Log.d(TAG, "delaying for takeoff stabilisation...")
                delay(takeoffStabilisationDelay)
            }

            Log.d(TAG, "takeoff on success")
            callback.onSuccess(msg)
        } catch (e: DJIErrorException) {
            Log.d(TAG, "takeoff on failure")
            callback.onFailure(e.error)
        }
    }

    fun land(
        callback: CompletionCallbackWithParam<EmptyMsg> = DEFAULT_CALLBACK_PARAM
    ) {
        Log.d(TAG, "land")
        stop(false)
        Log.d(TAG, "starting landing")
        acVM.startLanding(callback)
    }

    private var flightParamTransmissionJob: Job? = null
    private val flightParamFlow = MutableSharedFlow<FlightParam>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private fun startFlightParamTransmission() {
        Log.d(TAG, "starting flight param transmission job")
        flightParamTransmissionJob?.cancel()
        flightParamTransmissionJob = scope.launch {
            val buffer = mutableListOf<FlightParam>()
            val collectJob = launch { flightParamFlow.collect { buffer += it } }

            while (isActive) {
                // Collect all commands and stop after interval (collect alone blocks forever)
                delay(TRANSMISSION_INTERVAL)
                if (buffer.isNotEmpty()) {
                    if (buffer.size > 2) Log.i(TAG, "reducing ${buffer.size} flight params")
                    val combinedParam = buffer.reduce { param1, param2 -> param1.merge(param2) }
                    stickVM.sendVirtualStickAdvancedParam(combinedParam.build())
                    buffer.clear()
                }
            }

            collectJob.cancelAndJoin()
            Log.d(TAG, "flight param transmission job cancelled")
        }
    }

    private fun stopFlightParamTransmission() {
        Log.d(TAG, "cancelling flight param transmission job")
        flightParamTransmissionJob?.cancel() ?: Log.d(
            TAG,
            "no flight param transmission job to cancel"
        )
        flightParamTransmissionJob = null
    }

    fun sendFlightParam(flightParam: FlightParam) {
        scope.launch { flightParamFlow.emit(flightParam) }
    }

    private suspend fun sendStickParamForDuration(
        duration: Duration,
        flightControlParam: FlightParam
    ) {
        requireVirtualStickAdvancedMode()

        val iterations = ((duration.inWholeMilliseconds) / TRANSMISSION_INTERVAL).toInt()

        repeat(iterations) {
            if (!currentCoroutineContext().isActive) return@repeat
            sendFlightParam(flightControlParam)
            delay(TRANSMISSION_INTERVAL)
        }
    }

    fun flyToIntelligent(
        target: LocationCoordinate3D,
        callback: CompletionCallbackWithParam<LocationCoordinate3D>? = null,
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
        callback: CompletionCallbackWithParam<LocationCoordinate3D>? = null
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

    fun smoothVelocity(
        startLocation: LocationCoordinate3D,
        currentLocation: LocationCoordinate3D,
        endLocation: LocationCoordinate3D,
        curYaw: Double,
        maxVelocity: Double,
        minVelocity: Double = 0.5,
        accelerationDist: Double, decelerationDist: Double,
    ): Triple<Double, Double, Double> {
        require(maxVelocity > 0) { "maxVelocity must be positive" }
        require(minVelocity > 0) { "minVelocity must be positive" }
        require(accelerationDist >= 0) { "accelerationDist must be non-negative" }
        require(decelerationDist >= 0) { "decelerationDist must be non-negative" }

        val (x, y, z) = vectorToTarget(currentLocation, endLocation, curYaw)

        val distToTarget = currentLocation.distanceTo(endLocation)
        val distFromStart = startLocation.distanceTo(currentLocation)

        val decelerationFactor = if (decelerationDist > 0) {
            if (distToTarget < decelerationDist)
                distToTarget / decelerationDist
            else 1.0
        } else 0.0

        val accelFactor = if (accelerationDist > 0) {
            if (distFromStart < accelerationDist)
                max(
                    minVelocity,
                    distFromStart / accelerationDist
                ) else 1.0
        } else 1.0

        val speedFactor = min(accelFactor, decelerationFactor)

        val scaledSpeed = maxVelocity * speedFactor
        val v = scaledSpeed.coerceIn(minVelocity, maxVelocity)

        return Triple(x * v, y * v, z * v)
    }

    suspend fun flyToSticks(
        target: LocationCoordinate3D,
        callback: CompletionCallbackWithParam<LocationCoordinate3D>? = null,
        maxVelocity: Double = 1.0,
        accelerationDist: Double = 2.0,
        decelerationDist: Double = 5.0,
        approachTolerance: Double = 1.0,
    ) = coroutineScope {
        val start = location.value!!

        while (isActive) {
            delay(TRANSMISSION_INTERVAL)

            val cur = location.value ?: continue
            val curYaw = attitude.value?.yaw ?: continue

            // Distance check (3D)
            val dist3D = cur.distanceTo(target)
            if (dist3D <= approachTolerance) break

            // Calculate velocity towards target
            val (vx, vy, vz) = smoothVelocity(
                start,
                cur,
                target,
                curYaw,
                maxVelocity,
                accelerationDist = accelerationDist,
                decelerationDist = decelerationDist,
            )

            val convergeParam = FlightParam()
            convergeParam.apply {
                pitch = vy
                roll = vx
                yaw = 0.0
                verticalThrottle = vz
            }
            sendFlightParam(convergeParam)
        }

        callback?.onSuccess(location.value)
    }

    suspend fun followSticks(
        target: LiveData<LocationCoordinate3D>,
        maxVelocity: Double = 1.0,
        accelerationDist: Double = 2.0,
        decelerationDist: Double = 5.0,
        approachTolerance: Double = 3.0,
        escapeBuffer: Double = 1.0
    ) = coroutineScope {
        var start = location.value!!
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
                start = cur
                delay(1000)
                continue
            }

            // Calculate velocity towards target
            val (vx, vy, vz) = smoothVelocity(
                start,
                cur,
                curTarget,
                curYaw,
                maxVelocity,
                accelerationDist = accelerationDist,
                decelerationDist = decelerationDist,
            )

            val convergeParam = FlightParam()
            convergeParam.apply {
                pitch = vy
                roll = vx
                yaw = 0.0
                verticalThrottle = vz
            }
            sendFlightParam(convergeParam)
        }
    }

    suspend fun flyBySticks(
        direction: LocationUtils.RelativeDirection, distance: Double,
        velocity: Double = 0.5,
        coordinateSystem: FlightCoordinateSystem = FlightCoordinateSystem.BODY,
        callback: CompletionCallback = DEFAULT_CALLBACK
    ) = coroutineScope {
        require(velocity >= 0) { "velocity must be positive" }
        if (velocity == 0.0) {
            callback.onSuccess()
            return@coroutineScope
        }

        val travelTime = abs(distance / velocity)

        val signDist = sign(distance)
        val signDir = direction.sign

        val v = signDir * signDist * velocity

        val flightControlParam = FlightParam().apply {
            pitch = 0.0
            roll = 0.0
            yaw = 0.0
            verticalThrottle = 0.0

            this.coordinateSystem = coordinateSystem

            when (direction) {
                LocationUtils.RelativeDirection.FORWARD -> roll = v
                LocationUtils.RelativeDirection.BACKWARD -> roll = v
                LocationUtils.RelativeDirection.RIGHT -> pitch = v
                LocationUtils.RelativeDirection.LEFT -> pitch = v
                LocationUtils.RelativeDirection.UP -> verticalThrottle = v
                LocationUtils.RelativeDirection.DOWN -> verticalThrottle = v
            }
        }

        Log.i(
            TAG,
            "flying by sticks ${if (signDir < 0) "-" else ""}${direction.name} for $travelTime seconds"
        )

        sendStickParamForDuration(travelTime.seconds, flightControlParam)
        callback.onSuccess()
    }

    fun flyTo(
        location: LocationCoordinate3D,
        callback: CompletionCallbackWithParam<LocationCoordinate3D>? = null
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

    suspend fun ascendBy(distance: Double, velocity: Double = 0.5) =
        flyBySticks(LocationUtils.RelativeDirection.UP, distance, velocity)

    suspend fun ascendTo(
        altitude: Double,
        velocity: Double = 0.5,
    ) = coroutineScope {
        require(velocity >= 0) { "velocity must be positive" } // Safety check

        val h = height.value!!
        val direction = if (altitude > h) 1 else -1
        val goingUp = direction >= 0
        val vy = velocity * direction

        while (isActive) {
            delay(TRANSMISSION_INTERVAL)

            val h = height.value ?: continue
            val dy = altitude - h

            // Overshoot check
            if (goingUp && dy < 0) break
            if (!goingUp && dy > 0) break

            // Send param
            val verticalParam = FlightParam().apply { verticalThrottle = vy }
            sendFlightParam(verticalParam)
        }
        sendFlightParam(FlightParam().apply { verticalThrottle = 0.0 })
    }

    suspend fun forwardBy(distance: Double, velocity: Double = 1.0) =
        flyBySticks(LocationUtils.RelativeDirection.FORWARD, distance, velocity)

    suspend fun leftBy(distance: Double, velocity: Double = 1.0) =
        flyBySticks(LocationUtils.RelativeDirection.LEFT, distance, velocity)

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
                (minVelocity + (velocity - minVelocity) * velocityFactor)
                    .coerceAtLeast(minVelocity)


            val flightParam = FlightParam().apply {
                yaw = currentVelocity * spinSign
            }
            sendFlightParam(flightParam)
        }

        callback.onSuccess()
    }

    enum class CircleFaceMode {
        CENTER,
        OUTER,
        TANGENT,
        TANGENT_BACK
    }

    suspend fun flyCircle(
        radius: Double, velocity: Double, count: Double = 1.0,
        clockwise: Boolean = true,
        faceMode: CircleFaceMode = CircleFaceMode.CENTER,
        fromCenter: Boolean = true,
        callback: CompletionCallback = DEFAULT_CALLBACK
    ) = coroutineScope {
        require(velocity > 0) { "velocity must be positive" }

        val travelDuration = 2.0 * Math.PI * count * radius / velocity
        val rotationSign = if (clockwise) 1 else -1

        Log.i(TAG, "flying circle for $travelDuration seconds")

        val circleMotionParam = FlightParam().apply {
            when (faceMode) {
                CircleFaceMode.CENTER -> pitch = -velocity * rotationSign
                CircleFaceMode.OUTER -> pitch = velocity * rotationSign
                CircleFaceMode.TANGENT -> roll = velocity
                CircleFaceMode.TANGENT_BACK -> roll = -velocity
            }

            yaw = (velocity / radius).toDegrees() * rotationSign
        }

        val posDelay = 1000L
        if (fromCenter) {
            forwardBy(radius, velocity) // fly out from center
            delay(posDelay)
        }
        val spinAmount = (
                when (faceMode) {
                    CircleFaceMode.CENTER -> 180.0 // spin to face center
                    CircleFaceMode.OUTER -> 0.0 // remain facing outwards
                    CircleFaceMode.TANGENT -> 90.0 * rotationSign // spin to face tangent
                    CircleFaceMode.TANGENT_BACK -> -90.0 * rotationSign // spin to face tangent backwards
                } + if (!fromCenter) 180.0 else 0.0
                ).normalizeAngle()
        spinBy(spinAmount) // spin to face circle direction
        sendStickParamForDuration(travelDuration.seconds, circleMotionParam) // send circle motion
        spinBy(-spinAmount) // spin back to face starting direction
        if (fromCenter) {
            delay(posDelay)
            forwardBy(-radius, velocity) // return to center
        }
        callback.onSuccess()
    }

    suspend fun scanGround(
        scanRadius: Double,
        velocity: Double,
        faceMode: CircleFaceMode = CircleFaceMode.CENTER,
        clockwise: Boolean = true,
        callback: CompletionCallback = DEFAULT_CALLBACK
    ) = coroutineScope {
        camGimbalVM.setCameraGimbalMode(GimbalMode.YAW_FOLLOW)
        camGimbalVM.lookTo(scanRadius, -height.value!! * .75)
        flyCircle(
            scanRadius,
            velocity,
            clockwise = clockwise,
            fromCenter = true,
            faceMode = faceMode,
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


    suspend fun lookAtWithSpin(
        target: LocationCoordinate2D,
        height: Double,
        angleOffset: Double = 0.0
    ) {
        val currentLocation =
            this.location.value ?: throw IllegalStateException("can't get location")
        val heading = this.heading.value ?: throw IllegalStateException("can't get heading")
        val currentHeight = this.height.value ?: throw IllegalStateException("can't get height")

        // turn the aircraft to face the location (look straight ahead at it)
        val headingDiffToTarget = (currentLocation.as2D.bearingTo(target)
                - heading.normalizeAngle()
                ).wrap180()
        spinBy(headingDiffToTarget + angleOffset)

        // aim the camera at the location
        camGimbalVM.lookTo(currentLocation.as2D.distanceTo(target), height - currentHeight)
    }

    suspend fun lookAtAndTrack(
        liveTargetLocation: LiveData<LocationCoordinate3D>,
        angleOffset: Double = 0.0,
        updateInterval: Duration = 1.seconds,
    ) = coroutineScope {
        while (isActive) {
            delay((1.0 / updateInterval.inWholeMilliseconds).toLong())

            val targetLocation = liveTargetLocation.value ?: continue

            lookAtWithSpin(
                targetLocation.as2D,
                targetLocation.altitude,
                angleOffset
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

    suspend fun flyToWithPoi(
        target: LiveData<LocationCoordinate3D>,
        poi: LiveData<LocationCoordinate3D> = target,
        maxVelocity: Double = 3.0,
        approachTolerance: Double = 1.0,
        escapeBuffer: Double = 1.0,
        poiHeadingOffset: Double,
    ) = coroutineScope {
        // Follow target location
        launch { followSticks(target, maxVelocity, approachTolerance, escapeBuffer) }
        // Keep Eyes on poi location
        launch { lookAtAndTrack(poi, poiHeadingOffset) }
    }

    suspend fun perchShoulder(
        targetLocation: LiveData<LocationCoordinate3D>,
        perchHeight: Double,
        perchDistance: Double,
        targetHeading: LiveData<Double>? = null,
        faceTarget: Boolean = true,
    ) = coroutineScope {
        val perchLocation = MediatorLiveData<LocationCoordinate3D>().apply {
            fun update() {
                val tl = targetLocation.value ?: return
                val al = location.value ?: return

                // If live target heading is not specified, simply calc the heading to target (facing away from us).
                val heading = targetHeading?.value ?: al.as2D.bearingTo(tl.as2D)

                // Adjust perch location to target location moved "back" (towards aircraft) by perch distance.
                value = tl.translate(
                    perchDistance,
                    LocationUtils.RelativeDirection.BACKWARD,
                    heading
                ).apply { altitude = perchHeight }
            }

            addSource(targetLocation) { update() }
            targetHeading?.let { addSource(it) { update() } }
            update()
        }

        flyToWithPoi(
            perchLocation,
            targetLocation,
            poiHeadingOffset = if (faceTarget) 0.0 else 180.0
        )
    }

    suspend fun trailShoulder(
        targetLocation: LiveData<LocationCoordinate3D>,
        perchHeight: Double,
        tailDistance: Double,
        faceTarget: Boolean = true,
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
            lookAtWithSpin(
                target.as2D,
                target.altitude,
                angleOffset = if (faceTarget) 0.0 else 180.0
            )

            // signed deltas
            val dz = perchHeight - current.altitude
            val vSign = if (dz >= 0) 1 else -1
            val dxy = current.as2D.distanceTo(target.as2D) - tailDistance

            // Vertical: smooth to zero at perch height
            val vz =
                if (abs(dz) <= verticalTolerance) 0.0
                else vSign * (abs(dz).pow(2) / (abs(dz) + 1.0)).coerceAtMost(maxVelocity)

            // Forward: smooth to zero at perch distance
            val vf =
                if (abs(dxy) <= approachTolerance) 0.0
                else (dxy.pow(2) / (abs(dxy) + 1.0)).coerceAtMost(maxVelocity)

            val param = FlightParam().apply {
                roll = vf
                verticalThrottle = vz
            }

            sendFlightParam(param)
        }
    }
}