package eu.magicsk.transi

import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import androidx.core.os.BundleCompat
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import eu.magicsk.transi.adapters.TableInfoAdapter
import eu.magicsk.transi.data.models.MHDTableData
import eu.magicsk.transi.data.remote.responses.Stop
import eu.magicsk.transi.databinding.SheetDepartureActionsBinding
import eu.magicsk.transi.databinding.SheetServiceInfoBinding
import eu.magicsk.transi.util.tableNotification
import eu.magicsk.transi.view_models.TableViewModel
import java.util.Date

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

/** Long-press actions for one departure. */
class DepartureActionsSheet : BottomSheetDialogFragment(R.layout.sheet_departure_actions) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = SheetDepartureActionsBinding.bind(view)
        val departure = BundleCompat.getParcelable(requireArguments(), ARG_DEPARTURE, MHDTableData::class.java)
        val stop = BundleCompat.getParcelable(requireArguments(), ARG_STOP, Stop::class.java)
        if (departure == null || stop == null) return
        binding.departureActionsTitle.text = getString(R.string.table_departure_title, departure.line, departure.headsign)
        binding.departureActionsNotify.setOnClickListener {
            tableNotification(stop, departure.tripId, requireContext())
            dismiss()
        }
        binding.departureActionsShare.setOnClickListener {
            val time = DateFormat.getTimeFormat(requireContext()).format(Date(departure.departureTime))
            val text = getString(
                R.string.table_share_text, departure.line, departure.headsign, time, stop.name,
                "transi://table/${stop.id}/${departure.id}"
            )
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            startActivity(Intent.createChooser(send, null))
            dismiss()
        }
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
