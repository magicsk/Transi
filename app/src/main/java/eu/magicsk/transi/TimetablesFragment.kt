package eu.magicsk.transi

import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.View.generateViewId
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.setMargins
import androidx.core.view.setPadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import eu.magicsk.transi.data.remote.responses.idsbk.Session
import eu.magicsk.transi.databinding.FragmentTimetablesBinding
import eu.magicsk.transi.util.animatedAlphaChange
import eu.magicsk.transi.util.dpToPx
import eu.magicsk.transi.util.getLineColor
import eu.magicsk.transi.util.getLineTextColor
import eu.magicsk.transi.util.isDarkTheme
import eu.magicsk.transi.view_models.MainViewModel
import eu.magicsk.transi.view_models.TimetablesViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TimetablesFragment : Fragment() {
    private var _binding: FragmentTimetablesBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTimetablesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.apply {
            val timetablesViewModel =
                ViewModelProvider(requireActivity())[TimetablesViewModel::class.java]
            val mainViewModel =
                ViewModelProvider(requireActivity())[MainViewModel::class.java]
            mainViewModel.idsbkSession.observe(viewLifecycleOwner) { idsbkSession ->
                idsbkSession?.let {
                    TimetablesErrorBtn.setOnClickListener {
                        fetchTimetables(timetablesViewModel, idsbkSession)
                    }
                    fetchTimetables(timetablesViewModel, idsbkSession)
                }
            }
        }
    }

    private fun fetchTimetables(
        timetablesViewModel: TimetablesViewModel,
        idsbkSession: Session
    ) {
        binding.apply {
            TimetablesError.isVisible = false
            TimetablesContent.isVisible = true
            CoroutineScope(Dispatchers.IO).launch {
                val timetables = timetablesViewModel.getTimetables(idsbkSession)
                if (timetables != null && timetables.routes.isNotEmpty()) {
                    timetables.routes.forEach { line ->
                        val context = root.context
                        val resources = root.resources
                        val lineBtn = TextView(context)
                        val rounded = line.route_type == 0 || line.route_type == 2
                        if (rounded) {
                            lineBtn.setBackgroundResource(R.drawable.round_shape)
                            if (line.route_type == 2) {
                                lineBtn.setPadding(5f.dpToPx(context))
                            } else {
                                lineBtn.setPadding(
                                    14f.dpToPx(context),
                                    5f.dpToPx(context),
                                    14f.dpToPx(context),
                                    5f.dpToPx(context)
                                )
                            }
                        } else {
                            lineBtn.setBackgroundResource(R.drawable.rounded_shape)
                        }
                        val drawable = lineBtn.background
                        @Suppress("DEPRECATION")
                        drawable.setColorFilter(
                            ContextCompat.getColor(
                                context,
                                getLineColor(line.short_name, isDarkTheme(resources))
                            ), PorterDuff.Mode.SRC
                        )
                        lineBtn.foreground = RippleDrawable(
                            ColorStateList.valueOf(
                                ContextCompat.getColor(
                                    context,
                                    R.color.colorControlHighlight
                                )
                            ),
                            null,
                            lineBtn.background
                        )

                        lineBtn.setTextColor(
                            ContextCompat.getColor(
                                context,
                                getLineTextColor(line.short_name)
                            )
                        )

                        val params = ViewGroup.MarginLayoutParams(
                            ViewGroup.MarginLayoutParams.WRAP_CONTENT,
                            ViewGroup.MarginLayoutParams.WRAP_CONTENT,
                        )
                        params.setMargins(5f.dpToPx(context))
                        lineBtn.layoutParams = params
                        lineBtn.textSize = 22f
                        lineBtn.typeface = Typeface.DEFAULT_BOLD
                        lineBtn.background = drawable
                        lineBtn.text = line.short_name
                        lineBtn.setOnClickListener {
                            val timetableBundle = Bundle()
                            timetableBundle.putInt("route_id", line.route_id)
                            timetableBundle.putString("short_name", line.short_name)
                            timetableBundle.putString("long_name", line.long_name)
                            findNavController().navigate(
                                R.id.action_navigationTimetables_to_navigationTimetable,
                                timetableBundle
                            )
                        }
                        lineBtn.id = generateViewId()
                        CoroutineScope(Dispatchers.Main).launch {
                            when (line.route_type) {
                                0 -> TimetableTramsLines.addView(lineBtn)
                                2 -> TimetableTrainsLines.addView(lineBtn)
                                3 -> {
                                    if (line.short_name.contains("N")) TimetableNightLinesLines.addView(
                                        lineBtn
                                    )
                                    else TimetableBusesLines.addView(lineBtn)
                                }

                                50 -> {
                                    if (line.short_name.contains("N")) TimetableNightLinesLines.addView(
                                        lineBtn
                                    )
                                    else TimetableTrolleybusesLines.addView(lineBtn)
                                }

                                else -> TimetableRegionalBusesLines.addView(lineBtn)
                            }
                        }

                    }
                } else {
                    CoroutineScope(Dispatchers.Main).launch {
                        TimetablesError.isVisible = true
                        TimetablesContent.isVisible = false
                    }
                }
                CoroutineScope(Dispatchers.Main).launch {
                    animatedAlphaChange(1F, 0F, 100, TimetablesLoadingIndicator)
                }
            }
        }
    }
}
