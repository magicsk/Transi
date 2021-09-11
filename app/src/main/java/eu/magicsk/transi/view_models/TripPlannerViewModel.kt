package eu.magicsk.transi.view_models

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.magicsk.transi.data.remote.responses.TripPlannerJSON
import eu.magicsk.transi.repository.DataRepository
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TripPlannerViewModel @Inject constructor(
    private val repository: DataRepository
) : ViewModel() {

    private val tripLiveData = MutableLiveData<TripPlannerJSON>()
    val trip: LiveData<TripPlannerJSON> = tripLiveData

    fun getTrip(time: Long, from: String, to: String) = viewModelScope.launch {
        val trip = repository.getTrip(time, from, to, 0)
        tripLiveData.value = trip.data
    }

}