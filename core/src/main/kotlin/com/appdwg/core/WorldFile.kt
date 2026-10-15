package com.appdwg.core

/**
 * World file (.jgw, .pgw, .tfw, .wld): six numbers giving the pixel size and the centre of the
 * top-left pixel, in the order A (pixel width), D, B (rotations), E (pixel height, negative),
 * C, F (centre of the top-left pixel).
 */
object WorldFile {
    /**
     * Plan extent of a [width] x [height] picture, from the outer edge of its corner pixels. Fails with
     * a message in Portuguese when the text is not a world file or the picture is rotated.
     */
    fun extent(text: String, width: Int, height: Int): Bounds {
        val v = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.take(6)
            .map { it.replace(',', '.').toDoubleOrNull() ?: error(tr("arquivo de georreferência inválido")) }
        require(v.size == 6) { tr("arquivo de georreferência inválido: precisa de 6 números") }
        val (a, d, b, e) = v
        val c = v[4]
        val f = v[5]
        require(d == 0.0 && b == 0.0) { tr("imagem rotacionada não é suportada") }
        require(a > 0 && e < 0) { tr("arquivo de georreferência inválido") }
        val minX = c - a / 2
        val maxY = f - e / 2
        return Bounds(minX, maxY + height * e, minX + width * a, maxY)
    }
}
