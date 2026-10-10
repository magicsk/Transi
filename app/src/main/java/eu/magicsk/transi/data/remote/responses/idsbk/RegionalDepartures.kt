package eu.magicsk.transi.data.remote.responses.idsbk

data class RegionalDepartures(
    val current: List<RegionalDeparture>?
)

data class RegionalDeparture(
    val trip_id: Long,
    val trip_headsign: String?,
    val route_short_name: String?,
    /** Minutes after local midnight. */
    val departure: Int,
    val stop_id: Int,
    val stop_code: String?,
    /** Seconds; null when the trip is not tracked. */
    val trip_delay: Int?,
    val last_station: String?,
    val last_station_id: Int?,
)
