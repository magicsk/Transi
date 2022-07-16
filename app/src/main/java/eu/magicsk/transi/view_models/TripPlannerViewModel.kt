package eu.magicsk.transi.view_models

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.magicsk.transi.data.remote.responses.StopsJSONItem
import eu.magicsk.transi.repository.DataRepository
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject

@HiltViewModel
class TripPlannerViewModel @Inject constructor(
    private val repository: DataRepository
) : ViewModel() {
    private var tripLiveData = MutableLiveData<JSONObject?>()
    val selectedFromStop = MutableLiveData<StopsJSONItem?>()
    val selectedToStop = MutableLiveData<StopsJSONItem?>()
    val trip: LiveData<JSONObject?> = tripLiveData

    fun getTrip(
        v: Int = 7,
        from: String,
        to: String,
        date: String = "",
        time: String = "",
        arrivalDeparture: Int = 0,
        features: String = "pl=1.0-4.0",
        preference: Int = 0,
        moreTimeForTransfer: Int = 0,
        transportType: String = "30.1-11.1-3.1-6.1-2.1-15.1-22.1",
        rate: String = "2.1-21.1-49.1",
        carriers: String = "1.1-80.1-214.1-219.1",
        service: String = "Planner",
        format: Int = 0
    ) = viewModelScope.launch {
        val trip = repository.getTrip(
            v,
            from,
            to,
            date,
            time,
            arrivalDeparture,
            features,
            preference,
            moreTimeForTransfer,
            transportType,
            rate,
            carriers,
            service,
            format
        )
        println(trip.message)
        tripLiveData.value = JSONObject(trip.data ?: "")
    }

    fun setSelectedFromStop(value: StopsJSONItem) {
        selectedFromStop.value = value
    }

    fun setSelectedToStop(value: StopsJSONItem?) {
        selectedToStop.value = value
    }

    fun clear() {
        selectedFromStop.value = null
        selectedToStop.value = null
        tripLiveData.value = null
    }
}