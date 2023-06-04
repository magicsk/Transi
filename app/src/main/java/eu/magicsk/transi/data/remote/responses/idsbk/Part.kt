package eu.magicsk.transi.data.remote.responses.idsbk

data class Part(
    val start_stop_id: Int,
    val start_stop_name: String,
    val start_stop_code: String?,
    val start_station_id: Int,
    val start_stop_gps: StopGps,
    val start_departure: String,
    val end_stop_id: Int,
    val end_stop_name: String,
    val end_stop_code: String?,
    val end_station_id: Int,
    val end_stop_gps: StopGps,
    val end_arrival: String,
    val duration: Int,
    val route_type: Int,
    val trip_id: Int?,
    val trip_route_id: Int?,
    val trip_headsign: String?,
    val trip_short_name: String?,
    val route_short_name: String?,
    val trip_zones: List<Int>?,
    val ticket_id: Int?
)