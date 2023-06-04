package eu.magicsk.transi.data.remote.responses.idsbk

data class Journey(
    val parts: List<Part>,
    val zones: List<Int>,
    val ticket_id: Int
)