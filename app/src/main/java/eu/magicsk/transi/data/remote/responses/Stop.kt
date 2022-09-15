package eu.magicsk.transi.data.remote.responses

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.io.Serializable

@Parcelize
data class Stop(
    val id: Int,
    val station_id: Int,
    val name: String,
    val city: String,
    val type: String,
    val trips_count: Int,
    val lat: Double,
    val lng: Double,
    val platform_labels: PlatformLabels?
) : Parcelable, Serializable
