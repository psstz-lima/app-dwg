package com.appdwg.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.appdwg.core.Bounds
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
data class BasemapSource(val id: String, val label: String, val url: String, val maxZoom: Int, val bulk: Boolean, val credit: String)

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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var enabled by mutableStateOf(prefs.getBoolean("enabled", false)); private set
    var source by mutableStateOf(BASEMAP_SOURCES.firstOrNull { it.id == prefs.getString("source", null) } ?: BASEMAP_SOURCES[0]); private set
    var opacity by mutableStateOf(prefs.getFloat("opacity", 0.8f)); private set
    /** Fetch missing tiles from the network while viewing. */
    var online by mutableStateOf(prefs.getBoolean("online", true)); private set
    /** Bumped when a tile arrives, so the plan redraws. */
    var version by mutableIntStateOf(0); private set

    fun enable(v: Boolean) { enabled = v; prefs.edit().putBoolean("enabled", v).apply() }
    fun choose(s: BasemapSource) { source = s; prefs.edit().putString("source", s.id).apply(); version++ }
    fun changeOpacity(v: Float) { opacity = v; prefs.edit().putFloat("opacity", v).apply() }
    fun allowOnline(v: Boolean) { online = v; prefs.edit().putBoolean("online", v).apply() }

    private val memory = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    /** Tiles not on disk nor fetched, with when that was found, so they are not retried every frame. */
    private val missing = HashMap<String, Long>()
    private val loading = HashSet<String>()
    private val corners = object : LruCache<String, TileCorners>(4096) {}
    private val net = Semaphore(4)
    private val disk = Semaphore(2)

    private fun key(s: BasemapSource, t: TileId) = "${s.id}/${t.z}/${t.x}/${t.y}"
    private fun file(s: BasemapSource, t: TileId) = File(root, "${s.id}/${t.z}/${t.x}/${t.y}")

    /** Picture of [t] if loaded; otherwise starts loading it from disk (or the network when [fetch]). */
    private fun bitmap(t: TileId, fetch: Boolean): Bitmap? {
        val s = source
        val k = key(s, t)
        memory.get(k)?.let { return it }
        synchronized(loading) {
            if (k in loading) return null
            val since = missing[k]
            if (since != null && !(fetch && online && System.currentTimeMillis() - since > 30_000)) return null
            loading += k
        }
        scope.launch {
            val f = file(s, t)
            var bmp: Bitmap? = null
            // An empty file marks a tile the server has no picture for: a coarser one is drawn instead.
            val known = f.exists()
            if (known && f.length() > 0) disk.withPermit { bmp = BitmapFactory.decodeFile(f.path) }
            if (bmp == null && !known && fetch && online) net.withPermit { bmp = download(s, t)?.let { BitmapFactory.decodeFile(it.path) } }
            synchronized(loading) {
                loading -= k
                if (bmp == null) missing[k] = System.currentTimeMillis() else missing -= k
            }
            bmp?.let { memory.put(k, it); withContext(Dispatchers.Main) { version++ } }
        }
        return null
    }

    /** Saves [t] to disk; null when the server has no tile or the network fails. */
    private fun download(s: BasemapSource, t: TileId): File? = runCatching {
        val c = URL(WebTiles.url(s.url, t)).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", "AppDWG/1.0 (Android; levantamento de campo)")
        try {
            if (c.responseCode != 200) return null
            val f = file(s, t)
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".part")
            c.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
            if (isPlaceholder(tmp)) { tmp.delete(); f.writeBytes(ByteArray(0)); return f }
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

    private fun cornersOf(t: TileId, zone: Int, south: Boolean): TileCorners {
        val k = "$zone$south/${t.z}/${t.x}/${t.y}"
        return corners.get(k) ?: WebTiles.corners(t, zone, south).also { corners.put(k, it) }
    }

    /** Tiles to draw over the UTM area [view] at [mpp] metres per screen pixel. */
    fun visible(view: Bounds, mpp: Double, zone: Int, south: Boolean): List<BasemapTile> {
        val s = source
        val lat = runCatching { Utm.toGeographic(view.center.x, view.center.y, zone, south).first }.getOrNull() ?: return emptyList()
        if (lat.isNaN() || kotlin.math.abs(lat) > 85) return emptyList()
        val z = WebTiles.zoomFor(mpp, lat, 2, s.maxZoom)
        val tiles = WebTiles.covering(view, zone, south, z)
        if (tiles.size > 400) return emptyList()
        val out = ArrayList<BasemapTile>(tiles.size)
        for (t in tiles) {
            val own = bitmap(t, fetch = true)
            if (own != null) { out += BasemapTile(own, android.graphics.Rect(0, 0, own.width, own.height), cornersOf(t, zone, south)); continue }
            // Coarser tile, cropped to this tile's part of it.
            var p = t
            for (up in 1..6) {
                if (p.z <= 1) break
                p = p.parent
                val b = bitmap(p, fetch = false) ?: continue
                val n = 1 shl up
                val cw = b.width / n; val ch = b.height / n
                val ox = (t.x - (p.x shl up)) * cw; val oy = (t.y - (p.y shl up)) * ch
                if (cw > 0 && ch > 0) out += BasemapTile(b, android.graphics.Rect(ox, oy, ox + cw, oy + ch), cornersOf(t, zone, south))
                break
            }
        }
        return out
    }

    // --- Area download ---------------------------------------------------------------------

    var downloadJob: Job? by mutableStateOf(null); private set
    var progress by mutableStateOf<Pair<Int, Int>?>(null); private set
    var lastResult by mutableStateOf<String?>(null); private set

    fun count(area: Bounds, zone: Int, south: Boolean, maxZoom: Int) = WebTiles.pyramid(area, zone, south, 10, maxZoom).size

    fun download(area: Bounds, zone: Int, south: Boolean, maxZoom: Int) {
        val s = source
        if (!s.bulk || downloadJob?.isActive == true) return
        val tiles = WebTiles.pyramid(area, zone, south, 10, maxZoom)
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
                lastResult = if (!isActive) "Download interrompido."
                else if (failed.get() == 0) "${tiles.size} tiles prontos para uso offline."
                else "${tiles.size - failed.get()} de ${tiles.size} tiles baixados (${failed.get()} falharam — sem internet?)."
                progress = null
                version++
            }
        }
    }

    fun cancelDownload() { downloadJob?.cancel(); progress = null; lastResult = "Download interrompido." }

    /** Disk used by the saved tiles of every source, in bytes. */
    fun diskUsage(): Long = root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    fun clear() {
        root.deleteRecursively()
        memory.evictAll()
        synchronized(loading) { missing.clear() }
        version++
    }
}
