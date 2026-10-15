package com.appdwg.core

/** Plan coordinate. x is Easting, y is Northing (LandXML stores them as "N E"). */
data class Point2(val x: Double, val y: Double)

data class Point3(val x: Double, val y: Double, val z: Double)

data class Bounds(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
    val width get() = maxX - minX
    val height get() = maxY - minY
    val center get() = Point2((minX + maxX) / 2, (minY + maxY) / 2)

    fun union(other: Bounds?): Bounds =
        if (other == null) this
        else Bounds(minOf(minX, other.minX), minOf(minY, other.minY), maxOf(maxX, other.maxX), maxOf(maxY, other.maxY))

    companion object {
        fun of(xs: Sequence<Point2>): Bounds? {
            var minX = Double.POSITIVE_INFINITY
            var minY = Double.POSITIVE_INFINITY
            var maxX = Double.NEGATIVE_INFINITY
            var maxY = Double.NEGATIVE_INFINITY
            var any = false
            for (p in xs) {
                any = true
                if (p.x < minX) minX = p.x
                if (p.y < minY) minY = p.y
                if (p.x > maxX) maxX = p.x
                if (p.y > maxY) maxY = p.y
            }
            return if (any) Bounds(minX, minY, maxX, maxY) else null
        }
    }
}

/**
 * Plan line from a CAD drawing (DXF line, polyline, arc), kept as drawn. [color] is ARGB, or null for
 * the foreground color (CAD color 7). [byLayer] is true when the color comes from the layer, so it
 * follows a change of the layer color.
 */
class Polyline(
    val layer: String,
    val xs: DoubleArray,
    val ys: DoubleArray,
    val closed: Boolean,
    val color: Int? = null,
    val byLayer: Boolean = false,
    /** Elevations of the points, for 3D lines; null for flat 2D drawing. */
    val zs: DoubleArray? = null,
) {
    /** Extent of the points, worked out once (the plan asks for it on every frame). */
    val bounds: Bounds? by lazy {
        if (xs.isEmpty()) null else {
            var x0 = xs[0]; var x1 = xs[0]; var y0 = ys[0]; var y1 = ys[0]
            for (i in 1 until xs.size) { val x = xs[i]; val y = ys[i]; if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y }
            Bounds(x0, y0, x1, y1)
        }
    }
}

/**
 * Text from a CAD drawing. Placed at ([x], [y]) by [hAlign] (0 left, 1 centre, 2 right) and
 * [vAlign]; [height] is in drawing units and [rotation] in radians, counter-clockwise from east.
 */
class PlanText(
    val layer: String,
    val color: Int?,
    val byLayer: Boolean,
    val x: Double,
    val y: Double,
    val height: Double,
    val rotation: Double,
    val text: String,
    val hAlign: Int,
    val vAlign: Int,
) {
    companion object {
        const val BASELINE = 0
        const val BOTTOM = 1
        const val MIDDLE = 2
        const val TOP = 3
    }
}

/** Filled area from a CAD hatch: its boundary loops, filled even-odd so islands stay open. */
class Hatch(val layer: String, val color: Int?, val byLayer: Boolean, val loops: List<Polyline>)

/**
 * Raster image placed in the drawing (an orthophoto, for example). The pixels are in [file], outside
 * the drawing. Image pixel (0, 0) is the upper-left corner; the lower-left corner is at
 * ([originX], [originY]), and [pixelU] / [pixelV] are one pixel along the width / height.
 * [boundary] is the visible (clipped) outline in plan coordinates.
 */
class PlanImage(
    val layer: String,
    val file: String,
    val originX: Double,
    val originY: Double,
    val pixelU: Point2,
    val pixelV: Point2,
    val widthPx: Int,
    val heightPx: Int,
    val boundary: Polyline,
) {
    /** File name without folders (DXF stores Windows paths). */
    val fileName get() = file.substringAfterLast('\\').substringAfterLast('/')
}

data class CgPoint(val name: String, val code: String?, val position: Point3)

/** Field/COGO point the user imported or created, kept on the "_POINTS" layer. [z] is NaN when unknown. */
data class CogoPoint(val number: String, val description: String, val x: Double, val y: Double, val z: Double)

/** Layer of the user's COGO points. */
const val POINTS_LAYER = "_POINTS"

data class CoordinateSystemInfo(val name: String?, val epsgCode: String?, val horizontalDatum: String?)

data class LandXmlProject(
    val linearUnit: String?,
    val coordinateSystem: CoordinateSystemInfo?,
    val surfaces: List<Surface>,
    val alignments: List<Alignment>,
    val cgPoints: List<CgPoint>,
    /** Things the reader skipped or could not interpret, in Portuguese for display. */
    val warnings: List<String>,
    /** Drawing lines, texts and hatches; only DXF files have them. */
    val polylines: List<Polyline> = emptyList(),
    val texts: List<PlanText> = emptyList(),
    val hatches: List<Hatch> = emptyList(),
    val images: List<PlanImage> = emptyList(),
    /** Drawings referenced from other files (xrefs), with where they are placed. */
    val xrefs: List<XrefDrawing> = emptyList(),
    /** Layer states and colors (ARGB, null for the foreground color) from the drawing. */
    val layersOff: Set<String> = emptySet(),
    val layersFrozen: Set<String> = emptySet(),
    val layerColors: Map<String, Int?> = emptyMap(),
    /** COGO points imported or created by the user, on [POINTS_LAYER]. */
    val points: List<CogoPoint> = emptyList(),
    /** 3D models (OBJ, IFC…) already placed in project coordinates. */
    val models: List<ModelMesh> = emptyList(),
) {
    /** CAD layers that hold something to show, sorted by name. */
    val layers: List<String> by lazy {
        (polylines.map { it.layer } + texts.map { it.layer } + hatches.map { it.layer } + images.map { it.layer } +
            (if (polylines.isEmpty() && texts.isEmpty()) emptyList() else surfaces.map { it.name }) +
            (if (points.isEmpty()) emptyList() else listOf(POINTS_LAYER)) + models.map { it.layer })
            .distinct().sortedBy { it.lowercase() }
    }

    /** Extent of everything in the project, worked out once: the plan reads it on every frame. */
    val bounds: Bounds? by lazy {
            var b: Bounds? = null
            surfaces.forEach { s -> s.bounds?.let { b = it.union(b) } }
            alignments.forEach { a -> a.bounds?.let { b = it.union(b) } }
            polylines.forEach { l -> l.bounds?.let { b = it.union(b) } }
            hatches.forEach { h -> h.loops.forEach { l -> l.bounds?.let { b = it.union(b) } } }
            images.forEach { i -> i.boundary.bounds?.let { b = it.union(b) } }
            Bounds.of(texts.asSequence().map { Point2(it.x, it.y) })?.let { b = it.union(b) }
            Bounds.of(cgPoints.asSequence().map { Point2(it.position.x, it.position.y) })?.let { b = it.union(b) }
            Bounds.of(points.asSequence().map { Point2(it.x, it.y) })?.let { b = it.union(b) }
            for (m in models) Bounds.of((0 until m.positions.size / 3).asSequence().map { Point2(m.positions[3 * it], m.positions[3 * it + 1]) })?.let { b = it.union(b) }
            b
    }
}
