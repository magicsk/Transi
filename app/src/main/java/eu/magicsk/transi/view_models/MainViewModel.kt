package eu.magicsk.transi.view_models

import android.location.Location
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import eu.magicsk.transi.data.remote.responses.StopsJSON
import eu.magicsk.transi.data.remote.responses.StopsJSONItem

class MainViewModel : ViewModel() {

    val stopList = MutableLiveData<StopsJSON>()
    val actualLocation = MutableLiveData<Location>()
    val selectedStop = MutableLiveData<StopsJSONItem>()

    fun setStopList(value: StopsJSON) {
        stopList.value = value
    }

    fun setActualLocation(value: Location) {
        actualLocation.value = value
    }

    fun setSelectedStop(value: StopsJSONItem) {
        selectedStop.value = value
    }
}