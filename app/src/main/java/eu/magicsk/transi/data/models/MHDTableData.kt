package eu.magicsk.transi.data.models

data class MHDTableData(
    val Id: Long,
    val line: String,
    val platform: String,
    val busID: String,
    val headsign: String,
    val departureTime: Long,
    val delay: Int,
    val type: String,
    val currentStopId: Int,
    val lastStopId: Int,
    val lastStopName: String,
    val stuck: Boolean,
    var expanded: Boolean
)
