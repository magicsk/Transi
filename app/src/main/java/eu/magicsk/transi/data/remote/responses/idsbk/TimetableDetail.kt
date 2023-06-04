package eu.magicsk.transi.data.remote.responses.idsbk

data class TimetableDetail(
    val t: Int,
    val trip_id: Int,
    val trip_flags: Int,
    val trip_short_name: String,
    val trip_headsign: String,
    val route_short_name: String,
    val route_type: Int,
    val external_id: String,
    val group_notes: List<GroupNote>,
    val trip_delay: Int?,
    val last_station: String?,
    val last_station_id: Int?,
    val last_stop_id: Int?,
    val last_stop_code: String?
)