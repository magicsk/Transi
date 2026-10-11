package eu.magicsk.transi

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.flexbox.FlexboxLayout
import eu.magicsk.transi.data.remote.responses.idsbk.Route
import eu.magicsk.transi.databinding.FragmentTimetablesBinding
import eu.magicsk.transi.databinding.TimetablesSectionBinding
import eu.magicsk.transi.timetable.ARG_OFFLINE
import eu.magicsk.transi.timetable.ARG_ROUTE_ID
import eu.magicsk.transi.timetable.ARG_SHORT_NAME
import eu.magicsk.transi.timetable.TimetableCategory
import eu.magicsk.transi.timetable.TimetableDownloadState
import eu.magicsk.transi.timetable.showTimetableError
import eu.magicsk.transi.timetable.text
import eu.magicsk.transi.util.customizeLineText
import eu.magicsk.transi.util.dpToPx
import eu.magicsk.transi.view_models.MainViewModel
import eu.magicsk.transi.view_models.TimetableRoutes
import eu.magicsk.transi.view_models.TimetablesViewModel
import kotlinx.coroutines.launch

class TimetablesFragment : Fragment(R.layout.fragment_timetables) {
    private val viewModel: TimetablesViewModel by activityViewModels()
    private val mainViewModel: MainViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentTimetablesBinding.bind(view)
        ViewCompat.setAccessibilityHeading(binding.TimetablesTitle, true)

        viewModel.routesLoading.observe(viewLifecycleOwner) { loading ->
            if (loading) binding.TimetablesLoading.show() else binding.TimetablesLoading.hide()
        }
        viewModel.routes.observe(viewLifecycleOwner) {
            renderRoutes(binding, it)
            openRequestedLine()
        }
        mainViewModel.timetableLine.observe(viewLifecycleOwner) { openRequestedLine() }
        viewModel.routesFailed.observe(viewLifecycleOwner) { failed ->
            if (failed) {
                showTimetableError(
                    R.string.timetables_error,
                    onRetry = {
                        viewModel.dismissRoutesError()
                        viewModel.loadRoutes(mainViewModel.idsbkSession.value)
                    },
                    onCancel = viewModel::dismissRoutesError,
                )
            }
        }
        mainViewModel.idsbkSession.observe(viewLifecycleOwner) { viewModel.loadRoutes(it) }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.downloadState.collect { state ->
                    renderBanner(binding, state)
                    // Switches the list to the offline database once it is ready, and back when it is closed.
                    viewModel.loadRoutes(mainViewModel.idsbkSession.value)
                }
            }
        }
    }

    private fun renderBanner(binding: FragmentTimetablesBinding, state: TimetableDownloadState) {
        // The bar shows the percentage; the text stays put so TalkBack announces only state changes.
        val text = if (viewModel.showDownloadBanner) state.text(requireContext(), withProgress = false) else null
        binding.TimetablesBanner.isVisible = text != null
        binding.TimetablesBannerText.text = text
        binding.TimetablesBannerIcon.isVisible = state is TimetableDownloadState.Error
        binding.TimetablesBannerProgress.isVisible = state !is TimetableDownloadState.Error
        val percent = (state as? TimetableDownloadState.Downloading)?.percent
        binding.TimetablesBannerProgress.isIndeterminate = percent == null
        if (percent != null) binding.TimetablesBannerProgress.setProgressCompat(percent, true)
    }

    private fun renderRoutes(binding: FragmentTimetablesBinding, data: TimetableRoutes) {
        binding.TimetablesSections.removeAllViews()
        val byCategory = data.routes.groupBy { TimetableCategory.of(it.route_type, it.short_name) }
        TimetableCategory.entries.forEach { category ->
            val routes = byCategory[category] ?: return@forEach
            val section = TimetablesSectionBinding.inflate(layoutInflater, binding.TimetablesSections, true)
            section.TimetablesSectionTitle.setText(category.title)
            ViewCompat.setAccessibilityHeading(section.TimetablesSectionTitle, true)
            routes.forEach { section.TimetablesSectionLines.addView(lineBadge(it, data.offline)) }
        }
    }

    private fun lineBadge(route: Route, offline: Boolean): TextView {
        val context = requireContext()
        return TextView(context).apply {
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minWidth = 48f.dpToPx(context)
            minHeight = 48f.dpToPx(context)
            customizeLineText(this, route.short_name, context, resources)
            foreground = RippleDrawable(
                ColorStateList.valueOf(ContextCompat.getColor(context, R.color.colorControlHighlight)),
                null,
                background,
            )
            contentDescription = getString(R.string.timetable_line_description, route.short_name)
            layoutParams = FlexboxLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(4f.dpToPx(context), 4f.dpToPx(context), 4f.dpToPx(context), 4f.dpToPx(context)) }
            setOnClickListener { openLine(route, offline) }
        }
    }

    private fun openLine(route: Route, offline: Boolean) = findNavController().navigate(
        R.id.action_navigationTimetables_to_navigationTimetable,
        Bundle().apply {
            putInt(ARG_ROUTE_ID, route.route_id)
            putString(ARG_SHORT_NAME, route.short_name)
            putBoolean(ARG_OFFLINE, offline)
        },
    )

    // iOS pendingTimetableLine: a linked line opens once the routes are loaded; an unknown one leaves the list.
    private fun openRequestedLine() {
        val line = mainViewModel.timetableLine.value ?: return
        val data = viewModel.routes.value ?: return
        mainViewModel.timetableLine.value = null
        data.routes.firstOrNull { it.short_name == line }?.let { openLine(it, data.offline) }
    }
}
