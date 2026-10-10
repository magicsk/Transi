package eu.magicsk.transi.util

import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.core.content.edit
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import eu.magicsk.transi.R

/** Wires the "Trip planner" section of `R.xml.prefs_trip_planner`, once it is added to [fragment]. */
fun bindTripPlannerPreferences(fragment: PreferenceFragmentCompat) {
    fragment.findPreference<SeekBarPreference>(Prefs.TRIP_MAX_WALK_DURATION)?.showValueInTitle(R.string.trip_planner_max_walk)
    fragment.findPreference<SeekBarPreference>(Prefs.TRIP_MAX_TRANSFERS)?.showValueInTitle(R.string.trip_planner_max_transfers)
    // The list keeps its value as a string; the setting is stored as hours (Int), as the planner reads it.
    val saveDuration = fragment.findPreference<ListPreference>(Prefs.TRIP_SAVE_DURATION) ?: return
    val prefs = saveDuration.sharedPreferences ?: return
    saveDuration.value = prefs.intPref(Prefs.TRIP_SAVE_DURATION, Prefs.TRIP_SAVE_DURATION_DEFAULT).toString()
    saveDuration.setOnPreferenceChangeListener { _, value ->
        val hours = (value as? String)?.toIntOrNull() ?: return@setOnPreferenceChangeListener false
        prefs.edit { putInt(Prefs.TRIP_SAVE_DURATION, hours) }
        true
    }
}

private fun SeekBarPreference.showValueInTitle(@StringRes format: Int) {
    title = context.getString(format, value)
    setOnPreferenceChangeListener { preference, newValue ->
        preference.title = preference.context.getString(format, newValue as? Int ?: value)
        true
    }
}

/** An Int setting, also when it was stored as a string. */
fun SharedPreferences.intPref(key: String, default: Int): Int = try {
    getInt(key, default)
} catch (_: ClassCastException) {
    getString(key, null)?.toIntOrNull() ?: default
}
