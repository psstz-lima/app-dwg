package com.appdwg.app

import com.appdwg.core.tr

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
import com.appdwg.core.SurveySections
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
    /** Measurement sheets: one profile per survey state, completed where a survey does not reach. */
    val measurement: Boolean = false,
    val complete: Boolean = true,
    /** Picture of the company logo for the title block (content URI). */
    val logo: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("measurement", measurement); put("complete", complete); logo?.let { put("logo", it) }
        alignment?.let { put("alignment", it) }; from?.let { put("from", it) }; to?.let { put("to", it) }
        put("interval", interval); put("halfWidth", halfWidth); put("paper", paper); put("scale", scale); put("exaggeration", exaggeration)
        put("company", company); put("work", work); put("responsible", responsible); put("registration", registration); put("drawing", drawing)
    }

    companion object {
        fun fromJson(o: JSONObject) = SheetParams(
            o.optString("alignment").ifEmpty { null }, o.optDouble("from").takeIf { !it.isNaN() }, o.optDouble("to").takeIf { !it.isNaN() },
            o.optDouble("interval", 20.0), o.optDouble("halfWidth", 30.0), o.optString("paper", "A3"), o.optInt("scale", 200), o.optInt("exaggeration", 1),
            o.optString("company"), o.optString("work"), o.optString("responsible"), o.optString("registration"), o.optString("drawing"),
            o.optBoolean("measurement"), o.optBoolean("complete", true), o.optString("logo").ifEmpty { null },
        )
    }
}

/**
 * Cross-section sheets: sections at true scale stacked in columns, each with its grid, station,
 * axis heights and (with a road) cut and fill areas; a title block (carimbo) on every sheet.
 */
object SectionSheets {
    /** Units of the sheets being drawn: the shown ones; [k] is metres per unit. */
    private object U {
        var u = com.appdwg.core.ReportUnits()
        val k get() = if (u.metric) 1.0 else StationFormat.FOOT
    }

    private const val PT_PER_MM = 72f / 25.4f
    private val COLORS = intArrayOf(0xFF6D4C41.toInt(), 0xFFD32F2F.toInt(), 0xFF1565C0.toInt(), 0xFF2E7D32.toInt(), 0xFF6A1B9A.toInt(),
        0xFFEF6C00.toInt(), 0xFF00838F.toInt(), 0xFFAD1457.toInt())

    class Built(val pages: Int, val sections: Int)

    /**
     * Draws the sheets into [doc]. With [measurement], [surfaces] are the survey states in date
     * order: each section shows their profiles (completed when [SheetParams.complete]) and the cut
     * and fill areas between consecutive states, and a volume table closes the set.
     */
    fun render(doc: PdfDocument, p: SheetParams, axis: Alignment, surfaces: List<Surface>, road: List<RoadSection>?, title: String, date: String,
               measurement: Boolean = false, logo: android.graphics.Bitmap? = null): Built {
        U.u = com.appdwg.core.ReportUnits()
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

        class Box(val sec: CrossSection, val road: RoadSection?, val zMin: Double, val zMax: Double, val h: Float,
                  val elev: List<DoubleArray>, val filled: List<BooleanArray>?, val areas: List<Pair<Double, Double>>)
        val boxes = stations.mapNotNull { st ->
            val sec = CrossSection.at(axis, st, surfaces, p.halfWidth) ?: return@mapNotNull null
            val rs = road?.minByOrNull { abs(it.station - st) }?.takeIf { abs(it.station - st) < 0.01 }
            // Measurement: one profile per state, in order, even where a state misses this section (all gaps then).
            val raw = if (measurement) surfaces.map { sf -> sec.lines.firstOrNull { it.surface == sf.name }?.elevations ?: DoubleArray(sec.offsets.size) { Double.NaN } }
                else sec.lines.map { it.elevations }
            val done = if (measurement && p.complete) SurveySections.complete(raw) else null
            val elev = done?.elevations ?: raw
            val areas = if (measurement) (1 until elev.size).map { k -> SurveySections.areas(sec.offsets, elev[k - 1], elev[k]) } else emptyList()
            val zs = elev.flatMap { l -> l.filter { !it.isNaN() } } + listOfNotNull(sec.design) +
                rs?.let { r -> (r.top + r.datum).filter { abs(it.offset) <= p.halfWidth }.map { it.z } }.orEmpty()
            if (zs.isEmpty()) return@mapNotNull null
            // Whole metres around the section, at least 4 m tall.
            // Whole units (m or ft) around the section, at least 4 units tall; kept in metres.
            val k = U.k
            var lo = floor(zs.min() / k) - 1; var hi = ceil(zs.max() / k) + 1
            if (hi - lo < 4) { val c = (hi + lo) / 2; lo = floor(c - 2); hi = lo + 4 }
            lo *= k; hi *= k
            val notes = areas.size + (if (done?.anyFilled == true) 1 else 0)
            val h = headH + ((hi - lo) * vScale).toFloat() + footH + notes * 8f
            Box(sec, rs, lo, hi, min(h, areaBottom - areaTop), elev, done?.takeIf { it.anyFilled }?.filled, areas)
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
        val sectionPages = (placed.maxOfOrNull { it.page } ?: 0) + 1
        // Volume table of the measurement: the last period and the total since the first state.
        val tableRows = if (measurement && surfaces.size >= 2 && boxes.isNotEmpty()) {
            val st = boxes.map { it.sec.station }
            val last = SurveySections.volumes(st, boxes.map { it.areas.lastOrNull()?.first ?: 0.0 }, boxes.map { it.areas.lastOrNull()?.second ?: 0.0 })
            val total = boxes.map { b -> SurveySections.areas(b.sec.offsets, b.elev.first(), b.elev.last()) }
            val acc = SurveySections.volumes(st, total.map { it.first }, total.map { it.second })
            last.zip(acc)
        } else emptyList()
        val rowH = 11f
        val rowsPerPage = max(1, ((areaBottom - areaTop - 40f) / rowH).toInt())
        val tablePages = if (tableRows.isEmpty()) 0 else ceil((tableRows.size + 1).toDouble() / rowsPerPage).toInt()
        val pages = sectionPages + tablePages
        val anyFilled = boxes.any { it.filled != null }

        val thin = Paint().apply { strokeWidth = 0.3f; color = 0xFFBDBDBD.toInt(); style = Paint.Style.STROKE; isAntiAlias = true }
        val frame = Paint().apply { strokeWidth = 0.8f; color = 0xFF000000.toInt(); style = Paint.Style.STROKE; isAntiAlias = true }
        val text = Paint().apply { textSize = 6.5f; color = 0xFF000000.toInt(); isAntiAlias = true }
        val bold = Paint(text).apply { isFakeBoldText = true; textSize = 8f }

        for (pg in 0 until pages) {
            val pdfPage = doc.startPage(PdfDocument.PageInfo.Builder(pw.toInt(), ph.toInt(), pg + 1).create())
            val c = pdfPage.canvas
            c.drawRect(margin / 2, margin / 2, pw - margin / 2, ph - margin / 2, frame)
            if (pg < sectionPages) for (pl in placed.filter { it.page == pg }) {
                val names = surfaces.map { it.name }
                val extra = if (!measurement) emptyList() else pl.box.areas.mapIndexed { k, (cut, fill) ->
                    tr("{0}→{1}: C {2} · A {3} {4}", names.getOrElse(k) { "" }, names.getOrElse(k + 1) { "" }, U.u.a(cut), U.u.a(fill), U.u.area)
                } + listOfNotNull(pl.box.filled?.let { tr("Seção completada pelo app (tracejado fino)") })
                drawSection(c, pl.box.sec, pl.box.road, pl.box.zMin, pl.box.zMax, pl.x, pl.y, labelW, headH, hScale, vScale, p.halfWidth, thin, frame, text, bold,
                    pl.box.elev, pl.box.filled, extra)
            } else {
                val first = (pg - sectionPages) * rowsPerPage
                drawTable(c, tableRows.subList(first.coerceAtMost(tableRows.size), (first + rowsPerPage).coerceAtMost(tableRows.size)),
                    surfaces, areaLeft, areaTop, areaRight, rowH, text, bold, frame, thin, last = first + rowsPerPage >= tableRows.size, all = tableRows)
            }
            drawTitleBlock(c, p, axis.name, stations.first(), stations.last(), pw, ph, margin, blockH, pg + 1, pages, title, date, frame, text, bold, surfaces.map { it.name },
                measurement, anyFilled, logo)
            doc.finishPage(pdfPage)
        }
        return Built(pages, placed.size)
    }

    private fun drawSection(c: Canvas, s: CrossSection, road: RoadSection?, zMin: Double, zMax: Double, x0: Float, y0: Float, labelW: Float, headH: Float,
                            hScale: Float, vScale: Float, hw: Double, thin: Paint, frame: Paint, text: Paint, bold: Paint,
                            elev: List<DoubleArray>, filled: List<BooleanArray>?, extra: List<String>) {
        val left = x0 + labelW
        val top = y0 + headH
        val bottom = top + ((zMax - zMin) * vScale).toFloat()
        fun x(o: Double) = left + ((o + hw) * hScale).toFloat()
        fun y(z: Double) = bottom - ((z - zMin) * vScale).toFloat()
        // Grid in the report unit: every 5 m (10 ft) across, labels every 10 m (20 ft); every 1, 2 or 5 units up.
        val k = U.k
        val across = if (U.u.metric) 5.0 else 10.0
        val hwU = hw / k
        var o = ceil(-hwU / across) * across
        while (o <= hwU + 1e-9) {
            c.drawLine(x(o * k), top, x(o * k), bottom, thin)
            if (abs(o % (2 * across)) < 1e-6) c.drawText(if (o == 0.0) "0" else StationFormat.decimal(abs(o), 0), x(o * k) - 4f, bottom + 9f, text)
            o += across
        }
        // Labels at least 9 pt apart, whatever the unit and scale.
        val ptPerUnit = vScale * k
        val zStep = listOf(1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0).firstOrNull { it * ptPerUnit >= 9 } ?: 100.0
        var z = ceil(zMin / k / zStep) * zStep
        while (z <= zMax / k + 1e-9) {
            c.drawLine(left, y(z * k), x(hw), y(z * k), thin)
            c.drawText(StationFormat.decimal(z, 0), x0, y(z * k) + 2.5f, text)
            z += zStep
        }
        c.drawRect(left, top, x(hw), bottom, frame)
        // Axis, dashed.
        c.drawLine(x(0.0), top, x(0.0), bottom, Paint(frame).apply { pathEffect = DashPathEffect(floatArrayOf(4f, 3f), 0f); strokeWidth = 0.5f })
        // Surfaces: the first (ground) dashed brown, the others solid in their colours.
        elev.forEachIndexed { k, l ->
            val paint = Paint().apply {
                color = COLORS[k % COLORS.size]; style = Paint.Style.STROKE; strokeWidth = if (k == 0) 0.9f else 1.2f; isAntiAlias = true
                if (k == 0) pathEffect = DashPathEffect(floatArrayOf(5f, 2f), 0f)
            }
            // Stretches the app completed: thin and finely dashed, so they read apart from the survey.
            val made = filled?.getOrNull(k)
            val madePaint = Paint(paint).apply { strokeWidth = 0.6f; pathEffect = DashPathEffect(floatArrayOf(1.5f, 1.5f), 0f) }
            val path = Path(); val madePath = Path(); var pen = false
            for (i in s.offsets.indices) {
                val e = l[i]
                if (e.isNaN()) { pen = false; continue }
                if (pen) {
                    val px = x(s.offsets[i - 1]); val py = y(l[i - 1])
                    val target = if (made != null && (made[i] || made[i - 1])) madePath else path
                    target.moveTo(px, py); target.lineTo(x(s.offsets[i]), y(e))
                }
                pen = true
            }
            c.drawPath(path, paint)
            c.drawPath(madePath, madePaint)
        }
        s.design?.let { d -> c.drawCircle(x(0.0), y(d), 1.8f, Paint().apply { color = 0xFFD32F2F.toInt(); isAntiAlias = true }) }
        // Heading: station, axis heights, areas.
        c.drawText(tr("Estaca ") + U.u.station(s.station), left, y0 + 9f, bold)
        val parts = buildList {
            s.terrainOnAxis?.let { add(tr("Terreno {0}", U.u.l(it))) }
            (road?.grade ?: s.design)?.let { add(tr("Greide {0}", U.u.l(it))) }
            road?.terrainAxis?.let { t -> val d = road.grade - t; add((if (d >= 0) tr("Aterro ") else tr("Corte ")) + U.u.l(abs(d))) }
            road?.let { add(tr("Área C {0} {1} · A {2} {3}", U.u.a(it.cutArea), U.u.area, U.u.a(it.fillArea), U.u.area)) }
        }
        c.drawText(parts.joinToString(" · "), left, y0 + 19f, text)
        // Areas between states, under the section.
        extra.forEachIndexed { i, t -> c.drawText(t, left, bottom + 17f + i * 8f, text) }
    }

    /** Volume table of a measurement: areas and volumes of the last period, and the cumulative ones since the first state. */
    private fun drawTable(c: Canvas, rows: List<Pair<SurveySections.VolumeRow, SurveySections.VolumeRow>>, surfaces: List<Surface>, left: Float, top: Float, right: Float,
                          rowH: Float, text: Paint, bold: Paint, frame: Paint, thin: Paint, last: Boolean,
                          all: List<Pair<SurveySections.VolumeRow, SurveySections.VolumeRow>>) {
        val n = surfaces.size
        val period = "${surfaces[n - 2].name} → ${surfaces[n - 1].name}"
        val accum = "${surfaces[0].name} → ${surfaces[n - 1].name}"
        c.drawText(tr("QUADRO DE VOLUMES (média das áreas)"), left, top + 10f, Paint(bold).apply { textSize = 10f })
        val ar = " (${U.u.area})"; val vo = " (${U.u.vol})"
        val head = listOf(tr("Estaca"), tr("Área corte") + ar, tr("Área aterro") + ar, tr("Vol. corte") + vo, tr("Vol. aterro") + vo,
            tr("Área corte") + ar, tr("Área aterro") + ar, tr("Vol. corte") + vo, tr("Vol. aterro") + vo)
        val w = (right - left) / head.size
        var y = top + 26f
        c.drawText(tr("Período: {0}", period), left + w, y - 2f, bold)
        c.drawText(tr("Acumulado: {0}", accum), left + 5 * w, y - 2f, bold)
        y += 10f
        head.forEachIndexed { i, h -> c.drawText(h, left + i * w + 2f, y, bold) }
        c.drawLine(left, y + 3f, right, y + 3f, frame)
        fun line(cells: List<String>, p: Paint) { y += rowH; cells.forEachIndexed { i, t -> c.drawText(t, left + i * w + 2f, y, p) }; c.drawLine(left, y + 3f, right, y + 3f, thin) }
        fun v(x: Double) = U.u.v(x, 2)
        fun a(x: Double) = U.u.a(x, 2)
        for ((pr, ac) in rows) line(listOf(U.u.station(pr.station), a(pr.cutArea), a(pr.fillArea), v(pr.cutVolume), v(pr.fillVolume),
            a(ac.cutArea), a(ac.fillArea), v(ac.cutVolume), v(ac.fillVolume)), text)
        if (last) line(listOf(tr("Total"), "", "", v(all.sumOf { it.first.cutVolume }), v(all.sumOf { it.first.fillVolume }), "", "",
            v(all.sumOf { it.second.cutVolume }), v(all.sumOf { it.second.fillVolume })), bold)
    }

    private fun fit(t: String, paint: Paint, width: Float): String {
        if (paint.measureText(t) <= width) return t
        var n = t.length
        while (n > 1 && paint.measureText(t, 0, n) + paint.measureText("…") > width) n--
        return t.take(n).trimEnd() + "…"
    }

    private fun drawTitleBlock(c: Canvas, p: SheetParams, axis: String, s0: Double, s1: Double, pw: Float, ph: Float, margin: Float, h: Float,
                               page: Int, pages: Int, title: String, date: String, frame: Paint, text: Paint, bold: Paint, surfaces: List<String>,
                               measurement: Boolean = false, anyFilled: Boolean = false, logo: android.graphics.Bitmap? = null) {
        val w = 380f
        val x0 = pw - margin - w; val y0 = ph - margin - h
        val big = Paint(bold).apply { textSize = 12f }
        c.drawRect(x0, y0, x0 + w, y0 + h, frame)
        val rows = floatArrayOf(y0 + 26f, y0 + 50f, y0 + 74f, y0 + 96f)
        rows.forEach { c.drawLine(x0, it, x0 + w, it, frame) }
        c.drawLine(x0 + w * 0.6f, rows[1], x0 + w * 0.6f, y0 + h, frame)
        fun cell(label: String, value: String, x: Float, y: Float, p2: Paint = bold) { c.drawText(label, x + 4f, y + 8f, text); c.drawText(value, x + 4f, y + 19f, p2) }
        // Logo at the left of the company cell, fitted to its height.
        val logoW = logo?.let { l ->
            val hh = 22f; val ww = (hh * l.width / l.height.coerceAtLeast(1)).coerceAtMost(110f)
            c.drawBitmap(l, null, android.graphics.RectF(x0 + 2f, y0 + 2f, x0 + 2f + ww, y0 + 2f + hh), Paint().apply { isFilterBitmap = true })
            ww + 4f
        } ?: 0f
        c.drawText(fit(p.company.ifBlank { "Gromatic" }, big, w - 12f - logoW), x0 + 6f + logoW, y0 + 18f, big)
        cell(tr("Obra"), fit(p.work.ifBlank { title }, bold, w - 8f), x0, rows[0])
        // Title on two lines so it stays inside its cell.
        c.drawText(tr("Desenho"), x0 + 4f, rows[1] + 8f, text)
        c.drawText(if (measurement) tr("SEÇÕES DE MEDIÇÃO") else tr("SEÇÕES TRANSVERSAIS"), x0 + 4f, rows[1] + 16f, Paint(bold).apply { textSize = 7f })
        c.drawText(fit(tr("Eixo {0} · estacas {1} a {2}", axis, U.u.station(s0), U.u.station(s1)), text, w * 0.6f - 8f), x0 + 4f, rows[1] + 22.5f, text)
        cell(tr("Folha"), "$page / $pages", x0 + w * 0.6f, rows[1])
        cell(tr("Escalas"), "H 1:${p.scale} · V 1:${p.scale / max(1, p.exaggeration)}", x0, rows[2])
        cell(tr("Data"), date, x0 + w * 0.6f, rows[2])
        cell(tr("Responsável técnico"), listOf(p.responsible, p.registration.takeIf { it.isNotBlank() }?.let { "CREA $it" }).filterNotNull().filter { it.isNotBlank() }.joinToString(" · "),
            x0, rows[3])
        cell("Nº", p.drawing, x0 + w * 0.6f, rows[3])
        // Legend at the left of the block.
        var ly = y0 + 10f
        c.drawText(tr("Legenda"), margin, ly, bold); ly += 12f
        surfaces.take(COLORS.size).forEachIndexed { k, n ->
            val paint = Paint().apply { color = COLORS[k % COLORS.size]; strokeWidth = 1.2f; if (k == 0) pathEffect = DashPathEffect(floatArrayOf(5f, 2f), 0f) }
            c.drawLine(margin, ly - 2.5f, margin + 24f, ly - 2.5f, paint)
            c.drawText(n, margin + 30f, ly, text); ly += 10f
        }
        if (anyFilled) {
            c.drawLine(margin, ly - 2.5f, margin + 24f, ly - 2.5f, Paint().apply { color = 0xFF000000.toInt(); strokeWidth = 0.6f; pathEffect = DashPathEffect(floatArrayOf(1.5f, 1.5f), 0f) })
            c.drawText(tr("Trecho completado pelo app (interpolado ou pela primitiva)"), margin + 30f, ly, text)
        }
        c.drawText(if (U.u.metric) tr("Cotas e distâncias em metros. Gerado pelo Gromatic.") else tr("Cotas e distâncias em pés (ft). Gerado pelo Gromatic."),
            margin, ph - margin - 2f, text)
    }
}
