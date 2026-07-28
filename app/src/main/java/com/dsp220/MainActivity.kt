package com.dsp220.pro

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var dspWebView: WebView
    private lateinit var youtubeWebView: WebView

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var lastExtractedUrl: String = ""

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (filePathCallback != null) {
            val intent = result.data
            var results: Array<Uri>? = null
            if (result.resultCode == Activity.RESULT_OK && intent != null) {
                val dataString = intent.dataString
                val clipData = intent.clipData
                if (clipData != null) {
                    results = Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
                } else if (dataString != null) {
                    results = arrayOf(Uri.parse(dataString))
                }
            }
            filePathCallback?.onReceiveValue(results)
            filePathCallback = null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initNewPipeExtractor()
        setContentView(R.layout.activity_main)

        val container = findViewById<FrameLayout>(R.id.fragment_container)
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_navigation)

        // 1. SETUP WEBVIEW DSP CONTROL
        dspWebView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            webViewClient = WebViewClient()

            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                    this@MainActivity.filePathCallback = filePathCallback

                    val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "*/*"
                    }

                    try {
                        filePickerLauncher.launch(intent)
                    } catch (e: Exception) {
                        this@MainActivity.filePathCallback = null
                        return false
                    }
                    return true
                }
            }

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = true
                allowContentAccess = true
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = true
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }

            addJavascriptInterface(AndroidBridge(), "AndroidBridge")
            loadUrl("file:///android_asset/index.html")
        }

        // 2. SETUP WEBVIEW YOUTUBE (DENGAN AUTO INTERCEPTOR & AUTO AD-SKIP)
        youtubeWebView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    injectYouTubeAutoHook()
                }

                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    injectYouTubeAutoHook()
                }
            }

            webChromeClient = WebChromeClient()

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                useWideViewPort = true
                loadWithOverviewMode = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }

            addJavascriptInterface(AndroidBridge(), "AndroidBridge")
            loadUrl("https://m.youtube.com")
        }

        container.addView(dspWebView)
        container.addView(youtubeWebView)

        dspWebView.visibility = View.VISIBLE
        youtubeWebView.visibility = View.GONE

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navigation_dsp -> {
                    dspWebView.visibility = View.VISIBLE
                    youtubeWebView.visibility = View.GONE
                    true
                }
                R.id.navigation_youtube -> {
                    youtubeWebView.visibility = View.VISIBLE
                    dspWebView.visibility = View.GONE
                    true
                }
                else -> false
            }
        }
    }

    // Fungsi Pembersih URL YouTube agar NewPipe tidak error ParsingException
    private fun sanitizeYouTubeUrl(rawUrl: String): String {
        return try {
            val uri = Uri.parse(rawUrl)
            val videoId = uri.getQueryParameter("v")
            if (!videoId.isNullOrEmpty()) {
                "https://www.youtube.com/watch?v=$videoId"
            } else if (rawUrl.contains("/shorts/")) {
                val shortsId = rawUrl.substringAfter("/shorts/").substringBefore("?").substringBefore("&")
                "https://www.youtube.com/shorts/$shortsId"
            } else {
                rawUrl
            }
        } catch (e: Exception) {
            rawUrl
        }
    }

    // Injeksi skrip stabil: Memisahkan ekstraksi audio dan skip iklan dengan aman
    private fun injectYouTubeAutoHook() {
        val jsHook = """
            (function() {
                if (window.ytDspInjected) return;
                window.ytDspInjected = true;
                
                // 1. Sembunyikan Banner, Promo, & Popup Iklan Mobile via CSS
                try {
                    var style = document.createElement('style');
                    style.type = 'text/css';
                    style.innerHTML = `
                        ytm-promoted-sparkles-web-renderer, 
                        ytm-companion-ad-renderer, 
                        ad-slot-renderer,
                        ytm-promoted-video-renderer,
                        .ad-showing,
                        .ad-container,
                        .ytp-ad-overlay-container { 
                            display: none !important; 
                        }
                    `;
                    document.head.appendChild(style);
                } catch(e){}

                // 2. LOOP KHUSUS NEWPIPE EXTRACTOR (TIDAK TERGANGGU AD BLOCKER)
                setInterval(function() {
                    try {
                        var currentUrl = window.location.href;
                        if (currentUrl.includes('/watch') || currentUrl.includes('/shorts/')) {
                            var video = document.querySelector('video');
                            if (video) {
                                video.muted = true; // Mute video YouTube bawaan agar suara tidak tumpuk
                            }
                            if (window.AndroidBridge && window.AndroidBridge.autoExtractYouTubeAudio) {
                                window.AndroidBridge.autoExtractYouTubeAudio(currentUrl);
                            }
                        }
                    } catch(e) {}
                }, 1000);

                // 3. LOOP KHUSUS AUTO-SKIP IKLAN VIDEO (CEPAT 300ms)
                setInterval(function() {
                    try {
                        // Cek apakah ada indikator iklan sedang diputar
                        var isAd = document.querySelector('.ad-showing, .ad-interrupting, .ytp-ad-player-overlay, .ytm-ad-player-overlay');
                        var video = document.querySelector('video');

                        // Jika iklan muncul, paksa loncat ke akhir durasi video iklan
                        if (isAd && video && !isNaN(video.duration)) {
                            video.currentTime = video.duration;
                        }

                        // Klik tombol skip jika tersedia
                        var skipBtn = document.querySelector('.ytp-ad-skip-button, .ytp-skip-ad-button, .ytp-ad-skip-button-modern, .ytm-ad-skip-button, .ytp-ad-skip-button-slot');
                        if (skipBtn) {
                            skipBtn.click();
                        }
                    } catch(e) {}
                }, 300);

            })();
        """.trimIndent()

        youtubeWebView.evaluateJavascript("javascript:$jsHook", null)
    }

    private fun initNewPipeExtractor() {
        try {
            NewPipe.init(object : Downloader() {
                override fun execute(request: Request): Response {
                    val connection = URL(request.url()).openConnection() as HttpURLConnection

                    val method = request.httpMethod() ?: "GET"
                    connection.requestMethod = method

                    request.headers().forEach { (key, values) ->
                        if (!key.equals("Accept-Encoding", ignoreCase = true)) {
                            if (key.equals("Cookie", ignoreCase = true)) {
                                connection.setRequestProperty(key, values.joinToString("; "))
                            } else {
                                values.forEach { value ->
                                    connection.addRequestProperty(key, value)
                                }
                            }
                        }
                    }

                    if (connection.getRequestProperty("User-Agent") == null) {
                        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    }

                    if (method == "POST" && request.dataToSend() != null) {
                        connection.doOutput = true
                        connection.outputStream.use { os ->
                            os.write(request.dataToSend())
                        }
                    }

                    val responseCodeValue = connection.responseCode
                    val responseMessage = connection.responseMessage
                    val responseHeaders = connection.headerFields

                    val responseBody = try {
                        connection.inputStream.bufferedReader().use { it.readText() }
                    } catch (e: Exception) {
                        connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    }

                    return Response(responseCodeValue, responseMessage, responseHeaders, responseBody, request.url())
                }
            })
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    inner class AndroidBridge {

        @JavascriptInterface
        fun autoExtractYouTubeAudio(url: String) {
            val cleanUrl = sanitizeYouTubeUrl(url)
            
            // Hindari ekstraksi berulang untuk URL lagu yang sama
            if (cleanUrl == lastExtractedUrl || cleanUrl.isBlank()) return
            lastExtractedUrl = cleanUrl

            extractYouTubeAudio(cleanUrl)
        }

        @JavascriptInterface
        fun extractYouTubeAudio(url: String) {
            val cleanUrl = sanitizeYouTubeUrl(url)
            
            Thread {
                try {
                    val extractor = ServiceList.YouTube.getStreamExtractor(cleanUrl)
                    extractor.fetchPage()

                    val audioStreams = extractor.audioStreams
                    val videoStreams = extractor.videoStreams

                    val playableUrl = when {
                        !audioStreams.isNullOrEmpty() -> audioStreams[0].url
                        !videoStreams.isNullOrEmpty() -> videoStreams[0].url
                        else -> null
                    }

                    if (playableUrl != null) {
                        runOnUiThread {
                            dspWebView.evaluateJavascript("javascript:onAudioExtracted('$playableUrl');", null)
                        }
                    } else {
                        runOnUiThread {
                            dspWebView.evaluateJavascript("javascript:onExtractionFailed('Format audio tidak ditemukan.');", null)
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        val errorClean = e.toString().replace("'", "\\'")
                        dspWebView.evaluateJavascript("javascript:onExtractionFailed('$errorClean');", null)
                    }
                }
            }.start()
        }
    }

    override fun onBackPressed() {
        if (youtubeWebView.visibility == View.VISIBLE && youtubeWebView.canGoBack()) {
            youtubeWebView.goBack()
        } else if (dspWebView.visibility == View.VISIBLE && dspWebView.canGoBack()) {
            dspWebView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
