package com.appdwg.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
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
        /** OSGB36 (British National Grid). */
        val AIRY = Ellipsoid("Airy 1830", 6377563.396, 299.3249646)
        val all = listOf(GRS80, WGS84, GRS67, INTERNATIONAL, AIRY)
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
        // Modern frames tied to ITRF: within a metre or so of WGS 84 (PROJ also uses no shift for them).
        val NAD83 = Datum("nad83", "NAD83", Ellipsoid.GRS80, Helmert(), "D_North_American_1983")
        val NAD83_2011 = Datum("nad83_2011", "NAD83(2011)", Ellipsoid.GRS80, Helmert(), "D_NAD_1983_2011")
        val NAD83_CSRS = Datum("nad83_csrs", "NAD83(CSRS)", Ellipsoid.GRS80, Helmert(), "D_North_American_1983_CSRS")
        val POSGAR2007 = Datum("posgar2007", "POSGAR 2007", Ellipsoid.WGS84, Helmert(), "D_POSGAR_2007")
        val MAGNA = Datum("magna", "MAGNA-SIRGAS", Ellipsoid.GRS80, Helmert(), "D_MAGNA")
        val SIRGAS_CHILE = Datum("sirgas_chile", "SIRGAS-Chile 2016", Ellipsoid.GRS80, Helmert(), "D_SIRGAS-Chile_2016")
        val MEXICO_ITRF2008 = Datum("mex_itrf2008", "Mexico ITRF2008", Ellipsoid.GRS80, Helmert(), "D_Mexico_ITRF2008")
        val ETRS89 = Datum("etrs89", "ETRS89", Ellipsoid.GRS80, Helmert(), "D_ETRS_1989")
        val RGF93 = Datum("rgf93", "RGF93", Ellipsoid.GRS80, Helmert(), "D_RGF_1993")
        /** EPSG 1314 (Ordnance Survey), position vector. */
        val OSGB36 = Datum("osgb36", "OSGB36", Ellipsoid.AIRY, Helmert(446.448, -125.157, 542.06, 0.15, 0.247, 0.842, -20.489), "D_OSGB_1936")
        val GDA2020 = Datum("gda2020", "GDA2020", Ellipsoid.GRS80, Helmert(), "D_GDA2020")
        val NZGD2000 = Datum("nzgd2000", "NZGD2000", Ellipsoid.GRS80, Helmert(), "D_NZGD_2000")
        val all = listOf(SIRGAS2000, WGS84, SAD69, SAD69_96, CORREGO_ALEGRE, NAD83, NAD83_2011, NAD83_CSRS, POSGAR2007, MAGNA, SIRGAS_CHILE,
            MEXICO_ITRF2008, ETRS89, RGF93, OSGB36, GDA2020, NZGD2000)
    }
}

/** Map projection of a coordinate system. */
sealed interface Projection {
    /** Latitude and longitude in degrees, unprojected. */
    data object Geographic : Projection
    /** Transverse Mercator (UTM, LTM, RTM or any other), angles in degrees. */
    data class TransverseMercator(val lat0: Double, val lon0: Double, val k0: Double, val falseEasting: Double, val falseNorthing: Double) : Projection
    /**
     * Lambert Conformal Conic. Two standard parallels ([lat1], [lat2]) with origin [lat0]/[lon0], or one
     * ([lat2] null) with scale [k0] at [lat1] = [lat0].
     */
    data class LambertConic(val lat0: Double, val lon0: Double, val lat1: Double, val lat2: Double?, val k0: Double,
                            val falseEasting: Double, val falseNorthing: Double) : Projection
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
    /** Country or region it is grouped under in the list. */
    val region: String = "",
) {
    val isGeographic get() = projection is Projection.Geographic

    /** Plan coordinates (E, N) of a WGS 84 / SIRGAS 2000 position. Geographic systems give (lon, lat). */
    fun fromWgs84(latDeg: Double, lonDeg: Double, h: Double = 0.0): Point2 {
        val (la, lo) = if (datum.toSirgas.isIdentity && datum.ellipsoid == Ellipsoid.GRS80 || datum == Datum.WGS84) latDeg to lonDeg
        else Geodesy3.shift(latDeg, lonDeg, h, Ellipsoid.GRS80, datum.ellipsoid, datum.toSirgas, inverse = true)
        return when (val p = projection) {
            Projection.Geographic -> Point2(lo, la)
            is Projection.TransverseMercator -> TM.forward(la, lo, p, datum.ellipsoid)
            is Projection.LambertConic -> LCC.forward(la, lo, p, datum.ellipsoid)
        }
    }

    /** WGS 84 / SIRGAS 2000 latitude and longitude of plan coordinates (E, N). */
    fun toWgs84(e: Double, n: Double): Pair<Double, Double> {
        val (la, lo) = when (val p = projection) {
            Projection.Geographic -> n to e
            is Projection.TransverseMercator -> TM.inverse(e, n, p, datum.ellipsoid)
            is Projection.LambertConic -> LCC.inverse(e, n, p, datum.ellipsoid)
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
            is Projection.LambertConic -> "PROJCS[\"${name.replace(' ', '_').replace("/", "")}\",$geog,PROJECTION[\"Lambert_Conformal_Conic\"]," +
                "PARAMETER[\"False_Easting\",${p.falseEasting}],PARAMETER[\"False_Northing\",${p.falseNorthing}],PARAMETER[\"Central_Meridian\",${p.lon0}]," +
                "PARAMETER[\"Standard_Parallel_1\",${p.lat1}],PARAMETER[\"Standard_Parallel_2\",${p.lat2 ?: p.lat1}],PARAMETER[\"Scale_Factor\",${p.k0}]," +
                "PARAMETER[\"Latitude_Of_Origin\",${p.lat0}],UNIT[\"Meter\",1.0]]"
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

    /** Northing of the origin latitude on the central meridian; at a pole it is a quarter meridian. */
    private fun originY(lat0: Double, s: Series) = when {
        lat0 == 0.0 -> 0.0
        abs(lat0) >= 89.999999 -> Math.signum(lat0) * s.aa * PI / 2
        else -> raw(lat0, 0.0, s).first
    }

    fun forward(latDeg: Double, lonDeg: Double, p: Projection.TransverseMercator, el: Ellipsoid): Point2 {
        val s = Series(el)
        val (y, x) = raw(latDeg, lonDeg - p.lon0, s)
        val y0 = originY(p.lat0, s)
        return Point2(p.falseEasting + p.k0 * x, p.falseNorthing + p.k0 * (y - y0))
    }

    fun inverse(e: Double, n: Double, p: Projection.TransverseMercator, el: Ellipsoid): Pair<Double, Double> {
        val s = Series(el)
        val y0 = originY(p.lat0, s)
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

/** Lambert Conformal Conic (EPSG 9801 one parallel, 9802 two parallels). */
object LCC {
    private fun m(phi: Double, e2: Double) = cos(phi) / sqrt(1 - e2 * sin(phi) * sin(phi))
    private fun t(phi: Double, e: Double) = tan(PI / 4 - phi / 2) / ((1 - e * sin(phi)) / (1 + e * sin(phi))).pow(e / 2)

    private class Cone(val n: Double, val af: Double, val r0: Double)

    private fun cone(p: Projection.LambertConic, el: Ellipsoid): Cone {
        val e2 = el.e2; val e = sqrt(e2)
        val f1 = Math.toRadians(p.lat1); val f0 = Math.toRadians(p.lat0)
        return if (p.lat2 == null) {
            val n = sin(f0)
            val af = el.a * m(f0, e2) / (n * t(f0, e).pow(n)) * p.k0
            Cone(n, af, af * t(f0, e).pow(n))
        } else {
            val f2 = Math.toRadians(p.lat2)
            val n = if (abs(p.lat1 - p.lat2) < 1e-12) sin(f1)
                else (ln(m(f1, e2)) - ln(m(f2, e2))) / (ln(t(f1, e)) - ln(t(f2, e)))
            val af = el.a * m(f1, e2) / (n * t(f1, e).pow(n))
            Cone(n, af, af * t(f0, e).pow(n))
        }
    }

    fun forward(latDeg: Double, lonDeg: Double, p: Projection.LambertConic, el: Ellipsoid): Point2 {
        val c = cone(p, el); val e = sqrt(el.e2)
        val r = c.af * t(Math.toRadians(latDeg), e).pow(c.n)
        val th = c.n * Math.toRadians(lonDeg - p.lon0)
        return Point2(p.falseEasting + r * sin(th), p.falseNorthing + c.r0 - r * cos(th))
    }

    fun inverse(east: Double, north: Double, p: Projection.LambertConic, el: Ellipsoid): Pair<Double, Double> {
        val c = cone(p, el); val e = sqrt(el.e2)
        val x = east - p.falseEasting; val y = c.r0 - (north - p.falseNorthing)
        val r = Math.signum(c.n) * sqrt(x * x + y * y)
        val th = atan2(Math.signum(c.n) * x, Math.signum(c.n) * y)
        val tt = (r / c.af).pow(1 / c.n)
        var phi = PI / 2 - 2 * atan(tt)
        repeat(10) { phi = PI / 2 - 2 * atan(tt * ((1 - e * sin(phi)) / (1 + e * sin(phi))).pow(e / 2)) }
        return Math.toDegrees(phi) to p.lon0 + Math.toDegrees(th / c.n)
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

    /** Regions in the list, in the order they appear after the user's own region. */
    const val BRAZIL = "Brasil"
    const val NORTH_AMERICA = "EUA e Canadá"
    const val MEXICO = "México"
    const val ARGENTINA = "Argentina"
    const val COLOMBIA = "Colômbia"
    const val CHILE = "Chile"
    const val SOUTH_AMERICA = "América do Sul (Peru, Equador, Uruguai, Paraguai, Bolívia, Venezuela)"
    const val PORTUGAL = "Portugal"
    const val FRANCE = "França"
    const val UK = "Reino Unido"
    const val EUROPE = "Europa (ETRS89, inclui Alemanha e Espanha)"
    const val AUSTRALIA = "Austrália"
    const val NEW_ZEALAND = "Nova Zelândia"
    const val WORLD = "Mundo (WGS 84)"
    val regions = listOf(BRAZIL, NORTH_AMERICA, MEXICO, ARGENTINA, COLOMBIA, CHILE, SOUTH_AMERICA, PORTUGAL, FRANCE, UK, EUROPE, AUSTRALIA, NEW_ZEALAND, WORLD)

    /** Region of a country code (ISO 3166), to list it first. */
    fun regionOf(country: String): String? = when (country.uppercase()) {
        "BR" -> BRAZIL; "US", "CA" -> NORTH_AMERICA; "MX" -> MEXICO; "AR" -> ARGENTINA; "CO" -> COLOMBIA; "CL" -> CHILE
        "PE", "EC", "UY", "PY", "BO", "VE" -> SOUTH_AMERICA; "PT" -> PORTUGAL; "FR" -> FRANCE; "GB", "UK" -> UK
        "DE", "ES", "IT", "NL", "BE", "AT", "CH", "PL", "IE", "DK", "SE", "NO", "FI", "CZ", "GR", "RO", "HU" -> EUROPE
        "AU" -> AUSTRALIA; "NZ" -> NEW_ZEALAND; else -> null
    }

    private fun tm(id: String, name: String, epsg: String, datum: Datum, region: String, lat0: Double, lon0: Double, k0: Double, fe: Double, fn: Double) =
        Crs(id, name, epsg, datum, Projection.TransverseMercator(lat0, lon0, k0, fe, fn), region = region)

    val all: List<Crs> = buildList {
        fun utms(datum: Datum, region: String, zones: Map<String, String?>) {
            for ((z, code) in zones) add(utm(datum, z.dropLast(1).toInt(), z.endsWith("S"), code).copy(region = region))
        }
        fun range(from: Int, to: Int, south: Boolean, code: (Int) -> String?) = (from..to).associate { "$it${if (south) "S" else "N"}" to code(it) }
        // Brasil
        add(Crs("sirgas2000_geo", "SIRGAS 2000 (geográficas)", "4674", Datum.SIRGAS2000, Projection.Geographic, region = BRAZIL))
        utms(Datum.SIRGAS2000, BRAZIL, range(17, 25, true) { (31960 + it).toString() } + range(18, 22, false) { (31954 + it).toString() } +
            mapOf("23N" to "6210", "24N" to "6211"))
        add(Crs("sad69_geo", "SAD 69 (geográficas)", "4618", Datum.SAD69, Projection.Geographic, region = BRAZIL))
        utms(Datum.SAD69, BRAZIL, range(17, 25, true) { (29170 + it).toString() } + range(18, 22, false) { (29150 + it).toString() })
        add(Crs("sad69_96_geo", "SAD 69 (96) (geográficas)", "5527", Datum.SAD69_96, Projection.Geographic, region = BRAZIL))
        utms(Datum.SAD69_96, BRAZIL, mapOf("18S" to "5875", "19S" to "5876", "20S" to "5877", "21S" to "5531", "22S" to "5858", "23S" to "5533",
            "24S" to "5534", "25S" to "5535"))
        add(Crs("corrego_geo", "Córrego Alegre (geográficas)", "4225", Datum.CORREGO_ALEGRE, Projection.Geographic, region = BRAZIL))
        utms(Datum.CORREGO_ALEGRE, BRAZIL, range(21, 25, true) { (22500 + it).toString() })
        // EUA e Canadá
        utms(Datum.NAD83, NORTH_AMERICA, range(10, 19, false) { (26900 + it).toString() })
        utms(Datum.NAD83_2011, NORTH_AMERICA, range(10, 19, false) { (6329 + it).toString() })
        utms(Datum.NAD83_CSRS, NORTH_AMERICA, mapOf("7N" to "3154", "8N" to "3155", "9N" to "3156", "10N" to "3157", "11N" to "2955", "12N" to "2956",
            "13N" to "2957", "14N" to "3158", "15N" to "3159", "16N" to "3160", "17N" to "2958", "18N" to "2959", "19N" to "2960", "20N" to "2961",
            "21N" to "2962", "22N" to "3761"))
        // México
        utms(Datum.MEXICO_ITRF2008, MEXICO, range(11, 16, false) { (6355 + it).toString() })
        add(Crs("mex_lcc", "Mexico ITRF2008 / LCC", "6372", Datum.MEXICO_ITRF2008, Projection.LambertConic(12.0, -102.0, 17.5, 29.5, 1.0, 2_500_000.0, 0.0), region = MEXICO))
        // Argentina: POSGAR 2007 faixas 1-7
        for (f in 1..7) add(tm("posgar_$f", "POSGAR 2007 / Argentina $f", (5342 + f).toString(), Datum.POSGAR2007, ARGENTINA,
            -90.0, -75.0 + 3 * f, 1.0, f * 1_000_000.0 + 500_000.0, 0.0))
        // Colômbia
        add(tm("ctm12", "MAGNA-SIRGAS 2018 / Origen-Nacional (CTM12)", "9377", Datum.MAGNA, COLOMBIA, 4.0, -73.0, 0.9992, 5_000_000.0, 2_000_000.0))
        for ((nm, code, lon) in listOf(Triple("Far West", "3114", -80.0775079166667), Triple("West", "3115", -77.0775079166667),
            Triple("Bogota", "3116", -74.0775079166667), Triple("East Central", "3117", -71.0775079166667), Triple("East", "3118", -68.0775079166667)))
            add(tm("magna_$code", "MAGNA-SIRGAS / Colombia $nm zone", code, Datum.MAGNA, COLOMBIA, 4.59620041666667, lon, 1.0, 1_000_000.0, 1_000_000.0))
        // Chile
        utms(Datum.SIRGAS_CHILE, CHILE, mapOf("18S" to "9154", "19S" to "9155"))
        // América do Sul: SIRGAS 2000 e WGS 84 nos fusos da região
        utms(Datum.WGS84, SOUTH_AMERICA, range(17, 21, true) { (32700 + it).toString() } + range(17, 21, false) { (32600 + it).toString() })
        // Europa
        add(tm("pttm06", "ETRS89 / Portugal TM06", "3763", Datum.ETRS89, PORTUGAL, 39.6682583333333, -8.13310833333333, 1.0, 0.0, 0.0))
        add(Crs("lambert93", "RGF93 / Lambert-93", "2154", Datum.RGF93, Projection.LambertConic(46.5, 3.0, 49.0, 44.0, 1.0, 700_000.0, 6_600_000.0), region = FRANCE))
        add(tm("bng", "OSGB36 / British National Grid", "27700", Datum.OSGB36, UK, 49.0, -2.0, 0.9996012717, 400_000.0, -100_000.0))
        utms(Datum.ETRS89, EUROPE, range(28, 38, false) { (25800 + it).toString() })
        // Oceania
        for (z in 49..56) add(tm("mga_$z", "GDA2020 / MGA zone $z", (7800 + z).toString(), Datum.GDA2020, AUSTRALIA, 0.0, z * 6.0 - 183.0, 0.9996, 500_000.0, 10_000_000.0))
        add(tm("nztm", "NZGD2000 / NZTM 2000", "2193", Datum.NZGD2000, NEW_ZEALAND, 0.0, 173.0, 0.9996, 1_600_000.0, 10_000_000.0))
        // Mundo
        add(Crs("wgs84_geo", "WGS 84 (geográficas)", "4326", Datum.WGS84, Projection.Geographic, region = WORLD))
        utms(Datum.WGS84, WORLD, range(1, 60, true) { (32700 + it).toString() }.filterKeys { it.dropLast(1).toInt() !in 17..21 } +
            range(1, 60, false) { (32600 + it).toString() }.filterKeys { it.dropLast(1).toInt() !in 17..21 })
    }

    fun byId(id: String?) = all.firstOrNull { it.id == id }
    fun byEpsg(code: String?) = code?.trim()?.removePrefix("EPSG:")?.let { c -> all.firstOrNull { it.epsg == c } }

    /** Systems whose name or EPSG code contains every word of [query]. */
    fun search(query: String, extra: List<Crs> = emptyList()): List<Crs> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        val pool = extra + all
        if (words.isEmpty()) return pool
        fun norm(x: String) = java.text.Normalizer.normalize(x.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
        return pool.filter { c -> val t = norm(c.name + " " + (c.epsg ?: "") + " " + c.region); words.all { norm(it) in t } }
    }

    /** Regions in list order: the user's own first, then the rest as declared. */
    fun regionOrder(country: String): List<String> {
        val own = regionOf(country)
        return listOfNotNull(own) + regions.filter { it != own }
    }

    /** SIRGAS 2000 / UTM zone of a longitude (south of the equator by default). */
    fun utmFor(lonDeg: Double, south: Boolean = true) = utm(Datum.SIRGAS2000, (Math.floor((lonDeg + 180) / 6).toInt() + 1).coerceIn(1, 60), south,
        null).let { c -> all.firstOrNull { it.id == c.id } ?: c }

}

/** Coordinate systems read from a PROJ string (as epsg.io serves them), for systems outside the catalogue. */
object Proj4 {
    class Unsupported(message: String) : Exception(message)

    private val ellipsoids = mapOf(
        "GRS80" to Ellipsoid.GRS80, "WGS84" to Ellipsoid.WGS84, "aust_SA" to Ellipsoid.GRS67, "intl" to Ellipsoid.INTERNATIONAL, "airy" to Ellipsoid.AIRY,
        "clrk66" to Ellipsoid("Clarke 1866", 6378206.4, 294.978698213898), "bessel" to Ellipsoid("Bessel 1841", 6377397.155, 299.1528128),
        "krass" to Ellipsoid("Krassowsky 1940", 6378245.0, 298.3), "clrk80" to Ellipsoid("Clarke 1880 (RGS)", 6378249.145, 293.465),
        "GRS67" to Ellipsoid("GRS 1967", 6378160.0, 298.247167427),
    )

    /** [code] is the EPSG number, [name] its title; throws [Unsupported] for projections or datums the app cannot use. */
    fun parse(code: String, name: String, proj4: String): Crs {
        val p = HashMap<String, String>()
        for (tok in proj4.trim().split(Regex("\\s+"))) {
            val t = tok.removePrefix("+"); val i = t.indexOf('=')
            if (i < 0) p[t] = "" else p[t.substring(0, i)] = t.substring(i + 1)
        }
        fun d(k: String, def: Double? = null): Double = p[k]?.toDoubleOrNull() ?: def ?: throw Unsupported("parâmetro $k ausente")
        if (p["units"] != null && p["units"] != "m" && p["proj"] != "longlat") throw Unsupported("unidade ${p["units"]} (só metros)")
        if (p.containsKey("nadgrids")) throw Unsupported("transformação por grade (nadgrids)")
        val ellipsoid = when {
            p["a"] != null && p["rf"] != null -> Ellipsoid("a=${p["a"]}", d("a"), d("rf"))
            p["a"] != null && p["b"] != null -> d("a").let { a -> Ellipsoid("a=${p["a"]}", a, a / (a - d("b"))) }
            p["ellps"] != null -> ellipsoids[p["ellps"]] ?: throw Unsupported("elipsoide ${p["ellps"]}")
            p["datum"] == "WGS84" -> Ellipsoid.WGS84
            p["datum"] == "NAD83" -> Ellipsoid.GRS80
            else -> throw Unsupported("elipsoide não informado")
        }
        val tw = p["towgs84"]?.split(',')?.map { it.toDouble() }
        val helmert = when {
            tw == null -> if (p["datum"] != null && p["datum"] !in setOf("WGS84", "NAD83")) throw Unsupported("datum ${p["datum"]} sem parâmetros") else Helmert()
            tw.size >= 7 -> Helmert(tw[0], tw[1], tw[2], tw[3], tw[4], tw[5], tw[6])
            tw.size >= 3 -> Helmert(tw[0], tw[1], tw[2])
            else -> throw Unsupported("towgs84 incompleto")
        }
        val datum = Datum("epsg$code", name.substringBefore(" /").trim().ifEmpty { "EPSG $code" }, ellipsoid, helmert)
        val projection = when (p["proj"]) {
            "longlat", "latlong" -> Projection.Geographic
            "utm" -> { val z = d("zone").toInt(); Projection.TransverseMercator(0.0, z * 6.0 - 183.0, 0.9996, 500_000.0, if (p.containsKey("south")) 10_000_000.0 else 0.0) }
            "tmerc" -> Projection.TransverseMercator(d("lat_0", 0.0), d("lon_0", 0.0), p["k"]?.toDoubleOrNull() ?: d("k_0", 1.0), d("x_0", 0.0), d("y_0", 0.0))
            "lcc" -> {
                val lat1 = d("lat_1"); val lat2 = p["lat_2"]?.toDoubleOrNull()
                if (lat2 == null || lat2 == lat1) Projection.LambertConic(d("lat_0", lat1), d("lon_0", 0.0), lat1, null, p["k_0"]?.toDoubleOrNull() ?: d("k", 1.0), d("x_0", 0.0), d("y_0", 0.0))
                else Projection.LambertConic(d("lat_0", 0.0), d("lon_0", 0.0), lat1, lat2, 1.0, d("x_0", 0.0), d("y_0", 0.0))
            }
            else -> throw Unsupported("projeção ${p["proj"]} (só Transversa de Mercator, UTM, Lambert cônica e geográficas)")
        }
        return Crs("epsg_$code", name.ifBlank { "EPSG $code" }, code, datum, projection, custom = true, region = "Meus sistemas")
    }
}
