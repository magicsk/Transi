package eu.magicsk.transi.util

import android.app.Activity
import android.app.AlertDialog

fun simpleErrorAlert(activity: Activity, title: String, message: String) {
    val errorAlertBuilder = AlertDialog.Builder(activity)
    errorAlertBuilder.setTitle(title)
        .setPositiveButton("OK") { dialog, _ ->
            dialog.cancel()
        }
    val errorAlert = errorAlertBuilder.create()
    errorAlert.setMessage(message)
    errorAlert.show()
}