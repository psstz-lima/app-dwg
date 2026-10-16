package com.appdwg.app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Which 3D tiles to keep in GPU memory.
 *
 * Máxima may use native pixels, but only where the camera can tell. Farther away the pyramid
 * steps down. The whole set has to fit a budget: pixels × bytes per pixel × 1.33 (mipmaps),
 * at most a quarter of the device RAM and at most 256–384 MB depending on the heap class.
 * A 20,000 × 15,000 orthophoto is 798 MB at native RGB_565 with mipmaps, which is over that
 * budget on a 4 GB or a 6 GB device, so the far tiles stay coarse.
 */
internal object DrapeLod {
    const val STREAM_TILE = 1024
    /**
     * Textures uploaded on the GL thread each frame, on a GPU that can take two.
     * A Mali-G57 MC2 (Redmi Note 14 Pro 4G, 8 GB) or a Mali-G615 uploads one: a second
     * texImage2D plus a mipmap chain drops the frame under 30 fps. Decoding stays ahead of that.
     * RGB_565 stays the upload format. ETC2 or ASTC would be smaller, but encoding on the CPU
     * would delay the first frame, which has to match the old single-texture path.
     */
    const val UPLOADS_PER_FRAME = 2
    /** Background decodes that may be in flight. The GL thread still uploads fewer. */
    const val DECODES_IN_FLIGHT = 2
    /** Refined tiles drawn on top of the base. The rest of the view stays the base texture. */
    const val DRAW_REFINEMENTS = 6
    /** Streamed tiles at most this side get a mipmap chain. Larger ones are already near screen size. */
    const val MIPMAP_SIDE = 512

    /** One upload on Mali, [UPLOADS_PER_FRAME] on anything else. */
    fun uploadsPerFrame(renderer: String?): Int =
        if (renderer != null && renderer.contains("Mali", ignoreCase = true)) 1 else UPLOADS_PER_FRAME

    /** The base overview is mipmapped once, like the single texture from 0.1.5. Large streamed tiles are not. */
    fun mipmap(width: Int, height: Int, base: Boolean): Boolean =
        base || max(width, height) <= MIPMAP_SIDE

    const val KEEP = 0
    const val DROP_REPLACED = 1
    const val DROP_IF_OVER_BUDGET = 2

    data class Node(
        val level: Int,
        val fileX: Int,
        val fileY: Int,
        val fileW: Int,
        val fileH: Int,
    ) {
        val key: String get() = "$level:$fileX:$fileY:$fileW:$fileH"
    }

    data class Selection(
        val tiles: List<Node>,
        val bytes: Long,
        /** False when the tiles that need the finest allowed level do not fit. */
        val nearFits: Boolean,
        /** Tiles considered while choosing [tiles]. The search stops at 20000. */
        val steps: Int = 0,
    )

    data class CacheEntry(
        val key: String,
        val bytes: Long,
        val lastUsed: Long,
        val wanted: Boolean,
    )

    /** Mipmapped size of one texture. 1.33 covers the mip chain. */
    fun textureBytes(width: Int, height: Int, bytesPerPixel: Int): Long {
        if (width <= 0 || height <= 0 || bytesPerPixel <= 0) return 0
        return (width.toLong() * height.toLong() * bytesPerPixel.toLong() * 133 + 99) / 100
    }

    /**
     * Bytes the 3D picture may occupy.
     * A quarter of [totalMemBytes], and no more than a cap that slides from 256 MB at heap class
     * 128 to 384 MB at heap class 512.
     */
    fun budgetBytes(totalMemBytes: Long, memoryClassMb: Int): Long {
        if (totalMemBytes <= 0) return 0
        val quarter = totalMemBytes / 4
        val heap = memoryClassMb.coerceIn(128, 512)
        val cap = (256L shl 20) + ((128L shl 20) * (heap - 128) / (512 - 128))
        return min(quarter, cap)
    }

    /**
     * A non-overlapping cover of the clip. [finestLevel] is the most detailed pyramid level the
     * quality allows (0 native, 1 half, 2 quarter). Tiles near the camera use that level when
     * the screen needs it; the rest stay coarser. The result fits [budgetBytes].
     */
    fun plan(
        imageWidth: Int,
        imageHeight: Int,
        boundsMinX: Double,
        boundsMaxY: Double,
        boundsWidth: Double,
        boundsHeight: Double,
        cameraX: Double,
        cameraY: Double,
        cameraZ: Double,
        groundZ: Double,
        targetDistance: Double,
        worldPerPixel: Double,
        finestLevel: Int,
        topLevel: Int,
        tileSide: Int,
        budgetBytes: Long,
        bytesPerPixel: Int,
        clipX: Int = 0,
        clipY: Int = 0,
        clipW: Int = imageWidth,
        clipH: Int = imageHeight,
    ): Selection {
        if (imageWidth <= 0 || imageHeight <= 0 || tileSide <= 0) {
            return Selection(emptyList(), 0, nearFits = false)
        }
        if (clipW <= 0 || clipH <= 0) return Selection(emptyList(), 0, nearFits = true)
        if (budgetBytes <= 0) return Selection(emptyList(), 0, nearFits = false)
        val finest = finestLevel.coerceIn(0, 30)
        val top = topLevel.coerceIn(finest, 30)
        val side = tileSide.coerceIn(1, 4096)
        val mx = boundsWidth / imageWidth
        val my = boundsHeight / imageHeight
        val metersPerFile = min(mx, my).takeIf { it > 0.0 && it.isFinite() } ?: 1.0
        val reach = targetDistance.coerceAtLeast(1e-3)

        fun levelFor(distance: Double): Int {
            val mpp = worldPerPixel.coerceAtLeast(0.0) * (distance.coerceAtLeast(0.0) / reach)
            val perScreen = mpp / metersPerFile
            val geometric = stepsOfTwo(max(perScreen, 1.0))
            return geometric.coerceIn(finest, top)
        }

        fun distanceOf(n: Node): Double {
            val x0 = boundsMinX + n.fileX.toDouble() / imageWidth * boundsWidth
            val x1 = boundsMinX + (n.fileX + n.fileW).toDouble() / imageWidth * boundsWidth
            val y0 = boundsMaxY - (n.fileY + n.fileH).toDouble() / imageHeight * boundsHeight
            val y1 = boundsMaxY - n.fileY.toDouble() / imageHeight * boundsHeight
            val loX = min(x0, x1)
            val hiX = max(x0, x1)
            val loY = min(y0, y1)
            val hiY = max(y0, y1)
            val cx = cameraX.coerceIn(loX, hiX)
            val cy = cameraY.coerceIn(loY, hiY)
            val dx = cx - cameraX
            val dy = cy - cameraY
            val dz = groundZ - cameraZ
            return sqrt(dx * dx + dy * dy + dz * dz)
        }

        fun nodeBytes(n: Node): Long {
            val (w, h) = outputSize(n)
            return textureBytes(w, h, bytesPerPixel)
        }

        fun total(list: List<Node>): Long = list.sumOf { nodeBytes(it) }

        val start = grid(top, clipX, clipY, clipW, clipH, side)
        if (total(start) > budgetBytes) {
            var used = 0L
            val kept = ArrayList<Node>()
            for (n in start.sortedBy { distanceOf(it) }) {
                val b = nodeBytes(n)
                if (used + b <= budgetBytes) {
                    kept += n
                    used += b
                }
            }
            return Selection(kept, used, nearFits = false, steps = start.size)
        }

        val nodes = start.toMutableList()
        val skipped = HashSet<String>()
        var guard = 0
        while (guard++ < 20000) {
            val candidate = nodes
                .filter { n -> n.key !in skipped && n.level > levelFor(distanceOf(n)) }
                .minWithOrNull(compareBy<Node>({ distanceOf(it) }, { it.key }))
                ?: break
            val parts = split(candidate, finest, side)
            if (parts.isEmpty() || (parts.size == 1 && parts[0] == candidate)) {
                skipped += candidate.key
                continue
            }
            val nextBytes = total(nodes) - nodeBytes(candidate) + total(parts)
            if (nextBytes > budgetBytes) {
                skipped += candidate.key
                continue
            }
            nodes.remove(candidate)
            nodes += parts
        }
        val sorted = nodes.sortedWith(compareBy<Node>({ distanceOf(it) }, { it.key }))
        val nearFits = sorted.none { n ->
            val want = levelFor(distanceOf(n))
            want == finest && n.level > want
        }
        return Selection(sorted, total(sorted), nearFits, steps = guard)
    }

    /**
     * Orbit noise below this does not build a new tile set. A new set every frame evicts tiles
     * that the next frame asks for again, so the cover never stays resident.
     * [previous] and [next] are yaw, pitch, distance, target x, y, z.
     */
    fun cameraSettled(previous: FloatArray, next: FloatArray): Boolean {
        if (previous.size < 6 || next.size < 6 || !previous[0].isFinite() || !next[2].isFinite()) return false
        val dist = abs(previous[2]).coerceAtLeast(1f)
        if (abs(next[2] - previous[2]) / dist > 0.08f) return false
        if (abs(next[0] - previous[0]) > 0.04f) return false
        if (abs(next[1] - previous[1]) > 0.03f) return false
        val dx = next[3] - previous[3]
        val dy = next[4] - previous[4]
        val dz = next[5] - previous[5]
        return sqrt(dx * dx + dy * dy + dz * dz) <= dist * 0.06f
    }

    data class StreamTick(
        /** The base overview or a refined tile is already on the GPU. */
        val hasDrape: Boolean,
        /** Missing plan tiles to decode this step, nearest first. */
        val request: List<Node>,
        /** A plan tile is still missing and not given up. */
        val refining: Boolean,
    )

    /**
     * One frame of the stream. [baseReady] is the overview texture already uploaded, the same
     * picture 0.1.5 put on the GPU before any tile decode. The plan is only requested on top of
     * that. A tile in [blocked] failed (the pyramid file is not there yet) and is not asked again.
     */
    fun tick(
        baseReady: Boolean,
        residents: Set<String>,
        plan: List<Node>,
        queued: Set<String>,
        slots: Int,
        blocked: Set<String> = emptySet(),
    ): StreamTick {
        val missing = plan.filter { it.key !in residents && it.key !in queued && it.key !in blocked }
        val request = if (slots <= 0) emptyList() else missing.take(slots)
        val refining = plan.any { it.key !in residents && it.key !in blocked }
        return StreamTick(baseReady || residents.isNotEmpty(), request, refining)
    }

    data class Settle(
        val steps: Int,
        val converged: Boolean,
        val hasDrapeOnFirst: Boolean,
        /** Keys that were dropped and then requested again. */
        val readded: List<String>,
    )

    /**
     * Runs the stream until [plan] is resident or until it cannot admit another tile.
     * A stable plan only grows: a key that leaves is not asked again. The first step already
     * has the base drape, before any refined tile is added.
     */
    fun settle(plan: List<Node>, budget: Long, bytesOf: (Node) -> Long, slots: Int): Settle {
        val resident = LinkedHashMap<String, Long>()
        val removed = HashSet<String>()
        val readded = ArrayList<String>()
        var hasFirst = false
        var steps = 0
        val bound = plan.size.coerceAtLeast(1) + 2
        while (steps < bound) {
            val tick = tick(baseReady = true, residents = resident.keys, plan = plan, queued = emptySet(), slots = slots)
            if (steps == 0) hasFirst = tick.hasDrape
            if (!tick.hasDrape) return Settle(steps, converged = false, hasDrapeOnFirst = false, readded = readded)
            if (!tick.refining && tick.request.isEmpty()) return Settle(steps, converged = true, hasDrapeOnFirst = hasFirst, readded = readded)
            var added = 0
            for (node in tick.request) {
                val bytes = bytesOf(node)
                val used = resident.values.sum()
                if (!admits(used, bytes, 0L, budget)) continue
                if (node.key in removed) readded += node.key
                resident[node.key] = bytes
                added++
            }
            val wanted = plan.mapTo(HashSet()) { it.key }
            val gone = resident.keys.filter { it !in wanted }
            for (key in gone) {
                resident.remove(key)
                removed += key
            }
            if (added == 0 && gone.isEmpty()) break
            steps++
        }
        val done = plan.all { it.key in resident }
        return Settle(steps, done, hasFirst, readded)
    }

    /**
     * The whole clip at [topLevel], in tiles the GPU can hold. This is the picture shown while a
     * previous upload is still on the GPU: it is one coarse tile for a 300 MP file, so the old
     * textures can be deleted before the native tiles near the camera arrive.
     */
    fun cover(
        imageWidth: Int,
        imageHeight: Int,
        topLevel: Int,
        tileSide: Int,
        clipX: Int = 0,
        clipY: Int = 0,
        clipW: Int = imageWidth,
        clipH: Int = imageHeight,
    ): List<Node> {
        if (imageWidth <= 0 || imageHeight <= 0 || tileSide <= 0 || clipW <= 0 || clipH <= 0) return emptyList()
        return grid(topLevel.coerceIn(0, 30), clipX, clipY, clipW, clipH, tileSide.coerceIn(1, 4096))
    }

    fun outputSize(node: Node): Pair<Int, Int> {
        val f = 1 shl node.level.coerceIn(0, 30)
        val w = ((node.fileW + f - 1) / f).coerceAtLeast(1)
        val h = ((node.fileH + f - 1) / f).coerceAtLeast(1)
        return w to h
    }

    /**
     * What to do with a resident tile that may have fallen out of [plan].
     * A tile the plan replaced is dropped as soon as those replacements are resident, so the two
     * copies do not fight. A tile outside the plan stays until the budget needs the room.
     */
    fun dropReason(node: Node, plan: List<Node>, residentKeys: Set<String>): Int {
        if (node.key in plan.mapTo(HashSet()) { it.key }) return KEEP
        val overlapping = plan.filter { intersects(it, node) }
        if (overlapping.isEmpty()) return DROP_IF_OVER_BUDGET
        if (overlapping.all { it.key in residentKeys } && covers(node, overlapping)) return DROP_REPLACED
        return KEEP
    }

    /**
     * Bytes of resident tiles [node] will replace. They stay until the new tile is uploaded, so
     * that upload may use their part of the budget. Without this credit the last child of a split
     * never fits, the parent never drops, and the near view stays coarse.
     */
    fun replacedBytes(node: Node, residents: List<Pair<Node, Long>>, planKeys: Set<String>): Long {
        var credit = 0L
        for ((resident, bytes) in residents) {
            if (resident.key in planKeys || bytes <= 0) continue
            if (!intersects(resident, node)) continue
            credit += bytes
        }
        return credit
    }

    /**
     * Whether one more tile fits. [replacedBytes] is ignored because those textures come off as
     * soon as this one is up. A tile larger than the whole budget never fits.
     */
    fun admits(usedBytes: Long, nodeBytes: Long, replacedBytes: Long, budget: Long): Boolean {
        if (budget <= 0 || nodeBytes <= 0 || nodeBytes > budget) return false
        val credit = replacedBytes.coerceIn(0L, usedBytes.coerceAtLeast(0L))
        return usedBytes - credit + nodeBytes <= budget
    }

    /**
     * Keeps every wanted entry that fits, newest first, then the newest unwanted ones, until
     * [budget] is full. Wanted tiles that do not fit are left out; the caller treats that as the
     * near view not fitting.
     */
    fun evict(entries: List<CacheEntry>, budget: Long): List<CacheEntry> {
        if (budget <= 0) return emptyList()
        val ordered = entries.sortedWith(
            compareByDescending<CacheEntry> { it.wanted }
                .thenByDescending { it.lastUsed }
                .thenBy { it.key },
        )
        var used = 0L
        val keep = ArrayList<CacheEntry>()
        for (entry in ordered) {
            if (entry.bytes <= 0) continue
            if (used + entry.bytes <= budget) {
                used += entry.bytes
                keep += entry
            }
        }
        return keep
    }

    private fun stepsOfTwo(ratio: Double): Int {
        var value = ratio
        var steps = 0
        while (value >= 2.0 && steps < 30) {
            value /= 2.0
            steps++
        }
        return steps
    }

    private fun grid(level: Int, x: Int, y: Int, w: Int, h: Int, tileSide: Int): List<Node> {
        if (w <= 0 || h <= 0) return emptyList()
        val safe = level.coerceIn(0, 30)
        val f = 1 shl safe
        val outW = (w + f - 1) / f
        val outH = (h + f - 1) / f
        if (outW <= tileSide && outH <= tileSide) return listOf(Node(safe, x, y, w, h))
        val step = (tileSide.toLong() * f).coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
        val out = ArrayList<Node>()
        var yy = y
        val y1 = y + h
        val x1 = x + w
        while (yy < y1) {
            val hh = min(step, y1 - yy)
            var xx = x
            while (xx < x1) {
                val ww = min(step, x1 - xx)
                out += Node(safe, xx, yy, ww, hh)
                xx += ww
            }
            yy += hh
        }
        return out
    }

    private fun split(node: Node, finest: Int, tileSide: Int): List<Node> {
        if (node.level <= finest) return emptyList()
        val child = node.level - 1
        val left = node.fileW / 2
        val right = node.fileW - left
        val upper = node.fileH / 2
        val lower = node.fileH - upper
        val out = ArrayList<Node>()
        fun add(x: Int, y: Int, w: Int, h: Int) {
            if (w > 0 && h > 0) out += grid(child, x, y, w, h, tileSide)
        }
        add(node.fileX, node.fileY, left, upper)
        add(node.fileX + left, node.fileY, right, upper)
        add(node.fileX, node.fileY + upper, left, lower)
        add(node.fileX + left, node.fileY + upper, right, lower)
        return out
    }

    private fun intersects(a: Node, b: Node): Boolean {
        return a.fileX < b.fileX + b.fileW && b.fileX < a.fileX + a.fileW &&
            a.fileY < b.fileY + b.fileH && b.fileY < a.fileY + a.fileH
    }

    private data class Rect(val x: Int, val y: Int, val w: Int, val h: Int)

    private fun covers(target: Node, by: List<Node>): Boolean {
        var rest = listOf(Rect(target.fileX, target.fileY, target.fileW, target.fileH))
        for (cover in by) {
            if (rest.isEmpty()) return true
            val cut = Rect(cover.fileX, cover.fileY, cover.fileW, cover.fileH)
            rest = rest.flatMap { subtract(it, cut) }
        }
        return rest.isEmpty()
    }

    private fun subtract(a: Rect, b: Rect): List<Rect> {
        val ax2 = a.x + a.w
        val ay2 = a.y + a.h
        val bx2 = b.x + b.w
        val by2 = b.y + b.h
        val ix0 = max(a.x, b.x)
        val iy0 = max(a.y, b.y)
        val ix1 = min(ax2, bx2)
        val iy1 = min(ay2, by2)
        if (ix0 >= ix1 || iy0 >= iy1) return listOf(a)
        val out = ArrayList<Rect>()
        if (iy0 > a.y) out += Rect(a.x, a.y, a.w, iy0 - a.y)
        if (ay2 > iy1) out += Rect(a.x, iy1, a.w, ay2 - iy1)
        if (ix0 > a.x) out += Rect(a.x, iy0, ix0 - a.x, iy1 - iy0)
        if (ax2 > ix1) out += Rect(ix1, iy0, ax2 - ix1, iy1 - iy0)
        return out
    }
}
