package com.appdwg.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.appdwg.core.Calibration
import com.appdwg.core.GeoidGrid
import com.appdwg.core.GnssFix
import com.appdwg.core.Point3

/** Where the GNSS height comes from. */
enum class HeightMode { RECEIVER, GEOID_GRID, FIXED_N }

/** Height settings of the device (geoid model), kept between sessions; the calibration lives in the project. */
class Geodesy(context: Context) {
    private val prefs = context.getSharedPreferences("geodesy", Context.MODE_PRIVATE)
    var heightMode by mutableStateOf(HeightMode.entries.getOrElse(prefs.getInt("mode", 0)) { HeightMode.RECEIVER })
        private set
    var fixedN by mutableStateOf(prefs.getFloat("fixedN", 0f).toDouble())
        private set
    var grid by mutableStateOf<GeoidGrid?>(null)
    var gridMessage by mutableStateOf<String?>(null)
    /** Content URI of the last grid loaded, read again when the app starts. */
    val gridUri: String? get() = prefs.getString("gridUri", null)

    fun changeMode(m: HeightMode) { heightMode = m; prefs.edit().putInt("mode", m.ordinal).apply() }
    fun changeFixedN(n: Double) { fixedN = n; prefs.edit().putFloat("fixedN", n.toFloat()).apply() }
    fun rememberGrid(uri: String) { prefs.edit().putString("gridUri", uri).apply() }

    /** Undulation N used now at the fix, or null when the mode does not use one or the grid does not cover it. */
    fun undulation(fix: GnssFix): Double? = when (heightMode) {
        HeightMode.RECEIVER -> null
        HeightMode.GEOID_GRID -> grid?.undulation(fix.latitude, fix.longitude)
        HeightMode.FIXED_N -> fixedN
    }

    /** Orthometric height of the fix by the chosen mode; NaN when it cannot be had. */
    fun height(fix: GnssFix): Double = when (heightMode) {
        HeightMode.RECEIVER -> fix.altitude
        else -> fix.ellipsoidalHeight?.let { h -> undulation(fix)?.let { h - it } }
    } ?: Double.NaN

    /** Position of the fix in the project's coordinate system, and its height, before the site calibration. */
    fun measured(fix: GnssFix, crs: com.appdwg.core.Crs): Point3 =
        crs.fromWgs84(fix.latitude, fix.longitude).let { Point3(it.x, it.y, height(fix)) }

    /** Position in the local grid: the calibration applied when there is one. */
    fun local(fix: GnssFix, crs: com.appdwg.core.Crs, calibration: Calibration?): Point3 {
        val m = measured(fix, crs)
        return calibration?.apply(m) ?: m
    }
}
