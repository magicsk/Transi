package eu.magicsk.transi.util

import eu.magicsk.transi.R
import java.lang.NumberFormatException

fun getLineColor(lineNum: String, dark: Boolean): Int {
    when (lineNum) {
        "1" -> return R.color.l1
        "3" -> return R.color.l3
        "4", "14" -> return R.color.l4
        "5", "80" -> return R.color.l5
        "6", "99" -> return R.color.l6
        "7" -> return R.color.l7
        "8" -> return R.color.l8
        "9" -> return R.color.l9
        "21" -> return R.color.l21
        "33" -> return R.color.l33
        "37" -> return R.color.l37
        "40" -> return R.color.l40
        "42" -> return R.color.l42
        "44" -> return R.color.l44
        "47" -> return R.color.l47
        "49" -> return R.color.l49
        "50" -> return R.color.l50
        "60" -> return R.color.l60
        "61" -> return R.color.l61
        "63", "88" -> return R.color.l63
        "64" -> return R.color.l64
        "68" -> return R.color.l68
        "71" -> return R.color.l71
        "72" -> return R.color.l72
        "83", "84" -> return R.color.l83
        "93", "94" -> return R.color.l93
        "95" -> return R.color.l95
        "96" -> return R.color.l96
        "98" -> return R.color.l98
        "570" -> return R.color.l141
        "245" -> return R.color.l245
        "255", "637" -> return R.color.l255
        "256" -> return R.color.l256
        "257" -> return R.color.l257
        "258" -> return R.color.l258
        "269" -> return R.color.l269
        "523", "636" -> return R.color.l523
        "525", "527" -> return R.color.l525
        "540", "550" -> return R.color.l540
        "610" -> return R.color.l610
        "620" -> return R.color.l620
        "632" -> return R.color.l632
        "720", "740" -> return R.color.l720
        "737" -> return R.color.l737
        "298", "299", "598", "599", "699", "798", "799" -> return R.color.night_regional
        "Záhoráčik" -> return R.color.train
        "►" -> return if (dark) R.color.gray else R.color.cardview_light_background
        else -> {
            if (lineNum.startsWith("S") || lineNum.startsWith("R")) return R.color.train
            if (lineNum.startsWith("AT")) return R.color.AT_train
            if (lineNum.startsWith("N")) return R.color.night
            if (lineNum.startsWith("X")) return R.color.replacement
            try {
                if (lineNum.toInt() > 200) return R.color.regio
            } catch (_: NumberFormatException) {}
            return R.color.ldefault
        }
    }
}

fun getLineTextColor(lineNum: String): Int {
    when (lineNum) {
        "7" -> return R.color.regio_text
        "N21" -> return R.color.ln21
        "N29" -> return R.color.ln29
        "N31" -> return R.color.ln31
        "N33" -> return R.color.ln33
        "N34" -> return R.color.ln34
        "N37" -> return R.color.ln37
        "N44" -> return R.color.ln44
        "N47" -> return R.color.ln47
        "N53" -> return R.color.ln53
        "N55" -> return R.color.ln55
        "N56" -> return R.color.ln56
        "N61" -> return R.color.ln61
        "N70" -> return R.color.ln70
        "N72" -> return R.color.ln72
        "N74" -> return R.color.ln74
        "N80" -> return R.color.ln80
        "N91" -> return R.color.ln91
        "N93" -> return R.color.ln93
        "N95" -> return R.color.ln95
        "N99" -> return R.color.ln99
        "►" -> return R.color.material_on_surface_emphasis_high_type
        "298", "299", "598", "599", "699", "798", "799" -> return R.color.white
        else -> {
            if (lineNum.startsWith("X")) return R.color.replacement_text
            try {
                if (lineNum.toInt() > 200) return R.color.regio_text
            } catch (_: NumberFormatException) {}
            return R.color.white
        }
    }
}