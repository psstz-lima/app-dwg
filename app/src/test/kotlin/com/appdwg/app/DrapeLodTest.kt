package com.appdwg.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 3D picture memory. A 20,000 × 15,000 orthophoto is 300 MP, 798 MB with RGB_565 mipmaps.
 * That must not be uploaded whole on a 4 GB or a 6 GB device.
 */
class DrapeLodTest {
    @Test
    fun budgetIsAQuarterOfRamAndNeverAboveTheHeapCap() {
        assertEquals(256L shl 20, DrapeLod.budgetBytes(8L shl 30, 128))
        assertEquals(384L shl 20, DrapeLod.budgetBytes(8L shl 30, 512))
        // The quarter is the tighter limit on a 512 MB device.
        assertEquals(128L shl 20, DrapeLod.budgetBytes(512L shl 20, 128))
        assertEquals(0L, DrapeLod.budgetBytes(0, 256))

        // 4 GB, heap class 192: 277.33 MB. 6 GB, heap class 256: 298.67 MB.
        val ram4 = 4L shl 30
        val ram6 = 6L shl 30
        val budget4 = DrapeLod.budgetBytes(ram4, 192)
        val budget6 = DrapeLod.budgetBytes(ram6, 256)
        assertEquals(290_805_077L, budget4)
        assertEquals(313_174_698L, budget6)
        assertTrue(budget4 in (256L shl 20)..(384L shl 20))
        assertTrue(budget6 in (256L shl 20)..(384L shl 20))
        assertTrue(budget6 > budget4)
        assertEquals(ram4 / 4, (256L shl 20) * 4)
        assertTrue(budget4 < ram4 / 4)
        assertTrue(budget6 < ram6 / 4)
    }

    @Test
    fun threeHundredMegapixelsStaysInsideTheBudgetOn4GbAnd6Gb() {
        val native = DrapeLod.textureBytes(20_000, 15_000, 2)
        assertEquals(798_000_000L, native)
        val budget4 = DrapeLod.budgetBytes(4L shl 30, 192)
        val budget6 = DrapeLod.budgetBytes(6L shl 30, 256)
        assertTrue(native > budget4)
        assertTrue(native > budget6)

        val on4 = ortho(budget4, finest = 0)
        val on6 = ortho(budget6, finest = 0)
        assertTrue(on4.bytes <= budget4)
        assertTrue(on6.bytes <= budget6)
        assertTrue(on4.bytes < native)
        assertTrue(on6.bytes < native)
        assertTrue(on4.nearFits)
        assertTrue(on6.nearFits)
        // Native next to the camera, coarser toward the far corner.
        assertEquals(0, tileAt(on4, 10_000, 7_500).level)
        assertEquals(0, tileAt(on6, 10_000, 7_500).level)
        assertTrue(on4.tiles.any { it.level >= 2 })
        assertTrue(on6.tiles.any { it.level >= 2 })
        assertEquals(20_000L * 15_000L, on4.tiles.sumOf { it.fileW.toLong() * it.fileH })
        assertTrue(on6.bytes >= on4.bytes)
        val cover = DrapeLod.cover(20_000, 15_000, topLevel = 7, tileSide = DrapeLod.STREAM_TILE)
        assertEquals(20_000L * 15_000L, cover.sumOf { it.fileW.toLong() * it.fileH })
        val coverBytes = cover.sumOf {
            val (w, h) = DrapeLod.outputSize(it)
            DrapeLod.textureBytes(w, h, 2)
        }
        assertTrue(coverBytes < 2_000_000L)

        val alta = ortho(budget4, finest = 1)
        assertTrue(alta.tiles.none { it.level < 1 })
        assertEquals(1, tileAt(alta, 10_000, 7_500).level)
        val normal = ortho(budget4, finest = 2)
        assertTrue(normal.tiles.none { it.level < 2 })
        assertEquals(2, tileAt(normal, 10_000, 7_500).level)
    }

    @Test
    fun nearViewThatCannotFitIsReportedAndTheRestIsEvictedByLru() {
        val tiny = ortho(budget = 1024, finest = 0)
        assertFalse(tiny.nearFits)
        assertTrue(tiny.bytes <= 1024)

        val budget = DrapeLod.budgetBytes(4L shl 30, 192)
        val plan = ortho(budget, finest = 0)
        val wanted = plan.tiles.mapIndexed { index, node ->
            val (w, h) = DrapeLod.outputSize(node)
            DrapeLod.CacheEntry(node.key, DrapeLod.textureBytes(w, h, 2), lastUsed = 10_000L + index, wanted = true)
        }
        val stale = (0 until 40).map { index ->
            DrapeLod.CacheEntry("old-$index", 8_000_000L, lastUsed = index.toLong(), wanted = false)
        }
        val kept = DrapeLod.evict(stale + wanted, budget)
        assertTrue(kept.sumOf { it.bytes } <= budget)
        assertTrue(wanted.all { tile -> kept.any { it.key == tile.key } })
        val dropped = stale.filter { old -> kept.none { it.key == old.key } }
        assertTrue(dropped.isNotEmpty())
        val keptOld = kept.filter { !it.wanted }
        if (keptOld.isNotEmpty()) {
            assertTrue(keptOld.minOf { it.lastUsed } >= dropped.maxOf { it.lastUsed })
        }

        // Three wanted tiles and room for only two: the oldest wanted one goes, and so does a newer unwanted one.
        val entries = listOf(
            DrapeLod.CacheEntry("near-new", 100, 3, wanted = true),
            DrapeLod.CacheEntry("near-mid", 100, 2, wanted = true),
            DrapeLod.CacheEntry("near-old", 100, 1, wanted = true),
            DrapeLod.CacheEntry("far", 100, 99, wanted = false),
        )
        val two = DrapeLod.evict(entries, 250).map { it.key }
        assertEquals(setOf("near-new", "near-mid"), two.toSet())
        val three = DrapeLod.evict(entries, 400).map { it.key }
        assertEquals(setOf("near-new", "near-mid", "near-old", "far"), three.toSet())
    }

    @Test
    fun replacedTilesDropOnlyAfterTheNewOnesAreResident() {
        val coarse = DrapeLod.Node(2, 0, 0, 400, 400)
        val fine = listOf(
            DrapeLod.Node(1, 0, 0, 200, 200),
            DrapeLod.Node(1, 200, 0, 200, 200),
            DrapeLod.Node(1, 0, 200, 200, 200),
            DrapeLod.Node(1, 200, 200, 200, 200),
        )
        assertEquals(DrapeLod.KEEP, DrapeLod.dropReason(coarse, fine, setOf(fine[0].key, fine[1].key)))
        assertEquals(
            DrapeLod.DROP_REPLACED,
            DrapeLod.dropReason(coarse, fine, fine.map { it.key }.toSet()),
        )
        val elsewhere = DrapeLod.Node(3, 5_000, 5_000, 1_000, 1_000)
        assertEquals(DrapeLod.DROP_IF_OVER_BUDGET, DrapeLod.dropReason(elsewhere, fine, emptySet()))
        assertEquals(DrapeLod.KEEP, DrapeLod.dropReason(fine[0], fine, emptySet()))
    }

    @Test
    fun theLastChildOfASplitIsAdmittedWhileTheParentStillOccupiesTheBudget() {
        val parent = DrapeLod.Node(1, 0, 0, 2_048, 2_048)
        val children = listOf(
            DrapeLod.Node(0, 0, 0, 1_024, 1_024),
            DrapeLod.Node(0, 1_024, 0, 1_024, 1_024),
            DrapeLod.Node(0, 0, 1_024, 1_024, 1_024),
            DrapeLod.Node(0, 1_024, 1_024, 1_024, 1_024),
        )
        val tile = DrapeLod.textureBytes(1_024, 1_024, 2)
        assertEquals(tile, DrapeLod.textureBytes(DrapeLod.outputSize(parent).first, DrapeLod.outputSize(parent).second, 2))
        // Room for the parent and three children. The fourth needs the parent's credit.
        val budget = tile * 4
        val planKeys = children.map { it.key }.toSet()
        val residents = ArrayList<Pair<DrapeLod.Node, Long>>()
        residents += parent to tile
        var used = tile
        for (child in children) {
            val credit = DrapeLod.replacedBytes(child, residents, planKeys)
            assertEquals(tile, credit)
            assertTrue(DrapeLod.admits(used, tile, credit, budget))
            used += tile
            residents += child to tile
        }
        assertFalse(DrapeLod.admits(budget, tile, 0, budget))
        assertTrue(DrapeLod.admits(budget, tile, tile, budget))
        assertFalse(DrapeLod.admits(0, budget + 1, budget, budget))
        assertEquals(0L, DrapeLod.replacedBytes(DrapeLod.Node(0, 9_000, 9_000, 1_024, 1_024), residents, planKeys))
    }

    private fun tileAt(selection: DrapeLod.Selection, fileX: Int, fileY: Int): DrapeLod.Node =
        selection.tiles.first { fileX >= it.fileX && fileX < it.fileX + it.fileW && fileY >= it.fileY && fileY < it.fileY + it.fileH }

    /** 300 MP ortho over 2 km × 1.5 km, camera 40 m above the middle, 3 cm per screen pixel. */
    private fun ortho(budget: Long, finest: Int) = DrapeLod.plan(
        imageWidth = 20_000,
        imageHeight = 15_000,
        boundsMinX = 0.0,
        boundsMaxY = 1_500.0,
        boundsWidth = 2_000.0,
        boundsHeight = 1_500.0,
        cameraX = 1_000.0,
        cameraY = 750.0,
        cameraZ = 40.0,
        groundZ = 0.0,
        targetDistance = 40.0,
        worldPerPixel = 0.03,
        finestLevel = finest,
        topLevel = 7,
        tileSide = DrapeLod.STREAM_TILE,
        budgetBytes = budget,
        bytesPerPixel = 2,
    )
}
