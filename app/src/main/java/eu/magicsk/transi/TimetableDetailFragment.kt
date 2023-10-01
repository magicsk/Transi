package eu.magicsk.transi

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.setPadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.viewpager.widget.PagerAdapter
import eu.magicsk.transi.data.remote.responses.idsbk.Session
import eu.magicsk.transi.databinding.FragmentTimetableDetailBinding
import eu.magicsk.transi.util.*
import eu.magicsk.transi.view_models.MainViewModel
import eu.magicsk.transi.view_models.TimetablesViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TimetableDetailFragment : Fragment() {
    private var _binding: FragmentTimetableDetailBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTimetableDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val routeId = requireArguments().getInt("route_id")
        val lineNum = requireArguments().getString("short_name") ?: "Error"
        val lineDirection = requireArguments().getString("long_name") ?: "Error"
        val directionId = requireArguments().getInt("direction")
        val stationId = requireArguments().getInt("station_id")
        val stationName = requireArguments().getString("station_name")
        binding.apply {
            val context = root.context
            val resources = root.resources
            customizeLineText(TimetableDetailTitleLine, lineNum, context, resources)
            TimetableDetailTitleDirection.text = lineDirection
            TimetableDetailTitleDirection.isSelected = true
            TimetableDetailSubtitleText.text = stationName
            TimetableDetailSubtitleText.isSelected = true
            val timetablesViewModel =
                ViewModelProvider(requireActivity())[TimetablesViewModel::class.java]
            val mainViewModel = ViewModelProvider(requireActivity())[MainViewModel::class.java]
            mainViewModel.idsbkSession.observe(viewLifecycleOwner) { idsbkSession ->
                idsbkSession?.let {
                    val timetablePagerAdapter =
                        TimetablePagerAdapter(
                            context,
                            binding,
                            idsbkSession,
                            timetablesViewModel,
                            routeId,
                            directionId,
                            stationId
                        )
                    TimetableDetailDeparturesContainer.adapter = timetablePagerAdapter
                }
            }
        }
    }


    class TimetablePagerAdapter(
        val context: Context,
        val binding:  FragmentTimetableDetailBinding,
        private val idsbkSession: Session,
        private val timetablesViewModel: TimetablesViewModel,
        private val routeId: Int,
        private val directionId: Int,
        private val stationId: Int
    ) : PagerAdapter() {
        private val views = mutableMapOf<String, ViewGroup>()
        override fun isViewFromObject(view: View, `object`: Any): Boolean {
            return view === `object`
        }

        override fun instantiateItem(container: ViewGroup, position: Int): Any {
            val pageView = getViewGroup(
                position,
                context,
                timetablesViewModel,
                routeId,
                directionId,
                stationId
            )
            if (pageView.parent != null) {
                val parent = pageView.parent as ScrollView
                parent.removeView(pageView)
            }
            val scrollView = ScrollView(context)
            scrollView.addView(pageView)
            container.addView(scrollView)
            return scrollView
        }

        override fun destroyItem(container: ViewGroup, position: Int, `object`: Any) {
            container.removeView(`object` as View)
        }

        override fun getCount(): Int {
            return 10
        }

        private fun getViewGroup(
            day: Int,
            context: Context,
            timetablesViewModel: TimetablesViewModel,
            routeId: Int,
            directionId: Int,
            stationId: Int
        ): ViewGroup {
            val date = getDate(day.toLong())
            val existingView = views[date]
            if (existingView != null) return existingView

            val tableLayout = TableLayout(context)
            tableLayout.setPadding(15f.dpToPx(context))
            val headerText = TextView(context)
            headerText.text =
                if (date == getDate()) "Today" else getDate(day.toLong(), "dd.MM.yyyy")
            headerText.setPadding(20f.dpToPx(context))
            headerText.gravity = Gravity.CENTER
            tableLayout.addView(headerText)
            CoroutineScope(Dispatchers.IO).launch {
                val data =
                    timetablesViewModel.getTimetableDetail(
                        routeId,
                        "departures",
                        directionId,
                        date,
                        stationId,
                        idsbkSession
                    )
                println("fetched $date")
                CoroutineScope(Dispatchers.Main).launch {
                    animatedAlphaChange(1F, 0F, 100, binding.TimetableDetailLoadingIndicator)
                    val departureTimes = mutableListOf<Pair<Int, String>>()
                    val hours = mutableSetOf<Int>()
                    var lastTableRow: TableRow? = null
                    data?.departures?.forEach {
                        val hour = it.t / 60
                        val minute = (it.t % 60).toString().padStart(2, Char(48))
                        departureTimes.add(Pair(hour, minute))
                        hours.add(hour)
                    }
                    hours.forEachIndexed { index, hour ->
                        if (lastTableRow != null) tableLayout.addView(lastTableRow)
                        val hourTextView = TextView(context)
                        hourTextView.text = hour.toString()
                        hourTextView.width = 30f.dpToPx(context)
                        hourTextView.setPadding(5f.dpToPx(context))
                        hourTextView.gravity = Gravity.CENTER
                        val colorPrimaryContainer = TypedValue()
                        context.theme.resolveAttribute(
                            R.attr.colorPrimaryContainer,
                            colorPrimaryContainer,
                            true
                        )
                        val hourBackground =
                            ContextCompat.getDrawable(
                                context,
                                getBackgroundDrawable(index, hours.size)
                            )
                        hourBackground?.colorFilter =
                            PorterDuffColorFilter(colorPrimaryContainer.data, PorterDuff.Mode.SRC)
                        hourTextView.background = hourBackground
                        lastTableRow = TableRow(context)
                        lastTableRow!!.addView(hourTextView)
                        val spacer = LinearLayout(context)
                        spacer.setPadding(2f.dpToPx(context))
                        lastTableRow!!.addView(spacer)
                        departureTimes.forEach { departureTime ->
                            if (departureTime.first == hour) {
                                val minuteTextView = TextView(context)
                                minuteTextView.text = departureTime.second
                                minuteTextView.setPadding(5f.dpToPx(context))
                                lastTableRow!!.addView(minuteTextView)
                            }
                        }
                    }
                    if (lastTableRow != null) tableLayout.addView(lastTableRow)
                }
            }
            views[date] = tableLayout
            return tableLayout
        }

        private fun getBackgroundDrawable(index: Int, size: Int): Int {
            return when (index) {
                0 -> R.drawable.round_shape_top_only_25
                size - 1 -> R.drawable.round_shape_bottom_only_25
                else -> R.drawable.basic_shape
            }
        }
    }
}