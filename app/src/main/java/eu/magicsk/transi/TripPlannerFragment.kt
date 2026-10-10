package eu.magicsk.transi

import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import androidx.viewpager2.widget.ViewPager2
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointForward
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.datepicker.MaterialPickerOnPositiveButtonClickListener
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import dagger.hilt.android.AndroidEntryPoint
import eu.magicsk.transi.adapters.TripPlannerPage
import eu.magicsk.transi.adapters.TripPlannerPagerAdapter
import eu.magicsk.transi.data.models.ArrivalDeparture
import eu.magicsk.transi.data.models.BRATISLAVA
import eu.magicsk.transi.data.remote.responses.Stop
import eu.magicsk.transi.data.remote.responses.Stops
import eu.magicsk.transi.databinding.FragmentTripPlannerBinding
import eu.magicsk.transi.util.isActualLocation
import eu.magicsk.transi.view_models.MainViewModel
import eu.magicsk.transi.view_models.TripPlannerState
import eu.magicsk.transi.view_models.TripPlannerViewModel
import eu.magicsk.transi.util.stopTypeIcon
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@AndroidEntryPoint
class TripPlannerFragment : Fragment(R.layout.fragment_trip_planner) {
    private val viewModel: TripPlannerViewModel by activityViewModels()
    private val mainViewModel: MainViewModel by activityViewModels()
    private var stopList = Stops()
    private var backStackListener: FragmentManager.OnBackStackChangedListener? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentTripPlannerBinding.bind(view)
        // Both callbacks come during a layout or scroll, when the pages must not change yet.
        val pager = binding.TripPlannerPager
        val pagerAdapter = TripPlannerPagerAdapter { journey -> pager.post { viewModel.loadMoreIfNeeded(journey) } }
        pager.adapter = pagerAdapter
        binding.TripPlannerSettingsBtn.setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val page = pagerAdapter.pages.getOrNull(position) ?: return
                pager.post {
                    if (page.searchId != viewModel.state.value?.searchId) viewModel.showRecentSearch(page.searchId)
                }
            }
        })

        // The stops, session and location reach the planner before its state is drawn.
        mainViewModel.stopList.observe(viewLifecycleOwner) { stops ->
            stopList = stops
            viewModel.setStops(stops)
        }
        mainViewModel.idsbkSession.observe(viewLifecycleOwner) { viewModel.setSession(it?.session) }
        mainViewModel.actualLocation.observe(viewLifecycleOwner) { viewModel.onLocationAvailable() }
        viewModel.state.observe(viewLifecycleOwner) { render(binding, pagerAdapter, it) }

        binding.editTextFrom.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) openTypeAhead(binding, binding.editTextFrom, ORIGIN_FROM)
        }
        binding.editTextTo.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) openTypeAhead(binding, binding.editTextTo, ORIGIN_TO)
        }
        binding.switchBtn.setOnClickListener { viewModel.swap() }
        binding.timeDateBtn.setOnClickListener { openDatePicker() }
        binding.adToggle.addOnButtonCheckedListener { group, checkedId, isChecked ->
            val value = if (checkedId == R.id.arrivalBtn) ArrivalDeparture.ARRIVAL else ArrivalDeparture.DEPARTURE
            if (!isChecked || value == viewModel.state.value?.arrivalDeparture) return@addOnButtonCheckedListener
            group.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            viewModel.setArrivalDeparture(value)
        }

        // Pickers shown before a configuration change come back without their listeners.
        (childFragmentManager.findFragmentByTag(DATE_PICKER) as? MaterialDatePicker<*>)?.let(::listenToDatePicker)
        childFragmentManager.fragments.filterIsInstance<MaterialTimePicker>().forEach { picker ->
            picker.tag?.removePrefix(TIME_PICKER_PREFIX)?.let { listenToTimePicker(picker, LocalDate.parse(it)) }
        }

        val activityFragments = requireActivity().supportFragmentManager
        backStackListener = FragmentManager.OnBackStackChangedListener {
            if (activityFragments.findFragmentById(R.id.tripSearchFragmentLayout) == null && binding.tripSearchFragmentLayout.isVisible) {
                binding.editTextFrom.clearFocus()
                binding.editTextTo.clearFocus()
                WindowCompat.getInsetsController(requireActivity().window, view).hide(WindowInsetsCompat.Type.ime())
                showSearchOverlay(binding, false)
                viewModel.state.value?.let { render(binding, pagerAdapter, it) }
            }
        }.also(activityFragments::addOnBackStackChangedListener)
    }

    override fun onDestroyView() {
        backStackListener?.let(requireActivity().supportFragmentManager::removeOnBackStackChangedListener)
        backStackListener = null
        super.onDestroyView()
    }

    private fun render(binding: FragmentTripPlannerBinding, pagerAdapter: TripPlannerPagerAdapter, state: TripPlannerState) {
        val searching = binding.tripSearchFragmentLayout.isVisible
        // While the type-ahead is open, the fields hold the filter being typed.
        if (!searching) {
            binding.editTextFrom.showText(stopName(state.from))
            binding.editTextTo.showText(stopName(state.to))
        }
        showStopIcon(binding.fromIcon, state.from)
        showStopIcon(binding.toIcon, state.to)
        binding.adToggle.check(if (state.arrivalDeparture == ArrivalDeparture.ARRIVAL) R.id.arrivalBtn else R.id.departureBtn)
        val date = if (state.customDate) state.date.atZone(BRATISLAVA).format(dateButtonFormat) else getString(R.string.trip_planner_now)
        binding.timeDateBtn.text = date
        binding.timeDateBtn.contentDescription = getString(R.string.trip_planner_date_description, date)

        // With recent searches, the results page through them, oldest to newest.
        val recentPages = state.recents.asReversed().map {
            if (it.id == state.searchId) currentPage(it.id, state) else TripPlannerPage(it.id, it.trip, isCurrent = false)
        }
        // A search that is not a recent one yet still shows its state, like a missing location, last.
        val showsCurrent = state.loading || state.journeys != null || state.error != null
        val pages = when {
            recentPages.isEmpty() -> listOf(currentPage(CURRENT_PAGE, state))
            recentPages.none { it.isCurrent } && showsCurrent -> recentPages + currentPage(state.searchId, state)
            else -> recentPages
        }
        pagerAdapter.submit(pages)
        val currentIndex = pages.indexOfFirst { it.isCurrent }
        if (currentIndex >= 0 && currentIndex != binding.TripPlannerPager.currentItem) {
            binding.TripPlannerPager.setCurrentItem(currentIndex, ValueAnimator.areAnimatorsEnabled())
        }
        showPageDots(binding.TripPlannerPageDots, pages.size, currentIndex.coerceAtLeast(0), searching)
    }

    private fun currentPage(id: String, state: TripPlannerState) = TripPlannerPage(
        id, state.journeys, isCurrent = true, state.loading, state.loadingMore, state.error, state.generation
    )

    private fun showPageDots(dots: LinearLayout, count: Int, selected: Int, searching: Boolean) {
        dots.removeAllViews()
        dots.isVisible = count > 1 && !searching
        if (count <= 1) return
        val density = resources.displayMetrics.density
        val selectedColor = MaterialColors.getColor(dots, androidx.appcompat.R.attr.colorPrimary)
        val otherColor = MaterialColors.getColor(dots, com.google.android.material.R.attr.colorOnSurfaceVariant)
        repeat(count) { index ->
            val isSelected = index == selected
            dots.addView(View(dots.context).apply {
                setBackgroundResource(R.drawable.trip_planner_page_dot)
                backgroundTintList = ColorStateList.valueOf(if (isSelected) selectedColor else otherColor)
                alpha = if (isSelected) 1f else 0.3f
                layoutParams = LinearLayout.LayoutParams(
                    ((if (isSelected) 16 else 6) * density).roundToInt(),
                    (6 * density).roundToInt(),
                ).apply { marginStart = (3.5f * density).roundToInt(); marginEnd = marginStart }
            })
        }
        dots.contentDescription = getString(R.string.trip_planner_recent_page, selected + 1, count)
    }

    private fun showSearchOverlay(binding: FragmentTripPlannerBinding, shown: Boolean) {
        binding.tripSearchFragmentLayout.isVisible = shown
        binding.TripPlannerHeader.isVisible = !shown
        binding.TripPlannerPager.isVisible = !shown
        binding.TripPlannerModeRow.isVisible = !shown
        binding.TripPlannerPageDots.isVisible = !shown && binding.TripPlannerPageDots.childCount > 1
    }

    private fun openTypeAhead(binding: FragmentTripPlannerBinding, field: EditText, origin: String) {
        if (stopList.isEmpty()) {
            field.clearFocus()
            Toast.makeText(requireContext(), R.string.no_connection, Toast.LENGTH_SHORT).show()
            return
        }
        val fragments = requireActivity().supportFragmentManager
        if (fragments.backStackEntryCount > 0) {
            // Moving between the fields: the type-ahead reopens for the other one.
            fragments.popBackStackImmediate(TYPE_AHEAD, FragmentManager.POP_BACK_STACK_INCLUSIVE)
            field.requestFocus()
            return
        }
        showSearchOverlay(binding, true)
        val typeAhead = TypeAheadFragment().apply {
            arguments = Bundle().apply {
                putSerializable("stopsList", stopList)
                putBoolean("directions", false)
                putString("origin", origin)
            }
        }
        fragments.beginTransaction().replace(R.id.tripSearchFragmentLayout, typeAhead).addToBackStack(TYPE_AHEAD).commit()
    }

    private fun openDatePicker() {
        val state = viewModel.state.value ?: return
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(modeTitle(state))
            .setSelection(state.date.atZone(BRATISLAVA).toLocalDate().utcMillis())
            .setCalendarConstraints(
                CalendarConstraints.Builder()
                    .setValidator(DateValidatorPointForward.from(LocalDate.now(BRATISLAVA).minusDays(1).utcMillis()))
                    .build()
            )
            .setNegativeButtonText(R.string.trip_planner_now_action)
            .build()
        picker.show(childFragmentManager, DATE_PICKER)
        listenToDatePicker(picker)
    }

    private fun listenToDatePicker(picker: MaterialDatePicker<*>) {
        picker.clearOnPositiveButtonClickListeners()
        picker.clearOnNegativeButtonClickListeners()
        picker.addOnPositiveButtonClickListener(MaterialPickerOnPositiveButtonClickListener<Any?> { selection ->
            (selection as? Long)?.let { openTimePicker(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
        })
        picker.addOnNegativeButtonClickListener { viewModel.resetDateToNow() }
    }

    private fun openTimePicker(day: LocalDate) {
        val state = viewModel.state.value ?: return
        val time = state.date.atZone(BRATISLAVA)
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(time.hour)
            .setMinute(time.minute)
            .setTitleText(modeTitle(state))
            .setNegativeButtonText(R.string.trip_planner_now_action)
            .build()
        picker.show(childFragmentManager, TIME_PICKER_PREFIX + day)
        listenToTimePicker(picker, day)
    }

    private fun listenToTimePicker(picker: MaterialTimePicker, day: LocalDate) {
        picker.clearOnPositiveButtonClickListeners()
        picker.clearOnNegativeButtonClickListeners()
        picker.addOnPositiveButtonClickListener {
            viewModel.setDate(day.atTime(picker.hour, picker.minute).atZone(BRATISLAVA).toInstant())
        }
        picker.addOnNegativeButtonClickListener { viewModel.resetDateToNow() }
    }

    private fun modeTitle(state: TripPlannerState) = getString(
        if (state.arrivalDeparture == ArrivalDeparture.ARRIVAL) R.string.trip_planner_arrival else R.string.trip_planner_departure
    )

    private fun stopName(stop: Stop?) = when {
        stop == null -> ""
        stop.isActualLocation -> getString(R.string.actual_position)
        else -> stop.name
    }

    private fun showStopIcon(icon: ImageView, stop: Stop?) {
        if (stop == null) {
            icon.setImageResource(R.drawable.ic_filled_circle)
            icon.imageTintList = null
            icon.backgroundTintList =
                ColorStateList.valueOf(MaterialColors.getColor(icon, com.google.android.material.R.attr.colorSurfaceVariant))
            return
        }
        val (drawable, color) = stopTypeIcon(if (stop.isActualLocation) "location" else stop.type)
        val tint = ContextCompat.getColor(icon.context, color)
        icon.setImageResource(drawable)
        icon.imageTintList = ColorStateList.valueOf(tint)
        icon.backgroundTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(tint, 0x33))
    }

    private fun EditText.showText(value: String) {
        if (text.toString() != value) setText(value)
    }

    private fun LocalDate.utcMillis() = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private companion object {
        const val CURRENT_PAGE = "current"
        const val TYPE_AHEAD = "tripTypeAhead"
        // The type-ahead finds its field by these.
        const val ORIGIN_FROM = "editTextFrom"
        const val ORIGIN_TO = "editTextTo"
        const val DATE_PICKER = "tripDatePicker"
        /** Followed by the picked day, which the time picker needs after a configuration change. */
        const val TIME_PICKER_PREFIX = "tripTimePicker:"
        val dateButtonFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM H:mm")
    }
}
