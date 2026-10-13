package com.appdwg.core

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan

/** Reference ellipsoid: semi-major axis [a] (m) and inverse flattening [invF]. */
data class Ellipsoid(val name: String, val a: Double, val invF: Double) {
    val f get() = 1 / invF
    val e2 get() = f * (2 - f)

    companion object {
        val GRS80 = Ellipsoid("GRS 1980", 6378137.0, 298.257222101)
        val WGS84 = Ellipsoid("WGS 84", 6378137.0, 298.257223563)
        /** SAD 69 (GRS 1967 modified). */
        val GRS67 = Ellipsoid("GRS 1967 Modified", 6378160.0, 298.25)
        /** Córrego Alegre. */
        val INTERNATIONAL = Ellipsoid("International 1924", 6378388.0, 297.0)
        val all = listOf(GRS80, WGS84, GRS67, INTERNATIONAL)
    }
}

/**
 * Seven-parameter Helmert transformation from a datum to SIRGAS 2000, position-vector convention:
 * translations in metres, rotations in arc-seconds, scale in ppm. Three-parameter ones leave the
 * rest at zero.
 */
data class Helmert(
    val dx: Double = 0.0, val dy: Double = 0.0, val dz: Double = 0.0,
    val rx: Double = 0.0, val ry: Double = 0.0, val rz: Double = 0.0, val ds: Double = 0.0,
) {
    val isIdentity get() = dx == 0.0 && dy == 0.0 && dz == 0.0 && rx == 0.0 && ry == 0.0 && rz == 0.0 && ds == 0.0

    fun apply(p: DoubleArray, inverse: Boolean = false): DoubleArray {
        val sgn = if (inverse) -1.0 else 1.0
        val k = ASEC
        val rx = sgn * rx * k; val ry = sgn * ry * k; val rz = sgn * rz * k
        val s = 1 + sgn * ds * 1e-6
        val (x, y, z) = Triple(p[0], p[1], p[2])
        if (inverse) {
            // Subtract the shift first, then undo rotation and scale (small-angle).
            val a = x - dx; val b = y - dy; val c = z - dz
            return doubleArrayOf(s * (a - rz * b + ry * c), s * (rz * a + b - rx * c), s * (-ry * a + rx * b + c))
        }
        return doubleArrayOf(dx + s * (x - rz * y + ry * z), dy + s * (rz * x + y - rx * z), dz + s * (-ry * x + rx * y + z))
    }

    private companion object { const val ASEC = PI / (180 * 3600) }
}

/** Geodetic datum with its ellipsoid and its transformation to SIRGAS 2000. */
data class Datum(val id: String, val name: String, val ellipsoid: Ellipsoid, val toSirgas: Helmert, val wktName: String = name.replace(' ', '_')) {
    companion object {
        val SIRGAS2000 = Datum("sirgas2000", "SIRGAS 2000", Ellipsoid.GRS80, Helmert(), "D_SIRGAS_2000")
        /** For field work WGS 84 (G1762 and later) and SIRGAS 2000 agree to a few centimetres. */
        val WGS84 = Datum("wgs84", "WGS 84", Ellipsoid.WGS84, Helmert(), "D_WGS_1984")
        /** IBGE, R.PR 1/2005: SAD 69 → SIRGAS 2000. */
        val SAD69 = Datum("sad69", "SAD 69", Ellipsoid.GRS67, Helmert(-67.35, 3.88, -38.22), "D_South_American_1969")
        /** IBGE, R.PR 1/2005: SAD 69 (realização 1996) → SIRGAS 2000. */
        val SAD69_96 = Datum("sad69_96", "SAD 69 (96)", Ellipsoid.GRS67, Helmert(-66.87, 4.37, -38.52), "D_South_American_1969_96")
        /** IBGE, R.PR 1/2005: Córrego Alegre → SIRGAS 2000. */
        val CORREGO_ALEGRE = Datum("corrego", "Córrego Alegre", Ellipsoid.INTERNATIONAL, Helmert(-206.05, 168.28, -3.82), "D_Corrego_Alegre")
        val all = listOf(SIRGAS2000, WGS84, SAD69, SAD69_96, CORREGO_ALEGRE)
    }
}

/** Map projection of a coordinate system. */
sealed interface Projection {
    /** Latitude and longitude in degrees, unprojected. */
    data object Geographic : Projection
    /** Transverse Mercator (UTM, LTM, RTM or any other), angles in degrees. */
    data class TransverseMercator(val lat0: Double, val lon0: Double, val k0: Double, val falseEasting: Double, val falseNorthing: Double) : Projection
}

/**
 * A coordinate reference system. [local] marks a site grid with no known projection: positions are
 * projected in [projection] (UTM) and then moved by the site calibration.
 */
data class Crs(
    val id: String,
    val name: String,
    val epsg: String?,
    val datum: Datum,
    val projection: Projection,
    val custom: Boolean = false,
    val local: Boolean = false,
) {
    val isGeographic get() = projection is Projection.Geographic

    /** Plan coordinates (E, N) of a WGS 84 / SIRGAS 2000 position. Geographic systems give (lon, lat). */
    fun fromWgs84(latDeg: Double, lonDeg: Double, h: Double = 0.0): Point2 {
        val (la, lo) = if (datum.toSirgas.isIdentity && datum.ellipsoid == Ellipsoid.GRS80 || datum == Datum.WGS84) latDeg to lonDeg
        else Geodesy3.shift(latDeg, lonDeg, h, Ellipsoid.GRS80, datum.ellipsoid, datum.toSirgas, inverse = true)
        return when (val p = projection) {
            Projection.Geographic -> Point2(lo, la)
            is Projection.TransverseMercator -> TM.forward(la, lo, p, datum.ellipsoid)
        }
    }

    /** WGS 84 / SIRGAS 2000 latitude and longitude of plan coordinates (E, N). */
    fun toWgs84(e: Double, n: Double): Pair<Double, Double> {
        val (la, lo) = when (val p = projection) {
            Projection.Geographic -> n to e
            is Projection.TransverseMercator -> TM.inverse(e, n, p, datum.ellipsoid)
        }
        if (datum.toSirgas.isIdentity && datum.ellipsoid == Ellipsoid.GRS80 || datum == Datum.WGS84) return la to lo
        return Geodesy3.shift(la, lo, 0.0, datum.ellipsoid, Ellipsoid.GRS80, datum.toSirgas, inverse = false)
    }

    /** ESRI-style WKT for a shapefile's .prj. */
    fun prj(): String {
        val e = datum.ellipsoid
        val geog = "GEOGCS[\"GCS_${datum.wktName.removePrefix("D_")}\",DATUM[\"${datum.wktName}\",SPHEROID[\"${e.name.replace(' ', '_')}\",${e.a},${e.invF}]]," +
            "PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]]"
        return when (val p = projection) {
            Projection.Geographic -> geog
            is Projection.TransverseMercator -> "PROJCS[\"${name.replace(' ', '_').replace("/", "")}\",$geog,PROJECTION[\"Transverse_Mercator\"]," +
                "PARAMETER[\"False_Easting\",${p.falseEasting}],PARAMETER[\"False_Northing\",${p.falseNorthing}]," +
                "PARAMETER[\"Central_Meridian\",${p.lon0}],PARAMETER[\"Scale_Factor\",${p.k0}],PARAMETER[\"Latitude_Of_Origin\",${p.lat0}],UNIT[\"Meter\",1.0]]"
        }
    }
}

/** Datum shifts through geocentric coordinates. */
object Geodesy3 {
    fun toEcef(latDeg: Double, lonDeg: Double, h: Double, el: Ellipsoid): DoubleArray {
        val la = Math.toRadians(latDeg); val lo = Math.toRadians(lonDeg)
        val n = el.a / sqrt(1 - el.e2 * sin(la) * sin(la))
        return doubleArrayOf((n + h) * cos(la) * cos(lo), (n + h) * cos(la) * sin(lo), (n * (1 - el.e2) + h) * sin(la))
    }

    fun fromEcef(p: DoubleArray, el: Ellipsoid): Triple<Double, Double, Double> {
        val (x, y, z) = Triple(p[0], p[1], p[2])
        val lon = atan2(y, x)
        val r = hypot(x, y)
        var lat = atan2(z, r * (1 - el.e2))
        var h = 0.0
        repeat(8) {
            val n = el.a / sqrt(1 - el.e2 * sin(lat) * sin(lat))
            h = r / cos(lat) - n
            lat = atan2(z, r * (1 - el.e2 * n / (n + h)))
        }
        return Triple(Math.toDegrees(lat), Math.toDegrees(lon), h)
    }

    /** Position on [from] moved to [to] by [helmert] (datum → SIRGAS), or back with [inverse]. */
    fun shift(latDeg: Double, lonDeg: Double, h: Double, from: Ellipsoid, to: Ellipsoid, helmert: Helmert, inverse: Boolean): Pair<Double, Double> {
        val p = helmert.apply(toEcef(latDeg, lonDeg, h, from), inverse)
        val (la, lo, _) = fromEcef(p, to)
        return la to lo
    }
}

/** Transverse Mercator on any ellipsoid (Krüger series to n⁴, sub-millimetre within ±4° of the meridian). */
object TM {
    private class Series(el: Ellipsoid) {
        val n = el.f / (2 - el.f)
        val aa = el.a / (1 + n) * (1 + n * n / 4 + n * n * n * n / 64)
        val al = doubleArrayOf(n / 2 - 2 * n * n / 3 + 5 * n * n * n / 16 + 41 * n * n * n * n / 180,
            13 * n * n / 48 - 3 * n * n * n / 5 + 557 * n * n * n * n / 1440,
            61 * n * n * n / 240 - 103 * n * n * n * n / 140, 49561 * n * n * n * n / 161280)
        val be = doubleArrayOf(n / 2 - 2 * n * n / 3 + 37 * n * n * n / 96 - n * n * n * n / 360,
            n * n / 48 + n * n * n / 15 - 437 * n * n * n * n / 1440, 17 * n * n * n / 480 - 37 * n * n * n * n / 840, 4397 * n * n * n * n / 161280)
        val e = sqrt(el.e2)
    }

    private fun atanh(v: Double) = 0.5 * Math.log((1 + v) / (1 - v))

    /** Unscaled (xi, eta) times A of [lat]/[dLon] from the central meridian. */
    private fun raw(latDeg: Double, dLonDeg: Double, s: Series): Pair<Double, Double> {
        val phi = Math.toRadians(latDeg); val lam = Math.toRadians(dLonDeg)
        val t = sinh(atanh(sin(phi)) - s.e * atanh(s.e * sin(phi)))
        val xi0 = atan2(t, cos(lam)); val eta0 = atanh(sin(lam) / sqrt(1 + t * t))
        var xi = xi0; var eta = eta0
        for (j in 1..4) { xi += s.al[j - 1] * sin(2 * j * xi0) * cosh(2 * j * eta0); eta += s.al[j - 1] * cos(2 * j * xi0) * sinh(2 * j * eta0) }
        return s.aa * xi to s.aa * eta
    }

    fun forward(latDeg: Double, lonDeg: Double, p: Projection.TransverseMercator, el: Ellipsoid): Point2 {
        val s = Series(el)
        val (y, x) = raw(latDeg, lonDeg - p.lon0, s)
        val y0 = if (p.lat0 == 0.0) 0.0 else raw(p.lat0, 0.0, s).first
        return Point2(p.falseEasting + p.k0 * x, p.falseNorthing + p.k0 * (y - y0))
    }

    fun inverse(e: Double, n: Double, p: Projection.TransverseMercator, el: Ellipsoid): Pair<Double, Double> {
        val s = Series(el)
        val y0 = if (p.lat0 == 0.0) 0.0 else raw(p.lat0, 0.0, s).first
        val xi = ((n - p.falseNorthing) / p.k0 + y0) / s.aa
        val eta = (e - p.falseEasting) / p.k0 / s.aa
        var xi1 = xi; var eta1 = eta
        for (j in 1..4) { xi1 -= s.be[j - 1] * sin(2 * j * xi) * cosh(2 * j * eta); eta1 -= s.be[j - 1] * cos(2 * j * xi) * sinh(2 * j * eta) }
        val chi = kotlin.math.asin(sin(xi1) / cosh(eta1))
        val lon = p.lon0 + Math.toDegrees(atan2(sinh(eta1), cos(xi1)))
        var phi = chi
        repeat(8) {
            val sp = sin(phi)
            phi = 2 * atan(tan(PI / 4 + chi / 2) * Math.pow((1 + s.e * sp) / (1 - s.e * sp), s.e / 2)) - PI / 2
        }
        return Math.toDegrees(phi) to lon
    }
}

/** Built-in coordinate systems (no internet), searchable by name or EPSG code. */
object CrsCatalog {
    fun utm(datum: Datum, zone: Int, south: Boolean, epsg: String?, prefix: String = datum.name) = Crs(
        "${datum.id}_utm_$zone${if (south) "s" else "n"}", "$prefix / UTM ${zone}${if (south) "S" else "N"}", epsg, datum,
        Projection.TransverseMercator(0.0, zone * 6.0 - 183.0, 0.9996, 500_000.0, if (south) 10_000_000.0 else 0.0),
    )

    /** LTM: 1° zones, k0 0,999995, E 200 000, N 5 000 000 (south), SIRGAS 2000. */
    fun ltm(centralMeridian: Double) = Crs("ltm_" + centralMeridian, "SIRGAS 2000 / LTM MC ${fmt(centralMeridian)}°", null, Datum.SIRGAS2000,
        Projection.TransverseMercator(0.0, centralMeridian, 0.999995, 200_000.0, 5_000_000.0))

    /** RTM: 2° zones, k0 0,999995, E 400 000, N 5 000 000 (south), SIRGAS 2000. */
    fun rtm(centralMeridian: Double) = Crs("rtm_" + centralMeridian, "SIRGAS 2000 / RTM MC ${fmt(centralMeridian)}°", null, Datum.SIRGAS2000,
        Projection.TransverseMercator(0.0, centralMeridian, 0.999995, 400_000.0, 5_000_000.0))

    /** Site grid without a known projection: UTM of [zone] corrected by the site calibration. */
    fun local(zone: Int, south: Boolean = true) = utm(Datum.SIRGAS2000, zone, south, null).copy(
        id = "local_$zone${if (south) "s" else "n"}", name = "Local / sem projeção (calibração sobre UTM ${zone}${if (south) "S" else "N"})", local = true)

    private fun fmt(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else v.toString()

    val all: List<Crs> = buildList {
        add(Crs("sirgas2000_geo", "SIRGAS 2000 (geográficas)", "4674", Datum.SIRGAS2000, Projection.Geographic))
        for (z in 17..25) add(utm(Datum.SIRGAS2000, z, true, (31960 + z).toString()))
        for (z in 18..22) add(utm(Datum.SIRGAS2000, z, false, (31954 + z).toString()))
        add(Crs("sad69_geo", "SAD 69 (geográficas)", "4618", Datum.SAD69, Projection.Geographic))
        for (z in 17..25) add(utm(Datum.SAD69, z, true, (29170 + z).toString()))
        for (z in 18..22) add(utm(Datum.SAD69, z, false, (29150 + z).toString()))
        add(Crs("sad69_96_geo", "SAD 69 (96) (geográficas)", "5527", Datum.SAD69_96, Projection.Geographic))
        for (z in 17..25) add(utm(Datum.SAD69_96, z, true, null))
        for (z in 18..22) add(utm(Datum.SAD69_96, z, false, null))
        add(Crs("corrego_geo", "Córrego Alegre (geográficas)", "4225", Datum.CORREGO_ALEGRE, Projection.Geographic))
        for (z in 21..25) add(utm(Datum.CORREGO_ALEGRE, z, true, (22500 + z).toString()))
        add(Crs("wgs84_geo", "WGS 84 (geográficas)", "4326", Datum.WGS84, Projection.Geographic))
        for (z in 17..25) add(utm(Datum.WGS84, z, true, (32700 + z).toString()))
        for (z in 18..22) add(utm(Datum.WGS84, z, false, (32600 + z).toString()))
    }

    fun byId(id: String?) = all.firstOrNull { it.id == id }
    fun byEpsg(code: String?) = code?.trim()?.removePrefix("EPSG:")?.let { c -> all.firstOrNull { it.epsg == c } }

    /** Systems whose name or EPSG code contains every word of [query]. */
    fun search(query: String, extra: List<Crs> = emptyList()): List<Crs> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        val pool = extra + all
        if (words.isEmpty()) return pool
        return pool.filter { c -> val t = (c.name + " " + (c.epsg ?: "")).lowercase().replace("ó", "o"); words.all { it.replace("ó", "o") in t } }
    }

    /** SIRGAS 2000 / UTM zone of a longitude (south of the equator by default). */
    fun utmFor(lonDeg: Double, south: Boolean = true) = utm(Datum.SIRGAS2000, (Math.floor((lonDeg + 180) / 6).toInt() + 1).coerceIn(1, 60), south,
        null).let { c -> all.firstOrNull { it.id == c.id } ?: c }

}
