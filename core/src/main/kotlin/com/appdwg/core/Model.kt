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

data class CgPoint(val name: String, val code: String?, val position: Point3)

data class CoordinateSystemInfo(val name: String?, val epsgCode: String?, val horizontalDatum: String?)

data class LandXmlProject(
    val linearUnit: String?,
    val coordinateSystem: CoordinateSystemInfo?,
    val surfaces: List<Surface>,
    val alignments: List<Alignment>,
    val cgPoints: List<CgPoint>,
    /** Things the reader skipped or could not interpret, in Portuguese for display. */
    val warnings: List<String>,
) {
    val bounds: Bounds?
        get() {
            var b: Bounds? = null
            surfaces.forEach { s -> s.bounds?.let { b = it.union(b) } }
            alignments.forEach { a -> a.bounds?.let { b = it.union(b) } }
            Bounds.of(cgPoints.asSequence().map { Point2(it.position.x, it.position.y) })?.let { b = it.union(b) }
            return b
        }
}
