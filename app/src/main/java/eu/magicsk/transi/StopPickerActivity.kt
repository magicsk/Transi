package eu.magicsk.transi

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.commit
import eu.magicsk.transi.databinding.ActivityStopPickerBinding
import eu.magicsk.transi.util.TOP_APP_BAR_INSETS
import eu.magicsk.transi.util.padUnderStatusBar

/** Picks a stop for Settings; the result is its id, -1 for Actual location. */
class StopPickerActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val binding = ActivityStopPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { root, insets ->
            val bars = insets.getInsets(TOP_APP_BAR_INSETS)
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            // The bar reaches under the status bar; the rest stays clear of the bars and the keyboard.
            binding.pickerToolbar.padUnderStatusBar(bars)
            root.updatePadding(left = bars.left, right = bars.right, bottom = maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
        binding.pickerToolbar.setNavigationOnClickListener { finish() }
        // The list scrolls inside the card's rounded corners.
        binding.pickerContainer.clipToOutline = true
        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                replace(
                    R.id.pickerContainer,
                    TypeAheadFragment().apply {
                        arguments = Bundle().apply { putString("origin", TypeAheadFragment.ORIGIN_PICKER) }
                    }
                )
            }
        }
    }

    class PickStop : ActivityResultContract<Unit, Int?>() {
        override fun createIntent(context: Context, input: Unit) = Intent(context, StopPickerActivity::class.java)

        override fun parseResult(resultCode: Int, intent: Intent?): Int? =
            intent?.takeIf { resultCode == Activity.RESULT_OK && it.hasExtra(EXTRA_STOP_ID) }?.getIntExtra(EXTRA_STOP_ID, -1)
    }

    companion object {
        const val EXTRA_STOP_ID = "stopId"
    }
}
