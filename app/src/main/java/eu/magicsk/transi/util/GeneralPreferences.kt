package eu.magicsk.transi.util

import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import eu.magicsk.transi.R
import eu.magicsk.transi.StopPickerActivity
import eu.magicsk.transi.data.remote.responses.Stop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Wires the "General" section (res/xml/prefs_general.xml): the default stop summary and its stop picker.
 * Call from onCreatePreferences after adding the section; it registers an activity result launcher.
 */
fun bindGeneralPreferences(fragment: PreferenceFragmentCompat) {
    val preference = fragment.findPreference<Preference>(Prefs.DEFAULT_STOP_ID) ?: return
    val context = fragment.requireContext().applicationContext
    val prefs = Prefs.get(context)
    var stops: List<Stop>? = null
    fun updateSummary() {
        val stopId = prefs.getInt(Prefs.DEFAULT_STOP_ID, -1)
        preference.summary = when {
            stopId <= 0 -> context.getString(R.string.table_actual_location)
            else -> stops?.let { list -> list.firstOrNull { it.id == stopId }?.name ?: context.getString(R.string.pref_unknown_stop) }
        }
    }
    val picker = fragment.registerForActivityResult(StopPickerActivity.PickStop()) { stopId ->
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
    fragment.lifecycleScope.launch {
        stops = withContext(Dispatchers.IO) { readCachedStops(context) }.orEmpty()
        updateSummary()
    }
}
