package eu.magicsk.transi.util

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.widget.Toast.LENGTH_LONG
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import eu.magicsk.transi.R
import eu.magicsk.transi.databinding.AlertBinding
import io.noties.markwon.Markwon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

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

/** Whether release [latest] is newer than [current]: dot-separated numbers after an optional "v"; anything else is not newer. */
fun isNewerVersion(latest: String, current: String): Boolean {
    fun parts(version: String): List<Int>? = version.removePrefix("v").split('.')
        .map { part -> part.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }?.toIntOrNull() ?: return null }
    val latestParts = parts(latest) ?: return false
    val currentParts = parts(current) ?: return false
    for (i in 0 until maxOf(latestParts.size, currentParts.size)) {
        val order = latestParts.getOrElse(i) { 0 }.compareTo(currentParts.getOrElse(i) { 0 })
        if (order != 0) return order > 0
    }
    return false
}

private const val SHA256_PREFIX = "sha256:"

/**
 * Whether [bytes] downloaded bytes hashing to [sha256] (hex) match the release asset's [expectedSize], the response's
 * [contentLength] (negative when unknown) and, when GitHub published one, the asset's `sha256:` [digest].
 */
fun isCompleteDownload(bytes: Long, sha256: String, expectedSize: Long?, contentLength: Long, digest: String?) =
    (expectedSize == null || bytes == expectedSize) &&
        (contentLength < 0 || bytes == contentLength) &&
        (digest == null || !digest.startsWith(SHA256_PREFIX) || digest.removePrefix(SHA256_PREFIX).equals(sha256, ignoreCase = true))

/** Owns the download so it survives the dialog being recreated by a configuration change. */
class UpdateViewModel : ViewModel() {
    sealed interface State {
        data object Notes : State
        data class Downloading(val percent: Int) : State
        data object Downloaded : State
        data object Failed : State
    }

    private val stateLiveData = MutableLiveData<State>(State.Notes)
    val state: LiveData<State> = stateLiveData

    // No replay: a download that finishes while the dialog is in the background leaves the Install button instead.
    private val installNowFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val installNow: SharedFlow<Unit> = installNowFlow

    // Closed once the dialog is really dismissed, so a download blocked on the network stops immediately.
    private var connection: HttpURLConnection? = null

    fun download(url: String, target: File, expectedSize: Long?, digest: String?) {
        // A second tap queued before the first one rendered must not start a second download into the same file.
        if (stateLiveData.value != State.Notes) return
        if (!isAllowedUpdateUrl(url)) {
            stateLiveData.value = State.Failed
            return
        }
        stateLiveData.value = State.Downloading(0)
        viewModelScope.launch {
            try {
                fetch(url, target, expectedSize, digest)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A dismissal closes the connection; that is a cancellation, not a failure to report. Any other
                // error, including a runtime one from HttpURLConnection, is a failed update rather than a crash.
                ensureActive()
                stateLiveData.value = State.Failed
                return@launch
            }
            stateLiveData.value = State.Downloaded
            installNowFlow.tryEmit(Unit)
        }
    }

    private suspend fun fetch(url: String, target: File, expectedSize: Long?, digest: String?) {
        // Opening does no network I/O; doing it on the main thread lets onCleared close this connection.
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
                val contentLength = connection.contentLengthLong
                val totalSize = if (contentLength > 0) contentLength else expectedSize ?: 0
                target.parentFile?.run {
                    deleteRecursively()
                    mkdirs()
                }
                val sha256 = MessageDigest.getInstance("SHA-256")
                var downloaded = 0L
                var shownPercent = 0
                try {
                    connection.inputStream.use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                sha256.update(buffer, 0, read)
                                downloaded += read
                                val percent = if (totalSize > 0) (downloaded * 100 / totalSize).toInt().coerceAtMost(100) else 0
                                if (percent != shownPercent) {
                                    shownPercent = percent
                                    withContext(Dispatchers.Main) { stateLiveData.value = State.Downloading(percent) }
                                }
                            }
                        }
                    }
                    if (!isCompleteDownload(downloaded, sha256.digest().toHexString(), expectedSize, contentLength, digest)) {
                        throw IOException("Update download does not match the release")
                    }
                } catch (e: Exception) {
                    // A truncated, corrupted or cancelled download must never be offered to the installer.
                    target.delete()
                    throw e
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    override fun onCleared() {
        connection?.disconnect()
    }
}

class UpdateAlert : DialogFragment() {
    private var _binding: AlertBinding? = null
    private val binding get() = _binding!!
    private val viewModel: UpdateViewModel by viewModels()
    private val apkFile
        get() = File(
            File(requireContext().cacheDir, "updates"),
            "eu.magicsk.transi.${requireArguments().getString(ARG_VERSION)}.apk"
        )

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
        viewModel.state.observe(viewLifecycleOwner, ::render)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.installNow.collect { install() } }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // Renders from scratch, because a recreated dialog starts from the layout in whatever state the download is.
    private fun render(state: UpdateViewModel.State) {
        // A stray back press or outside tap would silently drop the download; Cancel stays the way to stop it.
        isCancelable = state !is UpdateViewModel.State.Downloading
        binding.apply {
            when (state) {
                UpdateViewModel.State.Notes -> {
                    AlertTitle.text = getString(R.string.new_version)
                    Markwon.create(requireContext())
                        .setMarkdown(AlertText, requireArguments().getString(ARG_CHANGELOG).orEmpty())
                    AlertCancelButton.isVisible = true
                    AlertCancelButton.setOnClickListener { dismissNow() }
                    AlertConfirmButton.text = getString(R.string.update)
                    AlertConfirmButton.setOnClickListener {
                        val args = requireArguments()
                        viewModel.download(
                            args.getString(ARG_URL).orEmpty(),
                            apkFile,
                            args.getLong(ARG_SIZE, -1).takeIf { it >= 0 },
                            args.getString(ARG_DIGEST)
                        )
                    }
                }
                is UpdateViewModel.State.Downloading -> {
                    AlertTitle.text = getString(R.string.update_in_progress)
                    AlertText.text = getString(R.string.downloading, "${state.percent}%")
                    AlertProgress.isVisible = true
                    AlertProgress.progress = state.percent
                    AlertConfirmButton.isVisible = false
                    AlertCancelButton.isVisible = true
                    // Dismissing clears the view model, which closes the connection and cancels the download.
                    AlertCancelButton.setOnClickListener { dismiss() }
                }
                UpdateViewModel.State.Downloaded -> {
                    // The notes were read here, so What's New won't repeat them after the update.
                    Prefs.get(requireContext()).edit { putString(Prefs.CHANGELOG_VERSION, requireArguments().getString(ARG_VERSION)) }
                    AlertTitle.text = getString(R.string.update_downloaded)
                    AlertText.text = getString(R.string.finish_update)
                    AlertProgress.isVisible = true
                    AlertProgress.progress = 100
                    AlertConfirmButton.text = getString(R.string.install)
                    AlertConfirmButton.isVisible = true
                    AlertConfirmButton.setOnClickListener { install() }
                    AlertCancelButton.isVisible = false
                }
                UpdateViewModel.State.Failed -> {
                    Toast.makeText(requireContext(), R.string.update_failed, LENGTH_LONG).show()
                    // The failure may be delivered while the activity is saving its state, when a normal dismiss would throw.
                    dismissAllowingStateLoss()
                }
            }
        }
    }

    // The system installer asks for the "Install unknown apps" permission itself and then continues the install.
    private fun install() {
        val context = requireContext()
        // The system may clear the cache while the Install button waits.
        if (!apkFile.exists()) {
            Toast.makeText(context, R.string.update_failed, LENGTH_LONG).show()
            dismissAllowingStateLoss()
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", apkFile)
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.update_failed, LENGTH_LONG).show()
        }
    }

    companion object {
        private const val ARG_VERSION = "version"
        private const val ARG_CHANGELOG = "changelog"
        private const val ARG_URL = "downloadUrl"
        private const val ARG_SIZE = "size"
        private const val ARG_DIGEST = "digest"

        fun newInstance(version: String, changelog: String, downloadUrl: String, size: Long?, digest: String?) = UpdateAlert().apply {
            arguments = Bundle().apply {
                putString(ARG_VERSION, version)
                putString(ARG_CHANGELOG, changelog)
                putString(ARG_URL, downloadUrl)
                size?.let { putLong(ARG_SIZE, it) }
                putString(ARG_DIGEST, digest)
            }
        }
    }
}
