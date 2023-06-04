package eu.magicsk.transi.adapters

import android.app.NotificationManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.text.Html
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import eu.magicsk.transi.R
import eu.magicsk.transi.databinding.TripPlannerListBinding
import eu.magicsk.transi.util.Trip
import eu.magicsk.transi.util.sendNotification

class TripPlannerAdapter(
    var TripPlannerItemList: MutableList<Trip>,
    var from: String = "",
    var to: String = ""
) : RecyclerView.Adapter<TripPlannerAdapter.TripPlannerViewHolder>() {
    class TripPlannerViewHolder(val binding: TripPlannerListBinding) :
        RecyclerView.ViewHolder(binding.root)

    private var _binding: TripPlannerListBinding? = null
    private val binding get() = _binding!!

    fun addItems(items: MutableList<Trip>) {
        TripPlannerItemList.clear()
        TripPlannerItemList.addAll(items)
        notifyDataSetChanged()
    }

    fun addMoreItems(items: MutableList<Trip>) {
        TripPlannerItemList.addAll(items)
        notifyItemInserted(items.size)
    }

    fun setFromTo(newFrom: String = "", newTo: String = "") {
        if (newFrom != "") from = newFrom
        if (newTo != "") to = newTo
    }

    private fun onLongClick(
        current: Trip,
        context: Context
    ) {
        val notificationManager = context.getSystemService(NotificationManager::class.java) as NotificationManager
        println(from)
        val title = context.getString(R.string.trip_notification_title, from, to)
        var bigBody = ""
        current.parts.forEachIndexed { i, part ->
            part.apply {
                bigBody += if (type == 0) {
                    "\uD83D\uDEB6\t\t<b>${message}</b>"
                } else {
                    "<b>${line}</b> ▶ <b>${headsign}</b><br>\t\t\t${departure?.time} ${departure?.stop?.name}<br>\t\t\t${arrival?.time} ${arrival?.stop?.name}"
                }
                if (i != current.parts.size - 1) bigBody += "<br>"
            }
        }
        val shareBody = context.getString(R.string.trip_share_body, title, bigBody)

        notificationManager.sendNotification(
            title,
            context.getString(R.string.trip_notification_body, current.duration, current.departure, current.arrival),
            Html.fromHtml(bigBody, Html.FROM_HTML_MODE_LEGACY),
            Html.fromHtml(shareBody, Html.FROM_HTML_MODE_LEGACY),
            true,
            context.getString(R.string.trip_planner_notification_channel_id),
            1,
            false,
            context,
            shareAction = true
        )
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TripPlannerViewHolder {
        _binding = TripPlannerListBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return TripPlannerViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TripPlannerViewHolder, position: Int) {
        val current = TripPlannerItemList[position]
        holder.binding.apply {
            val context = root.context
            TableListCard.foreground = RippleDrawable(
                ColorStateList.valueOf(ContextCompat.getColor(context, R.color.colorControlHighlight)),
                null,
                TableListCard.background
            )
            TableListDurationStopList.text = current.duration
            TableListTime.text = context.getString(R.string.tripTime).format(current.departure, current.arrival)
            TableListItems.layoutManager =
                LinearLayoutManager(TableListItems.context, RecyclerView.VERTICAL, false)
            TableListItems.adapter =
                TripPlannerStepsAdapter(current.parts) {
                    onLongClick(
                        current,
                        context
                    )
                }
            root.setOnLongClickListener {
                onLongClick(
                    current,
                    context
                )
                true
            }
        }
    }

    override fun getItemCount(): Int {
        return TripPlannerItemList.size
    }
}