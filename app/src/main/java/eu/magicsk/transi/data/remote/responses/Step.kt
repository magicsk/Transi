package eu.magicsk.transi.data.remote.responses

import android.os.Parcelable
import kotlinx.android.parcel.Parcelize

@Parcelize
data class Step(
    val arrival_stop: String = "",
    val arrival_time: String = "",
    val departure_stop: String = "",
    val departure_time: String = "",
    val duration: String = "",
    val headsign: String = "",
    val line: Line = Line("",""),
    val num_stops: String = "",
    val stops: List<Stop> = listOf(),
    val text: String = "",
    val type: String = ""
) : Parcelable