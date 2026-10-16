package com.appdwg.core.drainage

import com.appdwg.core.Hydrology
import com.appdwg.core.Idf
import kotlin.math.max

/** A basin whose outlet is one placed device. Area is plan area, m². */
data class DeviceBasin(
    val deviceId: String,
    val areaM2: Double,
    val runoff: Double,
    /** Time of concentration, minutes. The rational method never uses less than 10. */
    val minutes: Double,
)

/** Local rational peak and the same peak plus everything arriving from upstream. */
data class ReachFlow(
    val minutes: Double,
    val intensity: Double,
    val local: Double,
    val accumulated: Double,
    val tooLarge: Boolean,
)

/**
 * Rational-method peaks for basins tied to devices, summed downstream along the network.
 * Manning capacity stays on [DrainageGraph]; the caller compares the accumulated flow with it.
 */
object DrainageFlow {
    fun peak(areaM2: Double, runoff: Double, idf: Idf, years: Double, minutes: Double): ReachFlow {
        val t = max(minutes, Hydrology.MIN_TC_MINUTES)
        val intensity = idf.intensity(t, years)
        val local = runoff * intensity * (areaM2 / 1e6) / 3.6
        return ReachFlow(t, intensity, local, local, areaM2 > Hydrology.RATIONAL_MAX_AREA)
    }

    /**
     * Each device receives its own peak plus the accumulated flow of every device that drains to it.
     * A loop is counted once.
     */
    fun accumulate(links: List<NetworkLink>, local: Map<String, Double>): Map<String, Double> {
        val nodes = LinkedHashSet<String>()
        nodes += local.keys
        for (link in links) {
            nodes += link.upstreamId
            nodes += link.downstreamId
        }
        val incoming = HashMap<String, MutableList<String>>()
        val pending = HashMap<String, Int>()
        for (id in nodes) pending[id] = 0
        for (link in links) {
            if (link.upstreamId == link.downstreamId) continue
            incoming.getOrPut(link.downstreamId) { ArrayList() }.add(link.upstreamId)
            pending[link.downstreamId] = (pending[link.downstreamId] ?: 0) + 1
        }
        val accumulated = HashMap<String, Double>()
        val queue = ArrayDeque<String>()
        for (id in nodes) if ((pending[id] ?: 0) == 0) queue.add(id)
        val done = HashSet<String>()
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (!done.add(id)) continue
            var sum = local[id] ?: 0.0
            for (upstream in incoming[id].orEmpty()) sum += accumulated[upstream] ?: 0.0
            accumulated[id] = sum
            for (link in links) {
                if (link.upstreamId != id || link.downstreamId == id) continue
                val left = (pending[link.downstreamId] ?: 1) - 1
                pending[link.downstreamId] = left
                if (left == 0) queue.add(link.downstreamId)
            }
        }
        for (id in nodes) if (id !in accumulated) accumulated[id] = local[id] ?: 0.0
        return accumulated
    }

    fun linked(basins: List<DeviceBasin>, links: List<NetworkLink>, idf: Idf, years: Double): Map<String, ReachFlow> {
        val localFlows = basins.associate { basin ->
            basin.deviceId to peak(basin.areaM2, basin.runoff, idf, years, basin.minutes)
        }
        val downstream = accumulate(links, localFlows.mapValues { it.value.local })
        return downstream.mapValues { (id, total) ->
            val local = localFlows[id]
            ReachFlow(
                local?.minutes ?: Hydrology.MIN_TC_MINUTES,
                local?.intensity ?: 0.0,
                local?.local ?: 0.0,
                total,
                local?.tooLarge == true,
            )
        }.filterValues { it.local > 0.0 || it.accumulated > 0.0 }
    }

    /** Replaces capacity issues with the accumulated flow against the Manning capacity already on [graph]. */
    fun applyCapacity(graph: DrainageGraph, flows: Map<String, ReachFlow>): DrainageGraph {
        val kept = graph.issues.filter { it.check != NetworkCheck.CAPACITY }
        val extra = ArrayList<NetworkIssue>()
        for ((id, flow) in flows) {
            val cap = graph.capacity[id] ?: 0.0
            if (flow.accumulated > cap + 1e-6) {
                extra += NetworkIssue(id, NetworkCheck.CAPACITY, flow = flow.accumulated, capacity = cap)
            }
        }
        return graph.copy(issues = kept + extra)
    }
}
