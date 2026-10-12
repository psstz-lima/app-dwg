package com.appdwg.core

import kotlin.math.abs

/** Everything the app shows about one plan position. */
data class PointInfo(
    val position: Point2,
    /** Terrain elevation at the position, from the first surface that covers it. */
    val terrainElevation: Double?,
    val surfaceName: String?,
    val alignment: AlignmentInfo?,
)

data class AlignmentInfo(
    val name: String,
    val stationOffset: StationOffset,
    /** Design grade elevation at the station, on the axis. */
    val designElevation: Double?,
    /** Terrain elevation on the axis at the same station. */
    val terrainOnAxis: Double?,
) {
    /** Design minus terrain on the axis: positive is fill (aterro), negative is cut (corte). */
    val cutFill: Double? get() = if (designElevation != null && terrainOnAxis != null) designElevation - terrainOnAxis else null
}

object PointQuery {
    fun at(project: LandXmlProject, p: Point2): PointInfo {
        var terrain: Double? = null
        var surfaceName: String? = null
        for (s in project.surfaces) {
            val z = s.elevationAt(p) ?: continue
            terrain = z
            surfaceName = s.name
            break
        }

        val nearest = project.alignments
            .mapNotNull { a -> a.stationOffset(p)?.let { a to it } }
            .minByOrNull { (_, so) -> abs(so.offset) + if (so.outside) 1e9 else 0.0 }

        val alignment = nearest?.let { (a, so) ->
            AlignmentInfo(
                name = a.name,
                stationOffset = so,
                designElevation = if (so.outside) null else a.profile?.elevationAt(so.station),
                terrainOnAxis = elevation(project, so.pointOnAlignment),
            )
        }
        return PointInfo(p, terrain, surfaceName, alignment)
    }

    private fun elevation(project: LandXmlProject, p: Point2): Double? =
        project.surfaces.firstNotNullOfOrNull { it.elevationAt(p) }
}
