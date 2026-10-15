package com.appdwg.core

import java.io.File
import kotlin.test.Test

/** REAL_DXF=path [REAL_PNT=path]: runs the design suggestions on real files and prints what they give. */
class RealDataCheck {
    private fun t(label: String, block: () -> Any?): Any? {
        val t0 = System.nanoTime(); val r = block(); println("REAL $label: ${(System.nanoTime() - t0) / 1_000_000} ms"); return r
    }

    @Test
    fun dxf() {
        val path = System.getenv("REAL_DXF") ?: return
        val p = t("ler DXF") { DxfReader(File(path).bufferedReader(Charsets.ISO_8859_1, 1 shl 16)).read { } } as LandXmlProject
        println("REAL superfícies ${p.surfaces.map { it.name + " " + it.triangleCount }}, eixos ${p.alignments.size}, linhas ${p.polylines.size}")
        p.polylines.filter { it.layer == "Q-PLATO" }.forEach { l -> var len = 0.0; for (i in 1 until l.xs.size) len += kotlin.math.hypot(l.xs[i] - l.xs[i - 1], l.ys[i] - l.ys[i - 1]); println("REAL Q-PLATO gap ${kotlin.math.hypot(l.xs.last() - l.xs[0], l.ys.last() - l.ys[0])} len $len") }
        val advice = ProjectAdvisor.advise(p)
        advice.forEach { println("REAL sugestão ${it.kind}: ${it.title} — ${it.reason}") }
        val terrain = p.surfaces.maxByOrNull { it.triangleCount } ?: return
        advice.firstOrNull { it.kind == Advice.Kind.AXIS_FROM_LINE }?.let { a ->
            val l = p.polylines[a.line]
            val pis = ProjectAdvisor.pisFromLine(l)
            run {
                val ll = p.polylines[a.line]
                val hs = (0 until ll.xs.size - 1).map { i -> Math.toDegrees(kotlin.math.atan2(ll.ys[i + 1] - ll.ys[i], ll.xs[i + 1] - ll.xs[i])) }
                println("REAL rumos " + hs.mapIndexed { i, h -> "%.1fm %.3f°".format(kotlin.math.hypot(ll.xs[i + 1] - ll.xs[i], ll.ys[i + 1] - ll.ys[i]), h) }.joinToString(" | "))
            }
            p.polylines.filter { it.layer == "F1-HZ-EIXO" }.forEach { ll -> println("REAL linha eixo ${ll.xs.size} -> ${ProjectAdvisor.pisFromLine(ll).map { Math.round(it.radius) }}") }
            println("REAL eixo da linha ${l.layer}: ${l.xs.size} vértices -> ${pis.size} PIs, raios ${pis.map { Math.round(it.radius) }}")
            val axis = AlignmentBuilder.fromPis("E", pis)
            println("REAL extensão ${axis.length}")
            val gr = GradeDesign.ground(axis, terrain, 6.0)
            println("REAL terreno no eixo: ${gr?.stations?.size} estacas de ${gr?.stations?.firstOrNull()} a ${gr?.stations?.lastOrNull()}, z ${gr?.z?.minOrNull()}..${gr?.z?.maxOrNull()}")
            val g = t("greide") { GradeDesign.suggest(axis, terrain, Template(), GradeDesign.GradeRules()) } as List<GradeSuggestion>
            g.forEach { println("REAL greide ${it.kind}: ${it.pvis.size} PVIs, rampa máx ${it.maxGrade}, corte ${it.cut.toInt()} aterro ${it.fill.toInt()}") }
            val f = t("escoamento 1024") { Hydrology.flow(terrain.rasterize(1024)!!) } as FlowGrid
            val c = Hydrology.crossings(f, axis.vertices, 5000.0)
            println("REAL bueiros: ${c.map { Math.round(it.area) }}")
            c.firstOrNull()?.let { s -> val b = Hydrology.basin(f, s.cell)!!; println("REAL bacia ${b.area} m², talvegue ${b.streamLength} m, tc ${b.kirpichMinutes} min") }
        }
        advice.firstOrNull { it.kind == Advice.Kind.PLATFORM_FROM_OUTLINE }?.let { a ->
            val l = p.polylines[a.line]
            val outline = List(l.xs.size) { Point2(l.xs[it], l.ys[it]) }
            val o = t("formas do platô") { PlatformOptions.suggest(outline, terrain, SlopeRule(1.0, 8.0, 3.0, 5.0), SlopeRule(1.5, 8.0, 3.0, 5.0)) } as List<PlatformOption>
            o.forEach { println("REAL platô ${it.kind}: cota ${it.baseElevation} rampa ${it.gradePercent} corte ${it.cut.toInt()} aterro ${it.fill.toInt()} talude ${it.maxSlopeHeight}") }
        }
    }

    @Test
    fun pnt() {
        val path = System.getenv("REAL_PNT") ?: return
        val f = File(path)
        val p = t("ler pnt e triangular") { AeccPointCache.read(f.inputStream().buffered(1 shl 16), f.length(), "BH") } as LandXmlProject
        val s = p.surfaces.first()
        println("REAL ${s.vertexCount} pontos, ${s.triangleCount} triângulos")
        for (n in listOf(1, Parallel.threads)) {
            Parallel.threads = n
            t("raster 2048 com $n núcleos") { s.rasterize(2048) }
            t("curvas 1 m com $n núcleos") { Contours.segments(s, 1.0) }
        }
        val g = s.rasterize(1024)!!
        val fl = t("escoamento 1024") { Hydrology.flow(g) } as FlowGrid
        val out = Hydrology.terrainOutlets(fl, 3)
        println("REAL saídas: ${out.map { Math.round(it.area) }}")
        val b = t("bacia da maior saída") { Hydrology.basin(fl, out.first().cell) } as Basin
        println("REAL bacia ${Math.round(b.area)} m², talvegue ${Math.round(b.streamLength)} m, desnível ${b.streamDrop}, tc ${b.kirpichMinutes} min, Kc ${b.compactness}")
        val bb = s.bounds!!
        val c = bb.center
        val i0 = (0 until s.vertexCount step 97).minByOrNull { kotlin.math.hypot(s.xs[it] - (c.x - 1200), s.ys[it] - (c.y - 1200)) }!!
        val i1 = (0 until s.vertexCount step 97).minByOrNull { kotlin.math.hypot(s.xs[it] - (c.x + 1200), s.ys[it] - (c.y + 1200)) }!!
        val a = Point2(s.xs[i0], s.ys[i0]); val e = Point2(s.xs[i1], s.ys[i1])
        println("REAL traçado de $a a $e (${kotlin.math.hypot(e.x - a.x, e.y - a.y)} m)")
        val routes = t("traçado") { RouteDesign.suggest(a, e, s, Template(), RouteDesign.RouteRules()) } as List<RouteSuggestion>
        routes.forEach { println("REAL rota ${it.kind}: ${Math.round(it.length)} m, ${it.pis.size - 2} curvas, rampa ${it.grade?.maxGrade}, corte ${it.grade?.cut?.toInt()} aterro ${it.grade?.fill?.toInt()}") }
    }
}
