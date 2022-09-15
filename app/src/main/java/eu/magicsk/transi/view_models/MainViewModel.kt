package eu.magicsk.transi.view_models

import android.location.Location
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import eu.magicsk.transi.data.remote.responses.Stop
import eu.magicsk.transi.data.remote.responses.Stops
import org.json.JSONArray
import org.json.JSONException

class MainViewModel : ViewModel() {

    val stopList = MutableLiveData<Stops>()
    val actualLocation = MutableLiveData<Location>()
    val selectedStop = MutableLiveData<Stop?>()
    val tableInfo = MutableLiveData("")

    fun setStopList(value: Stops) {
        stopList.value = value
    }

    fun setActualLocation(value: Location) {
        actualLocation.value = value
    }

    fun setSelectedStop(value: Stop) {
        selectedStop.value = value

    }

    fun setTableInfo(value: JSONArray) {
        tableInfo.value = ""
        for (i in 0 until value.length()) {
            val info = try {
                value.getString(i)
            } catch (_: JSONException) {
                ""
            }
            if (info != "" && tableInfo.value != "") tableInfo.value = "${tableInfo.value}\n\n$info"
            else if (info != "") tableInfo.value = info
        }
    }

    fun clear() {
        selectedStop.value = null
    }
}