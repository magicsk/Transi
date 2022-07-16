package eu.magicsk.transi.data.models

import org.json.JSONException
import org.json.JSONObject

class MHDTable {
    val tabs = mutableListOf<MHDTableData>()
    val sortedTabs = mutableListOf<MHDTableData>()
    val vehicleInfo = mutableListOf<MHDTableVehicle>()

    fun addTabs(data: JSONObject): MutableList<MHDTableData> {
        tabs.clear()
        val keys = data.keys()
        while (keys.hasNext()) {
            val platform: MutableList<MHDTableData> = mutableListOf()
            val key = keys.next()
            val tab = data.getJSONObject(key).getJSONArray("tab")
            for (i in 0 until tab.length()) {
                val item = MHDTableData(
                    try {
                        tab.getJSONObject(i).getLong("i")
                    } catch (e: JSONException) {
                        0
                    },
                    try {
                        tab.getJSONObject(i).getString("linka")
                    } catch (e: JSONException) {
                        "Err"
                    },
                    key,
                    try {
                        tab.getJSONObject(i).getString("issi")
                    } catch (e: JSONException) {
                        "offline"
                    },
                    try {
                        tab.getJSONObject(i).getString("cielStr")
                    } catch (e: JSONException) {
                        try {
                            tab.getJSONObject(i).getString("konecnaZstr")
                        } catch (e: JSONException) {
                            "Error"
                        }
                    },
                    try {
                        tab.getJSONObject(i).getLong("cas")
                    } catch (e: JSONException) {
                        0
                    },
                    try {
                        tab.getJSONObject(i).getInt("casDelta")
                    } catch (e: JSONException) {
                        0
                    },
                    try {
                        tab.getJSONObject(i).getString("typ")
                    } catch (e: JSONException) {
                        "cp"
                    },
                    try {
                        tab.getJSONObject(i).getInt("tuZidx")
                    } catch (e: JSONException) {
                        -1
                    },
                    try {
                        tab.getJSONObject(i).getInt("predoslaZidx")
                    } catch (e: JSONException) {
                        -1
                    },
                    try {
                        tab.getJSONObject(i).getString("predoslaZstr")
                            .replace("Bratislava, ", "")
                    } catch (e: JSONException) {
                        "none"
                    },
                    try {
                        tab.getJSONObject(i).getBoolean("uviaznute")
                    } catch (e: JSONException) {
                        false
                    },
                    false
                )
                platform.add(item)
            }
            tabs.addAll(platform)
        }
        return tabs
    }

    fun addVehicleInfo(data: JSONObject){
        val keys = data.keys()
        val item = MHDTableVehicle(
            try {
                data.getInt(keys.next())
            } catch (e: JSONException) {
                0
            },
            try {
                data.getInt(keys.next())
            } catch (e: JSONException) {
                0
            },
            try {
                data.getInt(keys.next())
            } catch (e: JSONException) {
                0
            },
            try {
                data.getInt(keys.next())
            } catch (e: JSONException) {
                0
            },
            try {
                data.getInt(keys.next())
            } catch (e: JSONException) {
                0
            },
            try {
                data.getString(keys.next())
            } catch (e: JSONException) {
                "error"
            },
            train = try {
                data.getString("train")
            } catch (e: JSONException) {
                null
            },
            issi = try {
                data.getString("issi")
            } catch (e: JSONException) {
                "error"
            }
        )
        vehicleInfo.add(item)
    }
}