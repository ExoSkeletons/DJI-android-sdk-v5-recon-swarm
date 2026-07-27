package dji.sampleV5.aircraft.models

import com.kcg.dr.utils.toDegrees
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotation
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotationMode
import dji.sdk.keyvalue.value.gimbal.GimbalMode
import dji.sdk.keyvalue.value.gimbal.GimbalResetType
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.et.action
import dji.v5.et.create
import dji.v5.et.set
import kotlin.math.atan2
import kotlin.math.sqrt

class CameraGimbalVM : DJIViewModel() {
    fun setCameraGimbalMode(
        mode: GimbalMode,
        callback: CommonCallbacks.CompletionCallback? = null
    ) = GimbalKey.KeyGimbalMode.create().set(
        mode,
        { callback?.onSuccess() },
        { callback?.onFailure(it) }
    )

    fun reset(callback: CommonCallbacks.CompletionCallback? = null) {
        GimbalKey.KeyGimbalReset.create().action(
            GimbalResetType.RECENTER, {
                GimbalKey.KeyRotateByAngle.create().action(
                    GimbalAngleRotation().apply {
                        mode = GimbalAngleRotationMode.ABSOLUTE_ANGLE
                    }, {
                        GimbalKey.KeyGimbalMode.create().set(
                            GimbalMode.YAW_FOLLOW,
                            { callback?.onSuccess() },
                            { callback?.onFailure(it) })
                    }, { callback?.onFailure(it) })
            }, { callback?.onFailure(it) }
        )
    }

    fun angleCamera(
        rotation: GimbalAngleRotation,
        mode: GimbalMode? = null,
        callback: CommonCallbacks.CompletionCallback? = null
    ) {
        mode?.let { mode ->
            setCameraGimbalMode(mode, object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    GimbalKey.KeyRotateByAngle.create().action(
                        rotation,
                        { callback?.onSuccess() },
                        { callback?.onFailure(it) })
                }

                override fun onFailure(error: IDJIError) {
                    callback?.onFailure(error)
                }
            })
        }
    }

    fun angleCamera(
        pitchDegrees: Double? = null,
        yawDegrees: Double? = null,
        rollDegrees: Double? = null,
        angleMode: GimbalAngleRotationMode = GimbalAngleRotationMode.ABSOLUTE_ANGLE,
        gimbalMode: GimbalMode? = null,
        durationSec: Double = 0.1,
        callback: CommonCallbacks.CompletionCallback? = null
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
        angleCamera(rotation, gimbalMode, callback)
    }

    fun pitch(
        degrees: Double,
        durationSec: Double = 0.0,
        angleMode: GimbalAngleRotationMode = GimbalAngleRotationMode.ABSOLUTE_ANGLE,
        gimbalMode: GimbalMode = GimbalMode.FPV,
        callback: CommonCallbacks.CompletionCallback? = null
    ) = angleCamera(
        pitchDegrees = degrees,
        durationSec = durationSec,
        angleMode = angleMode,
        gimbalMode = gimbalMode,
        callback = callback
    )

    fun roll(
        degrees: Double,
        durationSec: Double = 0.0,
        angleMode: GimbalAngleRotationMode = GimbalAngleRotationMode.ABSOLUTE_ANGLE,
        callback: CommonCallbacks.CompletionCallback? = null
    ) = angleCamera(
        rollDegrees = degrees,
        durationSec = durationSec,
        angleMode = angleMode,
        callback = callback
    )

    fun yaw(
        degrees: Double,
        durationSec: Double = 0.0,
        angleMode: GimbalAngleRotationMode = GimbalAngleRotationMode.ABSOLUTE_ANGLE,
        callback: CommonCallbacks.CompletionCallback? = null
    ) = angleCamera(
        yawDegrees = degrees,
        durationSec = durationSec,
        angleMode = angleMode,
        gimbalMode = GimbalMode.FREE,
        callback = callback
    )

    fun lookTo(
        forwardOffset: Double,
        verticalOffset: Double,
        callback: CommonCallbacks.CompletionCallback? = null
    ) = pitch(
        atan2(verticalOffset, forwardOffset).toDegrees(),
        callback = callback
    )

    fun lookTo(
        forwardOffset: Double,
        verticalOffset: Double,
        horizontalOffset: Double,
        callback: CommonCallbacks.CompletionCallback? = null
    ) {
        val dx = forwardOffset
        val dy = horizontalOffset
        val dz = verticalOffset

        val dh = sqrt(dx * dx + dy * dy)

        val yaw = atan2(dy, dx).toDegrees()
        val pitch = atan2(dz, dh).toDegrees()

        angleCamera(pitch, yaw, callback = callback)
    }
}