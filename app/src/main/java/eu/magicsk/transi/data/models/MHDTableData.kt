package eu.magicsk.transi.data.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.io.Serializable

@Parcelize
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
): Parcelable, Serializable
