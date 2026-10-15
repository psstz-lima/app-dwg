package com.appdwg.app

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.appdwg.core.Bounds
import com.appdwg.core.ElevationGrid
import com.appdwg.core.Parallel
import com.appdwg.core.Surface
import com.appdwg.core.SurfaceDifference
import kotlin.math.sqrt

/** Shaded elevation image of the surface [name], positioned by [bounds] in plan coordinates. */
class TerrainImage(val name: String, val bounds: Bounds, val image: ImageBitmap) {
    companion object {
        fun of(surface: Surface, maxSize: Int = 1024): TerrainImage? {
            val grid = surface.rasterize(maxSize) ?: return null
            return TerrainImage(surface.name, grid.bounds, render(grid, surface).asImageBitmap())
        }

        /**
         * Detailed image of the part of [surface] inside [region], [cols] x [rows] pixels, for a
         * zoomed-in view. Null when there is nothing there or [cancelled] stops it.
         */
        fun detail(surface: Surface, region: Bounds, cols: Int, rows: Int, cancelled: () -> Boolean): TerrainImage? {
            val clipped = surface.bounds?.let { b ->
                Bounds(maxOf(b.minX, region.minX), maxOf(b.minY, region.minY), minOf(b.maxX, region.maxX), minOf(b.maxY, region.maxY))
            } ?: return null
            if (clipped.width <= 0 || clipped.height <= 0) return null
            // Keeps the pixel size of the requested region for the clipped part.
            val c = maxOf(1, (cols * clipped.width / region.width).toInt())
            val r = maxOf(1, (rows * clipped.height / region.height).toInt())
            val grid = surface.rasterize(clipped, c, r, cancelled) ?: return null
            if (cancelled()) return null
            return TerrainImage(surface.name, grid.bounds, render(grid, surface).asImageBitmap())
        }

        /** Colors by the whole surface's elevation range, so detail and overview images match. */
        private fun render(g: ElevationGrid, surface: Surface): Bitmap {
            val min = surface.minElevation?.toFloat() ?: 0f
            val max = surface.maxElevation?.toFloat() ?: 1f
            val range = (max - min).takeIf { it > 0f } ?: 1f
            val cell = g.cellSize.toFloat()
            val pixels = IntArray(g.cols * g.rows)
            Parallel.forChunks(g.rows, 16) { r0, r1 ->
                for (r in r0 until r1) for (c in 0 until g.cols) {
                    val z = g[c, r]
                    if (z.isNaN()) continue
                    val shade = hillshade(g, c, r, z, cell)
                    pixels[r * g.cols + c] = ramp((z - min) / range, shade)
                }
                true
            }
            return Bitmap.createBitmap(pixels, g.cols, g.rows, Bitmap.Config.ARGB_8888)
        }

        /** Light from the north-west; returns a brightness factor around 1. */
        private fun hillshade(g: ElevationGrid, c: Int, r: Int, z: Float, cell: Float): Float {
            fun at(cc: Int, rr: Int): Float {
                if (cc !in 0 until g.cols || rr !in 0 until g.rows) return z
                val v = g[cc, rr]
                return if (v.isNaN()) z else v
            }
            // Vertical exaggeration so gentle terrain still reads.
            val ex = 3f
            val dzdx = (at(c + 1, r) - at(c - 1, r)) * ex / (2 * cell)
            val dzdy = (at(c, r - 1) - at(c, r + 1)) * ex / (2 * cell)
            val nx = -dzdx
            val ny = -dzdy
            val len = sqrt(nx * nx + ny * ny + 1f)
            val lx = -0.5f; val ly = 0.5f; val lz = 0.707f
            val dot = (nx * lx + ny * ly + lz) / len
            return (0.55f + 0.6f * dot).coerceIn(0.35f, 1.15f)
        }

        private val STOPS = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        private val COLORS = intArrayOf(0x2E7D32, 0x9CCC65, 0xFFEE58, 0xA1887F, 0xF5F5F5)
        /** The elevation colours, lowest first, evenly spaced (for legends). */
        val RAMP: List<Int> get() = COLORS.toList()

        private fun ramp(t: Float, shade: Float): Int {
            var i = 0
            while (i < STOPS.size - 2 && t > STOPS[i + 1]) i++
            val f = ((t - STOPS[i]) / (STOPS[i + 1] - STOPS[i])).coerceIn(0f, 1f)
            val a = COLORS[i]
            val b = COLORS[i + 1]
            fun ch(shift: Int): Int {
                val ca = (a shr shift) and 0xFF
                val cb = (b shr shift) and 0xFF
                return ((ca + (cb - ca) * f) * shade).toInt().coerceIn(0, 255)
            }
            return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}

/** Colored map of B − A: red where B is below A (cut), blue where above (fill), white near zero. */
object DifferenceImage {
    /** Difference shown at full color, in metres: the typical range, never under 5 cm. */
    fun scale(d: SurfaceDifference) = maxOf(d.typicalRange, 0.05)

    fun of(d: SurfaceDifference): TerrainImage {
        val g = d.grid
        val s = scale(d).toFloat()
        val pixels = IntArray(g.cols * g.rows)
        for (i in pixels.indices) {
            val v = g.values[i]
            if (v.isNaN()) continue
            pixels[i] = color((v / s).coerceIn(-1f, 1f))
        }
        val bitmap = Bitmap.createBitmap(pixels, g.cols, g.rows, Bitmap.Config.ARGB_8888)
        return TerrainImage("${d.otherName} − ${d.baseName}", g.bounds, bitmap.asImageBitmap())
    }

    /** Color for a difference scaled to −1 (full cut) .. +1 (full fill). */
    fun color(t: Float): Int {
        val end = if (t < 0) CUT else FILL
        val f = kotlin.math.abs(t)
        fun ch(shift: Int): Int {
            val w = (NEUTRAL shr shift) and 0xFF
            val e = (end shr shift) and 0xFF
            return (w + (e - w) * f).toInt()
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    const val CUT = 0xD32F2F
    const val FILL = 0x1565C0
    private const val NEUTRAL = 0xF5F5F5
}
