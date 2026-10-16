package com.appdwg.app

import com.appdwg.core.Alignment
import com.appdwg.core.Point2
import com.appdwg.core.Profile

/** Terrain elevations every [step] metres, the same points as "Gerar PVIs no terreno". */
internal fun terrainGradePvis(axis: Alignment, step: Double = 100.0, elevationAt: (Point2) -> Double?): List<Profile.Pvi> {
    val start = axis.startStation
    val end = axis.endStation
    if (!(end > start)) return emptyList()
    val hop = step.coerceAtLeast(1.0)
    val stations = ArrayList<Double>()
    var station = start
    while (station < end - 1e-6) {
        stations += station
        station += hop
    }
    stations += end
    return stations.map { at -> Profile.Pvi(at, axis.pointAt(at)?.let(elevationAt) ?: 0.0) }
}
