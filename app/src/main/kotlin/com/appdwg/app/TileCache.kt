package com.appdwg.app

/**
 * Which cached tiles still cover a coarser request.
 * Quality has three steps, so a jump from the sharpest level to the normal one
 * looks two levels down. Zoom can go further; four steps is the search limit.
 */
internal object TileCache {
    fun finerKeys(want: Int, tx: Int, ty: Int): List<Triple<Int, Int, Int>> {
        if (want <= 0) return emptyList()
        val out = ArrayList<Triple<Int, Int, Int>>()
        var step = 1
        while (want - step >= 0 && step <= 4) {
            val level = want - step
            val span = 1 shl step
            for (cy in 0 until span) for (cx in 0 until span) {
                out += Triple(level, span * tx + cx, span * ty + cy)
            }
            step++
        }
        return out
    }
}
