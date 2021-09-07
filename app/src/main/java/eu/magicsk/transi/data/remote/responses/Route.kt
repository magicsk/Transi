package eu.magicsk.transi.data.remote.responses

import android.os.Parcelable
import kotlinx.android.parcel.Parcelize

@Parcelize
data class Route(
    val arrival_departure_time: String,
    val duration: String,
    val steps: List<Step>,
    val zones: String
) : Parcelable