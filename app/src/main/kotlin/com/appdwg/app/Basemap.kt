package com.appdwg.app

import com.appdwg.core.tr

import android.content.Context
import android.graphics.Bitmap
import androidx.core.content.edit
import androidx.core.graphics.get
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.appdwg.core.Bounds
import com.appdwg.core.Crs
import com.appdwg.core.TileCorners
import com.appdwg.core.TileId
import com.appdwg.core.Utm
import com.appdwg.core.WebTiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** A web map tile server. [bulk] says whether its terms allow downloading an area ahead of time. */
data class BasemapSource(val id: String, private val labelPt: String, val url: String, val maxZoom: Int, val bulk: Boolean, private val creditPt: String) {
    val label: String get() = tr(labelPt)
    val credit: String get() = tr(creditPt)
    /** Esri sends a flat grey "map data not yet available" tile where it has no imagery. */
    val greyPlaceholders get() = id.startsWith("esri")
    /** OpenStreetMap asks apps to refresh cached tiles after a week and to keep few connections open. */
    val maxAgeMs: Long? get() = if (id == "osm") 7L * 24 * 3600 * 1000 else null
}

val BASEMAP_SOURCES = listOf(
    BasemapSource("esri_img", "Satélite", "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}", 19, true, "Esri, Maxar, Earthstar Geographics"),
    BasemapSource("esri_street", "Ruas", "https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/{z}/{y}/{x}", 19, true, "Esri, HERE, OpenStreetMap"),
    BasemapSource("esri_topo", "Topográfico", "https://server.arcgisonline.com/ArcGIS/rest/services/World_Topo_Map/MapServer/tile/{z}/{y}/{x}", 19, true, "Esri, HERE, OpenStreetMap"),
    // OpenStreetMap's tile policy forbids bulk downloads: tiles are only kept as they are viewed.
    BasemapSource("osm", "OpenStreetMap", "https://tile.openstreetmap.org/{z}/{x}/{y}.png", 19, false, "© colaboradores do OpenStreetMap"),
)

/** One tile ready to draw: picture, the part of it to use (pixels) and where that part lies in UTM. */
class BasemapTile(val bitmap: Bitmap, val src: android.graphics.Rect, val corners: TileCorners)

/**
 * Background map: tiles from a web server kept on disk (filesDir/tiles/<source>), so an area
 * viewed or downloaded once shows without network. Tiles missing at the zoom wanted are drawn
 * from a coarser tile already at hand.
 */
class BasemapStore(context: Context) {
    private val prefs = context.getSharedPreferences("basemap", Context.MODE_PRIVATE)
    private val root = File(context.filesDir, "tiles")
    private val images = TileImages(context)
    private val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var enabled by mutableStateOf(prefs.getBoolean("enabled", false)); private set
    var source by mutableStateOf(BASEMAP_SOURCES.firstOrNull { it.id == prefs.getString("source", null) } ?: BASEMAP_SOURCES[0]); private set
    var opacity by mutableStateOf(prefs.getFloat("opacity", 0.8f)); private set
    /** Fetch missing tiles from the network while viewing. */
    var online by mutableStateOf(prefs.getBoolean("online", true)); private set
    /** Bumped when a tile arrives, so the plan redraws. */
    var version by mutableIntStateOf(0); private set

    init {
        // Plain OpenStreetMap tiles (rural areas) were once taken for Esri's "no imagery" tiles and
        // stored empty, which left them blank for good: those markers are dropped once.
        if (!prefs.getBoolean("osmEmptyFixed", false)) {
            scope.launch {
                File(root, "osm").walkBottomUp().filter { it.isFile && it.length() == 0L }.forEach { it.delete() }
                prefs.edit { putBoolean("osmEmptyFixed", true) }
            }
        }
    }

    fun enable(v: Boolean) { enabled = v; prefs.edit { putBoolean("enabled", v) } }
    fun choose(s: BasemapSource) { source = s; prefs.edit { putString("source", s.id) }; version++ }
    fun changeOpacity(v: Float) { opacity = v; prefs.edit { putFloat("opacity", v) } }
    fun allowOnline(v: Boolean) { online = v; prefs.edit { putBoolean("online", v) } }

    /** Bitmap plus the Compose wrapper, so the place picker does not allocate one per tile per frame. */
    private class CachedTile(val bitmap: Bitmap, val image: ImageBitmap)

    // Room for a full tablet screen of tiles plus the coarser and finer ones drawn while zooming.
    private val memory = object : LruCache<String, CachedTile>(minOf(128L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 5).toInt()) {
        override fun sizeOf(key: String, value: CachedTile) = value.bitmap.byteCount
    }
    /**
     * Tiles that could not be fetched: when that last happened and how many times, so they are
     * retried after 2, 4, 8… seconds (at most a minute) instead of every frame or never.
     */
    private val missing = HashMap<String, Pair<Long, Int>>()
    private fun retryDelay(attempts: Int) = minOf(60_000L, 2_000L shl minOf(attempts - 1, 5))
    private val loading = HashSet<String>()
    private val corners = object : LruCache<String, TileCorners>(4096) {}
    private val net = Semaphore(4)
    private val osmNet = Semaphore(2)
    private val disk = Semaphore(2)

    private fun key(s: BasemapSource, t: TileId) = "${s.id}/${t.z}/${t.x}/${t.y}"
    private fun file(s: BasemapSource, t: TileId) = File(root, "${s.id}/${t.z}/${t.x}/${t.y}")

    /** Picture of [t] if loaded; otherwise starts loading it from disk (or the network when [fetch]). */
    private fun bitmap(t: TileId, fetch: Boolean, s: BasemapSource = source): Bitmap? = cached(t, fetch, s)?.bitmap

    private fun putTile(k: String, bmp: Bitmap) {
        memory.put(k, CachedTile(bmp, bmp.asImageBitmap()))
    }

    private fun cached(t: TileId, fetch: Boolean, s: BasemapSource = source): CachedTile? {
        val k = key(s, t)
        memory.get(k)?.let { return it }
        synchronized(loading) {
            if (k in loading) return null
            val miss = missing[k]
            if (miss != null && !(fetch && online && System.currentTimeMillis() - miss.first > retryDelay(miss.second))) return null
            loading += k
        }
        scope.launch {
            val f = file(s, t)
            var bmp: Bitmap? = null
            // An empty file marks a tile the server has no picture for: a coarser one is drawn instead.
            val known = f.exists()
            if (known && f.length() > 0) disk.withPermit { bmp = decodeTile(f, k) }
            val gate = if (s.id == "osm") osmNet else net
            if (bmp == null && !known && fetch && online) gate.withPermit { bmp = fetchTile(s, t, k) }
            // A stale tile is drawn at once and fetched again in the background.
            val age = s.maxAgeMs
            if (bmp != null && age != null && fetch && online && System.currentTimeMillis() - f.lastModified() > age) {
                scope.launch { gate.withPermit { fetchTile(s, t, k)?.let { fresh -> putTile(k, fresh); withContext(Dispatchers.Main) { version++ } } } }
            }
            synchronized(loading) {
                loading -= k
                if (bmp == null) missing[k] = System.currentTimeMillis() to ((missing[k]?.second ?: 0) + 1) else missing -= k
            }
            bmp?.let { putTile(k, it); withContext(Dispatchers.Main) { version++ } }
        }
        return null
    }

    /**
     * Downloads [t] with Coil (memory and disk cache, downsampled) and mirrors the encoded bytes
     * into the offline tile file. Null when the server has no tile or the network fails. An empty
     * file is the marker for Esri's grey "not available" tile.
     */
    private suspend fun fetchTile(s: BasemapSource, t: TileId, k: String): Bitmap? {
        val url = WebTiles.url(s.url, t)
        val bmp = images.decode(url, k, WebTiles.TILE_PX, opaque = true, disk = true, memory = true) ?: return null
        val f = file(s, t)
        if (!images.exportDisk(k, f)) {
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".part")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            if (f.exists()) f.delete()
            if (!tmp.renameTo(f)) tmp.delete()
        }
        if (s.greyPlaceholders && isPlaceholder(f)) {
            images.evict(k)
            f.delete()
            f.writeBytes(ByteArray(0))
            return null
        }
        return bmp
    }

    /** Map tiles are opaque JPEG or PNG. RGB_565 halves the cache against the default 32-bit decode. */
    private suspend fun decodeTile(f: File, k: String): Bitmap? =
        images.decode(f, k, WebTiles.TILE_PX, opaque = true, disk = false, memory = true)

    /** Whether a tile is a flat grey "map data not yet available" picture. */
    private suspend fun isPlaceholder(f: File): Boolean {
        if (f.length() > 12_000) return false
        // A reduced decode is enough to see a flat colour, and it does not allocate a full tile.
        val b = images.decode(f, f.path + "#sample", 32, opaque = true, disk = false, memory = false) ?: return true
        run {
            if (b.width < 2 || b.height < 2) return true
            val ref = b[b.width / 2, b.height / 2]
            val r = (ref shr 16) and 0xFF; val g = (ref shr 8) and 0xFF; val bl = ref and 0xFF
            if (kotlin.math.abs(r - g) > 6 || kotlin.math.abs(g - bl) > 6) return false
            val x0 = b.width / 8
            val y0 = b.height / 8
            val x1 = (b.width - 1 - x0).coerceAtLeast(x0)
            val y1 = (b.height - 1 - y0).coerceAtLeast(y0)
            var same = 0
            for (i in 0 until 16) for (j in 0 until 16) {
                val p = b[x0 + i * (x1 - x0) / 15, y0 + j * (y1 - y0) / 15]
                if (kotlin.math.abs(((p shr 16) and 0xFF) - r) < 6 && kotlin.math.abs((p and 0xFF) - bl) < 6) same++
            }
            return same >= 220
        }
    }

    private fun cornersOf(t: TileId, crs: Crs): TileCorners {
        val k = "${crs.id}/${t.z}/${t.x}/${t.y}"
        return corners.get(k) ?: WebTiles.corners(t, crs).also { corners.put(k, it) }
    }

    /** Tiles to draw over the UTM area [view] at [mpp] metres per screen pixel. */
    fun visible(view: Bounds, mpp: Double, crs: Crs): List<BasemapTile> {
        val s = source
        if (crs.isGeographic) return emptyList()
        val lat = runCatching { crs.toWgs84(view.center.x, view.center.y).first }.getOrNull() ?: return emptyList()
        if (lat.isNaN() || kotlin.math.abs(lat) > 85) return emptyList()
        // Tiles sized in dp, as web maps do, so street names stay readable on dense screens.
        val z = WebTiles.zoomFor(mpp * density, lat, 2, s.maxZoom)
        val tiles = WebTiles.covering(view, crs, z)
        if (tiles.size > 400) return emptyList()
        val out = ArrayList<BasemapTile>(tiles.size)
        for (t in tiles) {
            val own = bitmap(t, fetch = true)
            if (own != null) { out += BasemapTile(own, android.graphics.Rect(0, 0, own.width, own.height), cornersOf(t, crs)); continue }
            // Until the tile arrives: a coarser one cropped to its part, else the finer ones already at hand.
            var p = t
            var covered = false
            for (up in 1..6) {
                if (p.z <= 1) break
                p = p.parent
                val b = bitmap(p, fetch = false) ?: continue
                val n = 1 shl up
                val cw = b.width / n; val ch = b.height / n
                val ox = (t.x - (p.x shl up)) * cw; val oy = (t.y - (p.y shl up)) * ch
                if (cw > 0 && ch > 0) { out += BasemapTile(b, android.graphics.Rect(ox, oy, ox + cw, oy + ch), cornersOf(t, crs)); covered = true }
                break
            }
            if (!covered && t.z < s.maxZoom) for (c in t.children) {
                val b = memory.get(key(s, c))?.bitmap ?: continue
                out += BasemapTile(b, android.graphics.Rect(0, 0, b.width, b.height), cornersOf(c, crs))
                covered = true
            }
            // Nothing at hand: one tile two levels coarser (it covers 16 of these) fills in quickly.
            if (!covered && t.z > 3) bitmap(t.parent.parent, fetch = true)
        }
        return out
    }

    /**
     * OpenStreetMap tile [t] for the place picker, or until it arrives the part of a coarser one at
     * hand (picture and the part of it to draw); null when none is loaded yet.
     */
    fun pickerTile(t: TileId): Pair<ImageBitmap, android.graphics.Rect>? {
        val s = BASEMAP_SOURCES.first { it.id == "osm" }
        cached(t, fetch = true, s = s)?.let { return it.image to android.graphics.Rect(0, 0, it.bitmap.width, it.bitmap.height) }
        var p = t
        for (up in 1..8) {
            if (p.z <= 0) break
            p = p.parent
            val b = cached(p, fetch = up == 2, s = s) ?: continue
            val n = 1 shl up
            val cw = b.bitmap.width / n; val ch = b.bitmap.height / n
            if (cw <= 0 || ch <= 0) return null
            val ox = (t.x - (p.x shl up)) * cw; val oy = (t.y - (p.y shl up)) * ch
            return b.image to android.graphics.Rect(ox, oy, ox + cw, oy + ch)
        }
        return null
    }

    // --- Area download ---------------------------------------------------------------------

    var downloadJob: Job? by mutableStateOf(null); private set
    var progress by mutableStateOf<Pair<Int, Int>?>(null); private set
    var lastResult by mutableStateOf<String?>(null); private set

    fun count(area: Bounds, crs: Crs, maxZoom: Int) = WebTiles.pyramid(area, crs, 10, maxZoom).size

    fun download(area: Bounds, crs: Crs, maxZoom: Int) {
        val s = source
        if (!s.bulk || downloadJob?.isActive == true) return
        val tiles = WebTiles.pyramid(area, crs, 10, maxZoom)
        progress = 0 to tiles.size
        lastResult = null
        downloadJob = scope.launch {
            val done = AtomicInteger(); val failed = AtomicInteger()
            val queue = Channel<TileId>(Channel.UNLIMITED)
            tiles.forEach { queue.trySend(it) }
            queue.close()
            val workers = List(4) {
                launch {
                    for (t in queue) {
                        if (!isActive) break
                        if (!file(s, t).exists() && fetchTile(s, t, key(s, t)) == null && !file(s, t).exists()) failed.incrementAndGet()
                        else synchronized(loading) { missing -= key(s, t) }
                        val d = done.incrementAndGet()
                        if (d % 10 == 0 || d == tiles.size) withContext(Dispatchers.Main) { progress = d to tiles.size }
                    }
                }
            }
            workers.forEach { it.join() }
            withContext(Dispatchers.Main) {
                lastResult = if (!isActive) tr("Download interrompido.")
                else if (failed.get() == 0) tr("{0} tiles prontos para uso offline.", tiles.size)
                else tr("{0} de {1} tiles baixados ({2} falharam — sem internet?).", tiles.size - failed.get(), tiles.size, failed.get())
                progress = null
                version++
            }
        }
    }

    fun cancelDownload() { downloadJob?.cancel(); progress = null; lastResult = tr("Download interrompido.") }

    /** Disk used by the saved tiles of every source, in bytes. */
    fun diskUsage(): Long = root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    fun clear() {
        root.deleteRecursively()
        memory.evictAll()
        synchronized(loading) { missing.clear() }
        version++
    }
}
