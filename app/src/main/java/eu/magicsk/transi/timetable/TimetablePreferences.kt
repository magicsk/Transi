package eu.magicsk.transi.timetable

import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import eu.magicsk.transi.R
import eu.magicsk.transi.util.Prefs
import kotlinx.coroutines.launch

private const val KEY_DELETE_DATABASE = "timetable_delete_database"

@EntryPoint
@InstallIn(SingletonComponent::class)
interface TimetableDatabaseEntryPoint {
    fun timetableDatabase(): TimetableDatabase
}

/**
 * Wires the "Timetables" section; call after `addPreferencesFromResource(R.xml.prefs_timetables)`.
 * The switch summary follows the download state.
 */
fun bindTimetablesPreferences(fragment: PreferenceFragmentCompat) {
    val context = fragment.requireContext()
    val database = EntryPointAccessors.fromApplication(context, TimetableDatabaseEntryPoint::class.java)
        .timetableDatabase()
    val offline = checkNotNull(fragment.findPreference<SwitchPreferenceCompat>(Prefs.OFFLINE_TIMETABLES)) {
        "prefs_timetables must be added before binding it"
    }
    val delete = checkNotNull(fragment.findPreference<Preference>(KEY_DELETE_DATABASE)) {
        "prefs_timetables must be added before binding it"
    }

    offline.isChecked = database.isOfflineEnabled
    offline.setOnPreferenceChangeListener { _, enabled ->
        database.setOfflineEnabled(enabled == true)
        true
    }

    val deleteTitle = context.getString(R.string.timetable_delete_database)
    val errorColor = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorError, deleteTitle)
    delete.title = SpannableString(deleteTitle).apply {
        setSpan(ForegroundColorSpan(errorColor), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    delete.setOnPreferenceClickListener {
        fragment.showWhileViewExists(
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.timetable_delete_database_title)
                .setMessage(R.string.timetable_delete_database_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.timetable_delete) { _, _ ->
                    database.delete()
                    offline.isChecked = false
                }
        )
        true
    }

    fragment.lifecycleScope.launch {
        fragment.repeatOnLifecycle(Lifecycle.State.STARTED) {
            database.state.collect { state ->
                offline.summary = state.text(context) ?: context.getString(
                    if (state == TimetableDownloadState.Ready) R.string.timetable_db_ready else R.string.offline_timetables_summary
                )
            }
        }
    }
}
