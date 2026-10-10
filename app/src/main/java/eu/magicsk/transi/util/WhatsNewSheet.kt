package eu.magicsk.transi.util

import android.os.Bundle
import android.view.View
import androidx.annotation.DrawableRes
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.fragment.app.FragmentActivity
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import eu.magicsk.transi.BuildConfig
import eu.magicsk.transi.R
import eu.magicsk.transi.databinding.WhatsNewCategoryBinding
import eu.magicsk.transi.databinding.WhatsNewItemBinding
import eu.magicsk.transi.databinding.WhatsNewSheetBinding
import io.noties.markwon.Markwon

class ChangelogCategory(val title: String) {
    val items = mutableListOf<String>()

    @get:DrawableRes
    val icon: Int
        get() {
            val title = title.lowercase()
            return when {
                "feature" in title -> R.drawable.ic_auto_awesome
                "fix" in title -> R.drawable.ic_bug_report
                "improvement" in title -> R.drawable.ic_bolt
                else -> R.drawable.ic_pending
            }
        }
}

/** Parses GitHub release notes: `#` lines start a category, other non-empty lines become its items. */
fun parseChangelog(markdown: String): List<ChangelogCategory> {
    val categories = mutableListOf<ChangelogCategory>()
    for (line in markdown.lines().map { it.trim() }) {
        if (line.startsWith("#")) {
            categories += ChangelogCategory(line.trim('#', ' '))
        } else if (line.isNotEmpty()) {
            categories.lastOrNull()?.items?.add(line.removePrefix("- ").replaceFirstChar { it.uppercase() })
        }
    }
    return categories.filter { it.items.isNotEmpty() }
}

/** The after-update counterpart of [UpdateAlert]: the running version's release notes, shown once. */
class WhatsNewSheet : BottomSheetDialogFragment(R.layout.whats_new_sheet) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val version = requireArguments().getString(ARG_VERSION).orEmpty()
        // Marked seen only once actually shown, so a launch that closes before the sheet appears retries.
        Prefs.get(requireContext()).edit { putString(Prefs.CHANGELOG_VERSION, version) }
        requireDialog().setTitle(R.string.whats_new_title)
        (requireDialog() as BottomSheetDialog).behavior.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }

        val binding = WhatsNewSheetBinding.bind(view)
        ViewCompat.setAccessibilityHeading(binding.whatsNewTitle, true)
        binding.whatsNewVersion.text = getString(R.string.whats_new_version, version)
        val markwon = Markwon.create(requireContext())
        for (category in parseChangelog(requireArguments().getString(ARG_NOTES).orEmpty())) {
            val row = WhatsNewCategoryBinding.inflate(layoutInflater, binding.whatsNewCategories, true)
            row.whatsNewCategoryIcon.setImageResource(category.icon)
            row.whatsNewCategoryTitle.text = category.title
            ViewCompat.setAccessibilityHeading(row.whatsNewCategoryTitle, true)
            for (item in category.items) {
                val text = WhatsNewItemBinding.inflate(layoutInflater, row.whatsNewCategoryText, true).root
                markwon.setMarkdown(text, item)
                // Markwon makes every row clickable; only rows with a link should be actionable for TalkBack.
                if (text.urls.isEmpty()) text.movementMethod = null
            }
        }
        binding.whatsNewContinue.setOnClickListener { dismiss() }
    }

    companion object {
        private const val TAG = "whatsNew"
        private const val ARG_VERSION = "version"
        private const val ARG_NOTES = "notes"

        /** Shows the running version's release [notes] once after an update; call when they are known. */
        fun showOnce(activity: FragmentActivity, notes: String?) {
            val prefs = Prefs.get(activity)
            val version = BuildConfig.VERSION_NAME
            if (prefs.getString(Prefs.CHANGELOG_VERSION, null) == version) return
            if (parseChangelog(notes.orEmpty()).isEmpty()) {
                prefs.edit { putString(Prefs.CHANGELOG_VERSION, version) }
                return
            }
            val fragmentManager = activity.supportFragmentManager
            if (fragmentManager.findFragmentByTag(TAG) != null) return
            WhatsNewSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_VERSION, version)
                    putString(ARG_NOTES, notes)
                }
            }.show(fragmentManager, TAG)
        }
    }
}
