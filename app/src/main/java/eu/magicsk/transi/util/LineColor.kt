package eu.magicsk.transi.util

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import eu.magicsk.transi.R

/** iOS colorFromLineNum. */
@ColorRes
fun getLineColor(lineNum: String, dark: Boolean): Int = when (lineNum) {
    "1" -> R.color.l1
    "3" -> R.color.l3
    "4", "14" -> R.color.l4
    "80" -> R.color.l5
    "99" -> R.color.l6
    "7" -> R.color.l7
    "8" -> R.color.l8
    "9" -> R.color.l9
    "21" -> R.color.l21
    "33" -> R.color.l33
    "37" -> R.color.l37
    "40" -> R.color.l40
    "42" -> R.color.l42
    "44" -> R.color.l44
    "47" -> R.color.l47
    "49" -> R.color.l49
    "50" -> R.color.l50
    "60" -> R.color.l60
    "61" -> R.color.l61
    "63", "88" -> R.color.l63
    "64" -> R.color.l64
    "68" -> R.color.l68
    "71" -> R.color.l71
    "72" -> R.color.l72
    "83", "84" -> R.color.l83
    "93", "94" -> R.color.l93
    "95" -> R.color.l95
    "96" -> R.color.l96
    "98" -> R.color.l98
    "570" -> R.color.l141
    "245" -> R.color.l245
    "255", "637" -> R.color.l255
    "256" -> R.color.l256
    "257" -> R.color.l257
    "258" -> R.color.l258
    "269" -> R.color.l269
    "523", "636" -> R.color.l523
    "525", "527" -> R.color.l525
    "540", "550" -> R.color.l540
    "610" -> R.color.l610
    "620" -> R.color.l620
    "632" -> R.color.l632
    "720", "740" -> R.color.l720
    "737" -> R.color.l737
    "298", "299", "598", "599", "699", "798", "799" -> R.color.night_regional
    // iOS secondaryLabel.
    "►" -> if (dark) R.color.line_direction_dark else R.color.line_direction
    else -> when {
        lineNum.startsWith("S") || lineNum.startsWith("R") -> R.color.train
        lineNum.startsWith("AT") -> R.color.AT_train
        lineNum.startsWith("N") -> R.color.night
        lineNum.startsWith("X") -> R.color.replacement
        (lineNum.toIntOrNull() ?: 0) > 200 -> R.color.regio
        else -> R.color.ldefault
    }
}

/** iOS textColorFromLineNum. */
@ColorRes
fun getLineTextColor(lineNum: String): Int = when (lineNum) {
    "7" -> R.color.black
    "N21" -> R.color.ln21
    "N29" -> R.color.ln29
    "N31" -> R.color.ln31
    "N33" -> R.color.ln33
    "N34" -> R.color.ln34
    "N37" -> R.color.ln37
    "N44" -> R.color.ln44
    "N47" -> R.color.ln47
    "N53" -> R.color.ln53
    "N55" -> R.color.ln55
    "N56" -> R.color.ln56
    "N61" -> R.color.ln61
    "N70" -> R.color.ln70
    "N72" -> R.color.ln72
    "N74" -> R.color.ln74
    "N80" -> R.color.ln80
    "N91" -> R.color.ln91
    "N93" -> R.color.ln93
    "N95" -> R.color.ln95
    "N99" -> R.color.ln99
    "298", "299", "598", "599", "699", "798", "799" -> R.color.white
    else -> if (lineNum.startsWith("X") || (lineNum.toIntOrNull() ?: 0) > 200) R.color.black else R.color.white
}

/** DPB city lines: 1–199, night (N…) and replacement (X…) lines. */
fun isCityLine(lineNum: String) =
    lineNum.startsWith("N") || lineNum.startsWith("X") || (lineNum.toIntOrNull() ?: 0) in 1..199

/** S and R trains (like "S20", "R 805") and AT lines into Austria. */
fun isTrain(lineNum: String) = lineNum.startsWith("S") || lineNum.startsWith("R") || lineNum.startsWith("AT")

/** The vehicle a line runs, for where its badge has no room: trains, trams (below line 20), else buses. */
@DrawableRes
fun vehicleIcon(lineNum: String): Int = when {
    isTrain(lineNum) -> R.drawable.ic_train
    (lineNum.toIntOrNull() ?: 99) < 20 -> R.drawable.ic_tram
    else -> R.drawable.ic_bus
}
