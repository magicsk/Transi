package eu.magicsk.transi

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings.LOAD_CACHE_ELSE_NETWORK
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.fragment.app.Fragment
import eu.magicsk.transi.databinding.FragmentTimetableBinding

class TimetableFragment : Fragment() {
    private var _binding: FragmentTimetableBinding? = null
    private val binding get() = _binding!!
    private val initialUrl = "https://imhd.sk/ba/cestovne-poriadky"
    private val webViewClient = CustomWebView()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        _binding = FragmentTimetableBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val webviewSettings = binding.timetableWebview.settings
        binding.timetableWebview.webViewClient = webViewClient
        webviewSettings.javaScriptEnabled = true
        webviewSettings.domStorageEnabled = true
        webviewSettings.cacheMode = LOAD_CACHE_ELSE_NETWORK
        if (savedInstanceState != null) {
            binding.timetableWebview.restoreState(savedInstanceState)
        } else {
            binding.timetableWebview.loadUrl(initialUrl)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.timetableWebview.saveState(outState)
    }

    inner class CustomWebView : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
            view?.loadUrl(request.url.toString())
            return true
        }
    }
}
