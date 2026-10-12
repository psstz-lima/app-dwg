package com.appdwg.core

import org.xmlpull.v1.XmlPullParser

/**
 * Streaming LandXML 1.x reader for the data the app shows: TIN surfaces, alignments with their
 * design profiles, and COGO points. The caller supplies the pull parser (Android's `Xml.newPullParser()`
 * or kXML2 on the JVM) already positioned on its input, with namespace processing off.
 *
 * LandXML writes plan coordinates as "Northing Easting [Elevation]"; everything returned here is
 * converted to x = Easting, y = Northing.
 */
class LandXmlReader(private val parser: XmlPullParser) {

    private val warnings = LinkedHashSet<String>()
    private var linearUnit: String? = null
    private var coordinateSystem: CoordinateSystemInfo? = null
    private val surfaces = ArrayList<Surface>()
    private val rawAlignments = ArrayList<RawAlignment>()
    private val cgPoints = ArrayList<CgPoint>()

    fun read(): LandXmlProject {
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (local()) {
                    "Metric", "Imperial" -> if (linearUnit == null) linearUnit = parser.attr("linearUnit")
                    "CoordinateSystem" -> coordinateSystem = CoordinateSystemInfo(
                        name = parser.attr("name") ?: parser.attr("desc"),
                        epsgCode = parser.attr("epsgCode"),
                        horizontalDatum = parser.attr("horizontalDatum"),
                    )
                    "Surface" -> readSurface()
                    "Alignment" -> readAlignment()
                    "CgPoint" -> readCgPoint()
                }
            }
            event = parser.next()
        }

        val pointsByName = cgPoints.associateBy { it.name }
        val alignments = rawAlignments.map { it.build(pointsByName) }
        return LandXmlProject(linearUnit, coordinateSystem, surfaces, alignments, cgPoints, warnings.toList())
    }

    // region Surfaces

    private fun readSurface() {
        val name = parser.attr("name") ?: "Superfície ${surfaces.size + 1}"
        val depth = parser.depth
        val ids = PointIds()
        val xs = DoubleList()
        val ys = DoubleList()
        val zs = DoubleList()
        // Face corners as point ids; resolved to vertex indices once all points are known.
        val faceIds = IntList()
        var isTin = true

        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            if (event == XmlPullParser.END_TAG && parser.depth == depth) break
            if (event != XmlPullParser.START_TAG) continue
            when (local()) {
                "Definition" -> {
                    val type = parser.attr("surfType")
                    if (type != null && !type.equals("TIN", ignoreCase = true)) isTin = false
                }
                "P" -> {
                    val id = parser.attr("id")
                    val v = numbers(parser.nextText())
                    if (id != null && v.size >= 3) {
                        ids.put(ids.key(id), xs.size)
                        ys.add(v[0]); xs.add(v[1]); zs.add(v[2])
                    }
                }
                "F" -> {
                    val invisible = parser.attr("i") == "1"
                    val corners = parser.nextText().trim().split(WHITESPACE)
                    if (!invisible && corners.size >= 3) for (k in 0..2) faceIds.add(ids.key(corners[k]))
                }
            }
        }

        if (!isTin) {
            warnings.add("Superfície \"$name\" não é TIN e foi ignorada.")
            return
        }
        val faces = IntList()
        var skipped = 0
        val refs = faceIds.toArray()
        for (t in 0 until refs.size / 3) {
            val a = ids.index(refs[3 * t])
            val b = ids.index(refs[3 * t + 1])
            val c = ids.index(refs[3 * t + 2])
            if (a < 0 || b < 0 || c < 0) skipped++ else { faces.add(a); faces.add(b); faces.add(c) }
        }
        if (skipped > 0) warnings.add("Superfície \"$name\": $skipped triângulos com pontos inexistentes foram ignorados.")
        surfaces.add(Surface(name, xs.toArray(), ys.toArray(), zs.toArray(), faces.toArray()))
    }

    // endregion

    // region Alignments

    private class RawAlignment(
        val name: String,
        val startStation: Double,
        val elements: List<RawElement>,
        val profile: Profile?,
        val warnings: MutableSet<String>,
    ) {
        fun build(points: Map<String, CgPoint>): Alignment {
            val built = elements.mapNotNull { it.build(points) ?: run {
                warnings.add("Alinhamento \"$name\": um elemento referencia um ponto inexistente e foi ignorado.")
                null
            } }
            return Alignment(name, startStation, built, profile)
        }
    }

    /** A plan coordinate that is either written inline or refers to a CgPoint by name. */
    private class Coord(val value: Point2?, val ref: String?) {
        fun resolve(points: Map<String, CgPoint>): Point2? =
            value ?: ref?.let { points[it] }?.let { Point2(it.position.x, it.position.y) }
    }

    private class RawElement(
        val kind: String,
        val attrs: Map<String, String>,
        val coords: Map<String, Coord>,
    ) {
        fun build(points: Map<String, CgPoint>): AlignmentElement? {
            val start = coords["Start"]?.resolve(points) ?: return null
            val end = coords["End"]?.resolve(points) ?: return null
            val clockwise = attrs["rot"].equals("cw", ignoreCase = true)
            return when (kind) {
                "Line" -> AlignmentElement.Line(start, end)
                "Curve" -> {
                    val center = coords["Center"]?.resolve(points) ?: return null
                    AlignmentElement.Arc(start, end, center, clockwise)
                }
                "Spiral" -> {
                    val pi = coords["PI"]?.resolve(points) ?: return null
                    AlignmentElement.Spiral(
                        start = start,
                        end = end,
                        pi = pi,
                        length = attrs["length"]?.toDoubleOrNull() ?: return null,
                        radiusStart = radius(attrs["radiusStart"]),
                        radiusEnd = radius(attrs["radiusEnd"]),
                        clockwise = clockwise,
                    )
                }
                else -> null
            }
        }

        private fun radius(s: String?): Double =
            if (s == null || s.equals("INF", ignoreCase = true)) Double.POSITIVE_INFINITY
            else s.toDoubleOrNull() ?: Double.POSITIVE_INFINITY
    }

    private fun readAlignment() {
        val name = parser.attr("name") ?: "Alinhamento ${rawAlignments.size + 1}"
        val startStation = parser.attr("staStart")?.toDoubleOrNull() ?: 0.0
        val depth = parser.depth
        val elements = ArrayList<RawElement>()
        var profile: Profile? = null

        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            if (event == XmlPullParser.END_TAG && parser.depth == depth) break
            if (event != XmlPullParser.START_TAG) continue
            when (val tag = local()) {
                "Line", "Curve", "Spiral" -> elements.add(readElement(tag))
                "IrregularLine", "Chain" -> warnings.add("Alinhamento \"$name\": elemento $tag ainda não é suportado.")
                "ProfAlign" -> if (profile == null) profile = readProfAlign()
                "StaEquation" -> warnings.add("Alinhamento \"$name\": equações de estaca ainda não são aplicadas.")
            }
        }
        rawAlignments.add(RawAlignment(name, startStation, elements, profile, warnings))
    }

    private fun readElement(kind: String): RawElement {
        val attrs = HashMap<String, String>()
        for (i in 0 until parser.attributeCount) attrs[parser.getAttributeName(i).substringAfter(':')] = parser.getAttributeValue(i)
        val coords = HashMap<String, Coord>()
        val depth = parser.depth
        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            if (event == XmlPullParser.END_TAG && parser.depth == depth) break
            if (event != XmlPullParser.START_TAG) continue
            val tag = local()
            if (tag in COORD_TAGS) {
                val ref = parser.attr("pntRef")
                val v = numbers(parser.nextText())
                coords[tag] = Coord(if (v.size >= 2) Point2(v[1], v[0]) else null, ref)
            }
        }
        return RawElement(kind, attrs, coords)
    }

    private fun readProfAlign(): Profile {
        val name = parser.attr("name") ?: "Greide"
        val depth = parser.depth
        val pvis = ArrayList<Profile.Pvi>()
        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            if (event == XmlPullParser.END_TAG && parser.depth == depth) break
            if (event != XmlPullParser.START_TAG) continue
            val length = when (local()) {
                "PVI" -> 0.0
                "ParaCurve", "CircCurve" -> parser.attr("length")?.toDoubleOrNull() ?: 0.0
                "UnsymParaCurve" -> {
                    warnings.add("Greide \"$name\": curva vertical assimétrica tratada como simétrica.")
                    (parser.attr("lengthIn")?.toDoubleOrNull() ?: 0.0) + (parser.attr("lengthOut")?.toDoubleOrNull() ?: 0.0)
                }
                else -> continue
            }
            val v = numbers(parser.nextText())
            if (v.size >= 2) pvis.add(Profile.Pvi(v[0], v[1], length))
        }
        return Profile(name, pvis.sortedBy { it.station })
    }

    // endregion

    private fun readCgPoint() {
        val name = parser.attr("name") ?: parser.attr("oID") ?: (cgPoints.size + 1).toString()
        val code = parser.attr("code") ?: parser.attr("desc")
        val v = numbers(parser.nextText())
        if (v.size >= 2) cgPoints.add(CgPoint(name, code, Point3(v[1], v[0], if (v.size >= 3) v[2] else Double.NaN)))
    }

    private fun local() = parser.name.substringAfter(':')

    private fun XmlPullParser.attr(name: String): String? {
        for (i in 0 until attributeCount) if (getAttributeName(i).substringAfter(':') == name) return getAttributeValue(i)
        return null
    }

    private fun numbers(text: String): DoubleArray {
        val parts = text.trim().split(WHITESPACE)
        val out = DoubleArray(parts.size)
        var n = 0
        for (p in parts) p.toDoubleOrNull()?.let { out[n++] = it }
        return out.copyOf(n)
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
        val COORD_TAGS = setOf("Start", "End", "Center", "PI")
    }
}

/**
 * Maps surface point ids to vertex indices. LandXML ids are almost always small integers, which go
 * in a flat table; anything else gets a negative key backed by a hash map.
 */
private class PointIds {
    private var dense = IntArray(1024) { -1 }
    private val textKeys = HashMap<String, Int>()
    private val sparse = HashMap<Int, Int>()

    fun key(id: String): Int {
        val n = id.toIntOrNull()
        if (n != null && n in 0 until MAX_DENSE) return n
        return textKeys.getOrPut(id) { -(textKeys.size + 1) }
    }

    fun put(key: Int, index: Int) {
        if (key < 0) {
            sparse[key] = index
            return
        }
        if (key >= dense.size) {
            val old = dense.size
            dense = dense.copyOf(maxOf(key + 1, old * 2))
            dense.fill(-1, old, dense.size)
        }
        dense[key] = index
    }

    /** Vertex index for [key], or -1 when no point has that id. */
    fun index(key: Int): Int =
        if (key < 0) sparse[key] ?: -1 else if (key < dense.size) dense[key] else -1

    private companion object {
        const val MAX_DENSE = 50_000_000
    }
}

private class DoubleList {
    private var data = DoubleArray(1024)
    var size = 0
        private set

    fun add(v: Double) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }

    fun toArray() = data.copyOf(size)
}

private class IntList {
    private var data = IntArray(1024)
    var size = 0
        private set

    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }

    fun toArray() = data.copyOf(size)
}
