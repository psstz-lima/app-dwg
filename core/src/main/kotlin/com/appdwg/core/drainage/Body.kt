package com.appdwg.core.drainage

import com.appdwg.core.Point2
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * The solid and the plan outline drawn for a device.
 *
 * [Sweep.sweep] stays the quantity prism (section area times length). The drawing uses a wall
 * with its real thickness and an open channel, or a point solid at the catalog dimensions.
 * A device whose computed grade would bury it is seated up in [Sweep.visual] so the concrete
 * reads above the terrain; stored elevations used by the calculations do not change.
 */
object DeviceBody {
    fun previewRings(device: DeviceDef): List<List<SectionPoint>> = classify(device, emptyMap())?.preview.orEmpty()

    fun linear(
        device: DeviceDef,
        params: Map<String, Double>,
        nodes: List<PathNode>,
        signedOffset: Double,
        grade: GradeSpec,
        terrain: (Double, Double) -> Double?,
        profileAt: (Double) -> Double?,
    ): SweepMesh? {
        val shape = classify(device, params) ?: return null
        return when (shape) {
            is Tubes -> sweepTubes(shape, nodes, signedOffset, grade, terrain, profileAt)
            is Channel -> Sweep.visual(
                shape.outer, shape.inner, nodes, signedOffset, grade, terrain, profileAt,
                shape.widthM, shape.heightM, shape.innerWidthM,
            )
            else -> null
        }
    }

    fun point(
        device: DeviceDef,
        params: Map<String, Double>,
        x: Double,
        y: Double,
        rotationDeg: Double,
        skewDeg: Double,
        grade: GradeSpec,
        terrainZ: Double?,
        profileZ: Double?,
    ): SweepMesh? {
        val shape = classify(device, params) ?: return null
        val terrain: (Double, Double) -> Double? = { _, _ -> terrainZ }
        val profile: (Double) -> Double? = { profileZ }
        return when (shape) {
            is Box -> seat(shape.mesh(x, y, rotationDeg, skewDeg, grade, terrainZ, profileZ), terrainZ, grade)
            is Apron -> seat(shape.mesh(x, y, rotationDeg, skewDeg, grade, terrainZ, profileZ), terrainZ, grade)
            is Head -> seat(shape.mesh(x, y, rotationDeg, skewDeg, grade, terrainZ, profileZ), terrainZ, grade)
            is Tubes -> {
                val heading = Math.toRadians(rotationDeg)
                val nodes = listOf(
                    PathNode(x, y, 0.0, 0.0, corner = false),
                    PathNode(x + cos(heading), y + sin(heading), 1.0, 1.0, corner = false),
                )
                sweepTubes(shape, nodes, 0.0, grade, terrain, profile)
            }
            is Channel -> {
                val heading = Math.toRadians(rotationDeg)
                val nodes = listOf(
                    PathNode(x, y, 0.0, 0.0, corner = false),
                    PathNode(x + cos(heading), y + sin(heading), 1.0, 1.0, corner = false),
                )
                val skew = tan(Math.toRadians(skewDeg.coerceIn(-60.0, 60.0)))
                Sweep.visual(
                    shear(shape.outer, skew), shear(shape.inner, skew), nodes, 0.0, grade, terrain, profile,
                    shape.widthM, shape.heightM, shape.innerWidthM,
                )
            }
        }
    }

    private fun sweepTubes(
        shape: Tubes,
        nodes: List<PathNode>,
        signedOffset: Double,
        grade: GradeSpec,
        terrain: (Double, Double) -> Double?,
        profileAt: (Double) -> Double?,
    ): SweepMesh? {
        val parts = ArrayList<SweepMesh>()
        for (center in shape.centers) {
            val mesh = Sweep.visual(
                shift(shape.outer, center), shift(shape.inner, center), nodes, signedOffset, grade, terrain, profileAt,
                shape.widthM, shape.heightM, shape.innerWidthM,
            ) ?: continue
            parts += mesh
        }
        return merge(parts, shape.widthM, shape.heightM, shape.innerWidthM)
    }

    private fun seat(mesh: SweepMesh?, ground: Double?, grade: GradeSpec): SweepMesh? {
        if (mesh == null) return null
        if (grade.mode == GradeMode.HYDRAULIC) {
            val lift = if (grade.buried) 0.0 else HydraulicGrade.SURFACE_SEAT
            if (lift == 0.0) return mesh
            return copyMesh(mesh, Sweep.offsetZ(mesh.triangles, lift))
        }
        val lifted = Sweep.liftTriangles(mesh.triangles, ground)
        if (lifted === mesh.triangles) return mesh
        return copyMesh(mesh, lifted)
    }

    private fun copyMesh(mesh: SweepMesh, triangles: DoubleArray): SweepMesh = SweepMesh(
        mesh.frames, triangles, mesh.volume, mesh.length, mesh.footprint, mesh.invert, mesh.simplified,
        mesh.channel, mesh.channels, mesh.widthM, mesh.heightM, mesh.innerWidthM,
    ).also { it.hydraulic = mesh.hydraulic }

    private sealed interface Shape {
        val preview: List<List<SectionPoint>>
        val widthM: Double
        val heightM: Double
        val innerWidthM: Double
    }

    private class Channel(
        val outer: List<SectionPoint>,
        val inner: List<SectionPoint>,
        override val widthM: Double,
        override val heightM: Double,
        override val innerWidthM: Double,
    ) : Shape {
        override val preview: List<List<SectionPoint>> = listOf(outer, inner).filter { it.size >= 3 }
    }

    private class Tubes(
        val outer: List<SectionPoint>,
        val inner: List<SectionPoint>,
        val centers: List<Double>,
        override val widthM: Double,
        override val heightM: Double,
        override val innerWidthM: Double,
    ) : Shape {
        override val preview: List<List<SectionPoint>> = centers.flatMap { center ->
            listOf(shift(outer, center), shift(inner, center))
        }
    }

    private class Box(
        val outer: List<SectionPoint>,
        val inner: List<SectionPoint>,
        val depth: Double,
        val wall: Double,
        override val widthM: Double,
        override val heightM: Double,
        override val innerWidthM: Double,
    ) : Shape {
        override val preview: List<List<SectionPoint>> = listOf(outer, inner)

        fun mesh(x: Double, y: Double, rotationDeg: Double, skewDeg: Double, grade: GradeSpec, terrainZ: Double?, profileZ: Double?): SweepMesh? {
            val bottom = sole(grade, depth, terrainZ, profileZ)
            val floorTop = bottom + wall.coerceAtMost(depth * 0.45)
            val floor = Sweep.extrude(outer, emptyList(), x, y, rotationDeg, skewDeg, bottom, floorTop, widthM, heightM, innerWidthM)
            val walls = Sweep.extrude(outer, inner, x, y, rotationDeg, skewDeg, floorTop, bottom + depth, widthM, heightM, innerWidthM)
            val grate = grate(inner, x, y, rotationDeg, skewDeg, bottom + depth, widthM, heightM, innerWidthM)
            return joinExtrusions(listOfNotNull(floor, walls, grate), widthM, heightM, innerWidthM)
        }

        /** Bars across the opening, sitting on the rim, so a catch basin reads as a grate. */
        private fun grate(
            opening: List<SectionPoint>,
            x: Double,
            y: Double,
            rotationDeg: Double,
            skewDeg: Double,
            top: Double,
            widthM: Double,
            heightM: Double,
            innerWidthM: Double,
        ): SweepMesh? {
            if (opening.size < 3) return null
            val minX = opening.minOf { it.x }
            val maxX = opening.maxOf { it.x }
            val minY = opening.minOf { it.y }
            val maxY = opening.maxOf { it.y }
            val spanX = maxX - minX
            val spanY = maxY - minY
            if (spanX < 0.2 || spanY < 0.2) return null
            val across = spanY < spanX
            val span = if (across) spanY else spanX
            val bars = (span / 0.18).toInt().coerceIn(3, 8)
            val parts = ArrayList<SweepMesh>()
            for (i in 0 until bars) {
                val center = (i + 0.5) / bars
                val ring = if (across) {
                    val cy = minY + spanY * center
                    listOf(
                        SectionPoint(minX, cy - 0.035), SectionPoint(maxX, cy - 0.035),
                        SectionPoint(maxX, cy + 0.035), SectionPoint(minX, cy + 0.035),
                    )
                } else {
                    val cx = minX + spanX * center
                    listOf(
                        SectionPoint(cx - 0.035, minY), SectionPoint(cx + 0.035, minY),
                        SectionPoint(cx + 0.035, maxY), SectionPoint(cx - 0.035, maxY),
                    )
                }
                val bar = Sweep.extrude(ring, emptyList(), x, y, rotationDeg, skewDeg, top - 0.04, top + 0.06, widthM, heightM, innerWidthM)
                if (bar != null) parts += bar
            }
            return joinExtrusions(parts, widthM, heightM, innerWidthM)
        }
    }

    private class Apron(
        val outer: List<SectionPoint>,
        val groove: List<SectionPoint>,
        override val widthM: Double,
        override val heightM: Double,
        override val innerWidthM: Double,
    ) : Shape {
        override val preview: List<List<SectionPoint>> = listOf(outer, groove).filter { it.size >= 3 }

        fun mesh(x: Double, y: Double, rotationDeg: Double, skewDeg: Double, grade: GradeSpec, terrainZ: Double?, profileZ: Double?): SweepMesh? {
            val height = heightM
            val bottom = sole(grade, height, terrainZ, profileZ)
            val slabTop = bottom + height * 0.6
            val slab = Sweep.extrude(outer, emptyList(), x, y, rotationDeg, skewDeg, bottom, slabTop, widthM, heightM, innerWidthM)
            val walls = if (groove.size >= 3) {
                Sweep.extrude(outer, groove, x, y, rotationDeg, skewDeg, slabTop, bottom + height, widthM, heightM, innerWidthM)
            } else {
                null
            }
            return joinExtrusions(listOfNotNull(slab, walls), widthM, heightM, innerWidthM)
        }
    }

    private class Head(
        val dn: Double,
        override val widthM: Double,
        override val heightM: Double,
        override val innerWidthM: Double,
        override val preview: List<List<SectionPoint>>,
    ) : Shape {
        fun mesh(x: Double, y: Double, rotationDeg: Double, skewDeg: Double, grade: GradeSpec, terrainZ: Double?, profileZ: Double?): SweepMesh? {
            val built = headSolid(dn)
            val bottom = sole(grade, heightM, terrainZ, profileZ)
            val heading = Math.toRadians(rotationDeg)
            val dx = cos(heading)
            val dy = sin(heading)
            val skew = tan(Math.toRadians(skewDeg.coerceIn(-60.0, 60.0)))
            val soup = built.triangles
            val world = DoubleArray(soup.size)
            var i = 0
            while (i + 2 < soup.size) {
                val lx = soup[i]
                val ly = soup[i + 1]
                val sx = lx + ly * skew
                world[i] = x + sx * dx - ly * dy
                world[i + 1] = y + sx * dy + ly * dx
                world[i + 2] = bottom + soup[i + 2]
                i += 3
            }
            val footprint = built.footprint.map { point ->
                val sx = point.x + point.y * skew
                Point2(x + sx * dx - point.y * dy, y + sx * dy + point.y * dx)
            }
            val channel = built.channel.map { point ->
                val sx = point.x + point.y * skew
                Point2(x + sx * dx - point.y * dy, y + sx * dy + point.y * dx)
            }
            return SweepMesh(
                emptyList(), world, 0.0, 0.0, footprint, listOf(Point2(x, y)), false,
                channel, if (channel.size >= 3) listOf(channel) else emptyList(),
                widthM, heightM, innerWidthM,
            )
        }
    }

    private fun classify(device: DeviceDef, params: Map<String, Double>): Shape? {
        val type = device.outlineType
        if (type == "circular_barrels" || device.subtype == "bueiro") return tubes(device, params)
        if (type == "box" || "caixa" in device.subtype) return box(device, params)
        if (type == "flared_apron" || "dissip" in device.subtype) return apron(device, params)
        if ("boca" in device.subtype) return head(device)
        val ring = resolveSection(device, params)?.points.orEmpty()
        if (ring.size >= 3) return channel(device, params, ring)
        if (device.family == "MFC" || device.subtype == "meio_fio") return curb()
        return null
    }

    private fun channel(device: DeviceDef, params: Map<String, Double>, ring: List<SectionPoint>): Shape? {
        val open = DeviceLook.openMouth(device)
        val mouth = if (open) DeviceLook.withMouth(ring) else ring
        val finish = DeviceLook.finish(device)
        val thick = when {
            open && (finish == DeviceFinish.GRASS || finish == DeviceFinish.EARTH) -> 0.015
            else -> lining(device, params, dn = null)
        }
        val outer = if (thick <= 1e-4) mouth else offsetRing(mouth, thick)
        val width = spanX(outer)
        val height = spanY(outer)
        if (width < 0.02 || height < 0.02) return null
        return Channel(outer, mouth, width, height, spanX(mouth))
    }

    private fun curb(): Channel {
        val width = 0.15
        val height = 0.30
        val ring = listOf(
            SectionPoint(0.0, 0.0),
            SectionPoint(width, 0.0),
            SectionPoint(width, height),
            SectionPoint(0.0, height),
        )
        return Channel(ring, emptyList(), width, height, 0.0)
    }

    private fun tubes(device: DeviceDef, params: Map<String, Double>): Shape? {
        val dn = metres(device, params, "DN_cm") ?: return null
        if (dn < 0.05) return null
        val wall = lining(device, params, dn)
        val count = (raw(device, params, "n_linhas") ?: 1.0).toInt().coerceIn(1, 4)
        val spacing = dn + 0.30
        val centers = if (count == 1) listOf(0.0) else List(count) { index -> (index - (count - 1) / 2.0) * spacing }
        val inner = circle(dn)
        val outer = circle(dn + 2 * wall)
        val width = (count - 1) * spacing + dn + 2 * wall
        val height = dn + 2 * wall
        return Tubes(outer, inner, centers, width, height, dn)
    }

    private fun box(device: DeviceDef, params: Map<String, Double>): Shape? {
        val fromCode = Regex("""(\d{2,4})\D+(\d{2,4})""").find(device.code)?.groupValues
        val depth = metres(device, params, "profundidade_cm") ?: fromCode?.get(1)?.toDouble()?.div(100.0) ?: 1.5
        val along = metres(device, params, "A_cm") ?: metres(device, params, "comprimento_cm")
            ?: fromCode?.get(1)?.toDouble()?.div(100.0) ?: 1.0
        val across = metres(device, params, "largura_interna_cm") ?: metres(device, params, "largura_cm")
            ?: fromCode?.get(2)?.toDouble()?.div(100.0) ?: 1.0
        val wall = (metres(device, params, "paredes_cm") ?: 0.20).coerceIn(0.05, min(along, across) * 0.45)
        if (depth < 0.2 || along < 0.2 || across < 0.2) return null
        val outerL = along + 2 * wall
        val outerW = across + 2 * wall
        return Box(rect(outerL, outerW), rect(along, across), depth, wall, min(outerL, outerW), depth, min(along, across))
    }

    private fun apron(device: DeviceDef, params: Map<String, Double>): Shape? {
        val length = metres(device, params, "comprimento_cm") ?: return null
        val l1 = metres(device, params, "L1_cm") ?: return null
        val l2 = metres(device, params, "L2_cm") ?: return null
        if (length < 0.2 || l2 < 0.2) return null
        val rawPlan = listOf(
            SectionPoint(0.0, -l1 / 2.0),
            SectionPoint(length, -l2 / 2.0),
            SectionPoint(length, l2 / 2.0),
            SectionPoint(0.0, l1 / 2.0),
        )
        val plan = center(rawPlan)
        val inset = min(0.12, min(spanX(plan), spanY(plan)) * 0.15).coerceAtLeast(0.04)
        var groove = offsetRing(plan, -inset)
        if (groove.size < 3 || spanX(groove) >= spanX(plan) - 1e-3) groove = offsetRing(plan, inset)
        if (spanX(groove) >= spanX(plan) - 1e-3) groove = emptyList()
        val height = 0.30
        return Apron(plan, groove, min(spanX(plan), spanY(plan)), height, if (groove.isEmpty()) 0.0 else min(spanX(groove), spanY(groove)))
    }

    private fun head(device: DeviceDef): Head {
        val dn = pipeDn(device)
        val hw = max(dn + 0.90, dn * 2.6)
        val hh = max(dn + 0.55, dn * 1.7)
        val wing = max(0.7, dn * 1.4)
        val flare = tan(Math.toRadians(28.0))
        val thick = 0.16
        val openingBottom = 0.12
        val outer = listOf(
            SectionPoint(-hw / 2.0, 0.0),
            SectionPoint(hw / 2.0, 0.0),
            SectionPoint(hw / 2.0, hh),
            SectionPoint(-hw / 2.0, hh),
        )
        val opening = listOf(
            SectionPoint(-dn / 2.0, openingBottom),
            SectionPoint(dn / 2.0, openingBottom),
            SectionPoint(dn / 2.0, openingBottom + dn),
            SectionPoint(-dn / 2.0, openingBottom + dn),
        )
        val width = hw + 2 * (wing * flare + thick)
        return Head(dn, width, hh, dn, listOf(outer, opening))
    }
}

private fun pipeDn(device: DeviceDef): Double {
    for (code in device.adaptableTo) {
        val digits = Regex("(\\d{2,3})").find(code)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
        if (digits >= 30.0) return digits / 100.0
    }
    return 1.0
}

private data class HeadParts(val triangles: DoubleArray, val footprint: List<Point2>, val channel: List<Point2>)

private fun headSolid(dn: Double): HeadParts {
    val thick = 0.25
    val hw = max(dn + 0.90, dn * 2.6)
    val hh = max(dn + 0.55, dn * 1.7)
    val wingLen = max(0.7, dn * 1.4)
    val flare = tan(Math.toRadians(28.0))
    val wt = 0.16
    val z0 = 0.12
    val z1 = (z0 + dn).coerceAtMost(hh - 0.08)
    val soup = Soup()
    fun frame(x: Double) {
        val y0 = -hw / 2.0
        val y1 = hw / 2.0
        val iy0 = -dn / 2.0
        val iy1 = dn / 2.0
        soup.quad(x, y0, 0.0, x, y1, 0.0, x, y1, z0, x, y0, z0)
        soup.quad(x, y0, z1, x, y1, z1, x, y1, hh, x, y0, hh)
        soup.quad(x, y0, z0, x, iy0, z0, x, iy0, z1, x, y0, z1)
        soup.quad(x, iy1, z0, x, y1, z0, x, y1, z1, x, iy1, z1)
    }
    frame(0.0)
    frame(-thick)
    val y0 = -hw / 2.0
    val y1 = hw / 2.0
    val iy0 = -dn / 2.0
    val iy1 = dn / 2.0
    // Outer edges of the wall, and the four sides of the barrel opening.
    soup.quad(-thick, y0, 0.0, 0.0, y0, 0.0, 0.0, y0, hh, -thick, y0, hh)
    soup.quad(-thick, y1, hh, 0.0, y1, hh, 0.0, y1, 0.0, -thick, y1, 0.0)
    soup.quad(-thick, y0, hh, 0.0, y0, hh, 0.0, y1, hh, -thick, y1, hh)
    soup.quad(-thick, y0, 0.0, -thick, y1, 0.0, 0.0, y1, 0.0, 0.0, y0, 0.0)
    soup.quad(-thick, iy0, z0, 0.0, iy0, z0, 0.0, iy0, z1, -thick, iy0, z1)
    soup.quad(-thick, iy1, z1, 0.0, iy1, z1, 0.0, iy1, z0, -thick, iy1, z0)
    soup.quad(-thick, iy0, z1, 0.0, iy0, z1, 0.0, iy1, z1, -thick, iy1, z1)
    soup.quad(-thick, iy0, z0, -thick, iy1, z0, 0.0, iy1, z0, 0.0, iy0, z0)
    val wingTop = hh * 0.72
    fun wing(sign: Double) {
        val inner0 = sign * hw / 2.0
        val outer0 = sign * (hw / 2.0 + wt)
        val inner1 = sign * (hw / 2.0 + wingLen * flare)
        val outer1 = sign * (hw / 2.0 + wingLen * flare + wt)
        val x1 = wingLen
        soup.quad(0.0, inner0, 0.0, x1, inner1, 0.0, x1, outer1, 0.0, 0.0, outer0, 0.0)
        soup.quad(0.0, inner0, wingTop, 0.0, outer0, wingTop, x1, outer1, wingTop, x1, inner1, wingTop)
        soup.quad(0.0, inner0, 0.0, 0.0, inner0, wingTop, x1, inner1, wingTop, x1, inner1, 0.0)
        soup.quad(0.0, outer0, wingTop, 0.0, outer0, 0.0, x1, outer1, 0.0, x1, outer1, wingTop)
        soup.quad(x1, inner1, 0.0, x1, inner1, wingTop, x1, outer1, wingTop, x1, outer1, 0.0)
        soup.quad(0.0, outer0, 0.0, 0.0, outer0, wingTop, 0.0, inner0, wingTop, 0.0, inner0, 0.0)
    }
    wing(1.0)
    wing(-1.0)
    val s = 1.0
    val footprint = listOf(
        Point2(-thick, s * (hw / 2.0 + wt)),
        Point2(wingLen, s * (hw / 2.0 + wingLen * flare + wt)),
        Point2(wingLen, s * (hw / 2.0 + wingLen * flare)),
        Point2(0.0, s * hw / 2.0),
        Point2(0.0, -hw / 2.0),
        Point2(wingLen, -(hw / 2.0 + wingLen * flare)),
        Point2(wingLen, -(hw / 2.0 + wingLen * flare + wt)),
        Point2(-thick, -(hw / 2.0 + wt)),
    )
    val channel = listOf(
        Point2(-thick, -dn / 2.0),
        Point2(0.0, -dn / 2.0),
        Point2(0.0, dn / 2.0),
        Point2(-thick, dn / 2.0),
    )
    return HeadParts(soup.toArray(), footprint + footprint.first(), channel + channel.first())
}

private class Soup {
    private val values = ArrayList<Double>()

    fun quad(
        ax: Double, ay: Double, az: Double,
        bx: Double, by: Double, bz: Double,
        cx: Double, cy: Double, cz: Double,
        dx: Double, dy: Double, dz: Double,
    ) {
        tri(ax, ay, az, bx, by, bz, cx, cy, cz)
        tri(ax, ay, az, cx, cy, cz, dx, dy, dz)
    }

    fun tri(
        ax: Double, ay: Double, az: Double,
        bx: Double, by: Double, bz: Double,
        cx: Double, cy: Double, cz: Double,
    ) {
        val ux = bx - ax
        val uy = by - ay
        val uz = bz - az
        val vx = cx - ax
        val vy = cy - ay
        val vz = cz - az
        val nx = uy * vz - uz * vy
        val ny = uz * vx - ux * vz
        val nz = ux * vy - uy * vx
        if (nx * nx + ny * ny + nz * nz < 1e-16) return
        values += ax; values += ay; values += az
        values += bx; values += by; values += bz
        values += cx; values += cy; values += cz
    }

    fun toArray(): DoubleArray = values.toDoubleArray()
}

private fun joinExtrusions(parts: List<SweepMesh>, widthM: Double, heightM: Double, innerWidthM: Double): SweepMesh? {
    if (parts.isEmpty()) return null
    // A grate bar is the last piece and is only a few centimetres wide. The plan is the largest ring.
    val host = parts.maxBy { planSpan(it.footprint) }
    val opening = parts.filter { it.channel.size >= 4 }.maxByOrNull { planSpan(it.channel) }
    val channels = parts.map { it.channel }.filter { it.size >= 3 }
    return SweepMesh(
        host.frames, joinArrays(parts.map { it.triangles }), 0.0, host.length, host.footprint, host.invert, false,
        opening?.channel ?: host.channel, if (channels.isEmpty()) host.channels else channels,
        widthM, heightM, innerWidthM,
    )
}

private fun planSpan(points: List<Point2>): Double {
    if (points.isEmpty()) return 0.0
    val dx = points.maxOf { it.x } - points.minOf { it.x }
    val dy = points.maxOf { it.y } - points.minOf { it.y }
    return max(dx, dy)
}

private fun merge(parts: List<SweepMesh>, widthM: Double, heightM: Double, innerWidthM: Double): SweepMesh? {
    if (parts.isEmpty()) return null
    val first = parts.first()
    val last = parts.last()
    val channels = parts.map { it.channel }.filter { it.size >= 3 }
    val middle = parts[parts.size / 2]
    return SweepMesh(
        middle.frames, joinArrays(parts.map { it.triangles }), 0.0, middle.length, ribbon(first, last), middle.invert, false,
        channels.firstOrNull().orEmpty(), channels, widthM, heightM, innerWidthM,
    ).also { it.hydraulic = parts.firstNotNullOfOrNull { part -> part.hydraulic } }
}

private fun ribbon(first: SweepMesh, last: SweepMesh): List<Point2> {
    val a = first.footprint
    val b = last.footprint
    if (a.size < 4 || b.size < 4 || a.size % 2 != 0 || b.size % 2 != 0) return last.footprint.ifEmpty { a }
    val left = a.subList(0, a.size / 2)
    val rightForward = b.subList(b.size / 2, b.size).asReversed()
    return left + rightForward.asReversed()
}

private fun joinArrays(parts: List<DoubleArray>): DoubleArray {
    val cap = Sweep.MAX_TRIANGLES * 9
    var count = 0
    for (part in parts) count += part.size
    val out = DoubleArray(min(count, cap))
    var at = 0
    for (part in parts) {
        var i = 0
        while (i < part.size && at < out.size) out[at++] = part[i++]
        if (at >= out.size) break
    }
    return if (at == out.size) out else out.copyOf(at)
}

private fun sole(grade: GradeSpec, height: Double, terrainZ: Double?, profileZ: Double?): Double {
    val reference = Sweep.gradeElevation(grade, 0.0, 1.0, 0.0, terrainZ, profileZ)
    return when (grade.mode) {
        GradeMode.SURFACE_TOP, GradeMode.PROFILE -> reference - height
        GradeMode.HYDRAULIC -> reference - height - if (grade.buried) grade.minCover else 0.0
        else -> reference
    }
}

private fun shear(ring: List<SectionPoint>, skew: Double): List<SectionPoint> =
    if (skew == 0.0) ring else ring.map { SectionPoint(it.x + it.y * skew, it.y) }

private fun shift(ring: List<SectionPoint>, dx: Double): List<SectionPoint> =
    if (dx == 0.0) ring else ring.map { SectionPoint(it.x + dx, it.y) }

private fun center(ring: List<SectionPoint>): List<SectionPoint> {
    val cx = (ring.minOf { it.x } + ring.maxOf { it.x }) / 2.0
    val cy = (ring.minOf { it.y } + ring.maxOf { it.y }) / 2.0
    return ring.map { SectionPoint(it.x - cx, it.y - cy) }
}

private fun rect(length: Double, width: Double): List<SectionPoint> {
    val hx = length / 2.0
    val hy = width / 2.0
    return listOf(
        SectionPoint(-hx, -hy),
        SectionPoint(hx, -hy),
        SectionPoint(hx, hy),
        SectionPoint(-hx, hy),
    )
}

private fun spanX(ring: List<SectionPoint>): Double = if (ring.isEmpty()) 0.0 else ring.maxOf { it.x } - ring.minOf { it.x }

private fun spanY(ring: List<SectionPoint>): Double = if (ring.isEmpty()) 0.0 else ring.maxOf { it.y } - ring.minOf { it.y }

/** Positive [distance] moves a ring outward. A result that shrinks is flipped once. */
internal fun offsetRing(ring: List<SectionPoint>, distance: Double): List<SectionPoint> {
    if (ring.size < 3 || abs(distance) < 1e-9) return ring
    val once = offsetOnce(ring, distance)
    val before = max(spanX(ring), spanY(ring))
    val after = max(spanX(once), spanY(once))
    if (distance > 0.0 && after + 1e-4 < before) return offsetOnce(ring, -distance)
    if (distance < 0.0 && after > before + 1e-4) return offsetOnce(ring, -distance)
    return once
}

private fun offsetOnce(ring: List<SectionPoint>, distance: Double): List<SectionPoint> {
    val n = ring.size
    var area = 0.0
    for (i in ring.indices) {
        val a = ring[i]
        val b = ring[(i + 1) % n]
        area += a.x * b.y - b.x * a.y
    }
    val ccw = area >= 0.0
    fun outward(a: SectionPoint, b: SectionPoint): Pair<Double, Double> {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = hypot(dx, dy)
        if (len < 1e-9) return 0.0 to 0.0
        return if (ccw) (dy / len) to (-dx / len) else (-dy / len) to (dx / len)
    }
    val out = ArrayList<SectionPoint>(n)
    for (i in 0 until n) {
        val previous = ring[(i + n - 1) % n]
        val current = ring[i]
        val next = ring[(i + 1) % n]
        val n1 = outward(previous, current)
        val n2 = outward(current, next)
        var mx = n1.first + n2.first
        var my = n1.second + n2.second
        val len = hypot(mx, my)
        if (len < 1e-9) {
            mx = n1.first
            my = n1.second
        } else {
            mx /= len
            my /= len
        }
        val along = mx * n1.first + my * n1.second
        val scale = when {
            abs(along) < 0.2 -> distance / 0.2
            else -> distance / along
        }.coerceIn(-abs(distance) * 4, abs(distance) * 4)
        out += SectionPoint(current.x + mx * scale, current.y + my * scale)
    }
    return out
}

private fun raw(device: DeviceDef, overrides: Map<String, Double>, key: String): Double? =
    overrides[key] ?: device.parameters.firstOrNull { it.key == key }?.number

private fun metres(device: DeviceDef, overrides: Map<String, Double>, key: String): Double? {
    val value = raw(device, overrides, key) ?: return null
    return when {
        key.endsWith("_mm") -> value / 1000.0
        key.endsWith("_cm") -> value / 100.0
        else -> value
    }
}

/** How a catalog device is drawn: open or closed, and the colour of its lining. */
enum class DeviceFinish { GRASS, CONCRETE, STONE, EARTH, METAL }

object DeviceLook {
    fun finish(device: DeviceDef): DeviceFinish {
        val text = buildString {
            append(device.code.lowercase()).append(' ')
            append(device.name.lowercase()).append(' ')
            append(device.subtype.lowercase()).append(' ')
            append(device.family.lowercase()).append(' ')
            for (material in device.materials) append(material.name.lowercase()).append(' ')
        }
        val id = (device.code + " " + device.name).lowercase()
        val grass = "grama" in text || "veget" in text
        val concrete = "concreto" in text
        if (grass && ("grama" in id || !concrete)) return DeviceFinish.GRASS
        if ("pedra" in text && "concreto" !in id) return DeviceFinish.STONE
        if ("solo" in text && !concrete) return DeviceFinish.EARTH
        if (concrete) return DeviceFinish.CONCRETE
        if (device.outlineType.endsWith("_open")) return DeviceFinish.EARTH
        return DeviceFinish.CONCRETE
    }

    fun rgb(finish: DeviceFinish): Int = when (finish) {
        DeviceFinish.GRASS -> 0x3E8F45
        DeviceFinish.CONCRETE -> 0xC2C2BA
        DeviceFinish.STONE -> 0x8D7B6A
        DeviceFinish.EARTH -> 0xA67C52
        DeviceFinish.METAL -> 0x8A9096
    }

    /** Open channel (ditch, gutter). Pipes, slabs and boxes stay closed. */
    fun openMouth(device: DeviceDef): Boolean {
        val type = device.outlineType
        if (type.isEmpty()) return false
        if (type.startsWith("slab") || type == "circular_barrels" || type == "box" || type == "flared_apron") return false
        return type.endsWith("_open") || type.endsWith("_lined") || "triangle" in type || "trapezoid" in type
    }

    /** Turns the ring so the free water surface is the edge that closes it. */
    fun withMouth(ring: List<SectionPoint>): List<SectionPoint> {
        if (ring.size < 3) return ring
        val top = ring.maxOf { it.y }
        val height = top - ring.minOf { it.y }
        var best = -1
        var score = 0.0
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[(i + 1) % ring.size]
            val mid = (a.y + b.y) / 2.0
            if (mid < top - 0.02 && mid < top - height * 0.2) continue
            val dx = abs(b.x - a.x)
            val dy = abs(b.y - a.y)
            val next = dx - dy * 3.0
            if (next > score) {
                score = next
                best = i
            }
        }
        if (best < 0 || score < 0.02) return ring
        val start = (best + 1) % ring.size
        return List(ring.size) { ring[(start + it) % ring.size] }
    }
}

private fun lining(device: DeviceDef, params: Map<String, Double>, dn: Double?): Double {
    val stated = metres(device, params, "t_cm")
        ?: metres(device, params, "parede_cm")
        ?: metres(device, params, "paredes_cm")
    if (stated != null && stated > 0.0) return stated
    if (dn != null) return max(0.06, min(0.12, dn * 0.1))
    return 0.08
}
