package eu.magicsk.transi.data.models

data class MHDTableData(
    val Id: Long,
    val line: String,
    var busID: String,
    val headsign: String,
    var departureTime: Long,
    var delay: Int,
    var type: String,
    var lastStopId: Int,
)
