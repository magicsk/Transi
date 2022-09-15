package eu.magicsk.transi.data.remote.responses

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.io.Serializable

@Parcelize
class Stops : ArrayList<Stop>(), Parcelable, Serializable