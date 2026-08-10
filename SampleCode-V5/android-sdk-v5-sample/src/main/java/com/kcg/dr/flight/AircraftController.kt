package com.kcg.dr.flight

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.Observer
import com.kcg.dr.api.Responses.toJson
import com.kcg.dr.utils.CoroutineUtils
import com.kcg.dr.utils.DJIErrorException
import com.kcg.dr.utils.LocationUtils
import com.kcg.dr.utils.LocationUtils.RelativeDirection
import com.kcg.dr.utils.LocationUtils.RelativeDirection.*
import com.kcg.dr.utils.LocationUtils.bearingTo
import com.kcg.dr.utils.LocationUtils.distanceTo
import com.kcg.dr.utils.LocationUtils.translate
import com.kcg.dr.utils.as2D
import com.kcg.dr.utils.atAlt
import com.kcg.dr.utils.div
import com.kcg.dr.utils.dt
import com.kcg.dr.utils.mag
import com.kcg.dr.utils.minus
import com.kcg.dr.utils.normalizeAngle
import com.kcg.dr.utils.times
import com.kcg.dr.utils.toDegrees
import com.kcg.dr.utils.wrap180
import dji.sampleV5.aircraft.models.VirtualStickVM.RCStickValue
import dji.sampleV5.aircraft.util.ToastUtils
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.sdk.keyvalue.value.common.Attitude
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import dji.sdk.keyvalue.value.common.Velocity3D
import dji.sdk.keyvalue.value.common.XYZ
import dji.sdk.keyvalue.value.flightcontroller.FlightCoordinateSystem
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotation
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotationMode
import dji.sdk.keyvalue.value.gimbal.GimbalMode
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.et.create
import dji.v5.et.listen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

open class AircraftController(
    val vSticks: IVirtualStick,
    val rc: IRCState,
    val ac: IAircraft,
    val camGim: IGimbal,
) {
    interface IVirtualStick {
        suspend fun takeControl()

        suspend fun relinquishControl()

        val ownsControl: Boolean

        fun setSpeedLevel(speedLevel: Double)

        fun setLeftPosition(horizontal: Int, vertical: Int)

        fun setRightPosition(horizontal: Int, vertical: Int)

        suspend fun sendStickParam(param: FlightParam)

        suspend fun brake() {
            Log.d(TAG, "braking")
            setLeftPosition(0, 0)
            setRightPosition(0, 0)
        }
    }

    interface IRCState {
        val stickValue: StateFlow<RCStickValue>

        suspend fun listen()

        suspend fun stopListening()
    }

    interface IAircraft {
        val isFlying: StateFlow<Boolean>
        val height: StateFlow<Double>
        val location: StateFlow<LocationCoordinate3D?>
        val velocity: StateFlow<Velocity3D>
        val batteryPercent: StateFlow<Int>
        val attitude: StateFlow<Attitude>
        val heading: StateFlow<Double>

        suspend fun takeoff()
        suspend fun land()
        suspend fun stop(emergency: Boolean)

        suspend fun init()

        suspend fun destroy()
    }

    interface IGimbal {
        val attitude: StateFlow<Attitude>

        suspend fun setCameraGimbalMode(mode: GimbalMode)

        suspend fun reset()

        suspend fun resetAngle() = angleCamera(GimbalAngleRotation())

        suspend fun angleCamera(rotation: GimbalAngleRotation, mode: GimbalMode? = null)

        suspend fun angleCamera(
            pitchDegrees: Double? = null,
            yawDegrees: Double? = null,
            rollDegrees: Double? = null,
            angleMode: GimbalAngleRotationMode = GimbalAngleRotationMode.ABSOLUTE_ANGLE,
            gimbalMode: GimbalMode? = null,
            durationSec: Double = 0.1,
        ) {
            val rotation = GimbalAngleRotation()
            rotation.apply {
                pitch = pitchDegrees
                roll = rollDegrees
                yaw = yawDegrees
                mode = GimbalAngleRotationMode.ABSOLUTE_ANGLE

                pitchIgnored = pitchDegrees == null
                yawIgnored = yawDegrees == null
                rollIgnored = rollDegrees == null

                mode = angleMode
                duration = durationSec
            }
            angleCamera(rotation, gimbalMode)
        }

        suspend fun pitch(
            degrees: Double,
            durationSec: Double = 0.0,
            angleMode: GimbalAngleRotationMode = GimbalAngleRotationMode.ABSOLUTE_ANGLE,
            gimbalMode: GimbalMode = GimbalMode.FPV,
        ) = angleCamera(
            pitchDegrees = degrees,
            durationSec = durationSec,
            angleMode = angleMode,
            gimbalMode = gimbalMode,
        )

        suspend fun roll(
            degrees: Double,
            durationSec: Double = 0.0,
            angleMode: GimbalAngleRotationMode = GimbalAngleRotationMode.ABSOLUTE_ANGLE,
        ) = angleCamera(rollDegrees = degrees, durationSec = durationSec, angleMode = angleMode)

        suspend fun yaw(
            degrees: Double,
            durationSec: Double = 0.0,
            angleMode: GimbalAngleRotationMode = GimbalAngleRotationMode.ABSOLUTE_ANGLE,
        ) = angleCamera(
            yawDegrees = degrees,
            durationSec = durationSec,
            angleMode = angleMode,
            gimbalMode = GimbalMode.FREE,
        )

        suspend fun lookTo(
            forwardOffset: Double,
            verticalOffset: Double,
            callback: CommonCallbacks.CompletionCallback? = null
        ) = pitch(atan2(verticalOffset, forwardOffset).toDegrees())

        suspend fun lookTo(
            forwardOffset: Double,
            verticalOffset: Double,
            horizontalOffset: Double
        ) {
            val dx = forwardOffset
            val dy = horizontalOffset
            val dz = verticalOffset

            val dh = sqrt(dx * dx + dy * dy)

            val yaw = atan2(dy, dx).toDegrees()
            val pitch = atan2(dz, dh).toDegrees()

            angleCamera(pitch, yaw)
        }
    }

    companion object {
        const val TAG: String = "AircraftController"

        /** virtual stick controller requires constant sending of updates to move aircraft.
         * Sending freq. range per docs is 10-22hz iirc.
         **/
        private const val TRANSMISSION_FREQUENCY_HZ = 18L
        private const val TRANSMISSION_INTERVAL = (1000.0 / TRANSMISSION_FREQUENCY_HZ).toLong()

        private const val RC_OVERRIDE_THRESHOLD = 30

        private val DEFAULT_CALLBACK = object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                //Log.d(TAG, "Success")
            }

            override fun onFailure(error: IDJIError) {
                Log.d(TAG, "error: ${error.errorCode()} ${error.description()}")
                ToastUtils.showToast("error: ${error.errorCode()}")
            }
        }
        private val DEFAULT_CALLBACK_PARAM = object :
            CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
            override fun onSuccess(msg: EmptyMsg) {
            }

            override fun onFailure(error: IDJIError) {
                Log.d(TAG, "error: ${error.errorCode()} ${error.description()}")
                ToastUtils.showToast("error: ${error.errorCode()}")
            }
        }
    }

    class ControllerOverrideException(message: String = "Manual RC intervention detected") :
        CancellationException(message)

    data class FlightParam(
        var pitch: Double? = null,
        var roll: Double? = null,
        var yaw: Double? = null,
        var verticalThrottle: Double? = null,

        var coordinateSystem: FlightCoordinateSystem = FlightCoordinateSystem.BODY,
    ) {
        override fun toString(): String {
            return "" +
                    (if (pitch != null) "pitch: $pitch," else "") +
                    (if (roll != null) " roll: $roll," else "") +
                    (if (yaw != null) " yaw: $yaw," else "") +
                    (if (verticalThrottle != null) " verticalThrottle: $verticalThrottle" else "")
        }
    }

    operator fun FlightParam?.plus(other: FlightParam?): FlightParam {
        if (this == null) return other ?: FlightParam()
        if (other == null) return this
        return FlightParam().apply {
            pitch = other.pitch ?: this.pitch
            roll = other.roll ?: this.roll
            yaw = other.yaw ?: this.yaw
            verticalThrottle = other.verticalThrottle ?: this.verticalThrottle

            coordinateSystem = other.coordinateSystem
        }
    }

    suspend fun init(takeStickControl: Boolean = true) {
        ac.init()
        rc.listen()
        camGim.reset()

        if (takeStickControl) vSticks.takeControl()
        startFlightParamTransmission()

        // todo: replace with rc consume
        listOf(
            RemoteControllerKey.KeyStickLeftHorizontal,
            RemoteControllerKey.KeyStickLeftVertical,
            RemoteControllerKey.KeyStickRightHorizontal,
            RemoteControllerKey.KeyStickRightVertical
        ).forEach { stickKey ->
            stickKey.create().listen(this) { value ->
                val stickVal = value ?: return@listen
                if (abs(stickVal) > RC_OVERRIDE_THRESHOLD) {
                    if (vSticks.ownsControl) {
                        Log.d(TAG, "RC touched while stick is enabled. disabling.")
                        scope.launch { vSticks.relinquishControl() }
                    }
                    flightJob?.let {
                        if (it.isActive) {
                            Log.w(TAG, "RC touched while flight is active. cancelling.")
                            it.cancel(ControllerOverrideException())
                        }
                    }
                }
            }
        }
    }

    fun destroy() {
        stop(true)
        stopFlightParamTransmission()
        scope.launch {
            rc.stopListening()
            vSticks.relinquishControl()
            ac.destroy()
        }
    }

    fun isFlying(): Boolean = ac.isFlying.value


    private val scope = CoroutineScope(Dispatchers.IO)
    private var flightJob: Job? = null

    suspend fun safely(
        onRCOverride: () -> Unit = {},
        block: suspend AircraftController.() -> Unit
    ) = coroutineScope {
        runCatching {
            block()
        }.onFailure { e ->
            Log.w(TAG, "safely onFailure: ${e.toString()}: ${e.message.toString()}")
            when (e) {
                is ControllerOverrideException -> {
                    Log.w(TAG, "manual override in flight")
                    brake(true)
                    ac.stop(true)
                    onRCOverride()
                }

                is CancellationException -> {
                    Log.w(TAG, "cancellation in flight")
                    brake()
                }

                is DJIErrorException -> {
                    val error = e.error
                    Log.w(TAG, "${error.errorType()} error in flight: ${error.toJson()}", e)
                    brake(true)
                }

                else -> {
                    Log.w(TAG, "exception in flight: ${e.toString()}: ${e.message.toString()}", e)
                    brake(true)
                }
            }
            throw e
        }.onSuccess {
            Log.d(TAG, "safely onSuccess")
            if (isActive) brake()
        }
    }

    fun fly(
        onRCOverride: () -> Unit = {},
        block: suspend AircraftController.() -> Unit
    ) {
        val prevFlight = flightJob
        prevFlight?.let {
            if (it.isActive) {
                Log.w(TAG, "Previous flight $it is still active.")
                Log.d(TAG, "Cancelling previous flight scope...")
                it.cancel(CancellationException("New flight wants to start"))
            }
        }
        flightJob = scope.launch {
            val job = this.coroutineContext.job
            Log.d(TAG, "Launch new flight scope")
            prevFlight?.let {
                Log.i(TAG, "Joining previous flight...")
                // Wait for the previous flight to actually finish,
                // after inner cancellation
                it.join()
                Log.i(TAG, "Joining previous flight... prev finished")
            }
            try {
                Log.d(TAG, "flight mission started (in flight job)")
                runCatching {
                    safely(onRCOverride) { block() }
                }.onFailure { e ->
                    if (e is CancellationException) throw e
                }
                Log.d(TAG, "flight mission success")
            } finally {
                if (flightJob === job)
                    flightJob = null
            }
        }
    }

    suspend fun brake(returnStickControl: Boolean = false) {
        Log.d(TAG, "braking" + if (returnStickControl) " (& return control)" else "")
        vSticks.brake()
        if (returnStickControl) vSticks.relinquishControl()
    }

    suspend fun brakeFor(duration: Duration, returnStickControl: Boolean = false) {
        brake(returnStickControl)
        delay(duration)
    }


    fun stop(emergency: Boolean = true) {
        scope.launch {
            ac.stop(emergency)
            if (emergency) vSticks.relinquishControl()
        }
    }


    suspend fun takeoff(
        takeStickControl: Boolean = true,
        awaitStabilisation: Boolean = true,
    ) {
        Log.d(TAG, "takeoff")

        if (isFlying()) {
            Log.d(TAG, "already flying")
            return
        }

        if (takeStickControl) vSticks.takeControl()
        // Start takeoff
        Log.d(TAG, "starting takeoff")
        ac.takeoff()
        // Take stick control if required
        if (takeStickControl) vSticks.takeControl()
        // Wait for aircraft stabilisation
        if (awaitStabilisation) {
            val takeoffStabilisationDelay = 6.seconds
            Log.i(TAG, "delaying for takeoff stabilisation...")
            delay(takeoffStabilisationDelay)
        }

        Log.i(TAG, "takeoff on success")
    }

    suspend fun land() {
        Log.d(TAG, "land")
        stop(false)
        Log.d(TAG, "starting landing")
        ac.land()
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
                    val combinedParam = buffer.reduce { param1, param2 -> param1 + param2 }
                    vSticks.sendStickParam(combinedParam)
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
        vSticks.takeControl()

        val iterations = ((duration.inWholeMilliseconds) / TRANSMISSION_INTERVAL).toInt()

        repeat(iterations) {
            if (!currentCoroutineContext().isActive) return@repeat
            sendFlightParam(flightControlParam)
            delay(TRANSMISSION_INTERVAL)
        }
    }

    fun smoothVelocity(
        startLocation: LocationCoordinate3D,
        currentLocation: LocationCoordinate3D,
        endLocation: LocationCoordinate3D,
        curYaw: Double,
        maxVelocity: Double, minVelocity: Double = 0.5,
        accelerationDist: Double, decelerationDist: Double = accelerationDist,
    ): Triple<Double, Double, Double> {
        require(maxVelocity > 0) { "maxVelocity must be positive" }
        require(minVelocity > 0) { "minVelocity must be positive" }
        require(accelerationDist >= 0) { "accelerationDist must be non-negative" }
        require(decelerationDist >= 0) { "decelerationDist must be non-negative" }

        val (x, y, z) = LocationUtils.vectorToTarget(currentLocation, endLocation, curYaw)

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

    private fun springAngularVelocity(
        currentVelocity: Double,
        yawDiff: Double,
        maxVelocity: Double,
        dampFactor: Double = 0.12,
        pGain: Double = 2.0,
    ): Double {
        val idealVelocity = (yawDiff * pGain).coerceIn(-maxVelocity, maxVelocity)
        return (dampFactor * idealVelocity) + ((1.0 - dampFactor) * currentVelocity)
    }


    suspend fun flyToSticks(
        target: LocationCoordinate3D,
        callback: CommonCallbacks.CompletionCallbackWithParam<LocationCoordinate3D>? = null,
        maxVelocity: Double = 1.0,
        accelerationDist: Double = 2.0,
        decelerationDist: Double = 5.0,
        approachTolerance: Double = 1.0,
    ) = coroutineScope {
        val start = ac.location.value ?: return@coroutineScope

        while (isActive) {
            delay(TRANSMISSION_INTERVAL)

            val cur = ac.location.value ?: continue
            val curYaw = ac.attitude.value.yaw ?: continue

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
                verticalThrottle = vz
            }
            sendFlightParam(convergeParam)
        }

        callback?.onSuccess(ac.location.value)
    }

    suspend fun followSticks(
        target: LiveData<LocationCoordinate3D>,
        targetReachedCallback: CommonCallbacks.CompletionCallbackWithParam<LocationCoordinate3D>? = null,
        maxVelocity: Double = 1.0,
        accelerationDist: Double = 2.0,
        decelerationDist: Double = 5.0,
        approachTolerance: Double = 2.0,
        escapeTolerance: Double = 1.0
    ) = coroutineScope {
        var start = ac.location.value ?: return@coroutineScope
        var curTarget: LocationCoordinate3D? = target.value
        var targetReached = false

        while (isActive) {
            delay(TRANSMISSION_INTERVAL)

            val cur = ac.location.value ?: continue
            val curYaw = ac.attitude.value.yaw ?: continue

            // Adjust to live target
            curTarget = target.value ?: curTarget ?: continue

            // Distance check (3D)
            val dist3D = cur.distanceTo(curTarget)
            // Range check
            if (!targetReached && dist3D <= approachTolerance) {
                targetReached = true
                start = cur
                targetReachedCallback?.onSuccess(cur)
            }
            if (targetReached && dist3D > approachTolerance + escapeTolerance) {
                targetReached = false
            }

            // If we're within range, don't move (minimise jitter)
            if (targetReached) continue

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
                verticalThrottle = vz
            }
            sendFlightParam(convergeParam)
        }
    }

    suspend fun flyBy(
        direction: RelativeDirection, distance: Double,
        velocity: Double = 0.5,
        coordinateSystem: FlightCoordinateSystem = FlightCoordinateSystem.BODY,
        callback: CommonCallbacks.CompletionCallback = DEFAULT_CALLBACK
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
                FORWARD, BACKWARD -> roll = v
                RIGHT, LEFT -> pitch = v
                UP, DOWN -> verticalThrottle = v
            }
        }

        Log.i(
            TAG,
            "flying by sticks ${if (signDir < 0) "-" else ""}${direction.name} for $travelTime seconds"
        )

        sendStickParamForDuration(travelTime.seconds, flightControlParam)
        callback.onSuccess()
    }

    suspend fun flyBy(
        distance: XYZ,
        velocity: Double = 0.5,
    ) = coroutineScope {
        require(velocity >= 0) { "velocity must be positive" }
        if (velocity == 0.0) return@coroutineScope
        val mag = distance.mag
        if (mag <= 1e-3) return@coroutineScope

        val travelTime = abs(mag / velocity)
        val v = distance.dt(travelTime)
        val flightParam = FlightParam().apply {
            roll = v.x
            pitch = v.y
            verticalThrottle = v.z
        }
        Log.i(TAG, "flying by $distance. $travelTime seconds")
        sendStickParamForDuration(travelTime.seconds, flightParam)
    }


    suspend fun ascendBy(distance: Double, velocity: Double = 0.5) =
        flyBySticks(LocationUtils.RelativeDirection.UP, distance, velocity)

    suspend fun ascendTo(
        altitude: Double,
        velocity: Double = 0.5,
    ) = coroutineScope {
        require(velocity >= 0) { "velocity must be positive" } // Safety check

        val h = ac.height.value
        val direction = if (altitude > h) 1 else -1
        val goingUp = direction >= 0
        val vy = velocity * direction

        while (isActive) {
            delay(TRANSMISSION_INTERVAL)

            val h = ac.height.value
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
        callback: CommonCallbacks.CompletionCallback = DEFAULT_CALLBACK
    ) = coroutineScope {
        Log.d(TAG, "spinning by $angleDegrees degrees")
        require(velocity > 0) { "velocity must be positive" }
        require(minVelocity >= 0) { "min velocity must be non-negative" }
        require(velocity >= minVelocity) { "min velocity cannot be greater than target velocity" }
        require(targetToleranceDegrees > 0) { "target tolerance must be positive" }

        val spinSign = sign(angleDegrees)
        var cumulativeYaw = 0.0
        var lastYaw = ac.attitude.value.yaw ?: 0.0
        val totalAngle = abs(angleDegrees)

        while (isActive) {
            delay(TRANSMISSION_INTERVAL)

            val currentYaw = ac.attitude.value.yaw ?: continue

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

    @Serializable
    @SerialName("face_mode")
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
        callback: CommonCallbacks.CompletionCallback = DEFAULT_CALLBACK
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
            // fly out from center
            if (faceMode == CircleFaceMode.CENTER)
                forwardBy(-radius, velocity)  // go back from center, face is towards center
            else
                forwardBy(radius, velocity) // go forward from center, face is towards outer
            delay(posDelay)
        }
        val angleCorrection = (
                when (faceMode) {
                    CircleFaceMode.CENTER, CircleFaceMode.OUTER -> 0.0 // remain facing forward
                    CircleFaceMode.TANGENT -> 90.0 * rotationSign // spin to face tangent
                    CircleFaceMode.TANGENT_BACK -> -90.0 * rotationSign // spin to face tangent backwards
                } + if (!fromCenter) 180.0 else 0.0
                ).normalizeAngle()
        val angV = 140.0
        spinBy(angleCorrection, velocity = angV) // spin to face circle direction
        sendStickParamForDuration(travelDuration.seconds, circleMotionParam) // send circle motion
        delay(posDelay)
        spinBy(-angleCorrection, velocity = angV) // spin back to face starting direction
        if (fromCenter) {
            delay(posDelay)
            // return to center
            if (faceMode == CircleFaceMode.CENTER)
                forwardBy(radius, velocity)  // go forward to center, face is towards center
            else
                forwardBy(-radius, velocity) // go backward to center, face is towards outer
        }
        delay(posDelay)
        callback.onSuccess()
    }

    suspend fun scanGround(
        scanRadius: Double,
        velocity: Double,
        faceMode: CircleFaceMode = CircleFaceMode.CENTER,
        clockwise: Boolean = true,
        callback: CommonCallbacks.CompletionCallback = DEFAULT_CALLBACK
    ) = coroutineScope {
        camGim.setCameraGimbalMode(GimbalMode.YAW_FOLLOW)
        camGim.lookTo(scanRadius, -ac.height.value * .75)
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
        callback: CommonCallbacks.CompletionCallback = DEFAULT_CALLBACK
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
        callback: CommonCallbacks.CompletionCallback = DEFAULT_CALLBACK
    ) = flyRectangle(width, width, velocity, clockwise, callback)


    suspend fun oscillate(
        amplitudesMeters: XYZ, periodSeconds: XYZ = XYZ(5.0, 5.0, 5.0),
    ) = coroutineScope {
        var xyz0 = XYZ()
        var t = 0.0
        val dt = TRANSMISSION_INTERVAL.milliseconds.toDouble(DurationUnit.SECONDS)
        while (isActive) {
            val oscillation = sin(2 * PI * t)
            val rate = amplitudesMeters / periodSeconds
            val xyz =
                (rate * oscillation)
                    .apply {
                        x = if (x.isFinite()) x else 0.0
                        y = if (y.isFinite()) y else 0.0
                        z = if (z.isFinite()) z else 0.0
                    }

            val d = xyz - xyz0
            val v = d.dt(dt)

            sendFlightParam(FlightParam().apply {
                roll = v.x
                pitch = v.y
                verticalThrottle = v.z
            })

            t += dt
            xyz0 = xyz
            delay(dt.seconds)
        }
    }

    suspend fun oscillate(amplitudeMeters: Double, periodSeconds: Double) = oscillate(
        XYZ(0.0, 0.0, amplitudeMeters),
        XYZ(0.0, 0.0, periodSeconds)
    )


    suspend fun pitchCamera(angle: Double) = camGim.pitch(angle)
    suspend fun setCameraGimbalMode(mode: GimbalMode) = camGim.setCameraGimbalMode(mode)


    suspend fun lookAtWithSpin(
        target: LocationCoordinate2D,
        height: Double? = null,
        spinVelocity: Double = 100.0,
        fovTolerance: Double = 0.0,
        angleOffset: Double = 0.0,
    ) {
        val currentLocation = ac.location.value ?: return
        val heading = ac.heading.value
        val currentHeight = ac.height.value

        // turn the aircraft to face the location (look straight ahead at it)
        val headingDiffToTarget = (currentLocation.as2D.bearingTo(target)
                - heading.normalizeAngle()
                ).wrap180()
        if (abs(headingDiffToTarget) > fovTolerance / 2.0) // if fov = \|/ then angle tolerance = abs \|
            spinBy(headingDiffToTarget + angleOffset, velocity = spinVelocity)

        height?.let {
            // aim the camera at the location
            camGim.lookTo(
                currentLocation.as2D.distanceTo(target),
                it - currentHeight
            )
        }
    }

    suspend fun lookAtAndTrack(
        liveTarget: LiveData<LocationCoordinate3D>,
        maxVelocity: Double = 70.0,
        fovTolerance: Double = 1.0,
        angleOffset: Double = 0.0,
        gimbalUpdateInterval: Duration = 1.seconds,
    ) = coroutineScope {
        launch {
            var currentAngVelocity = 0.0
            while (isActive) {
                delay(TRANSMISSION_INTERVAL)

                val curLoc = ac.location.value ?: continue
                val curAtt = ac.attitude.value
                val targetLoc = liveTarget.value ?: continue

                val bearingTo = curLoc.as2D.bearingTo(targetLoc.as2D)
                val targetYaw = (bearingTo + angleOffset).wrap180()
                val yawDiff = (targetYaw - curAtt.yaw).wrap180()

                if (abs(yawDiff) <= fovTolerance) continue

                currentAngVelocity = springAngularVelocity(
                    currentVelocity = currentAngVelocity,
                    yawDiff = yawDiff,
                    maxVelocity = maxVelocity,
                )

                val spinParam = FlightParam().apply {
                    yaw = currentAngVelocity
                }
                sendFlightParam(spinParam)
            }
        }
        launch {
            while (isActive) {
                delay(gimbalUpdateInterval)

                val cur = ac.location.value ?: continue
                val target = liveTarget.value ?: continue
                val height = ac.height.value

                val dist2D = cur.as2D.distanceTo(target.as2D)
                val dh = target.altitude - height

                camGim.lookTo(dist2D, dh)
            }
        }
    }

    suspend fun wave(waves: Int = 2) {
        Log.d(TAG, "waving")
        require(waves > 0) { "wave count must be positive" }
        val t = 0.2
        val tm = (t * 1000).toLong()
        val rollAngle = 10.0
        val waveAngle = 40.0

        camGim.resetAngle()

        camGim.roll(rollAngle, t / 2, GimbalAngleRotationMode.RELATIVE_ANGLE)
        delay(tm)
        camGim.roll(-rollAngle * 2, t / 2, GimbalAngleRotationMode.RELATIVE_ANGLE)
        delay(tm)

        delay(400)

        repeat(waves) {
            camGim.pitch(-waveAngle, t)
            delay(tm)
            camGim.pitch(waveAngle * .5, t)
            delay(tm)
        }

        camGim.roll(rollAngle, t / 2, GimbalAngleRotationMode.RELATIVE_ANGLE)
        delay(tm)

        delay(100)

        camGim.resetAngle()
    }

    suspend fun gimbalFan() = coroutineScope {
        val scanDuration = 3.0
        val scanDurationMs = (scanDuration * 1000).toLong()
        camGim.pitch(0.0, 0.5)
        delay(1000)
        camGim.pitch(-90.0, scanDuration)
        delay(scanDurationMs)
        delay(500)
        camGim.pitch(0.0, scanDuration)
        delay(scanDurationMs)
    }


    suspend fun whileFollowing(
        targetLocation: LiveData<LocationCoordinate3D>,
        targetReachedCallback: CommonCallbacks.CompletionCallbackWithParam<LocationCoordinate3D>? = null,
        maxVelocity: Double = 1.0,
        accelerationDist: Double = 2.0,
        decelerationDist: Double = 5.0,
        approachTolerance: Double = 2.0,
        escapeTolerance: Double = 0.0,
        block: suspend () -> Unit
    ) = coroutineScope {
        CoroutineUtils.whileSuspendedBy({
            followSticks(
                targetLocation,
                targetReachedCallback,
                maxVelocity,
                accelerationDist,
                decelerationDist,
                approachTolerance,
                escapeTolerance,
            )
        }, block)
    }

    suspend fun withEyesOn(
        deviceLocation: LiveData<LocationCoordinate3D>,
        spinVelocity: Double = 100.0,
        fovTolerance: Double = 0.0,
        angleOffset: Double = 0.0,
        block: suspend () -> Unit
    ) = coroutineScope {
        CoroutineUtils.whileSuspendedBy(
            {
                lookAtAndTrack(
                    deviceLocation,
                    spinVelocity,
                    fovTolerance,
                    angleOffset,
                )
            },
            block
        )
    }


    suspend fun perchShoulder(
        targetLocation: LiveData<LocationCoordinate3D>,
        perchHeight: Double,
        perchDistance: Double,
        followVelocity: Double,
        targetHeading: LiveData<Double>? = null,
        watch12Duration: Duration = Duration.INFINITE,
        watch6Duration: Duration? = null,
    ) = coroutineScope {
        val perchLocation = MediatorLiveData<LocationCoordinate3D>().apply {
            fun update() {
                val tl = targetLocation.value ?: return
                val al = ac.location.value ?: return

                // If live target heading is not specified, simply calc the heading to target (facing away from us).
                val heading = targetHeading?.value ?: al.as2D.bearingTo(tl.as2D)

                // Adjust perch location to target location moved "back" (towards aircraft) by perch distance.
                value = tl.translate(
                    perchDistance,
                    LocationUtils.RelativeDirection.BACKWARD,
                    heading
                ).atAlt(perchHeight)
            }

            addSource(targetLocation) { update() }
            targetHeading?.let { addSource(it) { update() } }
            update()
        }
        val obs = Observer<LocationCoordinate3D> {}
        perchLocation.observeForever(obs)

        try {
            whileFollowing(perchLocation, maxVelocity = followVelocity) {
                while (isActive) {
                    ToastUtils.showToast("watching 12\n(${watch12Duration})")
                    withTimeoutOrNull(watch12Duration) {
                        lookAtAndTrack(targetLocation)
                    }
                    brakeFor(1.seconds)
                    watch6Duration?.let {
                        ToastUtils.showToast("watching 6\n(${watch6Duration})")
                        spinBy(180.0, velocity = 140.0)
                        delay(it)
                        /*withTimeoutOrNull(it) {
                            lookAtAndTrack(targetLocation, angleOffset = 180.0)
                        }*/
                    }
                }
            }
        } finally {
            perchLocation.removeObserver(obs)
        }
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
            val current = ac.location.value ?: continue

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