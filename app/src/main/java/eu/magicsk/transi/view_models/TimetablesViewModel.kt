package eu.magicsk.transi.view_models

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.magicsk.transi.data.remote.responses.idsbk.Timetable
import eu.magicsk.transi.data.remote.responses.idsbk.TimetableDetails
import eu.magicsk.transi.data.remote.responses.idsbk.TimetableDirections
import eu.magicsk.transi.data.remote.responses.idsbk.Timetables
import eu.magicsk.transi.repository.DataRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class TimetablesViewModel @Inject constructor(
    private val repository: DataRepository
) : ViewModel() {

    val timetables = MutableLiveData<Timetables>()
    val timetablesDirections = MutableLiveData<TimetableDirections>()
    val timetable = MutableLiveData<Timetable>()

    suspend fun getTimetables(): Timetables? =
        withContext(Dispatchers.IO) {
            val res = repository.getTimetables()
            res.data
        }

    fun getTimetableDirections(route: Int) = viewModelScope.launch {
        val res = repository.getTimetableDirections(route)
        res.data?.let { timetablesDirections.value = it }
    }

    fun getTimetable(route: Int, arrivalDeparture: String, direction: Int, date: String) =
        viewModelScope.launch {
            val res = repository.getTimetable(route, arrivalDeparture, direction, date)
            res.data?.let { timetable.value = it }
        }

    suspend fun getTimetableDetail(
        route: Int,
        arrivalDeparture: String,
        direction: Int,
        date: String,
        stop: Int
    ): TimetableDetails? =
        withContext(Dispatchers.IO) {
            val res = repository.getTimetableDetail(route, arrivalDeparture, direction, date, stop)
            res.data
        }
}