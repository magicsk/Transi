package eu.magicsk.transi.util

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.AlphaAnimation
import android.widget.TextView
import androidx.core.content.ContextCompat
import org.json.JSONArray
import java.text.Normalizer
import java.time.LocalDateTime
import java.time.LocalTime
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

fun addOneMinute(timeString: String): String {
    return try {
        val time = LocalTime.parse(timeString)
        time.plusMinutes(1).toString().substring(0, 5)
    } catch (e: Exception) {
        timeString
    }
}

fun animatedAlphaChange(from: Float, to: Float, offset: Long, View: View) {
    val animation = AlphaAnimation(from, to)
    animation.duration = 200
    animation.startOffset = offset
    animation.fillAfter = true
    View.startAnimation(animation)
}

/** iOS LineText: a capsule below line 20 and for trains, a 4dp rounded rectangle otherwise; one-digit lines share a width. */
fun customizeLineText(textView: TextView, lineNum: String, context: Context, resources: Resources) {
    val size = textView.textSize
    val number = lineNum.toIntOrNull() ?: 99
    textView.background = GradientDrawable().apply {
        setColor(ContextCompat.getColor(context, getLineColor(lineNum, isDarkTheme(resources))))
        // GradientDrawable caps the radius at half the height, which makes the capsule.
        cornerRadius = if (number < 20 || isTrain(lineNum)) size else 4f.dpToPx(context).toFloat()
    }
    val vertical = (size * 0.14f).roundToInt()
    val horizontal = (size * if (lineNum == "►") 0.55f else 0.21f).roundToInt()
    textView.setPadding(horizontal, vertical, horizontal, vertical)
    if (number < 10) {
        textView.width = (size * 1.57f).roundToInt()
    } else {
        textView.minWidth = 0
        textView.maxWidth = Int.MAX_VALUE
    }
    textView.gravity = Gravity.CENTER
    textView.setTextColor(ContextCompat.getColor(context, getLineTextColor(lineNum)))
    textView.text = lineNum
}
