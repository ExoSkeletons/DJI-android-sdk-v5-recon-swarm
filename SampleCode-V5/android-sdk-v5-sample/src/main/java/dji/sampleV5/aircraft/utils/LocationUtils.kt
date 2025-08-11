package dji.sampleV5.aircraft.utils

import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

fun Double.normalizeAngle() = ((this % 360) + 360) % 360

fun Double.toDegrees(): Double = Math.toDegrees(this)

fun Double.toRadians(): Double = Math.toRadians(this)

object LocationUtils {
    enum class RelativeDirection(val bearingOffsetDegrees: Float) {
        FORWARD(0f), BACKWARD(-180f),
        LEFT(-90f), RIGHT(90f),
        UP(0f), DOWN(0f)
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

    fun LocationCoordinate3D.distanceTo(other: LocationCoordinate3D): Double {
        val lat1 = Math.toRadians(this.latitude)
        val lon1 = Math.toRadians(this.longitude)
        val lat2 = Math.toRadians(other.latitude)
        val lon2 = Math.toRadians(other.longitude)

        val dLat = lat2 - lat1
        val dLon = lon2 - lon1

        val a = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return EARTH_RADIUS * c
    }

    fun LocationCoordinate2D.distanceTo(other: LocationCoordinate2D): Double {
        val lat1 = this.latitude
        val lon1 = this.longitude
        val lat2 = other.latitude
        val lon2 = other.longitude

        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val a = kotlin.math.sin(dLat / 2).pow(2) +
                kotlin.math.cos(Math.toRadians(lat1)) *
                kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon / 2).pow(2)

        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return EARTH_RADIUS * c
    }

    fun LocationCoordinate3D.distanceTo(other: LocationCoordinate2D): Double {
        return LocationCoordinate2D(this.longitude, this.latitude).distanceTo(other)
    }

    fun bearingFromTo(start: LocationCoordinate3D, end: LocationCoordinate3D): Double {
        val startLat = Math.toRadians(start.latitude)
        val startLng = Math.toRadians(start.longitude)
        val endLat = Math.toRadians(end.latitude)
        val endLng = Math.toRadians(end.longitude)

        val dLng = endLng - startLng
        val y = sin(dLng) * cos(endLat)
        val x = cos(startLat) * sin(endLat) - sin(startLat) * cos(endLat) * cos(dLng)
        return Math.toDegrees(atan2(y, x)).normalizeAngle()
    }

    private const val EARTH_RADIUS = 6378137.0 // meters
}