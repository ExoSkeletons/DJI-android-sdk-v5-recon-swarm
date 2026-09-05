@file:OptIn(InternalSerializationApi::class)

package com.kcg.dr.djiutils

import android.location.Location
import com.aviadl40.utils.math.mag
import com.aviadl40.utils.math.normalizeAngle
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import dji.sdk.keyvalue.value.common.XYZ
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.SerialName
import kotlin.math.cos
import kotlin.math.sin

private const val EARTH_RADIUS = 6378137.0 // meters


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
    ): XYZ {
        val dh = cur.as2D.distanceTo(target.as2D)
        val dz = target.altitude - cur.altitude

        val bearingToTarget = cur.as2D.bearingTo(target.as2D)
        val relBearingRad = Math.toRadians((bearingToTarget - curYaw).normalizeAngle())

        val dx = dh * cos(relBearingRad)
        val dy = dh * sin(relBearingRad)

        return XYZ(dx, dy, dz).normalized()
    }
}