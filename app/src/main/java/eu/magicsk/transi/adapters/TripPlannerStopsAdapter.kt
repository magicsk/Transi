package eu.magicsk.transi.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import eu.magicsk.transi.databinding.TripPlannerListStopsBinding
import eu.magicsk.transi.util.TripStop

class TripPlannerStopsAdapter(
    private val TripPlannerStopList: MutableList<TripStop>,
    private val onItemLongClick: () -> Unit,
    private val onItemClicked: () -> Unit
) : RecyclerView.Adapter<TripPlannerStopsAdapter.TripPlannerStopsViewHolder>() {
    class TripPlannerStopsViewHolder(
        val binding: TripPlannerListStopsBinding,
        private val onItemLongClick: () -> Unit,
        private val onItemClicked: () -> Unit
    ) :
        RecyclerView.ViewHolder(binding.root), View.OnClickListener {
        init {
            itemView.setOnClickListener(this)
            itemView.setOnLongClickListener {
                onItemLongClick()
                true
            }
        }

        override fun onClick(v: View?) {
            onItemClicked()
        }
    }
    private var _binding: TripPlannerListStopsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): TripPlannerStopsViewHolder {
        _binding = TripPlannerListStopsBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return TripPlannerStopsViewHolder(binding, onItemLongClick, onItemClicked)
    }

    override fun onBindViewHolder(holder: TripPlannerStopsViewHolder, position: Int) {
        val current = TripPlannerStopList[position]
        holder.binding.apply {
            TripPlannerListStopTime.text = current.time
            TripPlannerListStopName.text = current.name
            TripPlannerListStopName.isSelected = true
            TripPlannerListStopZone.text = current.zone
            TripPlannerListStopRequest.isVisible = current.request
        }
    }

    override fun getItemCount(): Int {
        return TripPlannerStopList.size
    }
}