package eu.magicsk.transi

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import eu.magicsk.transi.timetable.TimetableDatabase
import javax.inject.Inject

@HiltAndroidApp
class TransiApplication : Application() {
    @Inject
    lateinit var timetableDatabase: TimetableDatabase

    override fun onCreate() {
        super.onCreate()
        timetableDatabase.start()
    }
}
