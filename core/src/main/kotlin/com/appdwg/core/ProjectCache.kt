package com.appdwg.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A read project in a compact binary form, so a large file opens again without being parsed,
 * triangulated and indexed. Surfaces keep their lookup index. User data (points, models) is not
 * part of it: it only stands for what was read from the file.
 */
object ProjectCache {
    private const val MAGIC = 0x47524d43 // "GRMC"
    /** Bumped whenever the layout or what the readers produce changes, so old caches are ignored. */
    const val VERSION = 1

    fun write(p: LandXmlProject, out: OutputStream) {
        val o = DataOutputStream(out.buffered(1 shl 16))
        o.writeInt(MAGIC); o.writeInt(VERSION)
        o.str(p.linearUnit)
        o.writeBoolean(p.coordinateSystem != null)
        p.coordinateSystem?.let { o.str(it.name); o.str(it.epsgCode); o.str(it.horizontalDatum) }
        o.writeInt(p.surfaces.size)
        for (s in p.surfaces) {
            o.str(s.name); o.doubles(s.xs); o.doubles(s.ys); o.doubles(s.zs); o.ints(s.triangles)
            val idx = s.indexArrays()
            o.writeBoolean(idx != null)
            idx?.let { (side, starts, items) -> o.writeInt(side); o.ints(starts); o.ints(items) }
        }
        o.writeInt(p.alignments.size)
        for (a in p.alignments) {
            o.str(a.name); o.writeDouble(a.startStation)
            o.writeInt(a.elements.size)
            for (e in a.elements) when (e) {
                is AlignmentElement.Line -> { o.writeByte(0); o.pt(e.start); o.pt(e.end) }
                is AlignmentElement.Arc -> { o.writeByte(1); o.pt(e.start); o.pt(e.end); o.pt(e.center); o.writeBoolean(e.clockwise) }
                is AlignmentElement.Spiral -> {
                    o.writeByte(2); o.pt(e.start); o.pt(e.end); o.pt(e.pi); o.writeDouble(e.length)
                    o.writeDouble(e.radiusStart); o.writeDouble(e.radiusEnd); o.writeBoolean(e.clockwise)
                }
            }
            o.writeBoolean(a.profile != null)
            a.profile?.let { pr ->
                o.str(pr.name); o.writeInt(pr.pvis.size)
                for (v in pr.pvis) { o.writeDouble(v.station); o.writeDouble(v.elevation); o.writeDouble(v.curveLength) }
            }
        }
        o.writeInt(p.cgPoints.size)
        for (c in p.cgPoints) { o.str(c.name); o.str(c.code); o.writeDouble(c.position.x); o.writeDouble(c.position.y); o.writeDouble(c.position.z) }
        o.writeInt(p.warnings.size); p.warnings.forEach { o.str(it) }
        o.writeInt(p.polylines.size); p.polylines.forEach { o.line(it) }
        o.writeInt(p.texts.size)
        for (t in p.texts) {
            o.str(t.layer); o.color(t.color); o.writeBoolean(t.byLayer); o.writeDouble(t.x); o.writeDouble(t.y)
            o.writeDouble(t.height); o.writeDouble(t.rotation); o.str(t.text); o.writeInt(t.hAlign); o.writeInt(t.vAlign)
        }
        o.writeInt(p.hatches.size)
        for (h in p.hatches) { o.str(h.layer); o.color(h.color); o.writeBoolean(h.byLayer); o.writeInt(h.loops.size); h.loops.forEach { o.line(it) } }
        o.writeInt(p.images.size)
        for (i in p.images) {
            o.str(i.layer); o.str(i.file); o.writeDouble(i.originX); o.writeDouble(i.originY); o.pt(i.pixelU); o.pt(i.pixelV)
            o.writeInt(i.widthPx); o.writeInt(i.heightPx); o.line(i.boundary)
        }
        o.writeInt(p.xrefs.size)
        for (x in p.xrefs) {
            o.str(x.name); o.str(x.path); o.writeBoolean(x.overlay); o.writeInt(x.placements.size)
            for (pl in x.placements) { for (v in doubleArrayOf(pl.a, pl.b, pl.c, pl.d, pl.e, pl.f, pl.tz)) o.writeDouble(v); o.str(pl.layer) }
        }
        o.writeInt(p.layersOff.size); p.layersOff.forEach { o.str(it) }
        o.writeInt(p.layersFrozen.size); p.layersFrozen.forEach { o.str(it) }
        o.writeInt(p.layerColors.size); p.layerColors.forEach { (k, v) -> o.str(k); o.color(v) }
        o.flush()
    }

    /** The project saved by [write], or null when the data is not a cache of this version. */
    fun read(input: InputStream): LandXmlProject? {
        val i = DataInputStream(input.buffered(1 shl 16))
        if (i.readInt() != MAGIC || i.readInt() != VERSION) return null
        val unit = i.str()
        val crs = if (i.readBoolean()) CoordinateSystemInfo(i.str(), i.str(), i.str()) else null
        val surfaces = List(i.readInt()) {
            val s = Surface(i.str()!!, i.doubles(), i.doubles(), i.doubles(), i.ints())
            if (i.readBoolean()) { val side = i.readInt(); s.useIndex(side, i.ints(), i.ints()) }
            s
        }
        val alignments = List(i.readInt()) {
            val name = i.str()!!; val start = i.readDouble()
            val elements = List(i.readInt()) {
                when (val kind = i.readByte().toInt()) {
                    0 -> AlignmentElement.Line(i.pt(), i.pt())
                    1 -> AlignmentElement.Arc(i.pt(), i.pt(), i.pt(), i.readBoolean())
                    2 -> AlignmentElement.Spiral(i.pt(), i.pt(), i.pt(), i.readDouble(), i.readDouble(), i.readDouble(), i.readBoolean())
                    else -> error("elemento $kind")
                }
            }
            val profile = if (i.readBoolean()) Profile(i.str()!!, List(i.readInt()) { Profile.Pvi(i.readDouble(), i.readDouble(), i.readDouble()) }) else null
            Alignment(name, start, elements, profile)
        }
        val cg = List(i.readInt()) { CgPoint(i.str()!!, i.str(), Point3(i.readDouble(), i.readDouble(), i.readDouble())) }
        val warnings = List(i.readInt()) { i.str()!! }
        val lines = List(i.readInt()) { i.line() }
        val texts = List(i.readInt()) {
            PlanText(i.str()!!, i.color(), i.readBoolean(), i.readDouble(), i.readDouble(), i.readDouble(), i.readDouble(), i.str()!!, i.readInt(), i.readInt())
        }
        val hatches = List(i.readInt()) { Hatch(i.str()!!, i.color(), i.readBoolean(), List(i.readInt()) { i.line() }) }
        val images = List(i.readInt()) {
            PlanImage(i.str()!!, i.str()!!, i.readDouble(), i.readDouble(), i.pt(), i.pt(), i.readInt(), i.readInt(), i.line())
        }
        val xrefs = List(i.readInt()) {
            val name = i.str()!!; val path = i.str()!!; val overlay = i.readBoolean()
            XrefDrawing(name, path, List(i.readInt()) {
                Placement(i.readDouble(), i.readDouble(), i.readDouble(), i.readDouble(), i.readDouble(), i.readDouble(), i.readDouble(), i.str()!!)
            }, overlay)
        }
        val off = List(i.readInt()) { i.str()!! }.toSet()
        val frozen = List(i.readInt()) { i.str()!! }.toSet()
        val colors = List(i.readInt()) { i.str()!! to i.color() }.toMap()
        return LandXmlProject(unit, crs, surfaces, alignments, cg, warnings, lines, texts, hatches, images, xrefs, off, frozen, colors)
    }

    private fun DataOutputStream.str(s: String?) { writeBoolean(s != null); if (s != null) { val b = s.toByteArray(Charsets.UTF_8); writeInt(b.size); write(b) } }
    private fun DataInputStream.str(): String? = if (!readBoolean()) null else ByteArray(readInt()).also { readFully(it) }.toString(Charsets.UTF_8)
    private fun DataOutputStream.color(c: Int?) { writeBoolean(c != null); if (c != null) writeInt(c) }
    private fun DataInputStream.color(): Int? = if (readBoolean()) readInt() else null
    private fun DataOutputStream.pt(p: Point2) { writeDouble(p.x); writeDouble(p.y) }
    private fun DataInputStream.pt() = Point2(readDouble(), readDouble())

    // Arrays go through a byte buffer in one piece: much faster than value by value.
    private fun DataOutputStream.doubles(a: DoubleArray) {
        writeInt(a.size)
        val b = ByteBuffer.allocate(a.size * 8).order(ByteOrder.LITTLE_ENDIAN); b.asDoubleBuffer().put(a); write(b.array())
    }
    private fun DataInputStream.doubles(): DoubleArray {
        val n = readInt(); val bytes = ByteArray(n * 8); readFully(bytes)
        return DoubleArray(n).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asDoubleBuffer().get(it) }
    }
    private fun DataOutputStream.ints(a: IntArray) {
        writeInt(a.size)
        val b = ByteBuffer.allocate(a.size * 4).order(ByteOrder.LITTLE_ENDIAN); b.asIntBuffer().put(a); write(b.array())
    }
    private fun DataInputStream.ints(): IntArray {
        val n = readInt(); val bytes = ByteArray(n * 4); readFully(bytes)
        return IntArray(n).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(it) }
    }
    private fun DataOutputStream.line(l: Polyline) {
        str(l.layer); doubles(l.xs); doubles(l.ys); writeBoolean(l.closed); color(l.color); writeBoolean(l.byLayer)
        writeBoolean(l.zs != null); l.zs?.let { doubles(it) }
    }
    private fun DataInputStream.line(): Polyline {
        val layer = str()!!; val xs = doubles(); val ys = doubles(); val closed = readBoolean(); val color = color(); val byLayer = readBoolean()
        val zs = if (readBoolean()) doubles() else null
        return Polyline(layer, xs, ys, closed, color, byLayer, zs)
    }
}
