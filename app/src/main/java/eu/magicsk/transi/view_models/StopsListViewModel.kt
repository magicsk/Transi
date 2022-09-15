package eu.magicsk.transi.view_models

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.magicsk.transi.data.remote.responses.Stops
import eu.magicsk.transi.repository.DataRepository
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StopsListViewModel @Inject constructor(
    private val repository: DataRepository
) : ViewModel() {

    private val stopsLiveData = MutableLiveData<Stops>()
    val stops: LiveData<Stops> = stopsLiveData

    init {
        viewModelScope.launch {
            val stops = repository.getStops()
            stopsLiveData.value = stops.data!!
        }
    }
}