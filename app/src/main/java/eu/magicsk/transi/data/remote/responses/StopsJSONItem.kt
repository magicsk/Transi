package eu.magicsk.transi.data.remote.responses

import android.os.Parcelable
import kotlinx.android.parcel.Parcelize

@Parcelize
data class StopsJSONItem(
    val name: String,
    val html: String,
    val url: String,
    val value: String,
    val type: String,
    val id: Int,
    val lat: String?,
    val long: String?,
    val zone: Int,
    val platform_labels: PlatformLabels?
) : Parcelable
