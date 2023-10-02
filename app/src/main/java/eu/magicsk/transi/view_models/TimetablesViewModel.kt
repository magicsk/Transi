package eu.magicsk.transi.view_models

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.magicsk.transi.data.remote.responses.idsbk.Session
import eu.magicsk.transi.data.remote.responses.idsbk.Timetable
import eu.magicsk.transi.data.remote.responses.idsbk.TimetableDetails
import eu.magicsk.transi.data.remote.responses.idsbk.TimetableDirections
import eu.magicsk.transi.data.remote.responses.idsbk.Timetables
import eu.magicsk.transi.repository.DataRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class TimetablesViewModel @Inject constructor(
    private val repository: DataRepository
) : ViewModel() {

    suspend fun getTimetables(session: Session): Timetables? =
        withContext(Dispatchers.IO) {
            val res = repository.getTimetables(session.session)
            res.data
        }

    suspend fun getTimetableDirections(route: Int, session: Session): TimetableDirections? =
        withContext(Dispatchers.IO) {
            val res = repository.getTimetableDirections(session.session, route)
            res.data
        }

    suspend fun getTimetable(
        route: Int,
        arrivalDeparture: String,
        direction: Int,
        date: String,
        session: Session
    ): Timetable? =
        withContext(Dispatchers.IO) {
            val res =
                repository.getTimetable(session.session, route, arrivalDeparture, direction, date)
            res.data
        }

    suspend fun getTimetableDetail(
        route: Int,
        arrivalDeparture: String,
        direction: Int,
        date: String,
        stop: Int,
        session: Session
    ): TimetableDetails? =
        withContext(Dispatchers.IO) {
            val res = repository.getTimetableDetail(
                session.session,
                route,
                arrivalDeparture,
                direction,
                date,
                stop
            )
            res.data
        }
}