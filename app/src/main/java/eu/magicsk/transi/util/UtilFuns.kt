package eu.magicsk.transi.util

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.PorterDuff
import android.util.TypedValue
import android.view.View
import android.view.animation.AlphaAnimation
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.setPadding
import eu.magicsk.transi.R
import org.json.JSONArray
import java.text.Normalizer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

fun isDarkTheme(resources: Resources): Boolean {
    return when (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
        Configuration.UI_MODE_NIGHT_YES -> true
        else -> {
            false
        }
    }
}

fun Float.dpToPx(context: Context): Int {
    return TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        this,
        context.resources.displayMetrics
    ).roundToInt()
}

private val REGEX_UNACCENT = "\\p{InCombiningDiacriticalMarks}+".toRegex()

fun CharSequence.unaccent(): String {
    val temp = Normalizer.normalize(this, Normalizer.Form.NFD)
    return REGEX_UNACCENT.replace(temp, "")
}

fun JSONArray.size(): Int {
    return this.length() - 1
}

sealed class Either<out L, out R> {

    data class Left<out L>(val a: L) : Either<L, Nothing>()

    data class Right<out R>(val b: R) : Either<Nothing, R>()

    val isLeft: Boolean get() = this is Left<L>
    val isRight: Boolean get() = this is Right<R>
}

fun getDate(plusDays: Long = 0, format: String = "yyyyMMdd"): String {
    var current = LocalDateTime.now()
    current = current.plusDays(plusDays)
    val dateFormat = DateTimeFormatter.ofPattern(format)
    return current.format(dateFormat)
}

fun getMinutes(): Int {
    val current = LocalDateTime.now()
    return current.minute + current.hour * 60

}

fun animatedAlphaChange(from: Float, to: Float, offset: Long, View: View) {
    val animation = AlphaAnimation(from, to)
    animation.duration = 200
    animation.startOffset = offset
    animation.fillAfter = true
    View.startAnimation(animation)
}

fun customizeLineText(textView: TextView, lineNum: String, context: Context, resources: Resources) {
    val rounded =
        try {
            lineNum.startsWith("S") || lineNum.startsWith("R") || lineNum.startsWith("AT") || lineNum.toInt() < 10
        } catch (e: NumberFormatException) {
            false
        }
    if (rounded) {
        textView.setBackgroundResource(R.drawable.round_shape)
        if (!lineNum.startsWith("S") && !lineNum.startsWith("R") && !lineNum.startsWith("AT")) textView.setPadding(
            12f.dpToPx(context),
            5f.dpToPx(context),
            12f.dpToPx(context),
            5f.dpToPx(context)
        ) else {
            textView.setPadding(5f.dpToPx(context))
        }
    } else {
        textView.setBackgroundResource(R.drawable.rounded_shape)
    }
    val drawable = textView.background
    @Suppress("DEPRECATION")
    drawable.setColorFilter(
        ContextCompat.getColor(
            context,
            getLineColor(lineNum, isDarkTheme(resources))
        ), PorterDuff.Mode.SRC
    )
    textView.setTextColor(
        ContextCompat.getColor(
            context,
            getLineTextColor(lineNum)
        )
    )
    textView.background = drawable
    textView.text = lineNum
}
