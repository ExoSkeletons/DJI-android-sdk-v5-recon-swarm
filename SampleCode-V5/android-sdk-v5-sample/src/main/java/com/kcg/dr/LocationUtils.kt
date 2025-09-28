package com.kcg.dr

import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import dji.sdk.keyvalue.value.flightcontroller.FlightCoordinateSystem
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
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

    fun LocationCoordinate3D.distanceTo(other: LocationCoordinate3D): Double {
        val lat1 = Math.toRadians(this.latitude)
        val lon1 = Math.toRadians(this.longitude)
        val lat2 = Math.toRadians(other.latitude)
        val lon2 = Math.toRadians(other.longitude)

        val dLat = lat2 - lat1
        val dLon = lon2 - lon1

        // Horizontal surface distance (Haversine)
        val a = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        val horizontalDist = EARTH_RADIUS * c

        // Vertical difference
        val dAlt = other.altitude - this.altitude

        // 3D distance
        return sqrt(horizontalDist.pow(2) + dAlt.pow(2))
    }

    fun bearingDegreesFromTo(start: LocationCoordinate3D, end: LocationCoordinate3D): Double {
        val startLat = Math.toRadians(start.latitude)
        val startLng = Math.toRadians(start.longitude)
        val endLat = Math.toRadians(end.latitude)
        val endLng = Math.toRadians(end.longitude)

        val dLng = endLng - startLng
        val y = sin(dLng) * cos(endLat)
        val x = cos(startLat) * sin(endLat) - sin(startLat) * cos(endLat) * cos(dLng)
        return Math.toDegrees(atan2(y, x)).normalizeAngle()
    }

    fun calculateVelocityToTarget(
        cur: LocationCoordinate3D,
        target: LocationCoordinate3D,
        curYaw: Double,
        maxVelocity: Double,
        coordinateSystem: FlightCoordinateSystem
    ): Triple<Double, Double, Double> {
        // --- Step 1: Compute horizontal differences in meters ---
        val deltaLat = target.latitude - cur.latitude
        val deltaLon = target.longitude - cur.longitude

        // Approximate meters per degree at current latitude
        val latMeters = deltaLat * (Math.PI / 180) * EARTH_RADIUS
        val lonMeters =
            deltaLon * (Math.PI / 180) * EARTH_RADIUS * cos(Math.toRadians(cur.latitude))

        // vertical difference
        val deltaAlt = target.altitude - cur.altitude
        // horizontal distance
        val horizontalDist = sqrt(latMeters * latMeters + lonMeters * lonMeters)

        // Avoid division by zero
        if (horizontalDist == 0.0 && deltaAlt == 0.0) return Triple(0.0, 0.0, 0.0)

        var vx: Double
        var vy: Double
        when (coordinateSystem) {
            FlightCoordinateSystem.GROUND -> {
                // Ground frame: pitch = North/South, roll = East/West
                vy = ((latMeters / horizontalDist) * min(horizontalDist, maxVelocity))
                vx = ((lonMeters / horizontalDist) * min(horizontalDist, maxVelocity))
            }

            FlightCoordinateSystem.BODY -> {
                // Body frame: rotate horizontal vector by -curYaw
                val bearingRad =
                    atan2(lonMeters, latMeters)           // angle to target in world frame
                val relBearing = bearingRad - Math.toRadians(curYaw)   // rotate to drone's heading
                val speed = min(horizontalDist, maxVelocity)
                vy = (speed * cos(relBearing)) // forward/back relative to drone
                vx = (speed * sin(relBearing)) // left/right relative to drone
            }

            FlightCoordinateSystem.UNKNOWN -> return Triple(0.0, 0.0, 0.0)
        }

        // Vertical speed
        val vz = deltaAlt.coerceIn(-maxVelocity, maxVelocity)

        return Triple(vx, vy, vz)
    }


    private const val EARTH_RADIUS = 6378137.0 // meters
}