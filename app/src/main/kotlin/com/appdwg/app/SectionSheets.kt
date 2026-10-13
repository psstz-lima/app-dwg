package com.appdwg.app

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.pdf.PdfDocument
import com.appdwg.core.Alignment
import com.appdwg.core.CrossSection
import com.appdwg.core.RoadSection
import com.appdwg.core.StationFormat
import com.appdwg.core.Surface
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Settings of the cross-section sheets, as typed in their dialog. */
data class SheetParams(
    val alignment: String? = null,
    val from: Double? = null,
    val to: Double? = null,
    val interval: Double = 20.0,
    val halfWidth: Double = 30.0,
    /** Paper: "A3" or "A4", landscape. */
    val paper: String = "A3",
    /** Horizontal scale denominator; vertical = horizontal / exaggeration. */
    val scale: Int = 200,
    val exaggeration: Int = 1,
    val company: String = "",
    val work: String = "",
    val responsible: String = "",
    val registration: String = "",
    val drawing: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        alignment?.let { put("alignment", it) }; from?.let { put("from", it) }; to?.let { put("to", it) }
        put("interval", interval); put("halfWidth", halfWidth); put("paper", paper); put("scale", scale); put("exaggeration", exaggeration)
        put("company", company); put("work", work); put("responsible", responsible); put("registration", registration); put("drawing", drawing)
    }

    companion object {
        fun fromJson(o: JSONObject) = SheetParams(
            o.optString("alignment").ifEmpty { null }, o.optDouble("from").takeIf { !it.isNaN() }, o.optDouble("to").takeIf { !it.isNaN() },
            o.optDouble("interval", 20.0), o.optDouble("halfWidth", 30.0), o.optString("paper", "A3"), o.optInt("scale", 200), o.optInt("exaggeration", 1),
            o.optString("company"), o.optString("work"), o.optString("responsible"), o.optString("registration"), o.optString("drawing"),
        )
    }
}

/**
 * Cross-section sheets: sections at true scale stacked in columns, each with its grid, station,
 * axis heights and (with a road) cut and fill areas; a title block (carimbo) on every sheet.
 */
object SectionSheets {
    private const val PT_PER_MM = 72f / 25.4f
    private val COLORS = intArrayOf(0xFF6D4C41.toInt(), 0xFFD32F2F.toInt(), 0xFF1565C0.toInt(), 0xFF2E7D32.toInt(), 0xFF6A1B9A.toInt())

    class Built(val pages: Int, val sections: Int)

    fun render(doc: PdfDocument, p: SheetParams, axis: Alignment, surfaces: List<Surface>, road: List<RoadSection>?, title: String, date: String): Built {
        val (pw, ph) = if (p.paper == "A4") 842f to 595f else 1191f to 842f
        val margin = 28f
        val blockH = 118f
        val areaTop = margin; val areaBottom = ph - margin - blockH - 10f
        val areaLeft = margin; val areaRight = pw - margin
        val hScale = PT_PER_MM * 1000f / p.scale            // pt per metre, horizontal
        val vScale = hScale * p.exaggeration                 // pt per metre, vertical
        val labelW = 34f; val headH = 26f; val footH = 14f

        // Stations: every interval from the start, both ends included.
        val s0 = (p.from ?: axis.startStation).coerceIn(axis.startStation, axis.endStation)
        val s1 = (p.to ?: axis.endStation).coerceIn(s0, axis.endStation)
        val stations = ArrayList<Double>()
        var s = ceil(s0 / p.interval - 1e-9) * p.interval
        if (s - s0 > 1e-6) stations += s0
        while (s <= s1 + 1e-6) { stations += s; s += p.interval }
        if (stations.isEmpty() || s1 - stations.last() > 1e-6) stations += s1

        class Box(val sec: CrossSection, val road: RoadSection?, val zMin: Double, val zMax: Double, val h: Float)
        val boxes = stations.mapNotNull { st ->
            val sec = CrossSection.at(axis, st, surfaces, p.halfWidth) ?: return@mapNotNull null
            val rs = road?.minByOrNull { abs(it.station - st) }?.takeIf { abs(it.station - st) < 0.01 }
            val zs = sec.lines.flatMap { l -> l.elevations.filter { !it.isNaN() } } + listOfNotNull(sec.design) +
                rs?.let { r -> (r.top + r.datum).filter { abs(it.offset) <= p.halfWidth }.map { it.z } }.orEmpty()
            if (zs.isEmpty()) return@mapNotNull null
            // Whole metres around the section, at least 4 m tall.
            var lo = floor(zs.min()) - 1; var hi = ceil(zs.max()) + 1
            if (hi - lo < 4) { val c = (hi + lo) / 2; lo = floor(c - 2); hi = lo + 4 }
            val h = headH + ((hi - lo) * vScale).toFloat() + footH
            Box(sec, rs, lo, hi, min(h, areaBottom - areaTop))
        }
        val boxW = labelW + (2 * p.halfWidth * hScale).toFloat()
        val gap = 18f
        val cols = max(1, floor((areaRight - areaLeft + gap) / (boxW + gap)).toInt())
        // Columns filled top to bottom, left to right; a new sheet when they are full.
        data class Placed(val box: Box, val page: Int, val x: Float, val y: Float)
        val placed = ArrayList<Placed>()
        var page = 0; var col = 0; var y = areaTop
        for (b in boxes) {
            if (y + b.h > areaBottom + 0.5f && y > areaTop) {
                col++; y = areaTop
                if (col >= cols) { col = 0; page++ }
            }
            placed += Placed(b, page, areaLeft + col * (boxW + gap), y)
            y += b.h + gap
        }
        val pages = (placed.maxOfOrNull { it.page } ?: 0) + 1

        val thin = Paint().apply { strokeWidth = 0.3f; color = 0xFFBDBDBD.toInt(); style = Paint.Style.STROKE; isAntiAlias = true }
        val frame = Paint().apply { strokeWidth = 0.8f; color = 0xFF000000.toInt(); style = Paint.Style.STROKE; isAntiAlias = true }
        val text = Paint().apply { textSize = 6.5f; color = 0xFF000000.toInt(); isAntiAlias = true }
        val bold = Paint(text).apply { isFakeBoldText = true; textSize = 8f }

        for (pg in 0 until pages) {
            val pdfPage = doc.startPage(PdfDocument.PageInfo.Builder(pw.toInt(), ph.toInt(), pg + 1).create())
            val c = pdfPage.canvas
            c.drawRect(margin / 2, margin / 2, pw - margin / 2, ph - margin / 2, frame)
            for (pl in placed.filter { it.page == pg }) drawSection(c, pl.box.sec, pl.box.road, pl.box.zMin, pl.box.zMax, pl.x, pl.y, labelW, headH, hScale, vScale, p.halfWidth, thin, frame, text, bold)
            drawTitleBlock(c, p, axis.name, stations.first(), stations.last(), pw, ph, margin, blockH, pg + 1, pages, title, date, frame, text, bold, surfaces.map { it.name })
            doc.finishPage(pdfPage)
        }
        return Built(pages, placed.size)
    }

    private fun drawSection(c: Canvas, s: CrossSection, road: RoadSection?, zMin: Double, zMax: Double, x0: Float, y0: Float, labelW: Float, headH: Float,
                            hScale: Float, vScale: Float, hw: Double, thin: Paint, frame: Paint, text: Paint, bold: Paint) {
        val left = x0 + labelW
        val top = y0 + headH
        val bottom = top + ((zMax - zMin) * vScale).toFloat()
        fun x(o: Double) = left + ((o + hw) * hScale).toFloat()
        fun y(z: Double) = bottom - ((z - zMin) * vScale).toFloat()
        // Grid: every 5 m across (labels every 10 m), every metre up (labels every 1, 2 or 5 m).
        var o = -hw
        while (o <= hw + 1e-9) {
            c.drawLine(x(o), top, x(o), bottom, thin)
            if (abs(o % 10.0) < 1e-6) c.drawText(if (o == 0.0) "0" else StationFormat.decimal(abs(o), 0), x(o) - 4f, bottom + 9f, text)
            o += 5.0
        }
        val zStep = when { zMax - zMin > 40 -> 5.0; zMax - zMin > 15 -> 2.0; else -> 1.0 }
        var z = ceil(zMin / zStep) * zStep
        while (z <= zMax + 1e-9) {
            c.drawLine(left, y(z), x(hw), y(z), thin)
            c.drawText(StationFormat.decimal(z, 0), x0, y(z) + 2.5f, text)
            z += zStep
        }
        c.drawRect(left, top, x(hw), bottom, frame)
        // Axis, dashed.
        c.drawLine(x(0.0), top, x(0.0), bottom, Paint(frame).apply { pathEffect = DashPathEffect(floatArrayOf(4f, 3f), 0f); strokeWidth = 0.5f })
        // Surfaces: the first (ground) dashed brown, the others solid in their colours.
        s.lines.forEachIndexed { k, l ->
            val paint = Paint().apply {
                color = COLORS[k % COLORS.size]; style = Paint.Style.STROKE; strokeWidth = if (k == 0) 0.9f else 1.2f; isAntiAlias = true
                if (k == 0) pathEffect = DashPathEffect(floatArrayOf(5f, 2f), 0f)
            }
            val path = Path(); var pen = false
            for (i in s.offsets.indices) {
                val e = l.elevations[i]
                if (e.isNaN()) { pen = false; continue }
                if (pen) path.lineTo(x(s.offsets[i]), y(e)) else path.moveTo(x(s.offsets[i]), y(e)); pen = true
            }
            c.drawPath(path, paint)
        }
        s.design?.let { d -> c.drawCircle(x(0.0), y(d), 1.8f, Paint().apply { color = 0xFFD32F2F.toInt(); isAntiAlias = true }) }
        // Heading: station, axis heights, areas.
        c.drawText("Estaca " + StationFormat.estaca(s.station), left, y0 + 9f, bold)
        val parts = buildList {
            s.terrainOnAxis?.let { add("Terreno ${StationFormat.decimal(it, 3)}") }
            (road?.grade ?: s.design)?.let { add("Greide ${StationFormat.decimal(it, 3)}") }
            road?.terrainAxis?.let { t -> val d = road.grade - t; add((if (d >= 0) "Aterro " else "Corte ") + StationFormat.decimal(abs(d), 3)) }
            road?.let { add("Área C ${StationFormat.decimal(it.cutArea, 2)} m² · A ${StationFormat.decimal(it.fillArea, 2)} m²") }
        }
        c.drawText(parts.joinToString(" · "), left, y0 + 19f, text)
    }

    private fun fit(t: String, paint: Paint, width: Float): String {
        if (paint.measureText(t) <= width) return t
        var n = t.length
        while (n > 1 && paint.measureText(t, 0, n) + paint.measureText("…") > width) n--
        return t.take(n).trimEnd() + "…"
    }

    private fun drawTitleBlock(c: Canvas, p: SheetParams, axis: String, s0: Double, s1: Double, pw: Float, ph: Float, margin: Float, h: Float,
                               page: Int, pages: Int, title: String, date: String, frame: Paint, text: Paint, bold: Paint, surfaces: List<String>) {
        val w = 380f
        val x0 = pw - margin - w; val y0 = ph - margin - h
        val big = Paint(bold).apply { textSize = 12f }
        c.drawRect(x0, y0, x0 + w, y0 + h, frame)
        val rows = floatArrayOf(y0 + 26f, y0 + 50f, y0 + 74f, y0 + 96f)
        rows.forEach { c.drawLine(x0, it, x0 + w, it, frame) }
        c.drawLine(x0 + w * 0.6f, rows[1], x0 + w * 0.6f, y0 + h, frame)
        fun cell(label: String, value: String, x: Float, y: Float, p2: Paint = bold) { c.drawText(label, x + 4f, y + 8f, text); c.drawText(value, x + 4f, y + 19f, p2) }
        c.drawText(p.company.ifBlank { "Gromatic" }, x0 + 6f, y0 + 18f, big)
        cell("Obra", fit(p.work.ifBlank { title }, bold, w - 8f), x0, rows[0])
        // Title on two lines so it stays inside its cell.
        c.drawText("Desenho", x0 + 4f, rows[1] + 8f, text)
        c.drawText("SEÇÕES TRANSVERSAIS", x0 + 4f, rows[1] + 16f, Paint(bold).apply { textSize = 7f })
        c.drawText(fit("Eixo $axis · estacas ${StationFormat.estaca(s0)} a ${StationFormat.estaca(s1)}", text, w * 0.6f - 8f), x0 + 4f, rows[1] + 22.5f, text)
        cell("Folha", "$page / $pages", x0 + w * 0.6f, rows[1])
        cell("Escalas", "H 1:${p.scale} · V 1:${p.scale / max(1, p.exaggeration)}", x0, rows[2])
        cell("Data", date, x0 + w * 0.6f, rows[2])
        cell("Responsável técnico", listOf(p.responsible, p.registration.takeIf { it.isNotBlank() }?.let { "CREA $it" }).filterNotNull().filter { it.isNotBlank() }.joinToString(" · "),
            x0, rows[3])
        cell("Nº", p.drawing, x0 + w * 0.6f, rows[3])
        // Legend at the left of the block.
        var ly = y0 + 10f
        c.drawText("Legenda", margin, ly, bold); ly += 12f
        surfaces.take(5).forEachIndexed { k, n ->
            val paint = Paint().apply { color = COLORS[k % COLORS.size]; strokeWidth = 1.2f; if (k == 0) pathEffect = DashPathEffect(floatArrayOf(5f, 2f), 0f) }
            c.drawLine(margin, ly - 2.5f, margin + 24f, ly - 2.5f, paint)
            c.drawText(n, margin + 30f, ly, text); ly += 10f
        }
        c.drawText("Cotas e distâncias em metros. Gerado pelo Gromatic.", margin, ph - margin - 2f, text)
    }
}
