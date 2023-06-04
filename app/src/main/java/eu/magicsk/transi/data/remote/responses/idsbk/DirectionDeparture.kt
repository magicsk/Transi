package eu.magicsk.transi.data.remote.responses.idsbk

data class DirectionDeparture(
    val station_id: Int,
    val station_name: String,
    val departure: Int,
    val stop_id: Int,
    val stop_code: String
)