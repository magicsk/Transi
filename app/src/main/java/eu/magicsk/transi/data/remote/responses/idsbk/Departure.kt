package eu.magicsk.transi.data.remote.responses.idsbk

data class Departure(
    val trip_id: Int,
    val trip_flags: Int,
    val trip_headsign: String,
    val departure: Int,
    val route_type: Int,
    val external_id: String,
    val direction_departures: List<DirectionDeparture>,
    val trip_delay: Any?
)