package eu.magicsk.transi.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import eu.magicsk.transi.R
import eu.magicsk.transi.data.remote.responses.Stop
import kotlinx.android.synthetic.main.trip_planner_list_stops.view.*

class TripPlannerStopsAdapter(
    private val TripPlannerStopList: MutableList<Stop>,
    private val onItemClicked: () -> Unit
) : RecyclerView.Adapter<TripPlannerStopsAdapter.TripPlannerStopsViewHolder>() {
    class TripPlannerStopsViewHolder(
        itemView: View,
        private val onItemClicked: () -> Unit
    ) :
        RecyclerView.ViewHolder(itemView), View.OnClickListener {
        init {
            itemView.setOnClickListener(this)
        }

        override fun onClick(v: View?) {
            onItemClicked()
        }
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): TripPlannerStopsViewHolder {
        val context = parent.context
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.trip_planner_list_stops, parent, false)
        return TripPlannerStopsViewHolder(view, onItemClicked)
    }

    override fun onBindViewHolder(holder: TripPlannerStopsViewHolder, position: Int) {
        val current = TripPlannerStopList[position]
        holder.itemView.apply {
            TripPlannerListStopTime.text = current.time
            TripPlannerListStopName.text = current.stop
            TripPlannerListStopZone.text = current.zone

            if (current.request) {
                TripPlannerListStopRequest.visibility = View.VISIBLE
            } else TripPlannerListStopRequest.visibility = View.GONE
        }
    }

    override fun getItemCount(): Int {
        return TripPlannerStopList.size
    }


}