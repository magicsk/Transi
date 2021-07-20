package eu.magicsk.transi.data.remote.responses

data class Route(
    val arrival_departure_time: String,
    val duration: String,
    val steps: List<Step>,
    val zones: String
)