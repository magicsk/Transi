package eu.magicsk.transi.data.remote.responses

data class TripPlannerJSON(
    val routes: List<Route>,
    val status: String
)