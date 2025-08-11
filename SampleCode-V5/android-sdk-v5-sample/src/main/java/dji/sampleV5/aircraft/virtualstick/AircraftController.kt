package dji.sampleV5.aircraft.virtualstick

import android.util.Log
import androidx.lifecycle.MutableLiveData
import dji.sampleV5.aircraft.models.BasicAircraftControlVM
import dji.sampleV5.aircraft.models.IntelligentFlightVM
import dji.sampleV5.aircraft.models.VirtualStickVM
import dji.sampleV5.aircraft.util.ToastUtils
import dji.sampleV5.aircraft.utils.LocationUtils
import dji.sampleV5.aircraft.utils.LocationUtils.distanceTo
import dji.sampleV5.aircraft.utils.LocationUtils.translate
import dji.sampleV5.aircraft.utils.normalizeAngle
import dji.sampleV5.aircraft.utils.toDegrees
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
import dji.v5.common.callback.CommonCallbacks.CompletionCallback
import dji.v5.common.callback.CommonCallbacks.CompletionCallbackWithParam
import dji.v5.common.error.IDJIError
import dji.v5.et.create
import dji.v5.et.get
import dji.v5.et.listen
import dji.v5.manager.KeyManager
import dji.v5.manager.aircraft.virtualstick.Stick
import dji.v5.manager.intelligent.IMissionInfoListener
import dji.v5.manager.intelligent.IntelligentFlightManager
import dji.v5.manager.intelligent.flyto.FlyToInfo
import dji.v5.manager.intelligent.flyto.FlyToParam
import dji.v5.manager.intelligent.flyto.FlyToTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sign
import kotlin.math.sin


open class AircraftController(
    private val stickVM: VirtualStickVM,
    private val acVM: BasicAircraftControlVM,
    private val intFlVM: IntelligentFlightVM,
) {
    companion object {
        private const val FLIGHT_PARAM_SEND_FREQUENCY_HZ = 25L
        private val DEFAULT_CALLBACK = object : CompletionCallback {
            override fun onSuccess() {
            }

            override fun onFailure(error: IDJIError) {
                Log.e("Controller", "Error: ${error.errorCode()}")
            }
        }
        private val DEFAULT_CALLBACK_PARAM = object : CompletionCallbackWithParam<EmptyMsg> {
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


    var flightJob: Job? = null


    fun attachOnScreenSticks(
        leftStk: OnScreenJoystick,
        rightStk: OnScreenJoystick,
        callback: CompletionCallback? = null,
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
        if (activate) activate(object : CompletionCallback {
            override fun onSuccess() {
                callback?.onSuccess()
            }

            override fun onFailure(error: IDJIError) {
                callback?.onFailure(error)
            }
        })
    }

    private fun activate(callback: CompletionCallback = DEFAULT_CALLBACK) {
        if (!stickVMActive()) stickVM.enableVirtualStick(callback)
        intFlVM.initListener()
        FlightControllerKey.KeyAircraftLocation.create().listen(this) {
            it?.let {
                val updated = location.value!!
                updated.longitude = it.longitude
                updated.latitude = it.latitude
                location.postValue(updated)
            }
        }
        FlightControllerKey.KeyAircraftAttitude.create().listen(this) {
            it?.let {
                attitude.postValue(it)
            }
        }
        IntelligentFlightManager.getInstance().flyToMissionManager.addMissionInfoListener(object :
            IMissionInfoListener<FlyToInfo, FlyToTarget> {
            override fun onMissionInfoUpdate(info: FlyToInfo) {
                ToastUtils.showToast("msn info: $info")
            }

            override fun onMissionTargetUpdate(target: FlyToTarget) {
                ToastUtils.showToast("targ: $target")
            }
        })
    }

    fun stop(callback: CompletionCallback = DEFAULT_CALLBACK) {
        flightJob?.cancel()
        stickVM.enableVirtualStickAdvancedMode()
        stickVM.sendVirtualStickAdvancedParam(VirtualStickFlightControlParam())
        stickVM.disableVirtualStickAdvancedMode()
        IntelligentFlightManager.getInstance().flyToMissionManager.stopMission(callback)
        IntelligentFlightManager.getInstance().spotLightManager.stopMission(callback)
        IntelligentFlightManager.getInstance().poiMissionManager.stopMission(callback)
    }

    private fun disable(callback: CompletionCallback = DEFAULT_CALLBACK) {
        stop(callback)
        if (stickVMActive()) {
            stickVM.disableVirtualStickAdvancedMode()
            stickVM.disableVirtualStick(callback)
        }
        intFlVM.cleanListener()
        KeyManager.getInstance().cancelListen(this)
    }

    fun destroy() {
        disable()
    }

    private fun stickVMActive() =
        stickVM.currentVirtualStickStateInfo.value?.state?.isVirtualStickEnable == true

    private fun isAttached() = onScLeftStk != null && onScRightStk != null

    fun isActive() = stickVMActive() && isAttached()

    fun ascendBy(
        meters: Double, speedMps: Double,
        callback: CompletionCallback = DEFAULT_CALLBACK,
        prep: Boolean = true
    ) {
        require(speedMps > 0) { "Speed must be positive" }

        if (prep && !stickVMActive()) {
            activate(object : CompletionCallback {
                override fun onSuccess() = ascendBy(meters, speedMps, callback)
                override fun onFailure(error: IDJIError) = callback.onFailure(error)
            })
            return
        }
        stop()

        val durationSec = meters / speedMps
        val direction = sign(meters)

        val flightControlParam = VirtualStickFlightControlParam()
        with(flightControlParam) {
            pitch = .0
            roll = .0
            yaw = .0
            verticalThrottle = direction * speedMps
            verticalControlMode = VerticalControlMode.VELOCITY
        }

        flightJob?.cancel()
        flightJob = CoroutineScope(Dispatchers.Main).launch {
            val intervalMs = 1000L / FLIGHT_PARAM_SEND_FREQUENCY_HZ
            val iterations = (durationSec * 1000 / intervalMs).toInt()

            stickVM.enableVirtualStickAdvancedMode()
            repeat(iterations) {
                if (!this.isActive) return@launch

                stickVM.sendVirtualStickAdvancedParam(flightControlParam)
                //ToastUtils.showToast(flightControlParam.toJson().toString())
                delay(intervalMs)
            }
            stickVM.disableVirtualStickAdvancedMode()

            stop()
        }
    }

    fun forwardBy(
        meters: Double, speedMps: Double,
        callback: CompletionCallback = DEFAULT_CALLBACK,
        coordinateSystem: FlightCoordinateSystem = FlightCoordinateSystem.BODY,
        prep: Boolean = true,
    ) {
        require(speedMps > 0) { "Speed must be positive" }

        if (prep && !stickVMActive()) {
            activate(object : CompletionCallback {
                override fun onSuccess() = ascendBy(meters, speedMps, callback)
                override fun onFailure(error: IDJIError) = callback.onFailure(error)
            })
            return
        }
        stop()

        val durationSec = meters / speedMps
        val direction = sign(meters)

        val flightControlParam = VirtualStickFlightControlParam()
        with(flightControlParam) {
            pitch = direction * speedMps
            roll = .0
            yaw = .0
            verticalThrottle = .0
            rollPitchCoordinateSystem = coordinateSystem
            rollPitchControlMode = RollPitchControlMode.VELOCITY
        }

        flightJob?.cancel()
        flightJob = CoroutineScope(Dispatchers.Main).launch {
            val intervalMs = 1000L / FLIGHT_PARAM_SEND_FREQUENCY_HZ
            val iterations = (durationSec * 1000 / intervalMs).toInt()

            stickVM.enableVirtualStickAdvancedMode()
            repeat(iterations) {
                if (!this.isActive) return@launch

                stickVM.sendVirtualStickAdvancedParam(flightControlParam)
                delay(intervalMs)
            }
            stickVM.disableVirtualStickAdvancedMode()

            stop()
        }
    }


    fun takeoff(
        callback: CompletionCallbackWithParam<EmptyMsg> = DEFAULT_CALLBACK_PARAM,
        prep: Boolean = true,
    ) {
        if (prep) {
            if (!stickVMActive()) {
                activate(object : CompletionCallback {
                    override fun onSuccess() = takeoff(callback)
                    override fun onFailure(error: IDJIError) = callback.onFailure(error)
                })
                return
            }
        }
        acVM.startTakeOff(callback)
    }

    fun land(
        callback: CompletionCallbackWithParam<EmptyMsg> = DEFAULT_CALLBACK_PARAM,
        prep: Boolean = true,
    ) {
        if (prep) {
            if (!stickVMActive()) {
                activate(object : CompletionCallback {
                    override fun onSuccess() = land(callback)
                    override fun onFailure(error: IDJIError) {
                        callback.onFailure(error)
                    }
                })
                return
            }
        }

        stop()

        acVM.startLanding(callback)
    }

    fun flyToIntelligent(
        target: LocationCoordinate3D,
        callback: CompletionCallbackWithParam<LocationCoordinate3D>? = null,
    ) {
        val flyToTarget = FlyToTarget()
        flyToTarget.apply {
            maxSpeed = 1
            securityTakeoffHeight = 2
            targetLocation = target
        }
        val flyToParam = FlyToParam()
        flyToParam.apply { flyToMode = FlyToMode.SMART_HEIGHT }

        ToastUtils.showToast("pre fly to")
        IntelligentFlightManager.getInstance().flyToMissionManager.startMission(
            flyToTarget, flyToParam,
            object : CompletionCallback {
                override fun onSuccess() {
                    ToastUtils.showToast("flyTo success @${target.toJson()}")
                    callback?.onSuccess(target)
                }

                override fun onFailure(error: IDJIError) {
                    ToastUtils.showToast("flyTo fail $error")
                    callback?.onFailure(error)
                }
            }
        )
        /*intFlVM.setFlyToMode(FlyToMode.SMART_HEIGHT)
        intFlVM.startFlyTo(flyToTarget)*/
    }

    fun flyToVirtualSticks(
        target: LocationCoordinate3D,
        callback: CompletionCallbackWithParam<LocationCoordinate3D>? = null,
        maxSpeed: Float = 2.0f,
        positionTolerance: Double = 0.5,
        prep: Boolean = true,
    ) {
        if (prep) {
            if (!stickVMActive()) {
                activate(object : CompletionCallback {
                    override fun onSuccess() =
                        flyToVirtualSticks(target, callback, maxSpeed, positionTolerance)

                    override fun onFailure(error: IDJIError) {
                        callback?.onFailure(error)
                    }
                })
                return
            }
        }

        flightJob?.cancel()
        flightJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                stickVM.enableVirtualStickAdvancedMode()
                while (isActive) {
                    val curLat = location.value!!.latitude
                    val curLon = location.value!!.longitude
                    val curYaw = attitude.value!!.yaw

                    val dLat = target.latitude - curLat
                    val dLon = target.longitude - curLon
                    val horizontalDist = location.value!!.distanceTo(
                        LocationCoordinate2D(
                            target.latitude,
                            target.longitude
                        )
                    )
                    val verticalDist = target.altitude - location.value!!.altitude

                    if (horizontalDist < positionTolerance && (abs(verticalDist) < 0.5)) {
                        withContext(Dispatchers.Main) {
                            callback?.onSuccess(target)
                        }
                        break
                    }

                    val angleToTarget = atan2(dLon, dLat).toDegrees()
                    val bearingOffset = (angleToTarget - curYaw).normalizeAngle()
                    val rad = Math.toRadians(bearingOffset)

                    val vx = (maxSpeed * cos(rad))
                    val vy = (maxSpeed * sin(rad))
                    val vz = verticalDist.coerceIn(-1.0, 1.0)

                    val param = VirtualStickFlightControlParam()
                    param.apply {
                        pitch = vy
                        roll = vx
                        yaw = 0.0
                        verticalThrottle = vz
                        rollPitchControlMode = RollPitchControlMode.VELOCITY
                        verticalControlMode = VerticalControlMode.VELOCITY
                        yawControlMode = YawControlMode.ANGLE
                        rollPitchCoordinateSystem = FlightCoordinateSystem.BODY
                    }
                    stickVM.sendVirtualStickAdvancedParam(param)

                    delay(100L)
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
        callback: CompletionCallbackWithParam<LocationCoordinate3D>? = null
    ) {
        stop()
        if (FlightControllerKey.KeyIsWaypointSupport.create().get() == true)
            flyToIntelligent(location, callback)
        else
            flyToVirtualSticks(location, callback)
    }

    fun flyTo(
        location: LocationCoordinate2D,
        callback: CompletionCallbackWithParam<LocationCoordinate3D>? = null
    ) = flyTo(
        LocationCoordinate3D(
            location.latitude,
            location.longitude,
            this.location.value!!.altitude
        )
    )

    fun flyBy(
        distMeters: Double,
        direction: LocationUtils.Direction,
        callback: CompletionCallbackWithParam<LocationCoordinate3D>? = null,
    ) = flyToIntelligent(location.value!!.translate(distMeters, direction), callback)

    fun flyBy(
        distMeters: Double, direction: LocationUtils.RelativeDirection,
        callback: CompletionCallbackWithParam<LocationCoordinate3D>? = null,
    ) = flyToIntelligent(
        location.value!!.translate(
            distMeters, direction, currentHeadingDegrees = attitude.value!!.yaw
        ), callback
    )
}