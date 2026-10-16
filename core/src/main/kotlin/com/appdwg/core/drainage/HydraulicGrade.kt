package com.appdwg.core.drainage

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Longitudinal grade of a gravity drain.
 *
 * Water does not flow uphill. Working from upstream to downstream, the invert stays at or below
 * the cover line and never rises. Where the ground falls faster than the minimum grade, the
 * invert follows it (a grade break, still downhill) so the cut stays small. Where the ground
 * rises, the invert holds the minimum grade and the trench gets deeper.
 */
/**
 * Ground samples along a path. A hole in the TIN is filled from the samples that do hit it.
 * A path with no hit at all stays unknown (NaN): it is never treated as elevation 0.
 */
object GroundCover {
    fun fill(raw: Array<Double?>): Pair<DoubleArray, Boolean> {
        val n = raw.size
        val known = ArrayList<Int>(n)
        for (i in 0 until n) if (raw[i]?.isFinite() == true) known += i
        val out = DoubleArray(n) { Double.NaN }
        if (known.isEmpty()) return out to true
        for (i in 0 until n) {
            val value = raw[i]
            if (value != null && value.isFinite()) {
                out[i] = value
                continue
            }
            val prev = known.lastOrNull { it < i }
            val next = known.firstOrNull { it > i }
            out[i] = when {
                prev != null && next != null -> {
                    val t = (i - prev).toDouble() / (next - prev).toDouble()
                    raw[prev]!! * (1.0 - t) + raw[next]!! * t
                }
                prev != null -> raw[prev]!!
                next != null -> raw[next]!!
                else -> Double.NaN
            }
        }
        return out to (known.size != n)
    }
}

object HydraulicGrade {
    const val DEFAULT_MIN_SLOPE = 0.005
    const val SHORING_DEPTH = 1.25
    const val DEFAULT_CLEARANCE = 0.30
    const val DEFAULT_BEDDING = 0.10
    const val DEFAULT_SIDE = 0.5
    const val DEFAULT_MIN_COVER = 0.60
    const val DEFAULT_MAX_DEPTH = 6.0

    /** Drawn a few centimetres clear of the TIN so a surface device is not coplanar with it. */
    const val SURFACE_SEAT = 0.04

    data class Sample(
        val distance: Double,
        val terrain: Double,
        val invert: Double,
        /** Positive downhill toward the next sample, in the flow direction. Zero on the last. */
        val slopeDown: Double,
        val excavationDepth: Double,
        val excavationArea: Double,
        val backfillArea: Double,
        val beddingArea: Double,
        val fillArea: Double,
        val shoring: Boolean,
    )

    data class Result(
        /** Samples in path order, the order the device was drawn. */
        val samples: List<Sample>,
        /** True when the drawn start is the downstream end. */
        val downstreamAtStart: Boolean,
        val excavation: Double,
        val backfill: Double,
        val bedding: Double,
        val spoil: Double,
        val fill: Double,
        val tooDeep: Boolean,
        val shoring: Boolean,
        val length: Double,
        val minSlope: Double,
        val invertUpstream: Double,
        val invertDownstream: Double,
        val maxCut: Double,
        /** True when part of the path missed the TIN and was filled from the samples that hit it. */
        val offSurface: Boolean = false,
        /** Mean downhill grade, rise over run. Not the minimum the designer asked for. */
        val actualSlope: Double = minSlope,
    )

    fun design(
        distances: DoubleArray,
        terrain: DoubleArray,
        deviceHeight: Double,
        deviceWidth: Double,
        deviceArea: Double,
        buried: Boolean,
        minSlope: Double = DEFAULT_MIN_SLOPE,
        minCover: Double = if (buried) DEFAULT_MIN_COVER else 0.0,
        maxDepth: Double = DEFAULT_MAX_DEPTH,
        clearance: Double = DEFAULT_CLEARANCE,
        bedding: Double = if (buried) DEFAULT_BEDDING else 0.0,
        sideSlope: Double = DEFAULT_SIDE,
        /** Null picks the lower ground as downstream. */
        downstreamAtStart: Boolean? = null,
    ): Result {
        val n = min(distances.size, terrain.size)
        if (n < 2 || (0 until n).any { !terrain[it].isFinite() }) {
            return Result(emptyList(), false, 0.0, 0.0, 0.0, 0.0, 0.0, false, false, 0.0, minSlope, 0.0, 0.0, 0.0, offSurface = true)
        }
        val height = deviceHeight.coerceAtLeast(0.05)
        val width = deviceWidth.coerceAtLeast(0.05)
        val slopeMin = minSlope.coerceAtLeast(0.0)
        val cover = if (buried) minCover.coerceAtLeast(0.0) else 0.0
        val bed = bedding.coerceAtLeast(0.0)
        val side = sideSlope.coerceAtLeast(0.0)
        val limit = maxDepth.coerceAtLeast(0.2)
        val downStart = downstreamAtStart ?: (terrain[0] < terrain[n - 1] - 0.01)
        val order = if (downStart) (n - 1 downTo 0).toList() else (0 until n).toList()
        val ceiling = DoubleArray(n) { terrain[it] - cover - height }
        val invert = DoubleArray(n)
        invert[order[0]] = ceiling[order[0]]
        for (k in 1 until n) {
            val i = order[k]
            val prev = order[k - 1]
            val ds = abs(distances[i] - distances[prev])
            val held = invert[prev] - slopeMin * ds
            // As high as the cover allows, and at least the minimum grade below the previous invert.
            invert[i] = min(ceiling[i], held)
        }
        val bottom = width + 2.0 * clearance.coerceAtLeast(0.0)
        val area = deviceArea.coerceAtLeast(0.0)
        val samples = ArrayList<Sample>(n)
        var cut = 0.0
        var back = 0.0
        var bedVol = 0.0
        var spoil = 0.0
        var fill = 0.0
        var tooDeep = false
        var shoring = false
        var maxCut = 0.0
        val areas = Array(n) { doubleArrayOf(0.0, 0.0, 0.0, 0.0) }
        for (i in 0 until n) {
            val ground = terrain[i]
            val inv = invert[i]
            val dig = max(0.0, ground - inv + bed)
            val deep = dig > SHORING_DEPTH
            if (deep) shoring = true
            val slopeSide = if (deep) side else 0.0
            val excavArea = if (dig <= 1e-6) 0.0 else bottom * dig + slopeSide * dig * dig
            val buriedHeight = (ground - inv).coerceIn(0.0, height)
            val occupied = area * (buriedHeight / height)
            val bedArea = if (dig <= 1e-6) 0.0 else bottom * min(bed, dig)
            val backArea = max(0.0, excavArea - occupied - bedArea)
            val fillHeight = max(0.0, inv - ground)
            val fillArea = if (fillHeight <= 1e-6) 0.0 else bottom * fillHeight + side * fillHeight * fillHeight
            val depth = max(0.0, ground - inv)
            if (depth > limit) tooDeep = true
            if (depth > maxCut) maxCut = depth
            areas[i][0] = excavArea
            areas[i][1] = backArea
            areas[i][2] = bedArea
            areas[i][3] = fillArea
            val next = order.indexOf(i).let { at -> if (at < 0 || at + 1 >= n) -1 else order[at + 1] }
            val slopeDown = if (next < 0) 0.0 else {
                val ds = abs(distances[next] - distances[i])
                if (ds < 1e-9) 0.0 else (inv - invert[next]) / ds
            }
            samples += Sample(distances[i], ground, inv, slopeDown, dig, excavArea, backArea, bedArea, fillArea, deep)
        }
        for (i in 0 until n - 1) {
            val ds = abs(distances[i + 1] - distances[i])
            if (ds < 1e-9) continue
            cut += (areas[i][0] + areas[i + 1][0]) * 0.5 * ds
            back += (areas[i][1] + areas[i + 1][1]) * 0.5 * ds
            bedVol += (areas[i][2] + areas[i + 1][2]) * 0.5 * ds
            fill += (areas[i][3] + areas[i + 1][3]) * 0.5 * ds
        }
        spoil = max(0.0, cut - back)
        val length = abs(distances[n - 1] - distances[0])
        val upIndex = order.first()
        val downIndex = order.last()
        val drop = invert[upIndex] - invert[downIndex]
        val actual = if (length > 1e-9) max(0.0, drop / length) else slopeMin
        // A surface device is the catalog section. It is not a trench, so it has no backfill or shoring.
        return if (buried) {
            Result(
                samples, downStart, cut, back, bedVol, spoil, fill, tooDeep, shoring, length, slopeMin,
                invert[upIndex], invert[downIndex], maxCut, actualSlope = actual,
            )
        } else {
            Result(
                samples, downStart, 0.0, 0.0, 0.0, 0.0, 0.0, false, false, length, slopeMin,
                invert[upIndex], invert[downIndex], 0.0, actualSlope = actual,
            )
        }
    }
}

/** Buried pipes and culverts; gutters, curbs and ditches stay on the surface. */
fun deviceRunsBuried(device: DeviceDef): Boolean {
    val text = "${device.code} ${device.name} ${device.family} ${device.subtype}".lowercase()
    if (listOf("sarjeta", "meio-fio", "meio fio", "valeta", "descida", "dissip", "guia", "meiofio").any { it in text }) {
        return false
    }
    return listOf("bueiro", "dreno", "bstc", "bdtc", "coletor", "galeria", "tubo", "celular").any { it in text }
}
