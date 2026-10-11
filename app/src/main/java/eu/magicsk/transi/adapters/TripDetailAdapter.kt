package eu.magicsk.transi.adapters

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.StrikethroughSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.StringRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.core.view.children
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexboxLayout
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import eu.magicsk.transi.R
import eu.magicsk.transi.data.models.Journey
import eu.magicsk.transi.data.models.JourneyStep
import eu.magicsk.transi.data.models.MapFocus
import eu.magicsk.transi.data.models.Part
import eu.magicsk.transi.data.models.PartStop
import eu.magicsk.transi.data.models.StopsState
import eu.magicsk.transi.data.models.TicketCoverage
import eu.magicsk.transi.data.models.TransferBuffer
import eu.magicsk.transi.data.models.TripDetailState
import eu.magicsk.transi.data.models.ZoneChange
import eu.magicsk.transi.data.models.delayColor
import eu.magicsk.transi.data.models.delayMinutes
import eu.magicsk.transi.data.models.expected
import eu.magicsk.transi.data.models.hasArrived
import eu.magicsk.transi.data.models.legStops
import eu.magicsk.transi.data.models.minutes
import eu.magicsk.transi.data.models.minutesBetween
import eu.magicsk.transi.data.models.rideCount
import eu.magicsk.transi.data.models.steps
import eu.magicsk.transi.data.models.transferBuffers
import eu.magicsk.transi.data.models.walkMinutes
import eu.magicsk.transi.data.models.zoneChanges
import eu.magicsk.transi.data.models.zoneNames
import eu.magicsk.transi.data.remote.responses.idsbk.StopGps
import eu.magicsk.transi.databinding.TripDetailStepBinding
import eu.magicsk.transi.databinding.TripDetailSummaryBinding
import eu.magicsk.transi.databinding.TripDetailTimelineBinding
import eu.magicsk.transi.util.clock
import eu.magicsk.transi.util.customizeLineText
import eu.magicsk.transi.util.getLineColor
import eu.magicsk.transi.util.getLineTextColor
import eu.magicsk.transi.util.isDarkTheme
import java.time.Duration
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** A row of the trip detail sheet: the summary, then the steps, then each ride's stops (iOS TripDetailSheet). */
sealed interface TripDetailRow {
    data class Summary(val journey: Journey, val delays: Map<Int, Int>, val coverage: TicketCoverage, val arrived: Boolean) : TripDetailRow
    data class Header(@StringRes val title: Int) : TripDetailRow
    data class Ride(val index: Int, val part: Part, val delay: Int?) : TripDetailRow

    /** A walk in the steps or, with [timeline], in the stops; [ride] is the part it leads to. */
    data class Walk(
        val timeline: Boolean,
        val minutes: Int,
        val first: Part,
        val last: Part,
        val ride: Part?,
        val buffer: TransferBuffer?,
        val focus: MapFocus,
    ) : TripDetailRow

    data class Change(val timeline: Boolean, val to: Int, val ride: Part, val buffer: TransferBuffer?) : TripDetailRow
    data class Board(val index: Int, val part: Part, val stop: PartStop, val delay: Int?) : TripDetailRow
    data class StopsLoading(val index: Int, val line: String) : TripDetailRow
    data class StopsFailed(val index: Int, val line: String) : TripDetailRow
    data class Collapse(val index: Int, val line: String, val count: Int, val minutes: Int, val expanded: Boolean) : TripDetailRow
    data class Middle(val index: Int, val line: String, val stop: PartStop, val delay: Int?) : TripDetailRow
    data class Zone(val index: Int, val line: String, val change: ZoneChange) : TripDetailRow
    data class Alight(val index: Int, val line: String, val stop: PartStop, val delay: Int?, val isLast: Boolean) : TripDetailRow
    data class Arrive(val part: Part, val delay: Int?) : TripDetailRow
}

/** The sheet's rows for [state]; [coverage] is the journey's ticket. */
fun tripDetailRows(state: TripDetailState, coverage: TicketCoverage): List<TripDetailRow> {
    val journey = state.journey
    val parts = journey.parts
    val delays = state.delays
    val buffers = journey.transferBuffers(delays)
    val steps = journey.steps
    fun walk(step: JourneyStep.Walk, timeline: Boolean) = TripDetailRow.Walk(
        timeline,
        step.parts.sumOf { parts[it].minutes },
        parts[step.parts.first()],
        parts[step.parts.last()],
        step.to?.let(parts::get),
        step.to?.let(buffers::get),
        step.to?.let { MapFocus.Change(it) } ?: MapFocus.Leg(step.parts.first()),
    )

    val rows = mutableListOf<TripDetailRow>(TripDetailRow.Summary(journey, delays, coverage, journey.hasArrived(state.now, delays)))
    rows += TripDetailRow.Header(R.string.trip_detail_steps)
    steps.mapTo(rows) { step ->
        when (step) {
            is JourneyStep.Ride -> TripDetailRow.Ride(step.index, parts[step.index], delays[step.index])
            is JourneyStep.Walk -> walk(step, timeline = false)
            is JourneyStep.Change -> TripDetailRow.Change(false, step.to, parts[step.to], buffers[step.to])
        }
    }
    rows += TripDetailRow.Header(R.string.trip_detail_stops)
    steps.forEach { step ->
        when (step) {
            is JourneyStep.Ride -> {
                val index = step.index
                val part = parts[index]
                val line = part.routeShortName ?: "?"
                val stops = part.legStops
                val delay = delays[index]
                val isExpanded = index in state.expanded
                rows += TripDetailRow.Board(index, part, stops.first(), delay)
                when (state.stopsState[index]) {
                    StopsState.LOADING -> rows += TripDetailRow.StopsLoading(index, line)
                    StopsState.FAILED -> rows += TripDetailRow.StopsFailed(index, line)
                    null -> if (stops.size > 2) rows += TripDetailRow.Collapse(index, line, stops.size - 2, part.minutes, isExpanded)
                }
                if (isExpanded) {
                    val zoneChanges = stops.zoneChanges
                    stops.subList(1, stops.size - 1).forEachIndexed { offset, stop ->
                        zoneChanges.firstOrNull { it.index == offset + 1 }?.let { rows += TripDetailRow.Zone(index, line, it) }
                        rows += TripDetailRow.Middle(index, line, stop, delay)
                    }
                    zoneChanges.firstOrNull { it.index == stops.size - 1 }?.let { rows += TripDetailRow.Zone(index, line, it) }
                }
                rows += TripDetailRow.Alight(index, line, stops.last(), delay, isLast = step == steps.last())
            }
            is JourneyStep.Walk -> {
                rows += walk(step, timeline = true)
                if (step.to == null) {
                    // As on the trip Live Activity, the walk ends as late as the last vehicle.
                    val lastRide = parts.indexOfLast { !it.isWalk }.takeIf { it >= 0 }
                    rows += TripDetailRow.Arrive(parts[step.parts.last()], lastRide?.let(delays::get))
                }
            }
            is JourneyStep.Change -> rows += TripDetailRow.Change(true, step.to, parts[step.to], buffers[step.to])
        }
    }
    return rows
}

/** The trip detail sheet's list. Taps focus the map ([onFocus]), retry a leg's stops or show the stops in between. */
class TripDetailAdapter(
    private val onFocus: (MapFocus) -> Unit,
    private val onRetry: (Int) -> Unit,
    private val onToggle: (Int) -> Unit,
) : ListAdapter<TripDetailRow, RecyclerView.ViewHolder>(TripDetailRowDiff) {
    class SummaryHolder(val binding: TripDetailSummaryBinding) : RecyclerView.ViewHolder(binding.root)
    class HeaderHolder(val text: TextView) : RecyclerView.ViewHolder(text)
    class StepHolder(val binding: TripDetailStepBinding) : RecyclerView.ViewHolder(binding.root)

    /** Lines the time column and the rail's node up with the first line of the content, however it wraps. */
    class TimelineHolder(val binding: TripDetailTimelineBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            // Rows without a time keep the rail where rows with one have it, at any text size.
            val time = binding.timelineTime
            time.minWidth = max(time.minWidth, ceil(time.paint.measureText("00:00")).toInt() + time.paddingStart + time.paddingEnd)
            binding.timelineContent.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> align() }
        }

        private fun align() {
            val b = binding
            val title = b.timelineTitle.isVisible
            val text = if (title) b.timelineTitle else b.timelineDetail
            val baseline = b.timelineContent.top + (if (title) 0 else b.timelineDetailRow.top) + text.top + text.baseline
            b.timelineRail.nodeY = baseline - text.textSize * 0.35f
            val shift = (baseline - b.timelineTime.top - b.timelineTime.baseline).toFloat()
            b.timelineTime.translationY = shift
            b.timelineScheduled.translationY = shift
        }
    }

    override fun getItemViewType(position: Int) = when (val row = getItem(position)) {
        is TripDetailRow.Summary -> SUMMARY
        is TripDetailRow.Header -> HEADER
        is TripDetailRow.Ride -> STEP_RIDE
        is TripDetailRow.Walk -> if (row.timeline) TIMELINE_WALK else STEP_WALK
        is TripDetailRow.Change -> if (row.timeline) TIMELINE_CHANGE else STEP_CHANGE
        // Each kind of timeline row keeps its own holders, so none inherits another's badge or styling.
        is TripDetailRow.Board -> TIMELINE_BOARD
        is TripDetailRow.StopsLoading -> TIMELINE_LOADING
        is TripDetailRow.StopsFailed -> TIMELINE_FAILED
        is TripDetailRow.Collapse -> TIMELINE_COLLAPSE
        is TripDetailRow.Middle -> TIMELINE_MIDDLE
        is TripDetailRow.Zone -> TIMELINE_ZONE
        is TripDetailRow.Alight -> TIMELINE_ALIGHT
        is TripDetailRow.Arrive -> TIMELINE_ARRIVE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            SUMMARY -> SummaryHolder(TripDetailSummaryBinding.inflate(inflater, parent, false))
            HEADER -> HeaderHolder(inflater.inflate(R.layout.trip_detail_header, parent, false) as TextView).also {
                ViewCompat.setAccessibilityHeading(it.text, true)
            }
            STEP_RIDE, STEP_WALK, STEP_CHANGE -> StepHolder(TripDetailStepBinding.inflate(inflater, parent, false))
            else -> TimelineHolder(TripDetailTimelineBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val texts = Texts(holder.itemView)
        when (holder) {
            is SummaryHolder -> bindSummary(holder.binding, getItem(position) as TripDetailRow.Summary, texts)
            is HeaderHolder -> holder.text.setText((getItem(position) as TripDetailRow.Header).title)
            is StepHolder -> bindStep(holder.binding, getItem(position), texts)
            is TimelineHolder -> bindTimeline(holder.binding, getItem(position), texts)
        }
        applyEdges(holder, position)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: List<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it === EDGES }) applyEdges(holder, position)
        else onBindViewHolder(holder, position)
    }

    // A row's corners and divider depend on its neighbours, which a list change can move without changing the row.
    override fun onCurrentListChanged(previousList: List<TripDetailRow>, currentList: List<TripDetailRow>) {
        notifyItemRangeChanged(0, currentList.size, EDGES)
    }

    /** Rows between two headers share a card: rounded at its ends, steps divided. */
    private fun applyEdges(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is HeaderHolder) return
        val view = holder.itemView
        val first = position == 0 || getItem(position - 1) is TripDetailRow.Header
        val last = position == itemCount - 1 || getItem(position + 1) is TripDetailRow.Header
        val radius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20f, view.resources.displayMetrics)
        view.background = MaterialShapeDrawable(
            ShapeAppearanceModel.builder()
                .setTopLeftCorner(CornerFamily.ROUNDED, if (first) radius else 0f)
                .setTopRightCorner(CornerFamily.ROUNDED, if (first) radius else 0f)
                .setBottomLeftCorner(CornerFamily.ROUNDED, if (last) radius else 0f)
                .setBottomRightCorner(CornerFamily.ROUNDED, if (last) radius else 0f)
                .build()
        ).apply {
            fillColor = ColorStateList.valueOf(MaterialColors.getColor(view, com.google.android.material.R.attr.colorSurfaceContainerHighest))
        }
        view.clipToOutline = true
        if (holder is StepHolder) holder.binding.stepDivider.isVisible = !first
    }

    private fun bindSummary(b: TripDetailSummaryBinding, row: TripDetailRow.Summary, texts: Texts) {
        val context = b.root.context
        val journey = row.journey
        val parts = journey.parts
        val first = parts.first()
        val last = parts.last()
        // A late first vehicle moves the start; the last one moves the arrival, a walk after it included.
        val startDelay = if (first.isWalk) null else row.delays[0]
        val endDelay = parts.indexOfLast { !it.isWalk }.takeIf { it >= 0 }?.let(row.delays::get)
        val start = first.startDeparture.expected(startDelay)
        val minutes = minutesBetween(start, last.endArrival.expected(endDelay))
        val day = dayUnlessToday(context, start)
        b.tripSummaryMinutes.text = context.getString(R.string.trip_detail_minutes, minutes)
        val times = TextUtils.expandTemplate(
            context.getText(R.string.trip_detail_route), texts.time(first.startDeparture, startDelay), texts.time(last.endArrival, endDelay),
        )
        b.tripSummaryTimes.text = if (day != null) TextUtils.concat("$day ", times) else times
        bindLegBar(b.tripSummaryLegBar, parts)

        val changes = max(journey.rideCount - 1, 0)
        val changesText = if (changes == 0) context.getString(R.string.trip_detail_no_changes)
        else context.resources.getQuantityString(R.plurals.trip_detail_changes, changes, changes)
        val walkText = if (journey.walkMinutes > 0) context.getString(R.string.trip_detail_walk, journey.walkMinutes)
        else context.getString(R.string.trip_detail_no_walking)
        // Zone names once the stops are known; R-API journeys only list numeric zone ids, so count them.
        val names = journey.zoneNames
        val zoneCount = journey.zones?.size ?: 0
        val zonesText = when {
            names.size == 1 -> context.getString(R.string.trip_detail_zone, names[0])
            names.isNotEmpty() -> context.getString(R.string.trip_detail_zones, names.joinToString(" · "))
            else -> context.resources.getQuantityString(R.plurals.trip_planner_zones, zoneCount, zoneCount)
        }
        b.tripSummaryChips.removeAllViews()
        listOfNotNull(
            changesText,
            walkText.takeIf { journey.walkMinutes > 0 },
            zonesText.takeIf { names.isNotEmpty() || zoneCount > 0 },
        ).forEach { addChip(b.tripSummaryChips, it) }

        b.tripSummary.contentDescription = context.getString(
            R.string.trip_detail_summary_spoken,
            minutes,
            (day?.let { "$it " } ?: "") + texts.spokenTime(first.startDeparture, startDelay),
            texts.spokenTime(last.endArrival, endDelay),
            changesText,
            walkText,
            zonesText,
        )
        b.tripSummary.setOnClickListener { onFocus(MapFocus.Route) }
        ViewCompat.replaceAccessibilityAction(
            b.tripSummary, AccessibilityActionCompat.ACTION_CLICK, context.getString(R.string.trip_detail_summary_action), null,
        )

        b.tripSummaryActions.isVisible = journey.rideCount > 0
        b.tripSummaryTicket.bind(row.coverage)
        if (row.arrived) b.tripSummaryTicket.isVisible = false
        b.tripSummaryArrived.isVisible = row.arrived
    }

    /** Each leg's share of the journey in its line colour, at least a minute; walks grey; line labels where they fit. */
    private fun bindLegBar(bar: LinearLayout, parts: List<Part>) {
        val context = bar.context
        val dark = isDarkTheme(context.resources)
        val density = context.resources.displayMetrics.density
        val bold = ResourcesCompat.getFont(context, R.font.roboto_bold)
        bar.removeAllViews()
        bar.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        parts.forEachIndexed { index, part ->
            val line = part.routeShortName.orEmpty()
            val seconds = max(Duration.between(part.startDeparture, part.endArrival).seconds, 60L).toFloat()
            val color = if (part.isWalk) MaterialColors.getColor(bar, com.google.android.material.R.attr.colorOnSurfaceVariant)
            else ContextCompat.getColor(context, getLineColor(line, dark))
            val segment = TextView(context).apply {
                background = GradientDrawable().apply {
                    cornerRadius = 100 * density
                    setColor(color)
                }
                gravity = Gravity.CENTER
                minHeight = (16 * density).roundToInt()
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = bold
                maxLines = 1
                setTextColor(ContextCompat.getColor(context, getLineTextColor(line)))
                text = if (part.isWalk) "" else line
            }
            bar.addView(segment, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, seconds).apply {
                if (index > 0) marginStart = (2 * density).roundToInt()
            })
        }
        bar.doOnLayout {
            bar.children.filterIsInstance<TextView>().filter { it.width < it.height * 2 }.forEach { it.setTextColor(Color.TRANSPARENT) }
        }
    }

    private fun addChip(chips: FlexboxLayout, text: String) {
        val density = chips.resources.displayMetrics.density
        chips.addView(TextView(chips.context).apply {
            this.text = text
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
            setTextColor(MaterialColors.getColor(chips, com.google.android.material.R.attr.colorOnSurface))
            background = GradientDrawable().apply {
                cornerRadius = 14 * density
                setColor(MaterialColors.getColor(chips, com.google.android.material.R.attr.colorSurface))
            }
            setPadding((10 * density).roundToInt(), (4 * density).roundToInt(), (10 * density).roundToInt(), (4 * density).roundToInt())
        }, FlexboxLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = (6 * density).roundToInt()
            bottomMargin = (6 * density).roundToInt()
        })
    }

    private fun bindStep(b: TripDetailStepBinding, row: TripDetailRow, texts: Texts) {
        val context = b.root.context
        val size = b.stepDetail.textSize
        b.stepBadge.isVisible = row is TripDetailRow.Ride
        b.stepIcon.isVisible = row !is TripDetailRow.Ride
        b.stepSecondary.isVisible = row is TripDetailRow.Ride
        b.stepTitle.typeface = if (row is TripDetailRow.Ride) ResourcesCompat.getFont(context, R.font.roboto_bold) else Typeface.DEFAULT
        texts.delay(b.stepDelay, (row as? TripDetailRow.Ride)?.delay)
        val (focus, action) = when (row) {
            is TripDetailRow.Ride -> {
                val part = row.part
                customizeLineText(b.stepBadge, part.routeShortName ?: "?", context, context.resources)
                b.stepTitle.text = part.tripHeadsign.orEmpty()
                b.stepDetail.isVisible = true
                b.stepDetail.setTextColor(MaterialColors.getColor(b.stepDetail, com.google.android.material.R.attr.colorOnSurface))
                b.stepDetail.setCompoundDrawablesRelative(null, null, null, null)
                b.stepDetail.text = TextUtils.expandTemplate(
                    context.getText(R.string.trip_detail_route),
                    texts.stop(part.startStopName, part.startStopCode, size),
                    texts.stop(part.endStopName, part.endStopCode, size),
                )
                val stopCount = (part.stops?.size ?: 0) - 2
                b.stepSecondary.text = TextUtils.concat(
                    TextUtils.expandTemplate(
                        context.getText(R.string.trip_detail_time_range), texts.time(part.startDeparture, row.delay), texts.time(part.endArrival, row.delay),
                    ),
                    if (stopCount > 0) context.resources.getQuantityString(R.plurals.trip_detail_stop_count, stopCount, stopCount) else "",
                )
                b.root.contentDescription = texts.rideSpoken(part, row.delay)
                MapFocus.Leg(row.index) to R.string.trip_detail_leg_action
            }
            is TripDetailRow.Walk -> {
                b.stepIcon.setImageResource(R.drawable.ic_directions_walk)
                b.stepTitle.text = texts.walk(row, b.stepTitle.textSize)
                texts.buffer(b.stepDetail, row.buffer)
                b.root.contentDescription = listOfNotNull(b.stepTitle.text, b.stepDetail.text.takeIf { row.buffer != null }).joinToString(", ")
                row.focus to R.string.trip_detail_walk_action
            }
            is TripDetailRow.Change -> {
                b.stepIcon.setImageResource(R.drawable.ic_swap_vert)
                b.stepTitle.text = texts.change(row.ride, b.stepTitle.textSize)
                texts.buffer(b.stepDetail, row.buffer)
                b.root.contentDescription = listOfNotNull(b.stepTitle.text, b.stepDetail.text.takeIf { row.buffer != null }).joinToString(", ")
                MapFocus.Change(row.to) to R.string.trip_detail_change_action
            }
            else -> return
        }
        b.root.setOnClickListener { onFocus(focus) }
        ViewCompat.replaceAccessibilityAction(b.root, AccessibilityActionCompat.ACTION_CLICK, context.getString(action), null)
    }

    private fun bindTimeline(b: TripDetailTimelineBinding, row: TripDetailRow, texts: Texts) {
        val context = b.root.context
        val dark = isDarkTheme(context.resources)
        fun lineColor(line: String) = ContextCompat.getColor(context, getLineColor(line, dark))
        val secondary = MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorOnSurfaceVariant)
        val primary = MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorOnSurface)
        val medium = ResourcesCompat.getFont(context, R.font.roboto_medium)
        val size = b.timelineDetail.textSize

        // Everything off, then what this row shows.
        b.timelineTitle.isVisible = false
        b.timelineTitle.typeface = medium
        b.timelineDetailRow.isVisible = true
        b.timelineProgress.isVisible = false
        b.timelineBadge.isVisible = false
        b.timelineChevron.isVisible = false
        b.timelineRetry.isVisible = false
        b.timelineBuffer.isVisible = false
        b.timelineDetail.setTextColor(secondary)
        texts.delay(b.timelineDelay, null)
        b.root.setOnClickListener(null)
        b.root.isClickable = false
        b.root.isFocusable = true
        b.timelineContent.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        ViewCompat.setStateDescription(b.root, null)
        ViewCompat.replaceAccessibilityAction(b.root, AccessibilityActionCompat.ACTION_CLICK, null, null)
        setTime(b, null, null)

        // A row that shows its stop on the map when its position is known.
        fun tappable(gps: StopGps?) {
            gps ?: return
            b.root.setOnClickListener { onFocus(MapFocus.StopAt(gps)) }
            ViewCompat.replaceAccessibilityAction(
                b.root, AccessibilityActionCompat.ACTION_CLICK, context.getString(R.string.trip_detail_stop_action), null,
            )
        }

        when (row) {
            is TripDetailRow.Board -> {
                val line = row.part.routeShortName ?: "?"
                val color = lineColor(line)
                setTime(b, row.stop.time, row.delay)
                b.timelineRail.set(null, color, TimelineRailView.Node.STOP, color)
                b.timelineTitle.isVisible = true
                b.timelineTitle.text = texts.stop(row.stop.name, row.stop.platform, b.timelineTitle.textSize)
                b.timelineBadge.isVisible = true
                customizeLineText(b.timelineBadge, line, context, context.resources)
                b.timelineDetail.setTextColor(primary)
                b.timelineDetail.text = context.getString(R.string.trip_detail_to, row.part.tripHeadsign.orEmpty())
                texts.delay(b.timelineDelay, row.delay)
                b.root.contentDescription = context.getString(
                    R.string.trip_detail_board_spoken, line, row.part.tripHeadsign.orEmpty(), texts.spokenTime(row.stop.time, row.delay), row.stop.name,
                ) + texts.platformSpoken(row.stop.platform) + texts.delaySpoken(row.delay)
                tappable(row.stop.gps)
            }
            is TripDetailRow.StopsLoading -> {
                val color = lineColor(row.line)
                b.timelineRail.set(color, color, TimelineRailView.Node.NONE, color)
                b.timelineProgress.isVisible = true
                b.timelineDetail.setText(R.string.trip_detail_loading_stops)
                b.root.contentDescription = b.timelineDetail.text
            }
            is TripDetailRow.StopsFailed -> {
                val color = lineColor(row.line)
                b.timelineRail.set(color, color, TimelineRailView.Node.NONE, color)
                b.timelineDetail.setText(R.string.trip_detail_stops_failed)
                b.timelineRetry.isVisible = true
                b.timelineRetry.setOnClickListener { onRetry(row.index) }
                // The text and the Retry button are read one by one.
                b.root.isFocusable = false
                b.root.contentDescription = null
                b.timelineContent.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            }
            is TripDetailRow.Collapse -> {
                val color = lineColor(row.line)
                b.timelineRail.set(color, color, TimelineRailView.Node.NONE, color)
                b.timelineDetail.text = context.resources.getQuantityString(R.plurals.trip_detail_stops_between, row.count, row.count, row.minutes)
                b.timelineChevron.isVisible = true
                b.timelineChevron.rotation = if (row.expanded) 180f else 0f
                b.root.contentDescription = b.timelineDetail.text
                b.root.setOnClickListener { onToggle(row.index) }
                ViewCompat.setStateDescription(
                    b.root, context.getString(if (row.expanded) R.string.trip_detail_expanded else R.string.trip_detail_collapsed),
                )
                ViewCompat.replaceAccessibilityAction(
                    b.root, AccessibilityActionCompat.ACTION_CLICK,
                    context.getString(if (row.expanded) R.string.trip_detail_hide_stops else R.string.trip_detail_show_stops), null,
                )
            }
            is TripDetailRow.Middle -> {
                val color = lineColor(row.line)
                setTime(b, row.stop.time, row.delay)
                b.timelineRail.set(color, color, TimelineRailView.Node.MIDDLE, color)
                b.timelineDetail.setTextColor(primary)
                b.timelineDetail.text = texts.stop(row.stop.name, row.stop.platform, size)
                b.root.contentDescription = context.getString(
                    R.string.trip_detail_stop_spoken, texts.spokenTime(row.stop.time, row.delay), row.stop.name,
                ) + texts.platformSpoken(row.stop.platform)
                tappable(row.stop.gps)
            }
            is TripDetailRow.Zone -> {
                val color = lineColor(row.line)
                b.timelineRail.set(color, color, TimelineRailView.Node.NONE, color)
                val density = context.resources.displayMetrics.density
                b.timelineBadge.isVisible = true
                b.timelineBadge.text = context.getString(R.string.trip_detail_zone_border, row.change.from, row.change.to)
                b.timelineBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                b.timelineBadge.setTextColor(primary)
                b.timelineBadge.background = GradientDrawable().apply {
                    cornerRadius = 12 * density
                    setColor(MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorSurface))
                }
                b.timelineBadge.setPadding((8 * density).roundToInt(), (3 * density).roundToInt(), (8 * density).roundToInt(), (3 * density).roundToInt())
                b.timelineDetail.text = ""
                b.root.contentDescription = context.getString(R.string.trip_detail_zone_border_spoken, row.change.from, row.change.to)
            }
            is TripDetailRow.Alight -> {
                val color = lineColor(row.line)
                setTime(b, row.stop.time, row.delay)
                b.timelineRail.set(color, null, TimelineRailView.Node.STOP, color)
                b.timelineTitle.isVisible = true
                b.timelineTitle.text = texts.stop(row.stop.name, row.stop.platform, b.timelineTitle.textSize)
                b.timelineDetail.setText(if (row.isLast) R.string.trip_detail_arrive else R.string.trip_detail_get_off)
                val time = texts.spokenTime(row.stop.time, row.delay)
                b.root.contentDescription = context.getString(
                    if (row.isLast) R.string.trip_detail_arrive_spoken else R.string.trip_detail_get_off_spoken, time, row.stop.name,
                ) + texts.platformSpoken(row.stop.platform)
                tappable(row.stop.gps)
            }
            is TripDetailRow.Walk -> {
                b.timelineRail.set(null, null, TimelineRailView.Node.WALK, ContextCompat.getColor(context, R.color.table_grey))
                b.timelineDetail.setTextColor(primary)
                b.timelineDetail.text = texts.walk(row, size)
                texts.buffer(b.timelineBuffer, row.buffer)
                b.root.contentDescription = listOfNotNull(b.timelineDetail.text, b.timelineBuffer.text.takeIf { row.buffer != null }).joinToString(", ")
            }
            is TripDetailRow.Change -> {
                b.timelineRail.set(null, null, TimelineRailView.Node.WALK, ContextCompat.getColor(context, R.color.table_grey))
                b.timelineDetail.setTextColor(primary)
                b.timelineDetail.text = texts.change(row.ride, size)
                texts.buffer(b.timelineBuffer, row.buffer)
                b.root.contentDescription = listOfNotNull(b.timelineDetail.text, b.timelineBuffer.text.takeIf { row.buffer != null }).joinToString(", ")
            }
            is TripDetailRow.Arrive -> {
                val grey = ContextCompat.getColor(context, R.color.table_grey)
                setTime(b, row.part.endArrival, row.delay)
                b.timelineRail.set(null, null, TimelineRailView.Node.STOP, grey)
                b.timelineTitle.isVisible = true
                b.timelineTitle.text = row.part.endStopName.orEmpty()
                b.timelineDetail.setText(R.string.trip_detail_arrive)
                b.root.contentDescription = context.getString(
                    R.string.trip_detail_arrive_spoken, texts.spokenTime(row.part.endArrival, row.delay), row.part.endStopName.orEmpty(),
                )
                tappable(row.part.endStopGps)
            }
            else -> Unit
        }
    }

    /** The expected time, with the scheduled one struck through below it while they differ. */
    private fun setTime(b: TripDetailTimelineBinding, time: Instant?, delay: Int?) {
        val expected = time?.let { clock(it.expected(delay)) }
        val scheduled = time?.let(::clock)?.takeIf { it != expected }
        b.timelineTime.text = expected.orEmpty()
        b.timelineScheduled.isVisible = scheduled != null
        b.timelineScheduled.text = scheduled?.let { SpannableStringBuilder(it).apply { setSpan(StrikethroughSpan(), 0, length, 0) } }
    }

    private companion object {
        const val SUMMARY = 0
        const val HEADER = 1
        const val STEP_RIDE = 2
        const val STEP_WALK = 3
        const val STEP_CHANGE = 4
        const val TIMELINE_BOARD = 5
        const val TIMELINE_LOADING = 6
        const val TIMELINE_FAILED = 7
        const val TIMELINE_COLLAPSE = 8
        const val TIMELINE_MIDDLE = 9
        const val TIMELINE_ZONE = 10
        const val TIMELINE_ALIGHT = 11
        const val TIMELINE_WALK = 12
        const val TIMELINE_CHANGE = 13
        const val TIMELINE_ARRIVE = 14

        /** Rebinds a row's corners and divider only. */
        val EDGES = Any()
    }
}

/** The sheet's wording, worded and styled as on iOS. */
private class Texts(view: View) {
    private val context: Context = view.context
    @ColorInt
    private val secondary = MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurfaceVariant)
    @ColorInt
    private val onSurface = MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurface)

    /** The platform sign and letter after a stop name; nothing without a platform. They stay on the name's last line. */
    fun platform(platform: String?, textSize: Float): CharSequence {
        val code = platform?.takeIf { it.isNotEmpty() } ?: return ""
        val text = SpannableStringBuilder(" ")
        AppCompatResources.getDrawable(context, R.drawable.ic_stop)?.mutate()?.let { icon ->
            icon.setTint(secondary)
            val size = (textSize * 0.85f).roundToInt()
            icon.setBounds(0, 0, size, size)
            text.append(" ", ImageSpan(icon, ImageSpan.ALIGN_BASELINE), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        text.append("⁠").append(code)
        text.setSpan(ForegroundColorSpan(secondary), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return text
    }

    fun stop(name: String?, platform: String?, textSize: Float): CharSequence = TextUtils.concat(name.orEmpty(), platform(platform, textSize))

    /** The expected time, then the scheduled one struck through while they show differently. */
    fun time(scheduled: Instant, delay: Int?): CharSequence {
        val expected = clock(scheduled.expected(delay))
        val planned = clock(scheduled)
        if (expected == planned) return planned
        return SpannableStringBuilder("$expected ").append(planned, StrikethroughSpan(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    fun spokenTime(scheduled: Instant, delay: Int?): String {
        val expected = clock(scheduled.expected(delay))
        val planned = clock(scheduled)
        return if (expected == planned) planned else context.getString(R.string.trip_detail_time_spoken, expected, planned)
    }

    fun platformSpoken(platform: String?): String =
        platform?.takeIf { it.isNotEmpty() }?.let { context.getString(R.string.trip_detail_platform_spoken, it) }.orEmpty()

    fun delaySpoken(seconds: Int?): String {
        val minutes = delayMinutes(seconds ?: return "")
        return when {
            minutes > 0 -> context.resources.getQuantityString(R.plurals.trip_detail_late_spoken, minutes, minutes)
            minutes < 0 -> context.resources.getQuantityString(R.plurals.trip_detail_early_spoken, -minutes, -minutes)
            else -> context.getString(R.string.trip_detail_on_time_spoken)
        }
    }

    /** Worded and coloured as on the board; hidden without a known delay. */
    fun delay(view: TextView, seconds: Int?) {
        view.isVisible = seconds != null
        val minutes = delayMinutes(seconds ?: return)
        view.text = when {
            minutes > 0 -> context.getString(R.string.delay, minutes.toString())
            minutes < 0 -> context.getString(R.string.inAdvance, (-minutes).toString())
            else -> context.getString(R.string.table_no_delay)
        }
        view.setTextColor(ContextCompat.getColor(context, delayColor(minutes, "online")))
    }

    fun rideSpoken(part: Part, delay: Int?): String {
        val stopCount = (part.stops?.size ?: 0) - 2
        return context.getString(
            R.string.trip_detail_ride_spoken, part.routeShortName.orEmpty(), part.tripHeadsign.orEmpty(),
            spokenTime(part.startDeparture, delay), part.startStopName.orEmpty(),
        ) + platformSpoken(part.startStopCode) +
            context.getString(R.string.trip_detail_arrives_spoken, spokenTime(part.endArrival, delay), part.endStopName.orEmpty()) +
            platformSpoken(part.endStopCode) +
            (if (stopCount > 0) context.resources.getQuantityString(R.plurals.trip_detail_stops_spoken, stopCount, stopCount) else "") +
            delaySpoken(delay)
    }

    /** "Walk N min to platform X" within a station, else "Walk N min to {stop}" and its platform. */
    fun walk(row: TripDetailRow.Walk, textSize: Float): CharSequence {
        val name = row.ride?.startStopName ?: row.last.endStopName.orEmpty()
        val platform = (row.ride?.startStopCode ?: row.last.endStopCode)?.takeIf { it.isNotEmpty() }
        if (row.first.startStopName == name && platform != null) {
            return context.getString(R.string.trip_detail_walk_to_platform, row.minutes, platform)
        }
        return TextUtils.concat(context.getString(R.string.trip_detail_walk_to, row.minutes, name), platform(platform, textSize))
    }

    fun change(ride: Part, textSize: Float): CharSequence =
        TextUtils.concat(context.getString(R.string.trip_detail_change_at, ride.startStopName.orEmpty()), platform(ride.startStopCode, textSize))

    /** The time to spare at a change: a warning when tight (orange) or likely missed (red). */
    fun buffer(view: TextView, buffer: TransferBuffer?) {
        view.isVisible = buffer != null
        buffer ?: return
        val warning = buffer.isTight || buffer.isLikelyMissed
        view.text = when {
            buffer.isLikelyMissed -> context.getString(R.string.trip_detail_missed, -buffer.minutes)
            buffer.isTight -> context.getString(R.string.trip_detail_tight, buffer.minutes)
            else -> context.getString(R.string.trip_detail_spare, buffer.minutes)
        }
        view.setTextColor(if (warning) onSurface else secondary)
        view.typeface = if (warning) ResourcesCompat.getFont(context, R.font.roboto_medium) else Typeface.DEFAULT
        val icon = if (warning) AppCompatResources.getDrawable(context, R.drawable.ic_warning)?.mutate() else null
        icon?.let {
            it.setTint(ContextCompat.getColor(context, if (buffer.isLikelyMissed) R.color.table_red else R.color.table_orange))
            val size = view.textSize.roundToInt()
            it.setBounds(0, 0, size, size)
        }
        view.compoundDrawablePadding = (6 * context.resources.displayMetrics.density).roundToInt()
        view.setCompoundDrawablesRelative(icon, null, null, null)
    }
}

private object TripDetailRowDiff : DiffUtil.ItemCallback<TripDetailRow>() {
    private fun TripDetailRow.key(): Any = when (this) {
        is TripDetailRow.Summary -> 0
        is TripDetailRow.Header -> title
        is TripDetailRow.Ride -> index
        is TripDetailRow.Walk -> listOf(timeline, focus)
        is TripDetailRow.Change -> listOf(timeline, to)
        is TripDetailRow.Board -> index
        is TripDetailRow.StopsLoading -> index
        is TripDetailRow.StopsFailed -> index
        is TripDetailRow.Collapse -> index
        is TripDetailRow.Middle -> listOf(index, stop.name, stop.time)
        is TripDetailRow.Zone -> listOf(index, change.index)
        is TripDetailRow.Alight -> index
        is TripDetailRow.Arrive -> 0
    }

    override fun areItemsTheSame(oldItem: TripDetailRow, newItem: TripDetailRow) =
        oldItem::class == newItem::class && oldItem.key() == newItem.key()

    override fun areContentsTheSame(oldItem: TripDetailRow, newItem: TripDetailRow) = oldItem == newItem
}

/** A timeline row's rail: the line in from above and out below, the stop's ring, or a walk's dots. */
class TimelineRailView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    enum class Node(val diameterDp: Float, val ringDp: Float) {
        NONE(0f, 0f), STOP(16f, 4f), MIDDLE(10f, 2.5f), WALK(0f, 0f)
    }

    private val density = resources.displayMetrics.density
    private var top: Int? = null
    private var bottom: Int? = null
    private var node = Node.NONE
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4 * density
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dots = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4 * density
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(0.5f * density, 8 * density), 0f)
    }

    /** Where the node's centre sits, set by the row once its first line is laid out. */
    var nodeY = 0f
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    fun set(top: Int?, bottom: Int?, node: Node, @ColorInt nodeColor: Int) {
        this.top = top
        this.bottom = bottom
        this.node = node
        ring.color = nodeColor
        dots.color = nodeColor
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val x = width / 2f
        val height = height.toFloat()
        val top = top
        val bottom = bottom
        if (node == Node.WALK) {
            canvas.drawLine(x, 0f, x, height, dots)
            return
        }
        if (node == Node.NONE && top == bottom) {
            // One line, without a seam where two would meet.
            top?.let { canvas.drawLine(x, 0f, x, height, line.apply { color = it }) }
            return
        }
        val diameter = node.diameterDp * density
        val ringWidth = node.ringDp * density
        // Into the node's ring, not its middle, which stays clear whatever the row's background.
        val gap = (diameter - ringWidth) / 2
        top?.let { canvas.drawLine(x, 0f, x, nodeY - gap, line.apply { color = it }) }
        bottom?.let { canvas.drawLine(x, nodeY + gap, x, height, line.apply { color = it }) }
        if (diameter > 0) {
            ring.strokeWidth = ringWidth
            canvas.drawCircle(x, nodeY, gap, ring)
        }
    }
}
