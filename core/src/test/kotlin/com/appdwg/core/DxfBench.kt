package com.appdwg.core

import java.io.File
import kotlin.test.Test

class DxfBench {
    @Test
    fun bench() {
        val path = System.getenv("DXF_BENCH") ?: return
        repeat(8) {
            val t0 = System.nanoTime()
            var last = System.nanoTime()
            val p = DxfReader(File(path).bufferedReader(Charsets.UTF_8, 1 shl 16)).read { st -> val n = System.nanoTime(); println("  ${(n - last) / 1_000_000} ms antes de: $st"); last = n }
            println("  ${(System.nanoTime() - last) / 1_000_000} ms no fim")
            println("BENCH ${(System.nanoTime() - t0) / 1_000_000} ms, ${p.polylines.size} linhas, ${p.texts.size} textos, ${p.surfaces.sumOf { it.triangleCount }} triângulos")
        }
    }
}
