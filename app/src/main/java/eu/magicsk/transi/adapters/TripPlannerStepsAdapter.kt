package eu.magicsk.transi.adapters

import android.graphics.PorterDuff
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.setPadding
import androidx.recyclerview.widget.RecyclerView
import eu.magicsk.transi.R
import eu.magicsk.transi.data.remote.responses.Step
import eu.magicsk.transi.util.getLineColor
import eu.magicsk.transi.util.getLineTextColor
import eu.magicsk.transi.util.dpToPx
import eu.magicsk.transi.util.isDarkTheme
import kotlinx.android.synthetic.main.trip_planner_list_item_transit.view.*
import kotlinx.android.synthetic.main.trip_planner_list_item_walking.view.*

class TripPlannerStepsAdapter(
    private val TripPlannerStepList: MutableList<Step>,
    private val onItemClicked: (pos: Int) -> Unit
) : RecyclerView.Adapter<TripPlannerStepsAdapter.TripPlannerViewHolder>() {
    class TripPlannerViewHolder(itemView: View, private val onItemClicked: (pos: Int) -> Unit) :
        RecyclerView.ViewHolder(itemView), View.OnClickListener {
        init {
            itemView.setOnClickListener(this)
        }

        override fun onClick(v: View?) {
            val pos = adapterPosition
            onItemClicked(pos)
        }
    }

    private fun clearList() {
        TripPlannerStepList.clear()
        notifyItemRangeRemoved(0, itemCount)
    }

    private fun addItems(items: MutableList<Step>) {
        if (items.size > 0) {
            TripPlannerStepList.addAll(items)
            notifyItemRangeChanged(0, TripPlannerStepList.size)
        }

    }

    override fun getItemViewType(position: Int): Int {
        return if (TripPlannerStepList[position].type == "TRANSIT") 1 else 0
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TripPlannerViewHolder {
        val context = parent.context
        val inflater = LayoutInflater.from(context)
        val view = if (viewType == 1) {
            inflater.inflate(R.layout.trip_planner_list_item_transit, parent, false)
        } else {
            inflater.inflate(R.layout.trip_planner_list_item_walking, parent, false)
        }
        return TripPlannerViewHolder(view, onItemClicked)
    }

    override fun onBindViewHolder(holder: TripPlannerViewHolder, position: Int) {
        val current = TripPlannerStepList[position]
        holder.itemView.apply {
            when (current.type) {
                "TRANSIT" -> {
                    val rounded =
                        try {
                            current.line.number.contains("S") || current.line.number.toInt() < 10
                        } catch (e: NumberFormatException) {
                            false
                        }
                    if (rounded) {
                        TableListItemLineNum.setBackgroundResource(R.drawable.round_shape)
                        if (!current.line.number.contains("S")) TableListItemLineNum.setPadding(
                            12f.dpToPx(context),
                            5f.dpToPx(context),
                            12f.dpToPx(context),
                            5f.dpToPx(context)
                        ) else {
                            TableListItemLineNum.setPadding(5f.dpToPx(context))
                        }
                    } else {
                        TableListItemLineNum.setBackgroundResource(R.drawable.rounded_shape)
                    }
                    val drawable = TableListItemLineNum.background
                    drawable.setColorFilter(
                        ContextCompat.getColor(
                            context,
                            getLineColor(current.line.number, isDarkTheme(resources))
                        ), PorterDuff.Mode.SRC
                    )
                    TableListItemLineNum.setTextColor(
                        ContextCompat.getColor(
                            context,
                            getLineTextColor(current.line.number)
                        )
                    )

                    TableListItemLineNum.background = drawable
                    TableListItemLineNum.text = current.line.number
                    TableListItemHeadsign.text = "▶ ${current.headsign}"
                    TableListItemDepartureStop.text = current.departure_stop
                    TableListItemDepartureTime.text = current.departure_time
                    TableListItemArrivalStop.text = current.arrival_stop
                    TableListItemArrivalTime.text = current.arrival_time
                }
                "WALKING" -> {
                    TableListItemWalkingText.text = current.text
                }
                else -> {
                    TableListItemWalkingText.text = context.getString(R.string.error)
                }
            }
        }
    }

    override fun getItemCount(): Int {
        return TripPlannerStepList.size
    }
}