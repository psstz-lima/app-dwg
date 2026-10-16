package com.appdwg.core.drainage

import com.appdwg.core.Point2
import kotlin.math.abs
import kotlin.math.atan2
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

    /**
     * One straight placement on a flat grade, used to measure the solid.
     * A point device stands at the origin. A linear device runs 2 m along X.
     */
    fun reference(device: DeviceDef): SweepMesh? {
        val grade = GradeSpec(GradeMode.FIXED, z0 = 0.0, z1 = 0.0)
        return if (device.category == DeviceCategory.POINT) {
            point(device, emptyMap(), 0.0, 0.0, 0.0, 0.0, grade, 0.0, null)
        } else {
            linear(
                device, emptyMap(),
                listOf(
                    PathNode(0.0, 0.0, 0.0, 0.0, corner = false),
                    PathNode(2.0, 0.0, 2.0, 2.0, corner = false),
                ),
                0.0, grade, { _, _ -> 0.0 }, { null },
            )
        }
    }

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
            is Gallery -> sweepGallery(shape, nodes, signedOffset, grade, terrain, profileAt)
            is Box, is Apron, is Head, is Baffle -> null
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
            is Gallery -> {
                val heading = Math.toRadians(rotationDeg)
                val nodes = listOf(
                    PathNode(x, y, 0.0, 0.0, corner = false),
                    PathNode(x + cos(heading), y + sin(heading), 1.0, 1.0, corner = false),
                )
                val skew = tan(Math.toRadians(skewDeg.coerceIn(-60.0, 60.0)))
                val sheared = Gallery(
                    shape.parts.map { part ->
                        Channel(
                            shear(part.outer, skew), shear(part.inner, skew),
                            part.widthM, part.heightM, part.innerWidthM,
                        )
                    },
                    shape.widthM, shape.heightM, shape.innerWidthM,
                )
                sweepGallery(sheared, nodes, 0.0, grade, terrain, profile)
            }
            is Baffle -> seat(shape.mesh(x, y, rotationDeg, skewDeg, grade, terrainZ, profileZ), terrainZ, grade)
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

    private fun sweepGallery(
        shape: Gallery,
        nodes: List<PathNode>,
        signedOffset: Double,
        grade: GradeSpec,
        terrain: (Double, Double) -> Double?,
        profileAt: (Double) -> Double?,
    ): SweepMesh? {
        val parts = ArrayList<SweepMesh>()
        for (part in shape.parts) {
            val mesh = Sweep.visual(
                part.outer, part.inner, nodes, signedOffset, grade, terrain, profileAt,
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

    /** Several swept pieces: cells side by side, a slab on its feet, a cradle and its haunches. */
    private class Gallery(
        val parts: List<Channel>,
        override val widthM: Double,
        override val heightM: Double,
        override val innerWidthM: Double,
    ) : Shape {
        override val preview: List<List<SectionPoint>> =
            parts.flatMap { part -> listOf(part.outer, part.inner) }.filter { it.size >= 3 }
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
        val grateOn: Boolean = true,
    ) : Shape {
        override val preview: List<List<SectionPoint>> = listOf(outer, inner)

        fun mesh(x: Double, y: Double, rotationDeg: Double, skewDeg: Double, grade: GradeSpec, terrainZ: Double?, profileZ: Double?): SweepMesh? {
            val bottom = sole(grade, depth, terrainZ, profileZ)
            val floorTop = bottom + wall.coerceAtMost(depth * 0.45)
            val floor = Sweep.extrude(outer, emptyList(), x, y, rotationDeg, skewDeg, bottom, floorTop, widthM, heightM, innerWidthM)
            val walls = Sweep.extrude(outer, inner, x, y, rotationDeg, skewDeg, floorTop, bottom + depth, widthM, heightM, innerWidthM)
            val grate = if (grateOn) grate(inner, x, y, rotationDeg, skewDeg, bottom + depth, widthM, heightM, innerWidthM) else null
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
        val hw: Double,
        val hh: Double,
        val thick: Double,
        val wing: Double,
        override val widthM: Double,
        override val heightM: Double,
        override val innerWidthM: Double,
        override val preview: List<List<SectionPoint>>,
    ) : Shape {
        fun mesh(x: Double, y: Double, rotationDeg: Double, skewDeg: Double, grade: GradeSpec, terrainZ: Double?, profileZ: Double?): SweepMesh? {
            val built = headSolid(dn, hw, hh, thick, wing)
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
                emptyList(), world, Sweep.enclosedVolume(world), 0.0, footprint, listOf(Point2(x, y)), false,
                channel, if (channel.size >= 3) listOf(channel) else emptyList(),
                widthM, heightM, innerWidthM,
            )
        }
    }

    /** Stilling basin: flared slab, walls, and the baffle blocks the album counts. */
    private class Baffle(
        val length: Double,
        val widthIn: Double,
        val widthOut: Double,
        val depth: Double,
        val blockAlong: Double,
        val blockAcross: Double,
        val blocks: Int,
        override val widthM: Double,
        override val heightM: Double,
        override val innerWidthM: Double,
    ) : Shape {
        override val preview: List<List<SectionPoint>> = listOf(
            listOf(
                SectionPoint(0.0, -widthIn / 2.0),
                SectionPoint(length, -widthOut / 2.0),
                SectionPoint(length, widthOut / 2.0),
                SectionPoint(0.0, widthIn / 2.0),
            ),
        )

        fun mesh(x: Double, y: Double, rotationDeg: Double, skewDeg: Double, grade: GradeSpec, terrainZ: Double?, profileZ: Double?): SweepMesh? {
            val plan = center(preview.first())
            val inset = min(0.12, min(spanX(plan), spanY(plan)) * 0.12).coerceAtLeast(0.04)
            var groove = offsetRing(plan, -inset)
            if (groove.size < 3 || spanX(groove) >= spanX(plan) - 1e-3) groove = emptyList()
            val bottom = sole(grade, depth, terrainZ, profileZ)
            val slabTop = bottom + min(0.15, depth * 0.4)
            val slab = Sweep.extrude(plan, emptyList(), x, y, rotationDeg, skewDeg, bottom, slabTop, widthM, heightM, innerWidthM)
            val walls = if (groove.size >= 3) {
                Sweep.extrude(plan, groove, x, y, rotationDeg, skewDeg, slabTop, bottom + depth, widthM, heightM, innerWidthM)
            } else {
                null
            }
            val parts = ArrayList<SweepMesh>()
            if (slab != null) parts += slab
            if (walls != null) parts += walls
            val count = blocks.coerceIn(1, 16)
            val along = blockAlong.coerceIn(0.08, length * 0.4)
            val across = blockAcross.coerceIn(0.08, min(widthIn, widthOut) * 0.6)
            for (i in 0 until count) {
                val cx = -length / 2.0 + length * (i + 0.5) / count
                val ring = listOf(
                    SectionPoint(cx - along / 2.0, -across / 2.0),
                    SectionPoint(cx + along / 2.0, -across / 2.0),
                    SectionPoint(cx + along / 2.0, across / 2.0),
                    SectionPoint(cx - along / 2.0, across / 2.0),
                )
                val top = slabTop + (depth - (slabTop - bottom)) * 0.72
                val block = Sweep.extrude(ring, emptyList(), x, y, rotationDeg, skewDeg, slabTop, top, widthM, heightM, innerWidthM)
                if (block != null) parts += block
            }
            return joinExtrusions(parts, widthM, heightM, innerWidthM)
        }
    }

    private fun classify(device: DeviceDef, params: Map<String, Double>): Shape? {
        album(device, params)?.let { return it }
        val type = device.outlineType
        if (type == "circular_barrels" || device.subtype == "bueiro") return tubes(device, params)
        if (type == "box" || "caixa" in device.subtype) return box(device, params)
        if (type == "flared_apron" || type == "rect_apron" || "dissip" in device.subtype) return apron(device, params)
        if ("boca" in device.subtype) return head(device)
        val ring = resolveSection(device, params)?.points.orEmpty()
        if (ring.size >= 3) return channel(device, params, ring)
        if (device.family == "MFC" || device.subtype == "meio_fio") return curb(device)
        synthesized(device, params)?.let { return it }
        return hollowMarker()
    }

    /**
     * SZCC 140-35 publishes B and H but not the base (only SZCC 100-25 is legible: 25-50-25).
     * The base is taken as half the top, the same proportion as that drawing.
     */
    private fun synthesized(device: DeviceDef, params: Map<String, Double>): Shape? {
        if (!device.outlineType.startsWith("trapezoid")) return null
        val top = metres(device, params, "B_cm") ?: return null
        val height = metres(device, params, "H_cm") ?: return null
        val bottom = metres(device, params, "b_cm") ?: top / 2.0
        if (top < 0.05 || height < 0.05) return null
        val ring = listOf(
            SectionPoint(-top / 2.0, 0.0),
            SectionPoint(-bottom / 2.0, -height),
            SectionPoint(bottom / 2.0, -height),
            SectionPoint(top / 2.0, 0.0),
        )
        return channel(device, params, ring)
    }

    /** Families whose section is not the catalog ring: culverts, slabs, cradles, inlets, drains. */
    private fun album(device: DeviceDef, params: Map<String, Double>): Shape? {
        val type = device.outlineType
        val sub = device.subtype
        val family = device.family
        return when {
            type == "box_culvert" -> cells(device, params)
            type == "baffle_basin" -> baffle(device, params)
            type == "inlet_plan" -> inlet(device, params)
            type == "slab_over_channel" -> slab(device, params)
            type == "half_pipe_on_cradle" -> halfPipe(device, params)
            type == "arched_tunnel" -> arch(device, params)
            type == "rect_trench" || sub == "dreno_profundo" || sub == "dreno_subsuperficial" -> filterDrain(device, params)
            family == "BERCO" || sub == "berco" -> cradle(device, params)
            family == "CAP9" || sub == "canal_grelha" -> grateCanal(device)
            family == "TC" || sub == "tubo" -> tubes(device, params)
            sub == "secao_tipo" -> trenchType(device, params)
            sub == "camada_drenante" -> blanket()
            sub == "dreno_sub_horizontal" -> perforated()
            sub == "saida_dreno" -> head(device)
            sub == "urbano" -> urban(device)
            family == "MFC" || sub == "meio_fio" -> curb(device)
            else -> null
        }
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

    private fun tubes(device: DeviceDef, params: Map<String, Double>): Shape? {
        val dn = metres(device, params, "DN_cm")
            ?: if (device.family == "TC" || device.subtype == "tubo") 1.0 else null
            ?: return null
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
        val length = metres(device, params, "comprimento_cm") ?: metres(device, params, "C_cm") ?: return null
        val l2 = metres(device, params, "L2_cm") ?: metres(device, params, "largura_cm") ?: return null
        val l1 = metres(device, params, "L1_cm") ?: l2
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
        if (device.subtype == "saida_dreno") return sizedHead(0.15, 0.70, 0.55, 0.22, 0.40)
        val dn = pipeDn(device)
        val albumH = metres(device, emptyMap(), "h_cm")
        val albumW = metres(device, emptyMap(), "M_cm")
        val albumT = metres(device, emptyMap(), "g_cm")
        val albumL = metres(device, emptyMap(), "L_cm")
        if (albumH != null && albumW != null && albumH > 0.4 && albumW > 0.4) {
            val wing = albumL?.let { ((it - albumW) / 2.0).coerceAtLeast(0.4) }
            return sizedHead(dn, albumW, albumH, albumT ?: 0.20, wing)
        }
        return sizedHead(dn, null, null, null, null)
    }

    private fun sizedHead(dn: Double, hw0: Double?, hh0: Double?, thick0: Double?, wing0: Double?): Head {
        val hw = hw0 ?: max(dn + 0.90, dn * 2.6)
        val hh = hh0 ?: max(dn + 0.55, dn * 1.7)
        val thick = thick0 ?: 0.25
        val wing = wing0 ?: max(0.7, dn * 1.4)
        val flare = tan(Math.toRadians(28.0))
        val bore = dn.coerceIn(0.08, (hw - 0.2).coerceAtLeast(0.12))
        val z0 = min(0.12, hh * 0.25)
        val outer = listOf(
            SectionPoint(-hw / 2.0, 0.0),
            SectionPoint(hw / 2.0, 0.0),
            SectionPoint(hw / 2.0, hh),
            SectionPoint(-hw / 2.0, hh),
        )
        val opening = listOf(
            SectionPoint(-bore / 2.0, z0),
            SectionPoint(bore / 2.0, z0),
            SectionPoint(bore / 2.0, z0 + bore),
            SectionPoint(-bore / 2.0, z0 + bore),
        )
        val width = hw + 2 * (wing * flare + min(0.16, thick))
        return Head(dn, hw, hh, thick, wing, width, hh, bore, listOf(outer, opening))
    }

    /** Hollow cells. Shared walls are one album thickness, and the haunch is the mísula. */
    private fun cells(device: DeviceDef, params: Map<String, Double>): Shape? {
        val length = metres(device, params, "L_cm") ?: return null
        val height = metres(device, params, "H_cm") ?: return null
        if (length < 0.3 || height < 0.3) return null
        val count = (raw(device, params, "n_celulas") ?: 1.0).toInt().coerceIn(1, 6)
        val wall = (metres(device, params, "espessura_cm") ?: 0.15).coerceIn(0.08, min(length, height) * 0.45)
        val haunch = (metres(device, params, "misula_cm") ?: 0.0).coerceIn(0.0, min(length, height) * 0.45)
        val pitch = length + wall
        val total = count * length + (count + 1) * wall
        val origin = -total / 2.0
        val parts = ArrayList<Channel>()
        for (i in 0 until count) {
            val left = if (i == 0) wall else wall / 2.0
            val right = if (i == count - 1) wall else wall / 2.0
            val center = origin + wall + length / 2.0 + i * pitch
            val inner = shift(cellInner(length, height, haunch), center)
            val outer = shift(cellOuter(length, height, wall, left, right, haunch), center)
            parts += Channel(outer, inner, length + left + right, height + 2 * wall, length)
        }
        return Gallery(parts, total, height + 2 * wall, length)
    }

    private fun cellInner(length: Double, height: Double, haunch: Double): List<SectionPoint> {
        val half = length / 2.0
        if (haunch < 0.01) {
            return listOf(
                SectionPoint(-half, 0.0), SectionPoint(half, 0.0),
                SectionPoint(half, height), SectionPoint(-half, height),
            )
        }
        return listOf(
            SectionPoint(-half + haunch, 0.0),
            SectionPoint(half - haunch, 0.0),
            SectionPoint(half, haunch),
            SectionPoint(half, height - haunch),
            SectionPoint(half - haunch, height),
            SectionPoint(-half + haunch, height),
            SectionPoint(-half, height - haunch),
            SectionPoint(-half, haunch),
        )
    }

    private fun cellOuter(
        length: Double,
        height: Double,
        wall: Double,
        left: Double,
        right: Double,
        haunch: Double,
    ): List<SectionPoint> {
        val half = length / 2.0
        val x0 = -half - left
        val x1 = half + right
        val y0 = -wall
        val y1 = height + wall
        if (haunch < 0.01) {
            return listOf(SectionPoint(x0, y0), SectionPoint(x1, y0), SectionPoint(x1, y1), SectionPoint(x0, y1))
        }
        return listOf(
            SectionPoint(x0, y0),
            SectionPoint(x1, y0),
            SectionPoint(x1, haunch),
            SectionPoint(x1, height - haunch),
            SectionPoint(x1, y1),
            SectionPoint(x0, y1),
            SectionPoint(x0, height - haunch),
            SectionPoint(x0, haunch),
        )
    }

    private fun baffle(device: DeviceDef, params: Map<String, Double>): Shape? {
        val length = metres(device, params, "C_cm") ?: return null
        val l1 = metres(device, params, "L1_cm") ?: return null
        val l2 = metres(device, params, "L2_cm") ?: return null
        if (length < 0.2 || l2 < 0.2) return null
        val depth = metres(device, params, "h_cm") ?: 0.40
        val along = metres(device, params, "a_cm") ?: 0.25
        val across = metres(device, params, "b_cm") ?: 0.20
        val count = (raw(device, params, "n_blocos") ?: 6.0).toInt()
        return Baffle(length, l1, l2, depth, along, across, count, min(l1, l2), depth, min(l1, l2) * 0.6)
    }

    /** Curb inlet. a..d are the plan cotas; the throat is open, without a grate. */
    private fun inlet(device: DeviceDef, params: Map<String, Double>): Shape? {
        val along = metres(device, params, "d_cm") ?: return null
        val mouth = metres(device, params, "b_cm") ?: return null
        val outerAcross = metres(device, params, "c_cm") ?: mouth
        val lip = metres(device, params, "a_cm") ?: 0.14
        if (along < 0.2 || mouth < 0.1) return null
        val wall = ((outerAcross - mouth) / 2.0).takeIf { it > 0.05 } ?: 0.10
        val depth = (lip + 0.22).coerceIn(0.25, 0.80)
        val innerW = mouth
        val innerL = along
        return Box(
            rect(innerL + 2 * wall, innerW + 2 * wall), rect(innerL, innerW),
            depth, wall.coerceIn(0.08, 0.25), min(innerL, innerW), depth, min(innerL, innerW), grateOn = false,
        )
    }

    /** Slab 50×21 cm on side feet of 15–20 cm. Three convex pieces, so the shell stays closed. */
    private fun slab(device: DeviceDef, params: Map<String, Double>): Shape {
        val width = metres(device, params, "laje_largura_cm") ?: 0.50
        val thick = metres(device, params, "laje_espessura_cm") ?: 0.21
        val footW = 0.18
        val footH = 0.15
        val deck = filled(-width / 2.0, footH, width / 2.0, footH + thick)
        val left = filled(-width / 2.0 - footW, 0.0, -width / 2.0, footH)
        val right = filled(width / 2.0, 0.0, width / 2.0 + footW, footH)
        val span = width + 2 * footW
        return Gallery(listOf(deck, left, right), span, footH + thick, 0.0)
    }

    /** Half-round channel on the cradle quoted in the drawing note (31.2 cm). */
    private fun halfPipe(device: DeviceDef, params: Map<String, Double>): Shape {
        val diameter = metres(device, params, "D_cm") ?: 0.40
        val wall = 0.025
        val cradleH = 0.08
        val cradleW = 0.312
        val radius = diameter / 2.0
        val lift = cradleH + wall
        val inner = shiftY(semicircle(radius), lift)
        val outer = shiftY(semicircle(radius + wall), lift - wall)
        val pipe = Channel(outer, inner, diameter + 2 * wall, radius + wall + cradleH, diameter)
        val bed = filled(-cradleW / 2.0, 0.0, cradleW / 2.0, cradleH)
        val span = max(cradleW, diameter + 2 * wall)
        return Gallery(listOf(bed, pipe), span, radius + wall + cradleH, diameter)
    }

    /** Three-centred-style arch: straight walls and an elliptical crown, hollow. */
    private fun arch(device: DeviceDef, params: Map<String, Double>): Shape? {
        val width = raw(device, params, "B_m") ?: return null
        val height = raw(device, params, "H_m") ?: return null
        if (width < 0.3 || height < 0.3) return null
        val wall = max(0.15, min(0.25, width * 0.12))
        val rise = min(width / 2.0, height * 0.45)
        val spring = height - rise
        val inner = archRing(width, 0.0, spring, rise)
        val outer = archRing(width + 2 * wall, -wall, spring, rise + wall)
        return Channel(outer, inner, width + 2 * wall, height + wall, width)
    }

    private fun archRing(width: Double, y0: Double, spring: Double, rise: Double): List<SectionPoint> {
        val n = 8
        val points = ArrayList<SectionPoint>(n + 3)
        points += SectionPoint(-width / 2.0, y0)
        points += SectionPoint(width / 2.0, y0)
        points += SectionPoint(width / 2.0, spring)
        for (i in 1..n) {
            val t = Math.PI * i / n
            points += SectionPoint((width / 2.0) * cos(t), spring + rise * sin(t))
        }
        return points
    }

    /** Filter prism with a hollow perforated pipe. The outer ring is the catalog trench. */
    private fun filterDrain(device: DeviceDef, params: Map<String, Double>): Shape? {
        val ring = resolveSection(device, params)?.points?.takeIf { it.size >= 3 } ?: run {
            val w = metres(device, params, "largura_cm") ?: return null
            val h = metres(device, params, "profundidade_cm") ?: return null
            upright(w, h)
        }
        val width = spanX(ring)
        val height = spanY(ring)
        if (width < 0.05 || height < 0.05) return null
        val dn = when {
            device.materials.any { "15" in it.name } -> 0.15
            device.subtype == "dreno_subsuperficial" -> 0.10
            else -> 0.15
        }
        val pipeWall = if (dn <= 0.11) 0.004 else 0.008
        val cover = metres(device, params, "recobrimento_tubo_fundo_cm") ?: 0.10
        val minX = ring.minOf { it.x }
        val maxX = ring.maxOf { it.x }
        val minY = ring.minOf { it.y }
        val maxY = ring.maxOf { it.y }
        val cx = (minX + maxX) / 2.0
        val outerR = dn / 2.0 + pipeWall
        val low = minY + outerR + 0.005
        val high = maxY - outerR - 0.005
        val cy = if (low <= high) (minY + cover + outerR).coerceIn(low, high) else (minY + maxY) / 2.0
        val (shell, hole) = bored(ring, cx, cy, outerR)
        val boreR = dn / 2.0
        val bore = hole.map { point ->
            val dx = point.x - cx
            val dy = point.y - cy
            val len = hypot(dx, dy).coerceAtLeast(1e-6)
            SectionPoint(cx + dx / len * boreR, cy + dy / len * boreR)
        }
        val filter = Channel(shell, hole, width, height, dn)
        val pipe = Channel(hole, bore, dn + 2 * pipeWall, dn + 2 * pipeWall, dn)
        return Gallery(listOf(filter, pipe), width, height, dn)
    }

    /** Concrete cradle: slab under the pipe plus two haunches. a, b, c, d are centimetres. */
    private fun cradle(device: DeviceDef, params: Map<String, Double>): Shape? {
        val dn = metres(device, params, "DN_cm") ?: return null
        val under = unitCm(device, params, "a") ?: return null
        val height = unitCm(device, params, "c") ?: return null
        val width = unitCm(device, params, "d_linha_simples") ?: return null
        if (width < dn || height < under) return null
        val seat = dn.coerceAtMost(width - 0.04)
        val base = filled(-width / 2.0, 0.0, width / 2.0, under)
        val left = filled(-width / 2.0, under, -seat / 2.0, height)
        val right = filled(seat / 2.0, under, width / 2.0, height)
        return Gallery(listOf(base, left, right), width, height, seat)
    }

    /** Chapter 9 is a load class, not a type drawing. The envelopes are the named products. */
    private fun grateCanal(device: DeviceDef): Shape {
        val code = device.code
        val (innerW, innerH, wall) = when {
            "FFD" in code -> Triple(0.20, 0.15, 0.020)
            "CP" in code -> Triple(0.15, 0.18, 0.025)
            else -> Triple(0.10, 0.12, 0.030)
        }
        val inner = listOf(
            SectionPoint(-innerW / 2.0, innerH),
            SectionPoint(-innerW / 2.0, 0.0),
            SectionPoint(innerW / 2.0, 0.0),
            SectionPoint(innerW / 2.0, innerH),
        )
        val outer = offsetRing(inner, wall)
        val channel = Channel(outer, inner, innerW + 2 * wall, innerH + wall, innerW)
        val bars = ArrayList<Channel>()
        bars += channel
        val count = 3
        for (i in 0 until count) {
            val cx = -innerW / 2.0 + innerW * (i + 0.5) / count
            bars += filled(cx - 0.012, innerH - 0.005, cx + 0.012, innerH + 0.02)
        }
        return Gallery(bars, innerW + 2 * wall, innerH + wall + 0.02, innerW)
    }

    /** Open cut: barrel plus the album's side clearance and minimum cover. */
    private fun trenchType(device: DeviceDef, params: Map<String, Double>): Shape {
        val clearance = metres(device, params, "folga_lateral_min_cm") ?: 0.30
        val cover = metres(device, params, "recobrimento_min_cm") ?: 1.0
        val dn = 1.0
        val width = dn + 2 * clearance
        val depth = dn + cover
        val inner = listOf(
            SectionPoint(-width / 2.0, depth),
            SectionPoint(-width / 2.0, 0.0),
            SectionPoint(width / 2.0, 0.0),
            SectionPoint(width / 2.0, depth),
        )
        val outer = offsetRing(inner, 0.15)
        return Channel(outer, inner, spanX(outer), spanY(outer), width)
    }

    /** Draining blanket. The scanned sheet has no cotas; 20 cm is a layer, not a line. */
    private fun blanket(): Shape {
        val width = 2.0
        val thick = 0.20
        return filled(-width / 2.0, 0.0, width / 2.0, thick).let {
            Gallery(listOf(it), width, thick, 0.0)
        }
    }

    /** Sub-horizontal drain: 50 mm perforated pipe, 4 mm wall. */
    private fun perforated(): Tubes {
        val dn = 0.050
        val wall = 0.004
        return Tubes(circle(dn + 2 * wall), circle(dn), listOf(0.0), dn + 2 * wall, dn + 2 * wall, dn)
    }

    /**
     * Urban chambers whose album page is a scan. CLP uses the only figures on that note
     * (100 × 80 × 80 cm, wall 20 cm). The others keep a distinct chamber, not one cube.
     */
    private fun urban(device: DeviceDef): Shape {
        val family = device.family
        val code = device.code
        return when {
            family == "CHAMINÉ" || "CHAMIN" in code -> chamber(0.60, 0.60, 0.80, 0.10, grate = false)
            code.startsWith("PV-DET") -> chamber(0.80, 0.60, 0.20, 0.08, grate = false)
            family == "PV" -> chamber(1.00, 1.00, 1.40, 0.15, grate = false)
            family == "CLP" -> chamber(1.00, 0.80, 0.80, 0.20, grate = false)
            family == "BLD" -> chamber(1.60, 0.45, 0.90, 0.12, grate = true)
            family == "BLC" -> chamber(1.10, 0.40, 0.80, 0.12, grate = true)
            "GC" in code -> chamber(0.90, 0.40, 0.70, 0.10, grate = true)
            else -> chamber(0.90, 0.30, 0.60, 0.10, grate = false)
        }
    }

    private fun chamber(along: Double, across: Double, depth: Double, wall: Double, grate: Boolean): Box {
        return Box(
            rect(along + 2 * wall, across + 2 * wall), rect(along, across),
            depth, wall, min(along, across), depth, min(along, across), grateOn = grate,
        )
    }

    /**
     * Curb and gutter. The drawing vertices are marked needs_review; the radii are the album's
     * R = 3, 5 and 8 cm, and each type's area is its tabulated concrete per metre.
     */
    private fun curb(device: DeviceDef): Shape = when (device.code) {
        "MFC 01" -> curbGutter(0.50, 0.10, 0.15, 0.35, 0.03)
        "MFC 02" -> curbGutter(0.45, 0.08, 0.15, 0.35, 0.05)
        "MFC 03" -> curbGutter(0.28, 0.07, 0.12, 0.20, 0.05)
        "MFC 04" -> curbOnly(0.12, 0.26, 0.03)
        "MFC 05" -> curbOnly(0.15, 0.23, 0.05)
        "MFC 06" -> curbOnly(0.10, 0.23, 0.03)
        "MFC 07" -> curbOnly(0.16, 0.26, 0.08)
        "MFC 08" -> curbOnly(0.20, 0.38, 0.08)
        else -> curbOnly(0.15, 0.30, 0.03)
    }

    private fun curbOnly(width: Double, height: Double, chamfer: Double): Gallery {
        val ring = listOf(
            SectionPoint(0.0, 0.0),
            SectionPoint(width, 0.0),
            SectionPoint(width, height),
            SectionPoint(chamfer, height),
            SectionPoint(0.0, height - chamfer),
        )
        return Gallery(listOf(Channel(ring, emptyList(), width, height, 0.0)), width, height, 0.0)
    }

    private fun curbGutter(gutter: Double, thick: Double, curbW: Double, height: Double, chamfer: Double): Gallery {
        val pan = filled(0.0, 0.0, gutter, thick)
        val curb = Channel(
            listOf(
                SectionPoint(gutter, 0.0),
                SectionPoint(gutter + curbW, 0.0),
                SectionPoint(gutter + curbW, height),
                SectionPoint(gutter + chamfer, height),
                SectionPoint(gutter, height - chamfer),
            ),
            emptyList(), curbW, height, 0.0,
        )
        return Gallery(listOf(pan, curb), gutter + curbW, height, 0.0)
    }

    private fun filled(x0: Double, y0: Double, x1: Double, y1: Double): Channel {
        val ring = listOf(
            SectionPoint(x0, y0), SectionPoint(x1, y0), SectionPoint(x1, y1), SectionPoint(x0, y1),
        )
        return Channel(ring, emptyList(), abs(x1 - x0), abs(y1 - y0), 0.0)
    }

    private fun upright(width: Double, height: Double): List<SectionPoint> = listOf(
        SectionPoint(-width / 2.0, 0.0),
        SectionPoint(width / 2.0, 0.0),
        SectionPoint(width / 2.0, height),
        SectionPoint(-width / 2.0, height),
    )

    private fun semicircle(radius: Double): List<SectionPoint> {
        val n = 12
        return List(n + 1) { i ->
            val angle = Math.PI + Math.PI * i / n
            SectionPoint(radius * cos(angle), radius + radius * sin(angle))
        }
    }

    private fun bored(
        boundary: List<SectionPoint>,
        cx: Double,
        cy: Double,
        radius: Double,
    ): Pair<List<SectionPoint>, List<SectionPoint>> {
        val angles = ArrayList<Double>()
        for (point in boundary) angles += atan2(point.y - cy, point.x - cx)
        for (i in 0 until 12) angles += -Math.PI + 2.0 * Math.PI * i / 12.0
        val sorted = angles.distinctBy { (it * 1e5).toLong() }.sorted()
        val outer = sorted.map { rayOnRect(boundary, cx, cy, it) }
        val hole = sorted.map { angle -> SectionPoint(cx + radius * cos(angle), cy + radius * sin(angle)) }
        return outer to hole
    }

    private fun rayOnRect(rect: List<SectionPoint>, cx: Double, cy: Double, angle: Double): SectionPoint {
        val dx = cos(angle)
        val dy = sin(angle)
        var best = Double.POSITIVE_INFINITY
        var hit = SectionPoint(cx + dx * 0.01, cy + dy * 0.01)
        for (i in rect.indices) {
            val a = rect[i]
            val b = rect[(i + 1) % rect.size]
            val ex = b.x - a.x
            val ey = b.y - a.y
            val det = dx * ey - dy * ex
            if (abs(det) < 1e-12) continue
            val t = ((a.x - cx) * ey - (a.y - cy) * ex) / det
            val u = ((a.x - cx) * dy - (a.y - cy) * dx) / det
            if (t > 1e-6 && u >= -1e-5 && u <= 1.0 + 1e-5 && t < best) {
                best = t
                hit = SectionPoint(cx + dx * t, cy + dy * t)
            }
        }
        return hit
    }

    /** Last resort so a device with no cota is still a hollow shell, not a solid cube. */
    private fun hollowMarker(): Shape {
        val wall = 0.08
        val innerW = 0.36
        val innerH = 0.40
        val hole = listOf(
            SectionPoint(-innerW / 2.0, 0.0),
            SectionPoint(innerW / 2.0, 0.0),
            SectionPoint(innerW / 2.0, innerH),
            SectionPoint(-innerW / 2.0, innerH),
        )
        val outer = offsetRing(hole, wall)
        return Channel(outer, hole, innerW + 2 * wall, innerH + 2 * wall, innerW)
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

private fun headSolid(dn: Double, hw: Double, hh: Double, thick: Double, wingLen: Double): HeadParts {
    val bore = dn.coerceIn(0.08, (hw - 0.2).coerceAtLeast(0.12))
    val flare = tan(Math.toRadians(28.0))
    val wt = min(0.16, thick)
    val z0 = min(0.12, hh * 0.2)
    val z1 = (z0 + bore).coerceAtMost(hh - 0.06).coerceAtLeast(z0 + 0.05)
    val soup = Soup()
    fun frame(x: Double) {
        val y0 = -hw / 2.0
        val y1 = hw / 2.0
        val iy0 = -bore / 2.0
        val iy1 = bore / 2.0
        soup.quad(x, y0, 0.0, x, y1, 0.0, x, y1, z0, x, y0, z0)
        soup.quad(x, y0, z1, x, y1, z1, x, y1, hh, x, y0, hh)
        soup.quad(x, y0, z0, x, iy0, z0, x, iy0, z1, x, y0, z1)
        soup.quad(x, iy1, z0, x, y1, z0, x, y1, z1, x, iy1, z1)
    }
    frame(0.0)
    frame(-thick)
    val y0 = -hw / 2.0
    val y1 = hw / 2.0
    val iy0 = -bore / 2.0
    val iy1 = bore / 2.0
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
        Point2(-thick, -bore / 2.0),
        Point2(0.0, -bore / 2.0),
        Point2(0.0, bore / 2.0),
        Point2(-thick, bore / 2.0),
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
        host.frames, joinArrays(parts.map { it.triangles }), parts.sumOf { it.volume }, host.length, host.footprint, host.invert, false,
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
    val spanned = ribbon(first, last)
    val widest = parts.maxBy { planSpan(it.footprint) }.footprint
    val footprint = if (planSpan(spanned) >= planSpan(widest)) spanned else widest
    return SweepMesh(
        middle.frames, joinArrays(parts.map { it.triangles }), parts.sumOf { it.volume }, middle.length, footprint, middle.invert, false,
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

private fun shiftY(ring: List<SectionPoint>, dy: Double): List<SectionPoint> =
    if (dy == 0.0) ring else ring.map { SectionPoint(it.x, it.y + dy) }

/** Cradle cotas are stored without a unit suffix. Values above 3 are centimetres. */
private fun unitCm(device: DeviceDef, overrides: Map<String, Double>, key: String): Double? {
    val value = raw(device, overrides, key) ?: return null
    return if (abs(value) > 3.0) value / 100.0 else value
}

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
        if (device.subtype == "dreno_profundo" || device.subtype == "dreno_subsuperficial" || device.subtype == "camada_drenante") {
            return DeviceFinish.STONE
        }
        if ("solo" in text || "vala" in text || "aterro" in text || device.subtype == "secao_tipo") {
            if (!concrete) return DeviceFinish.EARTH
        }
        if (!concrete && ("ferro" in text || "fundido" in text || "poliet" in text || "poliprop" in text || "pp/pe" in id)) {
            return DeviceFinish.METAL
        }
        if (device.subtype == "dreno_sub_horizontal") return DeviceFinish.METAL
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
        if (type.startsWith("slab") || type == "circular_barrels" || type == "box" || type == "box_culvert" ||
            type == "flared_apron" || type == "rect_apron" || type == "baffle_basin" ||
            type == "inlet_plan" || type == "arched_tunnel"
        ) {
            return false
        }
        if (type == "stepped_rect_channel" || type == "rect_channel_lined" || type == "half_pipe_on_cradle" || type == "rect_trench") {
            return true
        }
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
    if (device.outlineType == "rect_channel_lined") return 0.12
    return 0.08
}
