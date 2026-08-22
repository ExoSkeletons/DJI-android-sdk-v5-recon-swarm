package com.kcg.dr.djiutils

import dji.sdk.keyvalue.value.common.Velocity3D
import dji.sdk.keyvalue.value.common.XYZ
import kotlin.math.sqrt


operator fun XYZ.plus(other: XYZ): XYZ = XYZ(this.x + other.x, this.y + other.y, this.z + other.z)
operator fun XYZ.minus(other: XYZ): XYZ = XYZ(this.x - other.x, this.y - other.y, this.z - other.z)
operator fun XYZ.times(other: XYZ): XYZ = XYZ(this.x * other.x, this.y * other.y, this.z * other.z)
operator fun XYZ.div(other: XYZ): XYZ = XYZ(this.x / other.x, this.y / other.y, this.z / other.z)
operator fun XYZ.times(scalar: Double): XYZ = XYZ(this.x * scalar, this.y * scalar, this.z * scalar)
operator fun XYZ.div(scalar: Double): XYZ = XYZ(this.x / scalar, this.y / scalar, this.z / scalar)
inline val XYZ.mag get(): Double = sqrt(this.x * this.x + this.y * this.y + this.z * this.z)
fun XYZ.normalized(eps: Double = 1e-6): XYZ = if (mag < eps) XYZ(0.0, 0.0, 0.0) else this / this.mag
fun XYZ.asVector(): Triple<Double, Double, Double> = Triple(this.x, this.y, this.z)
fun Triple<Double, Double, Double>.asXYZ(): XYZ = XYZ(this.first, this.second, this.third)
fun Velocity3D.asXYZ(): XYZ = XYZ(this.x, this.y, this.z)
fun XYZ.dt(t: Double): Velocity3D = Velocity3D(this.x / t, this.y / t, this.z / t)