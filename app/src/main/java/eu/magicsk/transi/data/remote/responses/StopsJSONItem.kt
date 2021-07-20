package eu.magicsk.transi.data.remote.responses

data class StopsJSONItem(
    val `class`: String,
    val html: String,
    val id: Int,
    val lat: String,
    val long: String,
    val name: String,
    val url: String,
    val value: String
)