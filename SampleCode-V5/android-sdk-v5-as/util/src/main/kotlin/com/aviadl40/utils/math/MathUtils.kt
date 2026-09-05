package com.aviadl40.utils.math

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


inline val Pair<Double, Double>.x get(): Double = this.first
inline val Pair<Double, Double>.y get(): Double = this.second
inline val Pair<Double, Double>.mag get(): Double = sqrt(x * x + y * y)
fun Pair<Double, Double>.normalized(eps: Double = 1e-6): Pair<Double, Double> =
    if (mag < eps) Pair(0.0, 0.0)
    else Pair(x / mag, y / mag)

inline val Triple<Double, Double, Double>.x get(): Double = this.first
inline val Triple<Double, Double, Double>.y get(): Double = this.second
inline val Triple<Double, Double, Double>.z get(): Double = this.third
inline val Triple<Double, Double, Double>.mag get(): Double = sqrt(x * x + y * y + z * z)
fun Triple<Double, Double, Double>.normalized(eps: Double = 1e-6): Triple<Double, Double, Double> =
    if (mag < eps) Triple(0.0, 0.0, 0.0)
    else Triple(x / mag, y / mag, z / mag)
