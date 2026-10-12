package com.appdwg.app

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.appdwg.core.Bounds
import com.appdwg.core.ElevationGrid
import com.appdwg.core.Surface
import kotlin.math.sqrt

/** Shaded elevation image of a surface, positioned by [bounds] in plan coordinates. */
class TerrainImage(val bounds: Bounds, val image: ImageBitmap) {
    companion object {
        fun of(surface: Surface, maxSize: Int = 1024): TerrainImage? {
            val grid = surface.rasterize(maxSize) ?: return null
            return TerrainImage(grid.bounds, render(grid).asImageBitmap())
        }

        private fun render(g: ElevationGrid): Bitmap {
            var min = Float.POSITIVE_INFINITY
            var max = Float.NEGATIVE_INFINITY
            for (v in g.values) if (!v.isNaN()) {
                if (v < min) min = v
                if (v > max) max = v
            }
            val range = (max - min).takeIf { it > 0f } ?: 1f
            val cell = g.cellSize.toFloat()
            val pixels = IntArray(g.cols * g.rows)
            for (r in 0 until g.rows) for (c in 0 until g.cols) {
                val z = g[c, r]
                if (z.isNaN()) continue
                val shade = hillshade(g, c, r, z, cell)
                pixels[r * g.cols + c] = ramp((z - min) / range, shade)
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
