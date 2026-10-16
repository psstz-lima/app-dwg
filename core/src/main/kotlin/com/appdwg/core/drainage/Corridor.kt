package com.appdwg.core.drainage

import com.appdwg.core.Point2
import kotlin.math.max

/**
 * Earthwork surfaces swept along a device, in the same sense as a Civil 3D corridor:
 * the cut (trench walls and floor), the bedding, the backfill and the embankment.
 * Each surface is its own triangle soup so the view can show them one at a time.
 */
object Corridor {
    class Layers(
        val trench: DoubleArray,
        val bedding: DoubleArray,
        val backfill: DoubleArray,
        val fill: DoubleArray,
        val water: DoubleArray,
    )

    fun along(
        frames: List<SweepFrame>,
        terrain: (Double, Double) -> Double?,
        deviceHalfWidth: Double,
        innerHalfWidth: Double,
        buried: Boolean,
        bedding: Double,
    ): Layers {
        val used = stride(frames)
        if (used.size < 2) return empty()
        val trench = Soup()
        val bed = Soup()
        val back = Soup()
        val fill = Soup()
        val water = Soup()
        val half = deviceHalfWidth.coerceAtLeast(0.05)
        val clearance = HydraulicGrade.DEFAULT_CLEARANCE
        val bottomHalf = half + clearance
        val bedThick = if (buried) bedding.coerceAtLeast(0.0) else 0.0
        val sections = used.map { frame -> section(frame, terrain, half, bottomHalf, bedThick) }
        for (i in 0 until sections.size - 1) {
            val a = sections[i]
            val b = sections[i + 1]
            if (a.dig > 0.02 || b.dig > 0.02) {
                trench.quad(a.floorL, b.floorL, b.floorR, a.floorR)
                trench.quad(a.toeL, b.toeL, b.floorL, a.floorL)
                trench.quad(a.floorR, b.floorR, b.toeR, a.toeR)
            }
            if (a.bed > 0.01 || b.bed > 0.01) {
                bed.quad(a.floorL, b.floorL, b.floorR, a.floorR)
                bed.quad(a.bedL, b.bedL, b.floorL, a.floorL)
                bed.quad(a.floorR, b.floorR, b.bedR, a.bedR)
                bed.quad(a.bedL, a.bedR, b.bedR, b.bedL)
            }
            if (a.back > 0.01 || b.back > 0.01) {
                back.quad(a.deviceL, b.deviceL, b.bedL, a.bedL)
                back.quad(a.bedR, b.bedR, b.deviceR, a.deviceR)
                if (a.cover > 0.02 || b.cover > 0.02) back.quad(a.coverL, b.coverL, b.coverR, a.coverR)
            }
            if (a.fillH > 0.02 || b.fillH > 0.02) {
                fill.quad(a.fillL, b.fillL, b.fillR, a.fillR)
                fill.quad(a.toeFillL, b.toeFillL, b.fillL, a.fillL)
                fill.quad(a.fillR, b.fillR, b.toeFillR, a.toeFillR)
            }
            if (innerHalfWidth > 0.02) {
                val zA = a.frame.invertZ + 0.02
                val zB = b.frame.invertZ + 0.02
                water.quad(
                    at(a.frame, -innerHalfWidth, zA), at(b.frame, -innerHalfWidth, zB),
                    at(b.frame, innerHalfWidth, zB), at(a.frame, innerHalfWidth, zA),
                )
            }
        }
        // End walls so the trench reads as a volume, not a ribbon.
        cap(trench, sections.first(), front = true)
        cap(trench, sections.last(), front = false)
        return Layers(trench.pack(), bed.pack(), back.pack(), fill.pack(), water.pack())
    }

    /** A structure pit: vertical cut under the plan outline, down to [floorZ]. */
    fun pit(footprint: List<Point2>, ground: Double, floorZ: Double): Layers {
        val ring = footprint.distinctBy { Pair(it.x, it.y) }
        if (ring.size < 3 || ground - floorZ < 0.05) return empty()
        val trench = Soup()
        val n = ring.size
        val mid = P(ring.map { it.x }.average(), ring.map { it.y }.average(), floorZ)
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[(i + 1) % n]
            trench.quad(P(a.x, a.y, ground), P(b.x, b.y, ground), P(b.x, b.y, floorZ), P(a.x, a.y, floorZ))
            trench.tri(mid, P(b.x, b.y, floorZ), P(a.x, a.y, floorZ))
        }
        return Layers(trench.pack(), DoubleArray(0), DoubleArray(0), DoubleArray(0), DoubleArray(0))
    }

    private fun empty() = Layers(DoubleArray(0), DoubleArray(0), DoubleArray(0), DoubleArray(0), DoubleArray(0))

    private class Cut(
        val frame: SweepFrame,
        val dig: Double,
        val bed: Double,
        val back: Double,
        val cover: Double,
        val fillH: Double,
        val floorL: P, val floorR: P,
        val toeL: P, val toeR: P,
        val bedL: P, val bedR: P,
        val deviceL: P, val deviceR: P,
        val coverL: P, val coverR: P,
        val fillL: P, val fillR: P,
        val toeFillL: P, val toeFillR: P,
    )

    private fun section(frame: SweepFrame, terrain: (Double, Double) -> Double?, half: Double, bottomHalf: Double, bedThick: Double): Cut {
        val ground = terrain(frame.x, frame.y) ?: frame.invertZ
        val floorZ = frame.invertZ - bedThick
        val dig = max(0.0, ground - floorZ)
        val sloped = dig > HydraulicGrade.SHORING_DEPTH
        val extra = if (sloped) HydraulicGrade.DEFAULT_SIDE * dig else 0.0
        val topHalf = bottomHalf + extra
        val cover = max(0.0, ground - frame.topZ)
        val backH = max(0.0, minOf(ground, frame.topZ) - frame.invertZ)
        val fillH = max(0.0, frame.invertZ - ground)
        val toe = half + HydraulicGrade.DEFAULT_SIDE * fillH
        return Cut(
            frame, dig, bedThick, backH, cover, fillH,
            at(frame, -bottomHalf, floorZ), at(frame, bottomHalf, floorZ),
            at(frame, -topHalf, ground), at(frame, topHalf, ground),
            at(frame, -bottomHalf, frame.invertZ), at(frame, bottomHalf, frame.invertZ),
            at(frame, -half, frame.invertZ), at(frame, half, frame.invertZ),
            at(frame, -bottomHalf, frame.topZ), at(frame, bottomHalf, frame.topZ),
            at(frame, -half, frame.invertZ), at(frame, half, frame.invertZ),
            at(frame, -toe, ground), at(frame, toe, ground),
        )
    }

    private fun cap(soup: Soup, cut: Cut, front: Boolean) {
        if (cut.dig <= 0.02) return
        if (front) soup.quad(cut.toeL, cut.floorL, cut.floorR, cut.toeR)
        else soup.quad(cut.toeR, cut.floorR, cut.floorL, cut.toeL)
    }

    private fun stride(frames: List<SweepFrame>): List<SweepFrame> {
        if (frames.size <= 96) return frames
        val step = frames.size / 80
        val out = ArrayList<SweepFrame>()
        for (i in frames.indices) if (i == 0 || i == frames.lastIndex || i % step == 0) out += frames[i]
        return out
    }

    private fun at(frame: SweepFrame, across: Double, z: Double) = P(
        frame.x + frame.rightX * across * frame.miter,
        frame.y + frame.rightY * across * frame.miter,
        z,
    )

    private data class P(val x: Double, val y: Double, val z: Double)

    private class Soup {
        private val values = ArrayList<Double>()
        private val cap = 12_000 * 9

        fun tri(a: P, b: P, c: P) {
            if (values.size + 9 > cap) return
            values += a.x; values += a.y; values += a.z
            values += b.x; values += b.y; values += b.z
            values += c.x; values += c.y; values += c.z
        }

        fun quad(a: P, b: P, c: P, d: P) {
            tri(a, b, c)
            tri(a, c, d)
        }

        fun pack(): DoubleArray = DoubleArray(values.size) { values[it] }
    }
}
