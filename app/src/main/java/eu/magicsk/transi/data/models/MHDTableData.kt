package eu.magicsk.transi.data.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.io.Serializable

/** One departure on the board (iOS Connection). */
@Parcelize
data class MHDTableData(
    /** "{i}:{platform}" for socket rows, "{tripId}:{stopId}:{scheduled seconds}" for regional ones. */
    val id: String,
    val tripId: Long,
    val line: String,
    val platform: Int,
    val stopId: Int,
    val busID: String,
    val headsign: String,
    /** Scheduled departure, epoch seconds rounded to the minute. */
    val departureTimeCP: Long,
    /** Expected departure, epoch milliseconds. */
    val departureTime: Long,
    val delay: Int,
    val type: String,
    val currentStopId: Int,
    val lastStopId: Int,
    val lastStopName: String,
    val stuck: Boolean,
) : Parcelable, Serializable
