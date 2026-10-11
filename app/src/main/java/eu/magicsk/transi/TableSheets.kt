package eu.magicsk.transi

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.os.BundleCompat
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import eu.magicsk.transi.adapters.TableInfoAdapter
import eu.magicsk.transi.data.models.MHDTableData
import eu.magicsk.transi.data.models.clockText
import eu.magicsk.transi.data.remote.responses.Stop
import eu.magicsk.transi.databinding.SheetDepartureActionsBinding
import eu.magicsk.transi.databinding.SheetServiceInfoBinding
import eu.magicsk.transi.view_models.MainViewModel
import eu.magicsk.transi.view_models.TableViewModel

/** The board's service information texts. */
class ServiceInfoSheet : BottomSheetDialogFragment(R.layout.sheet_service_info) {
    private val tableViewModel: TableViewModel by viewModels(ownerProducer = { requireParentFragment() })

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = SheetServiceInfoBinding.bind(view)
        val adapter = TableInfoAdapter()
        binding.serviceInfoList.adapter = adapter
        binding.serviceInfoList.layoutManager = LinearLayoutManager(context)
        tableViewModel.infoTexts.observe(viewLifecycleOwner) { texts ->
            adapter.submitList(texts)
            binding.serviceInfoEmpty.isVisible = texts.isEmpty()
        }
    }
}

/** The departure's share text, with the link that opens it on the table. */
fun departureShareText(context: Context, departure: MHDTableData, stop: Stop): String = context.getString(
    R.string.table_share_text, departure.line, departure.headsign, clockText(context, departure.departureTime), stop.name,
    "transi://table/${stop.id}/${departure.id}"
)

/** Long-press actions for one departure. */
class DepartureActionsSheet : BottomSheetDialogFragment(R.layout.sheet_departure_actions) {
    private val mainViewModel: MainViewModel by activityViewModels()
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) follow() else explainNotificationsOff()
        dismiss()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = SheetDepartureActionsBinding.bind(view)
        val departure = BundleCompat.getParcelable(requireArguments(), ARG_DEPARTURE, MHDTableData::class.java)
        val stop = BundleCompat.getParcelable(requireArguments(), ARG_STOP, Stop::class.java)
        if (departure == null || stop == null) return
        binding.departureActionsTitle.text = getString(R.string.table_departure_title, departure.line, departure.headsign)
        binding.departureActionsNotify.setOnClickListener {
            val context = requireContext()
            when {
                NotificationManagerCompat.from(context).areNotificationsEnabled() -> {
                    follow()
                    dismiss()
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED -> notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else -> {
                    explainNotificationsOff()
                    dismiss()
                }
            }
        }
        binding.departureActionsShare.setOnClickListener {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, departureShareText(requireContext(), departure, stop))
            startActivity(Intent.createChooser(send, null))
            dismiss()
        }
        // iOS trailing swipes: the stop named as the vehicle's last stop on the map (only when there is one), the line's timetable.
        mainViewModel.stopList.observe(viewLifecycleOwner) { stops ->
            val lastStop = stops.firstOrNull { it.name == departure.lastStopName }
            binding.departureActionsMap.isVisible = lastStop != null
            binding.departureActionsMap.setOnClickListener { lastStop?.let { open(DeepLink.Map(it.id)) } }
        }
        binding.departureActionsTimetable.setOnClickListener { open(DeepLink.Timetable(departure.line)) }
    }

    private fun open(link: DeepLink) {
        val activity = activity as? MainActivity
        dismiss()
        activity?.navigate(link)
    }

    // The sheet is on screen, so the foreground service may start.
    private fun follow() {
        val departure = BundleCompat.getParcelable(requireArguments(), ARG_DEPARTURE, MHDTableData::class.java) ?: return
        val stop = BundleCompat.getParcelable(requireArguments(), ARG_STOP, Stop::class.java) ?: return
        LiveUpdatesService.follow(requireContext(), departure, stop)
    }

    private fun explainNotificationsOff() {
        Toast.makeText(requireContext(), R.string.live_updates_notifications_off, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val ARG_DEPARTURE = "departure"
        private const val ARG_STOP = "stop"

        fun newInstance(departure: MHDTableData, stop: Stop) = DepartureActionsSheet().apply {
            arguments = Bundle().apply {
                putParcelable(ARG_DEPARTURE, departure)
                putParcelable(ARG_STOP, stop)
            }
        }
    }
}
