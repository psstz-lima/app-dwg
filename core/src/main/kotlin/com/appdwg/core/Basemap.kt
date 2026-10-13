package com.appdwg.core

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.sinh
import kotlin.math.tan

/** One web map tile ("slippy map" numbering: x east, y south from the top-left). */
data class TileId(val z: Int, val x: Int, val y: Int) {
    val parent get() = TileId(z - 1, x shr 1, y shr 1)
}

/** Tile corners in project UTM coordinates: north-west, north-east, south-east, south-west. */
class TileCorners(val nw: Point2, val ne: Point2, val se: Point2, val sw: Point2)

/** Web Mercator tile arithmetic between latitude/longitude, tiles and the project's UTM zone. */
object WebTiles {
    const val TILE_PX = 256
    private const val MAX_LAT = 85.05112878

    /** Fractional tile position of a latitude/longitude at zoom [z]. */
    fun tileXY(lat: Double, lon: Double, z: Int): Pair<Double, Double> {
        val n = (1 shl z).toDouble()
        val la = Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT))
        return (lon + 180) / 360 * n to (1 - ln(tan(la) + 1 / cos(la)) / PI) / 2 * n
    }

    /** Latitude/longitude of the fractional tile position ([x], [y]) at zoom [z]. */
    fun latLon(x: Double, y: Double, z: Int): Pair<Double, Double> {
        val n = (1 shl z).toDouble()
        return Math.toDegrees(atan(sinh(PI * (1 - 2 * y / n)))) to x / n * 360 - 180
    }

    /** Ground size of one tile pixel (m) at latitude [lat] and zoom [z]. */
    fun metersPerPixel(lat: Double, z: Int) = 156543.03392804097 * cos(Math.toRadians(lat)) / (1 shl z)

    /** Zoom whose tile pixels are closest to [mpp] metres per screen pixel, kept in [minZ]..[maxZ]. */
    fun zoomFor(mpp: Double, lat: Double, minZ: Int = 1, maxZ: Int = 19): Int =
        Math.round(log2(156543.03392804097 * cos(Math.toRadians(lat)) / mpp)).toInt().coerceIn(minZ, maxZ)

    /** Corners of tile [t] in the plan coordinates of [crs]. */
    fun corners(t: TileId, crs: Crs): TileCorners {
        fun utm(x: Double, y: Double): Point2 {
            val (la, lo) = latLon(x, y, t.z)
            return crs.fromWgs84(la, lo)
        }
        val x = t.x.toDouble(); val y = t.y.toDouble()
        return TileCorners(utm(x, y), utm(x + 1, y), utm(x + 1, y + 1), utm(x, y + 1))
    }

    /** Tiles at zoom [z] covering the plan area [b] of [crs]. */
    fun covering(b: Bounds, crs: Crs, z: Int): List<TileId> {
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        // Edges sampled, since the UTM rectangle is slightly curved in Mercator.
        for (i in 0..4) for ((e, n) in listOf(
            b.minX + b.width * i / 4 to b.minY, b.minX + b.width * i / 4 to b.maxY,
            b.minX to b.minY + b.height * i / 4, b.maxX to b.minY + b.height * i / 4,
        )) {
            val (la, lo) = crs.toWgs84(e, n)
            val (x, y) = tileXY(la, lo, z)
            minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y)
        }
        val last = (1 shl z) - 1
        val out = ArrayList<TileId>()
        for (ty in floor(minY).toInt().coerceIn(0, last)..floor(maxY).toInt().coerceIn(0, last))
            for (tx in floor(minX).toInt().coerceIn(0, last)..floor(maxX).toInt().coerceIn(0, last)) out += TileId(z, tx, ty)
        return out
    }

    /** Tiles from [minZ] to [maxZ] over [b], for offline download. */
    fun pyramid(b: Bounds, crs: Crs, minZ: Int, maxZ: Int): List<TileId> =
        (minZ..maxZ).flatMap { covering(b, crs, it) }

    /** URL of [t] from a template with {z}, {x} and {y} (and {s} for a server letter). */
    fun url(template: String, t: TileId): String = template
        .replace("{z}", t.z.toString()).replace("{x}", t.x.toString()).replace("{y}", t.y.toString())
        .replace("{s}", "abc"[(t.x + t.y) % 3].toString())
}
