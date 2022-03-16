package eu.magicsk.transi.data.remote.responses

import android.os.Parcelable
import kotlinx.android.parcel.Parcelize

@Parcelize
data class Stop(
    val time: String,
    val stop: String,
    val zone: String,
    val request: Boolean
) : Parcelable