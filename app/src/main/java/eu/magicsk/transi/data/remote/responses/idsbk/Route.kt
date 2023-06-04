package eu.magicsk.transi.data.remote.responses.idsbk

data class Route(
    val route_id: Int,
    val short_name: String,
    val long_name: String,
    val description: String?,
    val route_type: Int,
    val color: String?,
    val text_color: String?,
    val graphics: Any?,
    val notes: List<Any>
)