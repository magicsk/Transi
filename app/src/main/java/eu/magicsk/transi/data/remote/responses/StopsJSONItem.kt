package eu.magicsk.transi.data.remote.responses

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.io.Serializable

@Parcelize
data class StopsJSONItem(
    val station_id: Int,
    val name: String,
    val html: String,
    val url: String,
    val value: String,
    val type: String,
    val id: Int,
    val lat: Double,
    val long: Double,
    val platform_labels: PlatformLabels?
) : Parcelable, Serializable
