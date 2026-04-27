@file:OptIn(InternalSerializationApi::class)

package com.kcg.dr

import android.location.Location
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import kotlinx.serialization.InternalSerializationApi
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

fun Double.normalizeAngle() = ((this % 360) + 360) % 360

fun Double.wrap180(): Double {
    var v = this % 360.0
    if (v > 180) v -= 360
    if (v < -180) v += 360
    return v
}

fun Double.toDegrees(): Double = Math.toDegrees(this)

fun Double.toRadians(): Double = Math.toRadians(this)

private fun Triple<Double, Double, Double>.normalized(): Triple<Double, Double, Double> {
    val (vx, vy, vz) = this
    val mag = sqrt(vx.pow(2) + vy.pow(2) + vz.pow(2))
    if (mag < 1e-6) return Triple(0.0, 0.0, 0.0)
    return Triple(vx / mag, vy / mag, vz / mag)
}

inline val LocationCoordinate3D.as2D get() = LocationCoordinate2D(this.latitude, this.longitude)

fun LocationCoordinate2D.as3D(altitude: Double) =
    LocationCoordinate3D(this.latitude, this.longitude, altitude)

fun LocationCoordinate3D.atAlt(altitude: Double) =
    LocationCoordinate3D(this.latitude, this.longitude, altitude)


object LocationUtils {
    enum class RelativeDirection(val sign: Int, val bearingOffsetDegrees: Float) {
        FORWARD(1, 0f), BACKWARD(-1, -180f),
        LEFT(1, -90f), RIGHT(-1, 90f),
        UP(1, 0f), DOWN(-1, 0f), ;
    }

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

    fun LocationCoordinate3D.distanceTo(other: LocationCoordinate3D): Double {
        val horizontal = this.as2D.distanceTo(other.as2D)
        val vertical = other.altitude - this.altitude
        return sqrt(horizontal * horizontal + vertical * vertical)
    }

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

        val mag = sqrt(dx * dx + dy * dy + dz * dz)
        if (mag < 1e-6) return Triple(0.0, 0.0, 0.0)
        return Triple(dx / mag, dy / mag, dz / mag)
    }


    private const val EARTH_RADIUS = 6378137.0 // meters
}