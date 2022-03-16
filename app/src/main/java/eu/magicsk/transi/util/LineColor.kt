package eu.magicsk.transi.util

import eu.magicsk.transi.R

fun getLineColor(lineNum: String, dark: Boolean): Int {
    when (lineNum) {
        "1", "95" -> return R.color.l1
        "2" -> return R.color.l2
        "3" -> return R.color.l3
        "4", "14" -> return R.color.l4
        "5", "80" -> return R.color.l5
        "6", "99" -> return R.color.l6
        "7", "522" -> return R.color.l7
        "8" -> return R.color.l8
        "9", "72", "202" -> return R.color.l9
        "21" -> return R.color.l21
        "31", "39" -> return R.color.l31
        "33" -> return R.color.l33
        "50" -> return R.color.l50
        "61" -> return R.color.l61
        "68" -> return R.color.l68
        "83", "84" -> return R.color.l83
        "88" -> return R.color.l88
        "93", "94" -> return R.color.l93
        "98" -> return R.color.l98
        "141", "570" -> return R.color.l141
        "71", "201" -> return R.color.l201
        "44", "203" -> return R.color.l203
        "32", "62", "204" -> return R.color.l204
        "60", "205" -> return R.color.l205
        "47", "207" -> return R.color.l207
        "46", "209" -> return R.color.l209
        "40", "210" -> return R.color.l210
        "42", "212" -> return R.color.l212
        "245", "638" -> return R.color.l245
        "255", "637" -> return R.color.l255
        "256" -> return R.color.l256
        "257" -> return R.color.l257
        "258" -> return R.color.l258
        "269", "524", "635" -> return R.color.l269
        "523", "636" -> return R.color.l523
        "525", "527", "639" -> return R.color.l525
        "530" -> return R.color.l530
        "540", "550" -> return R.color.l540
        "610" -> return R.color.l610
        "632" -> return R.color.l632
        "720", "740" -> return R.color.l720
        "737" -> return R.color.l737
        "298", "299", "598", "599", "699", "798", "799" -> return R.color.night_regional
        "Záhoráčik" -> return R.color.train
        "►" -> return if (dark) R.color.gray else R.color.cardview_light_background
        else -> {
            if (lineNum.contains("S")) return R.color.train
            if (lineNum.contains("N")) return R.color.night
            if (lineNum.contains("X")) return R.color.replacement
            return R.color.ldefault
        }
    }
}

fun getLineTextColor(lineNum: String): Int {
    when (lineNum) {
        "N21" -> return R.color.l21
        "N31" -> return R.color.l31
        "N33" -> return R.color.l33
        "N47" -> return R.color.l207
        "N61" -> return R.color.l61
        "N72" -> return R.color.l9
        "N80" -> return R.color.l5
        "N93" -> return R.color.l93
        "N95" -> return R.color.l1
        "N99" -> return R.color.l6
        "►" -> return R.color.material_on_surface_emphasis_high_type
        else -> {
            if (lineNum.contains("X")) return R.color.replacement_text
            return R.color.white
        }
    }
}