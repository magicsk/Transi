package eu.magicsk.transi.data.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.io.Serializable

@Parcelize
data class MHDTableVehicle(
    val id: Int,
    val lf: Int,
    val ac: Int,
    val img: Int,
    val imgt: Int,
    val type: String,
    val issi: String,
    val train: String?,
): Parcelable, Serializable
