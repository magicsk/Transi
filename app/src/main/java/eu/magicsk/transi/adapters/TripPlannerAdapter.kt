package eu.magicsk.transi.adapters

import android.app.NotificationManager
import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.text.htmlEncode
import androidx.core.text.parseAsHtml
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import eu.magicsk.transi.R
import eu.magicsk.transi.data.models.BRATISLAVA
import eu.magicsk.transi.data.models.Journey
import eu.magicsk.transi.data.models.Part
import eu.magicsk.transi.data.models.minutesBetween
import eu.magicsk.transi.databinding.TripPlannerListBinding
import eu.magicsk.transi.databinding.TripPlannerListStepTransitBinding
import eu.magicsk.transi.databinding.TripPlannerListStepWalkingBinding
import eu.magicsk.transi.databinding.TripPlannerLoadingMoreBinding
import eu.magicsk.transi.databinding.TripPlannerPageBinding
import eu.magicsk.transi.util.customizeLineText
import eu.magicsk.transi.util.getLineColor
import eu.magicsk.transi.util.isDarkTheme
import eu.magicsk.transi.util.sendNotification
import eu.magicsk.transi.view_models.TripPlannerError
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** One page of results: a recent search's cached journeys, or the current search with its loading state. */
data class TripPlannerPage(
    val searchId: String,
    val journeys: List<Journey>?,
    val isCurrent: Boolean,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: TripPlannerError? = null,
    val generation: Int = 0,
)

/** The results pager, one page per recent search. [onJourneyBound] drives the current page's infinite scroll. */
class TripPlannerPagerAdapter(
    private val onJourneyBound: (Journey) -> Unit,
) : RecyclerView.Adapter<TripPlannerPagerAdapter.PageHolder>() {
    /** Tapping a journey; journeys are not clickable while it is null. */
    var onJourneyClick: ((Journey) -> Unit)? = null

    var pages: List<TripPlannerPage> = emptyList()
        private set

    fun submit(newPages: List<TripPlannerPage>) {
        val oldPages = pages
        pages = newPages
        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldPages.size
            override fun getNewListSize() = newPages.size
            override fun areItemsTheSame(oldPosition: Int, newPosition: Int) =
                oldPages[oldPosition].searchId == newPages[newPosition].searchId

            override fun areContentsTheSame(oldPosition: Int, newPosition: Int) =
                oldPages[oldPosition] == newPages[newPosition]
        }).dispatchUpdatesTo(this)
    }

    class PageHolder(val binding: TripPlannerPageBinding) : RecyclerView.ViewHolder(binding.root) {
        val journeys = TripPlannerAdapter()
        var shownResults: String? = null

        init {
            binding.TripPlannerList.layoutManager = LinearLayoutManager(binding.root.context)
            binding.TripPlannerList.adapter = journeys
        }
    }

    override fun getItemCount() = pages.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        PageHolder(TripPlannerPageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
        val page = pages[position]
        val binding = holder.binding
        holder.journeys.onJourneyBound = if (page.isCurrent) onJourneyBound else null
        holder.journeys.onJourneyClick = onJourneyClick
        val rows: List<TripPlannerRow> = page.journeys.orEmpty().map { TripPlannerRow.JourneyRow(it) } +
            if (page.loadingMore) listOf(TripPlannerRow.LoadingMore) else emptyList()
        // New results start at the top; a recent's cached ones that became current page on as if just shown.
        val results = "${page.searchId}:${page.generation}"
        val newResults = holder.shownResults != results
        holder.shownResults = results
        holder.journeys.submitList(rows) {
            if (!newResults) return@submitList
            binding.TripPlannerList.scrollToPosition(0)
            if (page.isCurrent) holder.journeys.notifyItemRangeChanged(0, holder.journeys.itemCount, REBIND)
        }

        binding.TripPlannerStatus.isVisible = page.journeys.isNullOrEmpty() && !page.loading
        val (icon, title) = when (page.error) {
            TripPlannerError.NO_ROUTES -> R.drawable.ic_warning to R.string.trip_planner_no_routes
            TripPlannerError.NO_LOCATION -> R.drawable.ic_location_disabled to R.string.no_location
            null -> R.drawable.ic_trip_planner to R.string.trip_planner_empty
        }
        binding.TripPlannerStatusIcon.setImageResource(icon)
        binding.TripPlannerStatusTitle.setText(title)
        binding.TripPlannerStatusMessage.isVisible = page.error == TripPlannerError.NO_ROUTES
        binding.TripPlannerStatusMessage.setText(R.string.trip_planner_no_routes_message)
        binding.TripPlannerLoading.isVisible = page.loading
    }

    private companion object {
        /** Rebinds the rows in place, without the change animation. */
        val REBIND = Any()
    }
}

sealed interface TripPlannerRow {
    data class JourneyRow(val journey: Journey) : TripPlannerRow
    data object LoadingMore : TripPlannerRow
}

/** A page's journeys, then a spinner while more load. */
class TripPlannerAdapter : ListAdapter<TripPlannerRow, RecyclerView.ViewHolder>(RowDiff) {
    var onJourneyBound: ((Journey) -> Unit)? = null
    var onJourneyClick: ((Journey) -> Unit)? = null

    class JourneyHolder(val binding: TripPlannerListBinding) : RecyclerView.ViewHolder(binding.root)
    class LoadingHolder(binding: TripPlannerLoadingMoreBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemViewType(position: Int) = if (getItem(position) is TripPlannerRow.LoadingMore) LOADING else JOURNEY

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == LOADING) {
            LoadingHolder(TripPlannerLoadingMoreBinding.inflate(inflater, parent, false))
        } else {
            JourneyHolder(TripPlannerListBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val journey = (getItem(position) as? TripPlannerRow.JourneyRow)?.journey ?: return
        if (holder is JourneyHolder) bind(holder.binding, journey)
        onJourneyBound?.invoke(journey)
    }

    private fun bind(binding: TripPlannerListBinding, journey: Journey) {
        val context = binding.root.context
        binding.TripPlannerJourneyHeader.text = journeyHeader(context, journey)
        val parts = binding.TripPlannerJourneyParts
        parts.removeAllViews()
        val inflater = LayoutInflater.from(context)
        journey.parts.forEachIndexed { index, part ->
            parts.addView(
                if (part.isWalk) walkRow(inflater, parts, part, last = index == journey.parts.lastIndex)
                else transitRow(inflater, parts, part)
            )
        }

        val card = binding.TripPlannerCard
        val onClick = onJourneyClick
        if (onClick != null) {
            card.setOnClickListener { onClick(journey) }
        } else {
            card.setOnClickListener(null)
            card.isClickable = false
        }
        card.setOnLongClickListener {
            notifyJourney(context, journey)
            true
        }
        ViewCompat.replaceAccessibilityAction(
            card, AccessibilityActionCompat.ACTION_LONG_CLICK, context.getString(R.string.trip_planner_notify), null
        )
    }

    private fun walkRow(inflater: LayoutInflater, parent: ViewGroup, part: Part, last: Boolean): View {
        val row = TripPlannerListStepWalkingBinding.inflate(inflater, parent, false)
        row.TripPlannerListStepWalkingText.text = walkText(parent.context, part)
        val sameStop = part.startStopName == part.endStopName
        row.TripPlannerListStepWalkingPlatform.showPlatform(part.endStopCode.takeUnless { sameStop || last })
        return row.root
    }

    private fun transitRow(inflater: LayoutInflater, parent: ViewGroup, part: Part): View {
        val row = TripPlannerListStepTransitBinding.inflate(inflater, parent, false)
        val context = parent.context
        val error = context.getString(R.string.error)
        val line = part.routeShortName ?: error
        customizeLineText(row.TripPlannerListStepLineNum, line, context, context.resources)
        row.TripPlannerListStepLineArrow.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(context, getLineColor(line, isDarkTheme(context.resources))))
        row.TripPlannerListStepHeadsign.text = part.tripHeadsign ?: error
        row.TripPlannerListStepHeadsign.isSelected = true
        row.TripPlannerListStepDepartureTime.text = formatClock(part.startDeparture)
        row.TripPlannerListStepDepartureStop.text = part.startStopName ?: error
        row.TripPlannerListStepDepartureStop.isSelected = true
        row.TripPlannerListStepDeparturePlatform.showPlatform(part.startStopCode)
        row.TripPlannerListStepArrivalTime.text = formatClock(part.endArrival)
        row.TripPlannerListStepArrivalStop.text = part.endStopName ?: error
        row.TripPlannerListStepArrivalStop.isSelected = true
        row.TripPlannerListStepArrivalPlatform.showPlatform(part.endStopCode)
        return row.root
    }

    private fun TextView.showPlatform(code: String?) {
        text = code
        isVisible = code != null
    }

    private companion object {
        const val JOURNEY = 0
        const val LOADING = 1
    }
}

private object RowDiff : DiffUtil.ItemCallback<TripPlannerRow>() {
    override fun areItemsTheSame(oldItem: TripPlannerRow, newItem: TripPlannerRow) =
        if (oldItem is TripPlannerRow.JourneyRow && newItem is TripPlannerRow.JourneyRow) {
            oldItem.journey.id == newItem.journey.id
        } else {
            oldItem == newItem
        }

    override fun areContentsTheSame(oldItem: TripPlannerRow, newItem: TripPlannerRow) = oldItem == newItem
}

private val clockFormat = DateTimeFormatter.ofPattern("HH:mm")
private val dayFormat = DateTimeFormatter.ofPattern("EEE d MMM")

private fun formatClock(time: Instant): String = time.atZone(BRATISLAVA).format(clockFormat)

/** "{day if not today} HH:mm | {minutes} min | N zone(s)". */
private fun journeyHeader(context: Context, journey: Journey): String {
    val first = journey.parts.firstOrNull()
    val last = journey.parts.lastOrNull()
    if (first == null || last == null) return context.getString(R.string.error)
    val time = formatClock(first.startDeparture)
    val today = LocalDate.now(BRATISLAVA)
    val day = when (val date = first.startDeparture.atZone(BRATISLAVA).toLocalDate()) {
        today -> null
        today.plusDays(1) -> context.getString(R.string.trip_planner_tomorrow)
        today.minusDays(1) -> context.getString(R.string.trip_planner_yesterday)
        else -> date.format(dayFormat)
    }
    val zones = journey.zones?.size ?: 0
    return context.getString(
        R.string.trip_planner_journey_header,
        day?.let { context.getString(R.string.trip_planner_day_time, it, time) } ?: time,
        minutesBetween(first.startDeparture, last.endArrival),
        context.resources.getQuantityString(R.plurals.trip_planner_zones, zones, zones),
    )
}

/** "{N} min to {platform X | stop name}". */
private fun walkText(context: Context, part: Part): String {
    val minutes = minutesBetween(part.startDeparture, part.endArrival)
    return if (part.startStopName == part.endStopName) {
        context.getString(
            R.string.trip_planner_walk_to_platform,
            minutes,
            part.endStopCode ?: context.getString(R.string.trip_planner_unknown_platform),
        )
    } else {
        context.getString(R.string.trip_planner_walk_to, minutes, part.endStopName ?: context.getString(R.string.error))
    }
}

/** Long press: the journey step by step in a notification, with a share action. */
private fun notifyJourney(context: Context, journey: Journey) {
    val first = journey.parts.firstOrNull() ?: return
    val last = journey.parts.last()
    val error = context.getString(R.string.error)
    val title = context.getString(R.string.trip_notification_title, first.startStopName ?: error, last.endStopName ?: error)
    fun html(text: String?) = (text ?: error).htmlEncode()
    val steps = journey.parts.joinToString("<br>") { part ->
        if (part.isWalk) {
            "🚶\t\t<b>${html(walkText(context, part))}</b>"
        } else {
            "<b>${html(part.routeShortName)}</b> ▶ <b>${html(part.tripHeadsign)}</b>" +
                "<br>\t\t\t${formatClock(part.startDeparture)} ${html(part.startStopName)}" +
                "<br>\t\t\t${formatClock(part.endArrival)} ${html(part.endStopName)}"
        }
    }
    val minutes = minutesBetween(first.startDeparture, last.endArrival)
    val duration = if (minutes >= 60) {
        context.getString(R.string.tripDurationH, (minutes / 60).toString(), (minutes % 60).toString())
    } else {
        context.getString(R.string.tripDuration, minutes.toString())
    }
    context.getSystemService(NotificationManager::class.java).sendNotification(
        title,
        context.getString(R.string.trip_notification_body, duration, formatClock(first.startDeparture), formatClock(last.endArrival)),
        steps.parseAsHtml(),
        context.getString(R.string.trip_share_body, html(title), steps).parseAsHtml(),
        true,
        context.getString(R.string.trip_planner_notification_channel_id),
        1,
        false,
        context,
        shareAction = true,
    )
}
