package eu.magicsk.transi.util

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.util.TypedValue
import org.json.JSONArray
import java.text.Normalizer
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
    return this.length()-1
}

sealed class Either<out L, out R> {

    data class Left<out L>(val a: L) : Either<L, Nothing>()

    data class Right<out R>(val b: R) : Either<Nothing, R>()

    val isLeft: Boolean get() = this is Left<L>
    val isRight: Boolean get() = this is Right<R>
}