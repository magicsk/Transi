package eu.magicsk.transi.util

import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/** The insets a top app bar (Widget.Transi.TopAppBar) keeps clear of when drawn edge to edge. */
val TOP_APP_BAR_INSETS = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()

/** Extends the bar's background under the status bar and keeps its content below it and clear of side cutouts. */
fun View.padUnderStatusBar(bars: Insets) = updatePadding(left = bars.left, top = bars.top, right = bars.right)

/** [padUnderStatusBar] whenever the insets change, for a bar no other listener handles. */
fun View.fitUnderStatusBar() = ViewCompat.setOnApplyWindowInsetsListener(this) { bar, insets ->
    bar.padUnderStatusBar(insets.getInsets(TOP_APP_BAR_INSETS))
    insets
}
