package eu.magicsk.transi

import android.os.Bundle
import android.view.View
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.CheckBoxPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import dagger.hilt.android.AndroidEntryPoint
import eu.magicsk.transi.data.models.SeasonPass
import eu.magicsk.transi.data.models.TicketCatalogue
import eu.magicsk.transi.repository.TicketCatalogueProvider
import eu.magicsk.transi.util.Prefs
import javax.inject.Inject
import kotlinx.coroutines.launch

/** "My pass" zones: "100 + 101" together, then 101 and the regional zones from the IDS BK catalogue, or network-wide. */
@AndroidEntryPoint
class PassZonesFragment : PreferenceFragmentCompat() {
    @Inject
    lateinit var provider: TicketCatalogueProvider

    /** Zone name → its row; "100" is "100 + 101". */
    private val zoneRows = mutableMapOf<String, CheckBoxPreference>()
    private var shownZones: List<TicketCatalogue.Zone>? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = Prefs.FILE
        setPreferencesFromResource(R.xml.prefs_pass_zones, rootKey)
        val list = requireNotNull(findPreference<PreferenceCategory>(KEY_LIST))
        val networkWide = requireNotNull(findPreference<SwitchPreferenceCompat>(Prefs.PASS_NETWORK_WIDE))
        list.isVisible = !networkWide.isChecked
        networkWide.setOnPreferenceChangeListener { _, value ->
            list.isVisible = value != true
            true
        }
        addZone("100", requireNotNull(findPreference(KEY_CITY)))
        requireNotNull(findPreference<Preference>(KEY_RETRY)).setOnPreferenceClickListener {
            provider.load()
            true
        }
        provider.load()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().setTitle(R.string.pass_zones)
        // Like SettingsFragment: the list takes the side and bottom insets, the toolbar the top one.
        listView.clipToPadding = false
        ViewCompat.setOnApplyWindowInsetsListener(listView) { list, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            list.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom)
            insets
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { provider.state.collect(::render) }
        }
    }

    override fun onDestroyView() {
        requireActivity().setTitle(R.string.settings)
        super.onDestroyView()
    }

    private fun render(state: TicketCatalogueProvider.State) {
        val zones = state.catalogue?.zones
        findPreference<Preference>(KEY_LOADING)?.isVisible = zones == null && !state.loadFailed
        findPreference<Preference>(KEY_FAILED)?.isVisible = zones == null && state.loadFailed
        findPreference<Preference>(KEY_RETRY)?.isVisible = zones == null && state.loadFailed
        if (zones == null || zones == shownZones) return
        shownZones = zones
        val list = findPreference<PreferenceCategory>(KEY_LIST) ?: return
        zoneRows.filterKeys { it != "100" }.values.forEach(list::removePreference)
        zoneRows.keys.retainAll(setOf("100"))
        for (zone in zones) {
            // Published catalogues name every zone.
            val name = zone.zoneName?.takeIf { it != "100" } ?: continue
            val row = CheckBoxPreference(preferenceManager.context).apply {
                title = name
                summary = zone.zoneDescription
                isPersistent = false
                isIconSpaceReserved = false
            }
            list.addPreference(row)
            addZone(name, row)
        }
    }

    /** Checked while [zone] is on the pass; a tap switches it, and 101 with 100. */
    private fun addZone(zone: String, row: CheckBoxPreference) {
        val prefs = Prefs.get(requireContext())
        fun stored() = prefs.getString(Prefs.PASS_ZONES, null) ?: Prefs.PASS_ZONES_DEFAULT
        zoneRows[zone] = row
        row.isChecked = zone in SeasonPass.zoneSet(stored())
        row.setOnPreferenceChangeListener { _, _ ->
            val zones = SeasonPass.toggling(zone, stored())
            prefs.edit { putString(Prefs.PASS_ZONES, zones) }
            val selected = SeasonPass.zoneSet(zones)
            zoneRows.forEach { (name, zoneRow) -> zoneRow.isChecked = name in selected }
            false
        }
    }

    private companion object {
        const val KEY_LIST = "pass_zones_list"
        const val KEY_CITY = "pass_zone_100"
        const val KEY_LOADING = "pass_zones_loading"
        const val KEY_FAILED = "pass_zones_failed"
        const val KEY_RETRY = "pass_zones_retry"
    }
}
