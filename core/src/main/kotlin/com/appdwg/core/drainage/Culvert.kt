package com.appdwg.core.drainage

import kotlin.math.acos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** One barrel. [span] is the width, [rise] the height; they are equal for a circular pipe. */
data class CulvertBarrel(val shape: Shape, val span: Double, val rise: Double, val barrels: Int) {
    enum class Shape { CIRCLE, BOX }

    val area: Double get() = if (shape == Shape.CIRCLE) Math.PI * rise * rise / 4.0 else span * rise
}

data class CulvertHead(
    val inlet: Double,
    val outlet: Double,
    val headwater: Double,
    val velocity: Double,
    val overtopped: Boolean,
)

/**
 * Culvert headwater by inlet control (FHWA HDS-5, the method DNIT uses) and by outlet control
 * (energy plus Manning friction). The governing headwater is the larger of the two.
 * Inlet-control constants are the US customary fit, so the discharge is converted before they are applied.
 */
object Culvert {
    const val G = 9.80665
    const val KE_SQUARE = 0.5

    /** Concrete box, 90° headwall, square edge. HDS-5 chart 8, form 1. */
    private const val BOX_K = 0.061
    private const val BOX_M = 0.75
    private const val BOX_C = 0.0423
    private const val BOX_Y = 0.82

    /** Circular concrete pipe, square edge with headwall. HDS-5 chart 1. */
    private const val PIPE_K = 0.0098
    private const val PIPE_M = 2.0
    private const val PIPE_C = 0.0398
    private const val PIPE_Y = 0.67

    private const val FT = 0.3048
    private const val CFS = 35.3146667215

    /** DNIT concrete pipe diameters, then the same diameters as a twin barrel, then square boxes. */
    val CANDIDATES: List<CulvertBarrel> = buildList {
        val pipes = doubleArrayOf(0.40, 0.60, 0.80, 1.00, 1.20, 1.50, 2.00)
        for (d in pipes) add(CulvertBarrel(CulvertBarrel.Shape.CIRCLE, d, d, 1))
        for (d in pipes) add(CulvertBarrel(CulvertBarrel.Shape.CIRCLE, d, d, 2))
        val boxes = doubleArrayOf(1.00, 1.50, 2.00, 2.50, 3.00)
        for (b in boxes) add(CulvertBarrel(CulvertBarrel.Shape.BOX, b, b, 1))
        for (b in boxes) add(CulvertBarrel(CulvertBarrel.Shape.BOX, b, b, 2))
    }

    fun headwater(
        barrel: CulvertBarrel,
        discharge: Double,
        length: Double,
        slope: Double,
        tailwater: Double,
        allowable: Double,
        manningN: Double = 0.013,
        entranceLoss: Double = KE_SQUARE,
    ): CulvertHead {
        val q = discharge.coerceAtLeast(0.0) / barrel.barrels.coerceAtLeast(1)
        val inlet = inletControl(barrel, q, slope)
        val outlet = outletControl(barrel, q, length, slope, tailwater, manningN, entranceLoss)
        val hw = maxOf(inlet, outlet)
        val velocity = if (barrel.area > 0.0) q / barrel.area else 0.0
        return CulvertHead(inlet, outlet, hw, velocity, hw > allowable + 1e-6)
    }

    /** Smallest candidate whose headwater stays within [allowable]. The largest is returned when none does. */
    fun size(
        discharge: Double,
        length: Double,
        slope: Double,
        tailwater: Double,
        allowable: Double,
        manningN: Double = 0.013,
    ): Pair<CulvertBarrel, CulvertHead> {
        var best = CANDIDATES.first() to headwater(CANDIDATES.first(), discharge, length, slope, tailwater, allowable, manningN)
        for (barrel in CANDIDATES) {
            val head = headwater(barrel, discharge, length, slope, tailwater, allowable, manningN)
            if (!head.overtopped) return barrel to head
            if (head.headwater < best.second.headwater) best = barrel to head
        }
        return best
    }

    fun inletControl(barrel: CulvertBarrel, dischargePerBarrel: Double, slope: Double): Double {
        if (dischargePerBarrel <= 0.0 || barrel.rise <= 0.0 || barrel.area <= 0.0) return 0.0
        val qCfs = dischargePerBarrel * CFS
        val areaFt = barrel.area / (FT * FT)
        val dFt = barrel.rise / FT
        val specific = qCfs / (areaFt * sqrt(dFt))
        val hc = if (barrel.shape == CulvertBarrel.Shape.BOX) 1.5 * criticalBox(dischargePerBarrel, barrel.span) / barrel.rise
        else criticalCircleRatio(dischargePerBarrel, barrel.rise)
        val unsubmerged = hc + (if (barrel.shape == CulvertBarrel.Shape.BOX) BOX_K else PIPE_K) *
            specific.pow(if (barrel.shape == CulvertBarrel.Shape.BOX) BOX_M else PIPE_M) - 0.5 * slope
        val c = if (barrel.shape == CulvertBarrel.Shape.BOX) BOX_C else PIPE_C
        val y = if (barrel.shape == CulvertBarrel.Shape.BOX) BOX_Y else PIPE_Y
        val submerged = c * specific * specific + y - 0.5 * slope
        return barrel.rise * maxOf(unsubmerged, submerged, 0.0)
    }

    fun outletControl(
        barrel: CulvertBarrel,
        dischargePerBarrel: Double,
        length: Double,
        slope: Double,
        tailwater: Double,
        manningN: Double,
        entranceLoss: Double,
    ): Double {
        if (dischargePerBarrel <= 0.0 || barrel.area <= 0.0) return 0.0
        val velocity = dischargePerBarrel / barrel.area
        val radius = if (barrel.shape == CulvertBarrel.Shape.CIRCLE) barrel.rise / 4.0
        else barrel.area / (2.0 * (barrel.span + barrel.rise))
        val friction = if (radius <= 0.0 || manningN <= 0.0) 0.0
        else length * manningN * manningN * velocity * velocity / radius.pow(4.0 / 3.0)
        val entrance = entranceLoss * velocity * velocity / (2.0 * G)
        val dc = if (barrel.shape == CulvertBarrel.Shape.BOX) criticalBox(dischargePerBarrel, barrel.span)
        else criticalCircle(dischargePerBarrel, barrel.rise)
        val outletDepth = maxOf(tailwater, (dc + barrel.rise) / 2.0)
        return (outletDepth + entrance + friction - slope * length).coerceAtLeast(0.0)
    }

    /** Critical depth of a rectangular barrel, m. */
    fun criticalBox(discharge: Double, width: Double): Double {
        if (discharge <= 0.0 || width <= 0.0) return 0.0
        val q = discharge / width
        return (q * q / G).pow(1.0 / 3.0)
    }

    fun criticalCircle(discharge: Double, diameter: Double): Double {
        if (discharge <= 0.0 || diameter <= 0.0) return 0.0
        var lo = 1e-4
        var hi = diameter * 0.99
        val target = discharge * discharge / G
        repeat(40) {
            val mid = (lo + hi) / 2.0
            val (area, top) = circleSection(diameter, mid)
            val value = if (top <= 1e-9) 0.0 else area * area * area / top
            if (value < target) lo = mid else hi = mid
        }
        return (lo + hi) / 2.0
    }

    private fun criticalCircleRatio(discharge: Double, diameter: Double): Double {
        val dc = criticalCircle(discharge, diameter)
        val (area, top) = circleSection(diameter, dc)
        val velocityHead = if (area <= 0.0) 0.0 else discharge * discharge / (2.0 * G * area * area)
        return (dc + velocityHead) / diameter
    }

    /** Area and free-surface width of a partly full circular pipe. */
    fun circleSection(diameter: Double, depth: Double): Pair<Double, Double> {
        val r = diameter / 2.0
        val h = depth.coerceIn(0.0, diameter)
        if (h <= 0.0) return 0.0 to 0.0
        if (h >= diameter) return (Math.PI * r * r) to 0.0
        val theta = 2.0 * acos(((r - h) / r).coerceIn(-1.0, 1.0))
        val area = 0.5 * r * r * (theta - sin(theta))
        val top = 2.0 * sqrt((2.0 * r * h - h * h).coerceAtLeast(0.0))
        return area to top
    }
}
