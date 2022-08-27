package eu.magicsk.transi

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.widget.Toast.LENGTH_LONG
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import eu.magicsk.transi.databinding.AlertUpdateBinding
import io.noties.markwon.Markwon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL

class UpdateAlert(version: String, private val changelog: String, private val downloadUrl: String) : DialogFragment() {
    private var _binding: AlertUpdateBinding? = null
    private val binding get() = _binding!!
    private val fileName = "eu.magicsk.transi.$version.apk"

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        dialog!!.window?.setBackgroundDrawableResource(R.drawable.round_shape)
        _binding = AlertUpdateBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        val width = (resources.displayMetrics.widthPixels * 0.875).toInt()
        dialog!!.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.apply {
            val markwon = context?.let { Markwon.create(it) }
            markwon?.setMarkdown(UpdateAlertText, changelog)
            UpdateAlertCancelButton.setOnClickListener {
                dismissNow()
            }
            UpdateAlertConfirmButton.setOnClickListener {
                UpdateAlertTitle.text = context?.getString(R.string.update_in_progress)
                UpdateAlertText.text = context?.getString(R.string.downloading)?.format("0%")
                UpdateAlertProgress.isVisible = true
                UpdateAlertConfirmButton.isVisible = false
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        UpdateAlertCancelButton.setOnClickListener {
                            dismiss()
                        }
                        val path =
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                                .toString() + "/"
                        var outputFile = File("$path$fileName.apk")
                        var repetition = 1
                        while (outputFile.exists()) {
                            outputFile = File("$path$fileName ($repetition).apk")
                            repetition++
                        }

                        val directory = File(path)
                        if (!directory.exists()) {
                            directory.mkdirs()
                        }

                        val url = URL(downloadUrl)
                        val connection = url.openConnection() as HttpURLConnection
                        connection.requestMethod = "GET"
                        connection.connect()

                        val outputStream = FileOutputStream(outputFile)
                        val inputStream = connection.inputStream
                        val totalSize = connection.contentLength.toFloat() //size of apk

                        val buffer = ByteArray(1024)
                        var lengthOfFile: Int
                        var progress: Float
                        var downloaded = 0f
                        UpdateAlertCancelButton.setOnClickListener {
                            dismiss()
                            lifecycleScope.launch(Dispatchers.IO) {
                                connection.disconnect()
                                outputStream.close()
                                inputStream.close()
                            }
                        }
                        while (inputStream.read(buffer).also { lengthOfFile = it } != -1) {
                            outputStream.write(buffer, 0, lengthOfFile)
                            downloaded += lengthOfFile
                            progress = (downloaded * 100 / totalSize)
                            activity?.runOnUiThread {
                                UpdateAlertText.text = context?.getString(R.string.downloading)?.format("${progress.toInt()}%")
                                UpdateAlertProgress.progress = progress.toInt()
                            }
                        }
                        outputStream.close()
                        inputStream.close()
                        activity?.runOnUiThread {
                            UpdateAlertTitle.text = context?.getString(R.string.update_downloaded)
                            UpdateAlertText.text = context?.getString(R.string.finish_update)
                            UpdateAlertConfirmButton.text = context?.getString(R.string.install)
                            UpdateAlertConfirmButton.isVisible = true
                            UpdateAlertCancelButton.isVisible = false
                            UpdateAlertConfirmButton.setOnClickListener {
                                lifecycleScope.launch(Dispatchers.IO) {
                                    openNewVersion(outputFile.path)
                                }
                            }
                        }
                        openNewVersion(outputFile.path)
                    } catch (e: MalformedURLException) {
                        dismiss()
                        activity?.runOnUiThread {
                            Toast.makeText(context, "Update Error: " + e.message, LENGTH_LONG).show()
                        }
                    } catch (e: IOException) {
                        dismiss()
                        activity?.runOnUiThread {
                            Toast.makeText(context, "Update canceled", LENGTH_LONG).show()
                        }
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun openNewVersion(location: String) {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(
            getUriFromFile(location),
            "application/vnd.android.package-archive"
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context?.startActivity(intent)
    }

    private fun getUriFromFile(filePath: String): Uri? {
        return context?.let {
            FileProvider.getUriForFile(
                it,
                it.packageName + ".provider",
                File(filePath)
            )
        }
    }
}
