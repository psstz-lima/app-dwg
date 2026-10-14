package com.appdwg.app

import com.appdwg.core.tr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
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
import java.net.HttpURLConnection
import java.net.URL
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
                prefs.edit().putBoolean("osmEmptyFixed", true).apply()
            }
        }
    }

    fun enable(v: Boolean) { enabled = v; prefs.edit().putBoolean("enabled", v).apply() }
    fun choose(s: BasemapSource) { source = s; prefs.edit().putString("source", s.id).apply(); version++ }
    fun changeOpacity(v: Float) { opacity = v; prefs.edit().putFloat("opacity", v).apply() }
    fun allowOnline(v: Boolean) { online = v; prefs.edit().putBoolean("online", v).apply() }

    // Room for a full tablet screen of tiles plus the coarser and finer ones drawn while zooming.
    private val memory = object : LruCache<String, Bitmap>(minOf(128L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 5).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
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
    private fun bitmap(t: TileId, fetch: Boolean, s: BasemapSource = source): Bitmap? {
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
            if (known && f.length() > 0) disk.withPermit { bmp = BitmapFactory.decodeFile(f.path) }
            val gate = if (s.id == "osm") osmNet else net
            if (bmp == null && !known && fetch && online) gate.withPermit { bmp = download(s, t)?.let { BitmapFactory.decodeFile(it.path) } }
            // A stale tile is drawn at once and fetched again in the background.
            val age = s.maxAgeMs
            if (bmp != null && age != null && fetch && online && System.currentTimeMillis() - f.lastModified() > age) {
                scope.launch { gate.withPermit { download(s, t)?.let { BitmapFactory.decodeFile(it.path) }?.let { fresh -> memory.put(k, fresh); withContext(Dispatchers.Main) { version++ } } } }
            }
            synchronized(loading) {
                loading -= k
                if (bmp == null) missing[k] = System.currentTimeMillis() to ((missing[k]?.second ?: 0) + 1) else missing -= k
            }
            bmp?.let { memory.put(k, it); withContext(Dispatchers.Main) { version++ } }
        }
        return null
    }

    /** Saves [t] to disk; null when the server has no tile or the network fails. */
    private fun download(s: BasemapSource, t: TileId): File? = runCatching {
        val c = URL(WebTiles.url(s.url, t)).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", "Gromatic/1.0 (Android; survey and earthworks)")
        try {
            if (c.responseCode != 200) return null
            val f = file(s, t)
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".part")
            c.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
            if (s.greyPlaceholders && isPlaceholder(tmp)) { tmp.delete(); f.writeBytes(ByteArray(0)); return f }
            if (f.exists()) f.delete()
            if (!tmp.renameTo(f)) return null
            f
        } finally { c.disconnect() }
    }.getOrNull()

    /** Whether a tile is a flat grey "map data not yet available" picture. */
    private fun isPlaceholder(f: File): Boolean {
        if (f.length() > 12_000) return false
        val b = BitmapFactory.decodeFile(f.path) ?: return true
        try {
            val ref = b.getPixel(2, 2)
            val r = (ref shr 16) and 0xFF; val g = (ref shr 8) and 0xFF; val bl = ref and 0xFF
            if (kotlin.math.abs(r - g) > 6 || kotlin.math.abs(g - bl) > 6) return false
            var same = 0
            for (i in 0 until 16) for (j in 0 until 16) {
                val p = b.getPixel(8 + i * (b.width - 16) / 15, 8 + j * (b.height - 16) / 15)
                if (kotlin.math.abs(((p shr 16) and 0xFF) - r) < 6 && kotlin.math.abs((p and 0xFF) - bl) < 6) same++
            }
            return same >= 220
        } finally { b.recycle() }
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
                val b = memory.get(key(s, c)) ?: continue
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
    fun pickerTile(t: TileId): Pair<Bitmap, android.graphics.Rect>? {
        val s = BASEMAP_SOURCES.first { it.id == "osm" }
        bitmap(t, fetch = true, s = s)?.let { return it to android.graphics.Rect(0, 0, it.width, it.height) }
        var p = t
        for (up in 1..8) {
            if (p.z <= 0) break
            p = p.parent
            val b = bitmap(p, fetch = up == 2, s = s) ?: continue
            val n = 1 shl up
            val cw = b.width / n; val ch = b.height / n
            if (cw <= 0 || ch <= 0) return null
            val ox = (t.x - (p.x shl up)) * cw; val oy = (t.y - (p.y shl up)) * ch
            return b to android.graphics.Rect(ox, oy, ox + cw, oy + ch)
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
                        if (!file(s, t).exists() && download(s, t) == null) failed.incrementAndGet()
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
