package eu.magicsk.transi

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Animatable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import dagger.hilt.android.AndroidEntryPoint
import eu.magicsk.transi.adapters.MHDTableAdapter
import eu.magicsk.transi.databinding.FragmentMainBinding
import eu.magicsk.transi.util.Prefs
import eu.magicsk.transi.view_models.MainViewModel
import eu.magicsk.transi.view_models.TableViewModel
import eu.magicsk.transi.view_models.TableViewModel.Status
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@AndroidEntryPoint
class MainFragment : Fragment() {
    private var _binding: FragmentMainBinding? = null
    private val binding get() = _binding!!
    private val mainViewModel: MainViewModel by activityViewModels()
    private val tableViewModel: TableViewModel by viewModels()
    private var backStackListener: FragmentManager.OnBackStackChangedListener? = null
    private var statusJob: Job? = null
    private var showStatusBar = true
    private var statusHidden = false
    @DrawableRes
    private var positionIcon = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMainBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        backStackListener?.let { activity?.supportFragmentManager?.removeOnBackStackChangedListener(it) }
        backStackListener = null
        positionIcon = 0
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val tableAdapter = MHDTableAdapter { departure ->
            mainViewModel.tableStop.value?.let { stop ->
                DepartureActionsSheet.newInstance(departure, stop).show(childFragmentManager, "departureActions")
            }
        }

        binding.apply {
            ViewCompat.setAccessibilityHeading(MHDTableStopName, true)
            MHDTableList.adapter = tableAdapter
            MHDTableList.layoutManager = LinearLayoutManager(context)
            MHDTableList.itemAnimator?.changeDuration = 0

            mainViewModel.tableStop.observe(viewLifecycleOwner) { stop ->
                tableViewModel.setStop(stop)
                tableAdapter.platformLabels = stop?.platform_labels
                updateTitle()
            }
            mainViewModel.locationAvailable.observe(viewLifecycleOwner) {
                updateTitle()
                updatePositionButton()
            }
            mainViewModel.followLocation.observe(viewLifecycleOwner) { updatePositionButton() }
            mainViewModel.actualLocation.observe(viewLifecycleOwner) { updatePositionButton() }

            tableViewModel.departures.observe(viewLifecycleOwner) { departures ->
                tableAdapter.submitList(departures) { expandRequested(tableAdapter) }
                updateState()
            }
            mainViewModel.expandDeparture.observe(viewLifecycleOwner) { expandRequested(tableAdapter) }
            tableViewModel.vehicles.observe(viewLifecycleOwner) { tableAdapter.vehicles = it }
            tableViewModel.status.observe(viewLifecycleOwner) { status ->
                showStatus(status)
                updateState()
            }
            tableViewModel.empty.observe(viewLifecycleOwner) { updateState() }
            tableViewModel.unreadInfoCount.observe(viewLifecycleOwner) { unread ->
                val color = if (unread > 0) R.color.table_orange else R.color.table_grey
                MHDTableInfoButton.iconTint = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), color))
                MHDTableInfoButton.contentDescription =
                    getString(if (unread > 0) R.string.table_service_info_unread else R.string.table_service_info)
            }

            MHDTableActualTime.setOnClickListener { tableViewModel.reconnect() }
            ViewCompat.replaceAccessibilityAction(
                MHDTableActualTime, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
                getString(R.string.table_reconnect), null
            )
            MHDTableInfoButton.setOnClickListener {
                tableViewModel.markInfoTextsRead()
                ServiceInfoSheet().show(childFragmentManager, "serviceInfo")
            }
            MHDTableRefresh.setOnRefreshListener {
                viewLifecycleOwner.lifecycleScope.launch {
                    delay(500)
                    tableViewModel.reconnect()
                    _binding?.MHDTableRefresh?.isRefreshing = false
                }
            }

            positionBtn.setOnClickListener {
                when {
                    mainViewModel.followLocation.value == true -> mainViewModel.setFollowLocation(false)
                    mainViewModel.stopList.value.isNullOrEmpty() -> toast(R.string.no_connection)
                    mainViewModel.actualLocation.value == null -> toast(R.string.no_location)
                    else -> mainViewModel.setFollowLocation(true)
                }
            }

            editText.setOnFocusChangeListener { _, focused ->
                if (!focused) return@setOnFocusChangeListener
                val stops = mainViewModel.stopList.value
                if (stops.isNullOrEmpty()) {
                    editText.clearFocus()
                    toast(R.string.no_connection)
                    return@setOnFocusChangeListener
                }
                MHDTable.isVisible = false
                searchFragmentLayout.isVisible = true
                val typeAheadFragment = TypeAheadFragment().apply {
                    arguments = Bundle().apply {
                        putSerializable("stopsList", stops)
                        putBoolean("directions", true)
                        putString("origin", "editText")
                    }
                }
                activity?.supportFragmentManager?.beginTransaction()
                    ?.replace(R.id.searchFragmentLayout, typeAheadFragment)
                    ?.addToBackStack("typeAhead")
                    ?.commit()
            }
        }

        backStackListener = FragmentManager.OnBackStackChangedListener {
            val searchOpen = activity?.supportFragmentManager?.findFragmentById(R.id.searchFragmentLayout) != null
            _binding?.takeIf { !searchOpen }?.apply {
                MHDTable.isVisible = true
                searchFragmentLayout.isVisible = false
                editText.text = null
                editText.clearFocus()
            }
        }.also { activity?.supportFragmentManager?.addOnBackStackChangedListener(it) }

        // The board stays connected, and its clock and remaining times tick, only while the table is on screen.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val prefs = Prefs.get(requireContext())
                val showClock = prefs.getBoolean(Prefs.DISPLAY_TABLE_CLOCK, true)
                showStatusBar = prefs.getBoolean(Prefs.DISPLAY_SOCKET_STATUS, true)
                binding.MHDTableActualTime.isVisible = showClock
                updateState()
                tableViewModel.connect()
                try {
                    if (showClock) launch {
                        while (true) {
                            binding.MHDTableActualTime.text = LocalTime.now().format(CLOCK)
                            delay(1000 - System.currentTimeMillis() % 1000)
                        }
                    }
                    launch {
                        while (true) {
                            delay(10_000)
                            tableAdapter.refresh()
                        }
                    }
                    awaitCancellation()
                } finally {
                    tableViewModel.disconnect()
                }
            }
        }
    }

    private fun toast(message: Int) = Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()

    private fun updateTitle() {
        val binding = _binding ?: return
        val stop = mainViewModel.tableStop.value
        binding.MHDTableStopName.text = stop?.name
            ?: getString(if (mainViewModel.locationAvailable.value == false) R.string.loading else R.string.locating_nearest_stop)
        binding.MHDTableStopName.isSelected = true
    }

    private fun updatePositionButton() {
        val binding = _binding ?: return
        val follow = mainViewModel.followLocation.value == true
        val icon = when {
            !follow || mainViewModel.locationAvailable.value == false -> R.drawable.ic_location_disabled
            mainViewModel.actualLocation.value != null -> R.drawable.ic_my_location
            else -> R.drawable.ic_location_search
        }
        if (icon != positionIcon) {
            positionIcon = icon
            val drawable = ResourcesCompat.getDrawable(resources, icon, context?.theme)
            binding.positionBtn.icon = drawable
            if (ValueAnimator.areAnimatorsEnabled()) (drawable as? Animatable)?.start()
        }
        ViewCompat.setStateDescription(
            binding.positionBtn, getString(if (follow) R.string.table_following_nearest else R.string.table_fixed_stop)
        )
    }

    // iOS ConnectionStatusBar: grey while connecting, red when disconnected, gone half a second after connecting.
    private fun showStatus(status: Status) {
        val binding = _binding ?: return
        statusJob?.cancel()
        binding.MHDTableStatusText.setText(
            when (status) {
                Status.CONNECTING -> R.string.connecting
                Status.CONNECTED -> R.string.connected
                Status.DISCONNECTED -> R.string.disconnected
            }
        )
        binding.MHDTableStatusConnecting.isVisible = status == Status.CONNECTING
        binding.MHDTableStatusDisconnected.isVisible = status == Status.DISCONNECTED
        statusHidden = false
        if (status == Status.CONNECTED) {
            statusJob = viewLifecycleOwner.lifecycleScope.launch {
                delay(500)
                statusHidden = true
                updateState()
            }
        }
    }

    private fun updateState() {
        val binding = _binding ?: return
        binding.MHDTableLoading.isVisible = tableViewModel.showsLoading
        binding.MHDTableEmpty.isVisible = tableViewModel.showsEmpty
        binding.MHDTableStatus.isVisible = showStatusBar && !statusHidden && !tableViewModel.departures.value.isNullOrEmpty()
    }

    // A linked departure opens once the table lists it (iOS expandedConnectionId).
    private fun expandRequested(adapter: MHDTableAdapter) {
        val (stopId, departureId) = mainViewModel.expandDeparture.value ?: return
        if (stopId != mainViewModel.tableStop.value?.id) return
        val position = adapter.expand(departureId)
        if (position < 0) return
        mainViewModel.expandDeparture.value = null
        _binding?.MHDTableList?.scrollToPosition(position)
    }

    private companion object {
        val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}
