package eu.magicsk.transi.data.remote.responses

import android.os.Parcelable
import kotlinx.android.parcel.Parcelize

@Parcelize
data class TripPlannerJSON(
    val routes: List<Route>,
    val status: String
) : Parcelable