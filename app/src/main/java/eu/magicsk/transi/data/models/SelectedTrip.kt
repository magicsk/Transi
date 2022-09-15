package eu.magicsk.transi.data.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.io.Serializable

@Parcelize
data class SelectedTrip(
    var v: Int = 7,
    var from: Int? = null,
    var to: Int? = null,
    var date: String = "",
    var time: String = "",
    var arrivalDeparture: Int = 0,
    var features: String = "pl=1.0-4.0",
    var preference: Int = 0,
    var moreTimeForTransfer: Int = 0,
    var transportType: String = "30.1-11.1-3.1-6.1-2.1-15.1-22.1",
    var rate: String = "2.1-21.1-49.1",
    var carriers: String = "1.1-80.1-214.1-219.1",
    var service: String = "Planner",
    var format: Int = 0
) : Parcelable, Serializable
