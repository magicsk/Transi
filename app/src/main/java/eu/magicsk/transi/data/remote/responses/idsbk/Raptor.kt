package eu.magicsk.transi.data.remote.responses.idsbk

/** R-API `POST /mobile/v1/raptor/` body. Null fields are left out. */
data class RaptorRequest(
    val org_id: Int = 120,
    val max_walk_duration: Int,
    val max_transfers: Int,
    val search_from_hours: Int? = null,
    val search_to_hours: Int? = null,
    /** ISO-8601 UTC, like "2026-10-05T06:00:00Z". */
    val search_from: String? = null,
    val search_to: String? = null,
    val from_station_id: List<Int>,
    val to_station_id: List<Int>,
)

data class RaptorResponse(
    val journey: List<RaptorJourney>?
)

data class RaptorJourney(
    val journey_guid: String?,
    val parts: List<RaptorPart>?,
    val zones: List<Int>?,
    val ticket_id: Int?
)

data class RaptorPart(
    val start_stop_id: Int?,
    val start_stop_name: String?,
    val start_stop_code: String?,
    val start_station_id: Int?,
    val start_stop_gps: StopGps?,
    /** ISO-8601 UTC with milliseconds, like "2026-10-05T06:00:00.000Z". */
    val start_departure: String?,
    val end_stop_id: Int?,
    val end_stop_name: String?,
    val end_stop_code: String?,
    val end_station_id: Int?,
    val end_stop_gps: StopGps?,
    val end_arrival: String?,
    val duration: Int?,
    /** 64 walk, 50 trolleybus, 3 bus… */
    val route_type: Int?,
    val trip_id: Int?,
    val trip_route_id: Int?,
    val trip_headsign: String?,
    val trip_short_name: String?,
    val route_short_name: String?,
    val trip_zones: List<Int>?,
    /** Seconds; null until the vehicle runs. */
    val trip_delay: Int?,
    val ticket_id: Int?
)
