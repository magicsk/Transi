package eu.magicsk.transi

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.preference.DialogPreference
import androidx.preference.EditTextPreference
import androidx.preference.EditTextPreferenceDialogFragmentCompat
import androidx.preference.ListPreference
import androidx.preference.ListPreferenceDialogFragmentCompat
import androidx.preference.Preference
import androidx.preference.PreferenceDialogFragmentCompat
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

private const val DIALOG_TAG = "eu.magicsk.transi.PreferenceDialog"

/**
 * Shows the list and text preference dialogs as Material 3 dialogs; false for the others, which keep the library's.
 * Call from [PreferenceFragmentCompat.onDisplayPreferenceDialog].
 */
fun PreferenceFragmentCompat.showMaterialPreferenceDialog(preference: Preference): Boolean {
    val dialog = when (preference) {
        is ListPreference -> MaterialListPreferenceDialog()
        is EditTextPreference -> MaterialEditTextPreferenceDialog()
        else -> return false
    }
    if (parentFragmentManager.findFragmentByTag(DIALOG_TAG) != null) return true
    dialog.arguments = bundleOf("key" to preference.key)
    // The library's dialogs find their preference through the target fragment.
    @Suppress("DEPRECATION")
    dialog.setTargetFragment(this, 0)
    dialog.show(parentFragmentManager, DIALOG_TAG)
    return true
}

/** The library's dialog, built by [MaterialAlertDialogBuilder] instead of the AppCompat builder. */
private fun PreferenceDialogFragmentCompat.materialDialog(
    contentView: android.view.View?,
    bind: (android.view.View) -> Unit,
    prepare: (AlertDialog.Builder) -> Unit,
): Dialog {
    val preference = preference as DialogPreference
    val listener = this as DialogInterface.OnClickListener
    val builder = MaterialAlertDialogBuilder(requireContext())
        .setTitle(preference.dialogTitle ?: preference.title)
        .setIcon(preference.dialogIcon)
        .setPositiveButton(preference.positiveButtonText, listener)
        .setNegativeButton(preference.negativeButtonText, listener)
    if (contentView != null) {
        bind(contentView)
        builder.setView(contentView)
    } else {
        builder.setMessage(preference.dialogMessage)
    }
    prepare(builder)
    return builder.create()
}

class MaterialListPreferenceDialog : ListPreferenceDialogFragmentCompat() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        materialDialog(onCreateDialogView(requireContext()), ::onBindDialogView, ::onPrepareDialogBuilder)
}

class MaterialEditTextPreferenceDialog : EditTextPreferenceDialogFragmentCompat() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        materialDialog(onCreateDialogView(requireContext()), ::onBindDialogView, ::onPrepareDialogBuilder).apply {
            // Opens with the keyboard, as the library's dialog does.
            window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
}
