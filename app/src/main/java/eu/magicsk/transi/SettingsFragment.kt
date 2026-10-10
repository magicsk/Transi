package eu.magicsk.transi

import android.os.Bundle
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.Toast
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import eu.magicsk.transi.data.remote.responses.Stop
import eu.magicsk.transi.timetable.TimetableDatabase
import eu.magicsk.transi.timetable.TimetableDownloadState
import eu.magicsk.transi.timetable.showWhileViewExists
import eu.magicsk.transi.timetable.text
import eu.magicsk.transi.util.Prefs
import eu.magicsk.transi.util.bindTripPlannerPreferences
import eu.magicsk.transi.util.readCachedStops
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject

@AndroidEntryPoint
class SettingsFragment : PreferenceFragmentCompat() {
    @Inject
    lateinit var timetableDatabase: TimetableDatabase

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = Prefs.FILE
        // One XML per section, in the iOS order.
        val sections = listOf(
            R.xml.prefs_general,
            R.xml.prefs_timetables,
            R.xml.prefs_trip_planner,
            R.xml.prefs_developer,
        )
        setPreferencesFromResource(sections.first(), rootKey)
        sections.drop(1).forEach(::addPreferencesFromResource)
        bindGeneral()
        bindTimetables()
        bindTripPlannerPreferences(this)
        bindDeveloper()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        listView.clipToPadding = false
        ViewCompat.setOnApplyWindowInsetsListener(listView) { list, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()
            )
            list.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom)
            insets
        }
    }

    // The switch summary follows the download state.
    private fun bindTimetables() {
        val offline = requireNotNull(findPreference<SwitchPreferenceCompat>(Prefs.OFFLINE_TIMETABLES))
        val delete = requireNotNull(findPreference<Preference>("timetable_delete_database"))
        offline.isChecked = timetableDatabase.isOfflineEnabled
        offline.setOnPreferenceChangeListener { _, enabled ->
            timetableDatabase.setOfflineEnabled(enabled == true)
            true
        }
        val deleteTitle = getString(R.string.timetable_delete_database)
        val errorColor = MaterialColors.getColor(requireContext(), androidx.appcompat.R.attr.colorError, deleteTitle)
        delete.title = SpannableString(deleteTitle).apply {
            setSpan(ForegroundColorSpan(errorColor), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        delete.setOnPreferenceClickListener {
            showWhileViewExists(
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.timetable_delete_database_title)
                    .setMessage(R.string.timetable_delete_database_message)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.timetable_delete) { _, _ ->
                        timetableDatabase.delete()
                        offline.isChecked = false
                    }
            )
            true
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                timetableDatabase.state.collect { state ->
                    offline.summary = state.text(requireContext()) ?: getString(
                        if (state == TimetableDownloadState.Ready) R.string.timetable_db_ready else R.string.offline_timetables_summary
                    )
                }
            }
        }
    }

    // The default stop summary and its stop picker.
    private fun bindGeneral() {
        val preference = requireNotNull(findPreference<Preference>(Prefs.DEFAULT_STOP_ID))
        val prefs = Prefs.get(requireContext())
        var stops: List<Stop>? = null
        fun updateSummary() {
            val stopId = prefs.getInt(Prefs.DEFAULT_STOP_ID, -1)
            preference.summary = when {
                stopId <= 0 -> getString(R.string.table_actual_location)
                else -> stops?.let { list -> list.firstOrNull { it.id == stopId }?.name ?: getString(R.string.pref_unknown_stop) }
            }
        }
        val picker = registerForActivityResult(StopPickerActivity.PickStop()) { stopId ->
            if (stopId != null) {
                prefs.edit { putInt(Prefs.DEFAULT_STOP_ID, stopId) }
                updateSummary()
            }
        }
        preference.setOnPreferenceClickListener {
            picker.launch(Unit)
            true
        }
        updateSummary()
        val context = requireContext().applicationContext
        lifecycleScope.launch {
            stops = withContext(Dispatchers.IO) { readCachedStops(context) }.orEmpty()
            updateSummary()
        }
    }

    private fun bindDeveloper() {
        val apiUrl = requireNotNull(findPreference<EditTextPreference>(Prefs.MAGIC_API_URL_OVERRIDE))
        val reset = requireNotNull(findPreference<Preference>("magic_api_url_reset"))
        apiUrl.summaryProvider = Preference.SummaryProvider<EditTextPreference> {
            it.text.takeUnless { url -> url.isNullOrEmpty() } ?: getString(R.string.settings_api_url_default)
        }
        apiUrl.setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
        apiUrl.setOnPreferenceChangeListener { _, newValue ->
            val url = newValue.toString()
            val parsed = url.toHttpUrlOrNull()
            // Release builds block cleartext, so only debug builds accept http:// (local servers).
            val valid = url.isEmpty() || (parsed != null && (parsed.isHttps || BuildConfig.DEBUG))
            if (valid) {
                reset.isVisible = url.isNotEmpty()
            } else {
                Toast.makeText(requireContext(), R.string.settings_api_url_invalid, Toast.LENGTH_SHORT).show()
            }
            valid
        }
        reset.isVisible = !apiUrl.text.isNullOrEmpty()
        reset.setOnPreferenceClickListener {
            apiUrl.text = ""
            it.isVisible = false
            true
        }
    }
}
