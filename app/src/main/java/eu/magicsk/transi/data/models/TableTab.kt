package eu.magicsk.transi.data.models

data class TableTab(
    val Id: Long,
    val line: String,
    val busID: String,
    val headsign: String,
    val departureTime: Long,
    val delay: Int,
    val type: String,
    val lastStopId: Int,
)
