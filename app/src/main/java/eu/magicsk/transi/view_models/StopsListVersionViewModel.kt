package eu.magicsk.transi.view_models

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.magicsk.transi.data.remote.responses.StopsVersion
import eu.magicsk.transi.repository.DataRepository
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StopsListVersionViewModel @Inject constructor(
    private val repository: DataRepository
) : ViewModel() {

    private val stopsVersionLiveData = MutableLiveData<StopsVersion?>()
    val stopsVersion: MutableLiveData<StopsVersion?> = stopsVersionLiveData

    init {
        viewModelScope.launch {
            val stopsVersion = repository.getStopsVersion()
            stopsVersionLiveData.value = stopsVersion.data
        }
    }
}