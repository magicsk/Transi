package eu.magicsk.transi.data.remote.responses

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class PlatformLabel(
    val id: String,
    val label: String
)  : Parcelable
