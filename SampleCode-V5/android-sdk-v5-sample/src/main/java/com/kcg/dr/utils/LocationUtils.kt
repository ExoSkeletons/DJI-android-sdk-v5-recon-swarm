@file:OptIn(InternalSerializationApi::class)

package com.kcg.dr.utils

import android.location.Location
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import dji.sdk.keyvalue.value.common.Velocity3D
import dji.sdk.keyvalue.value.common.XYZ
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.SerialName
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val EPS = 1e-6
private const val EARTH_RADIUS = 6378137.0 // meters

fun Double.normalizeAngle() = ((this % 360) + 360) % 360
fun Double.wrap180(): Double {
    var v = this % 360.0
    if (v > 180) v -= 360
    if (v < -180) v += 360
    return v
}

fun Double.toDegrees(): Double = Math.toDegrees(this)
fun Double.toRadians(): Double = Math.toRadians(this)


operator fun XYZ.plus(other: XYZ): XYZ = XYZ(this.x + other.x, this.y + other.y, this.z + other.z)
operator fun XYZ.minus(other: XYZ): XYZ = XYZ(this.x - other.x, this.y - other.y, this.z - other.z)
operator fun XYZ.times(other: XYZ): XYZ = XYZ(this.x * other.x, this.y * other.y, this.z * other.z)
operator fun XYZ.div(other: XYZ): XYZ = XYZ(this.x / other.x, this.y / other.y, this.z / other.z)
operator fun XYZ.times(scalar: Double): XYZ = XYZ(this.x * scalar, this.y * scalar, this.z * scalar)
operator fun XYZ.div(scalar: Double): XYZ = XYZ(this.x / scalar, this.y / scalar, this.z / scalar)
inline val XYZ.mag get(): Double = sqrt(this.x * this.x + this.y * this.y + this.z * this.z)
fun XYZ.normalized(eps: Double = EPS): XYZ = if (mag < eps) XYZ(0.0, 0.0, 0.0) else this / this.mag
fun XYZ.asVector(): Triple<Double, Double, Double> = Triple(this.x, this.y, this.z)
fun Triple<Double, Double, Double>.asXYZ(): XYZ = XYZ(this.x, this.y, this.z)
fun Velocity3D.asXYZ(): XYZ = XYZ(this.x, this.y, this.z)
fun XYZ.dt(t: Double): Velocity3D = Velocity3D(this.x / t, this.y / t, this.z / t)


inline val Pair<Double, Double>.x get(): Double = this.first
inline val Pair<Double, Double>.y get(): Double = this.second
inline val Pair<Double, Double>.mag get(): Double = sqrt(x * x + y * y)
fun Pair<Double, Double>.normalized(eps: Double = EPS): Pair<Double, Double> =
    if (mag < eps) Pair(0.0, 0.0)
    else Pair(x / mag, y / mag)

inline val Triple<Double, Double, Double>.x get(): Double = this.first
inline val Triple<Double, Double, Double>.y get(): Double = this.second
inline val Triple<Double, Double, Double>.z get(): Double = this.third
inline val Triple<Double, Double, Double>.mag get(): Double = sqrt(x * x + y * y + z * z)
fun Triple<Double, Double, Double>.normalized(eps: Double = EPS): Triple<Double, Double, Double> =
    if (mag < eps) Triple(0.0, 0.0, 0.0)
    else Triple(x / mag, y / mag, z / mag)


inline val LocationCoordinate3D.as2D get() = LocationCoordinate2D(this.latitude, this.longitude)
fun LocationCoordinate2D.as3D(altitude: Double) =
    LocationCoordinate3D(this.latitude, this.longitude, altitude)

fun LocationCoordinate3D.atAlt(altitude: Double) =
    LocationCoordinate3D(this.latitude, this.longitude, altitude)

fun Location.asDjiLocation() = LocationCoordinate3D(
    this.latitude,
    this.longitude,
    this.altitude
)


object LocationUtils {
    @SerialName("direction")
    enum class RelativeDirection(val sign: Int, val bearingOffsetDegrees: Float) {
        FORWARD(1, 0f), BACKWARD(-1, -180f),
        LEFT(1, -90f), RIGHT(-1, 90f),
        UP(1, 0f), DOWN(-1, 0f), ;
    }

    @SerialName("cardinal")
    enum class Direction(val bearingDegrees: Float) {
        NORTH(0f), EAST(90f),
        SOUTH(180f), WEST(270f)
    }

    private fun translateNorth(
        location: LocationCoordinate3D,
        distMeters: Double,
    ): LocationCoordinate3D {
        val deltaLat = (distMeters / EARTH_RADIUS) * (180 / Math.PI)
        return LocationCoordinate3D(
            location.latitude + deltaLat,
            location.longitude,
            location.altitude
        )
    }

    private fun translateWest(
        location: LocationCoordinate3D,
        distMeters: Double,
    ): LocationCoordinate3D {
        val latRad = Math.toRadians(location.latitude)
        val deltaLong = (distMeters / (EARTH_RADIUS * cos(latRad))) * (180 / Math.PI)
        return LocationCoordinate3D(
            location.latitude,
            location.longitude - deltaLong,
            location.altitude
        )
    }

    fun LocationCoordinate3D.translate(
        distMeters: Double,
        direction: Direction,
    ) = when (direction) {
        Direction.NORTH, Direction.SOUTH -> translateNorth(
            this,
            if (direction == Direction.NORTH) distMeters else -distMeters
        )

        Direction.WEST, Direction.EAST -> translateWest(
            this,
            if (direction == Direction.WEST) distMeters else -distMeters
        )
    }

    fun LocationCoordinate3D.translate(
        distMeters: Double,
        relativeDirection: RelativeDirection,
        currentHeadingDegrees: Double,
    ): LocationCoordinate3D {
        val location = this

        val bearingRad = Math.toRadians(
            (currentHeadingDegrees + relativeDirection.bearingOffsetDegrees).normalizeAngle()
        )

        val deltaNorth = distMeters * cos(bearingRad)
        val deltaEast = distMeters * sin(bearingRad)

        val deltaLat = (deltaNorth / EARTH_RADIUS) * (180 / Math.PI)
        val deltaLng =
            (deltaEast / (EARTH_RADIUS * cos(Math.toRadians(location.latitude)))) * (180 / Math.PI)
        val deltaAlt = when (relativeDirection) {
            RelativeDirection.UP -> distMeters
            RelativeDirection.DOWN -> -distMeters
            else -> 0.0
        }

        return LocationCoordinate3D(
            location.latitude + deltaLat,
            location.longitude + deltaLng,
            location.altitude + deltaAlt
        )
    }

    fun LocationCoordinate2D.distanceTo(other: LocationCoordinate2D): Double {
        val l1 = Location("l1")
        l1.latitude = this.latitude
        l1.longitude = this.longitude
        val l2 = Location("l2")
        l2.latitude = other.latitude
        l2.longitude = other.longitude
        return l1.distanceTo(l2).toDouble()
    }

    fun LocationCoordinate3D.distanceTo(other: LocationCoordinate3D): Double =
        Pair(
            this.as2D.distanceTo(other.as2D),
            other.altitude - this.altitude
        ).mag

    fun LocationCoordinate2D.bearingTo(end: LocationCoordinate2D): Double {
        val start = this
        val l1 = Location("l1").apply {
            latitude = start.latitude
            longitude = start.longitude
        }
        val l2 = Location("l2").apply {
            latitude = end.latitude
            longitude = end.longitude
        }
        return l1.bearingTo(l2).toDouble().normalizeAngle()
    }

    fun vectorToTarget(
        cur: LocationCoordinate3D,
        target: LocationCoordinate3D,
        curYaw: Double, // degrees clockwise from North
    ): Triple<Double, Double, Double> {
        val dh = cur.as2D.distanceTo(target.as2D)
        val dz = target.altitude - cur.altitude

        val bearingToTarget = cur.as2D.bearingTo(target.as2D)
        val relBearingRad = Math.toRadians((bearingToTarget - curYaw).normalizeAngle())

        val dx = dh * cos(relBearingRad)
        val dy = dh * sin(relBearingRad)

        return Triple(dx, dy, dz).normalized()
    }
}