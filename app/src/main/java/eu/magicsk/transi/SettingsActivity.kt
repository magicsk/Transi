package eu.magicsk.transi

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import dagger.hilt.android.AndroidEntryPoint
import eu.magicsk.transi.databinding.ActivitySettingsBinding
import eu.magicsk.transi.util.fitUnderStatusBar

@AndroidEntryPoint
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // The toolbar shows the activity title, which sub-screens such as PassZonesFragment set; a title in the
        // layout would override it.
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        // The list in SettingsFragment takes the bottom inset.
        binding.toolbar.fitUnderStatusBar()
    }
}
