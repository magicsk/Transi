package eu.magicsk.transi.util

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.widget.Toast.LENGTH_LONG
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import eu.magicsk.transi.R
import eu.magicsk.transi.databinding.AlertBinding
import io.noties.markwon.Markwon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

// GitHub release assets redirect from github.com to one of the asset hosts.
private val updateHosts = setOf("github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")

private fun httpsHost(url: String): String? {
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    return uri.host?.lowercase()?.takeIf { uri.scheme.equals("https", ignoreCase = true) }
}

fun isAllowedUpdateUrl(url: String) = httpsHost(url) in updateHosts

// GitHub has moved release assets to new githubusercontent.com hosts before, so the final URL after redirects may use any of them.
fun isAllowedFinalUpdateUrl(url: String): Boolean {
    val host = httpsHost(url) ?: return false
    return host == "github.com" || host.endsWith(".githubusercontent.com")
}

class UpdateAlert : DialogFragment() {
    private var _binding: AlertBinding? = null
    private val binding get() = _binding!!
    private val apkFile
        get() = File(
            File(requireContext().cacheDir, "updates"),
            "eu.magicsk.transi.${requireArguments().getString(ARG_VERSION)}.apk"
        )
    // Closed with the view so a download blocked on the network stops as soon as the dialog goes away.
    private var connection: HttpURLConnection? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        dialog!!.window?.setBackgroundDrawableResource(R.drawable.round_shape)
        _binding = AlertBinding.inflate(inflater, container, false)
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
            AlertTitle.text = getString(R.string.new_version)
            AlertCancelButton.isVisible = true
            AlertConfirmButton.text = getString(R.string.update)
            Markwon.create(requireContext())
                .setMarkdown(AlertText, requireArguments().getString(ARG_CHANGELOG).orEmpty())

            AlertCancelButton.setOnClickListener {
                dismissNow()
            }
            AlertConfirmButton.setOnClickListener {
                startDownload()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        connection?.disconnect()
        _binding = null
    }

    private fun startDownload() {
        val url = requireArguments().getString(ARG_URL).orEmpty()
        if (!isAllowedUpdateUrl(url)) {
            fail()
            return
        }
        binding.apply {
            AlertTitle.text = getString(R.string.update_in_progress)
            AlertProgress.isVisible = true
            AlertConfirmButton.isVisible = false
            // Dismissing destroys the view, which cancels the view-scoped download below.
            AlertCancelButton.setOnClickListener { dismiss() }
        }
        showProgress(0)
        val apk = apkFile
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                download(url, apk)
            } catch (_: IOException) {
                // Dismissing closes the connection; that is a cancellation, not a failure to report.
                ensureActive()
                fail()
                return@launch
            }
            binding.apply {
                AlertTitle.text = getString(R.string.update_downloaded)
                AlertText.text = getString(R.string.finish_update)
                AlertConfirmButton.text = getString(R.string.install)
                AlertConfirmButton.isVisible = true
                AlertCancelButton.isVisible = false
                AlertConfirmButton.setOnClickListener { install() }
            }
            install()
        }
    }

    private suspend fun download(url: String, target: File) {
        // Opening does no network I/O; doing it on the main thread lets onDestroyView close this connection.
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        this.connection = connection
        withContext(Dispatchers.IO) {
            try {
                // After the response arrives, connection.url is the final URL after redirects.
                if (connection.responseCode != HttpURLConnection.HTTP_OK || !isAllowedFinalUpdateUrl(connection.url.toString())) {
                    throw IOException("Unexpected update download response")
                }
                val totalSize = connection.contentLengthLong
                target.parentFile?.run {
                    deleteRecursively()
                    mkdirs()
                }
                var downloaded = 0L
                var shownPercent = -1
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            val percent = if (totalSize > 0) (downloaded * 100 / totalSize).toInt() else 0
                            if (percent != shownPercent) {
                                shownPercent = percent
                                withContext(Dispatchers.Main) { showProgress(percent) }
                            }
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun showProgress(percent: Int) {
        binding.apply {
            AlertText.text = getString(R.string.downloading, "$percent%")
            AlertProgress.progress = percent
        }
    }

    private fun fail() {
        Toast.makeText(requireContext(), R.string.update_failed, LENGTH_LONG).show()
        dismiss()
    }

    // The system installer asks for the "Install unknown apps" permission itself and then continues the install.
    private fun install() {
        val context = requireContext()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", apkFile)
        startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }

    companion object {
        private const val ARG_VERSION = "version"
        private const val ARG_CHANGELOG = "changelog"
        private const val ARG_URL = "downloadUrl"

        fun newInstance(version: String, changelog: String, downloadUrl: String) = UpdateAlert().apply {
            arguments = Bundle().apply {
                putString(ARG_VERSION, version)
                putString(ARG_CHANGELOG, changelog)
                putString(ARG_URL, downloadUrl)
            }
        }
    }
}
