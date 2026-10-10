package eu.magicsk.transi

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import eu.magicsk.transi.timetable.bindTimetablesPreferences
import eu.magicsk.transi.util.Prefs
import eu.magicsk.transi.util.bindGeneralPreferences
import eu.magicsk.transi.util.bindTripPlannerPreferences
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class SettingsFragment : PreferenceFragmentCompat() {

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
        bindGeneralPreferences(this)
        bindTimetablesPreferences(this)
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
