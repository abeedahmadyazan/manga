package com.yazan.manga.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import kotlin.coroutines.resume

/**
 * Natural WebView scraper — lets the REAL Chrome engine solve Cloudflare
 * challenges NATURALLY without any interference.
 *
 * Key insight: Android WebView IS Chrome. CF challenges are designed to be
 * solved by real browsers. Previous attempts FAILED because we injected
 * "stealth" JS that BROKE CF's own challenge script.
 *
 * This version:
 * 1. Does NOT inject any JS before CF clears
 * 2. Lets the WebView solve CF naturally (it's Chrome!)
 * 3. Only hides the webdriver flag AFTER CF clears (not before)
 * 4. Waits up to 45 seconds for CF to clear
 * 5. Extracts data only after the real page is loaded
 */
object StealthWebView {

    private const val TAG = "StealthWebView"
    private const val DEFAULT_TIMEOUT_MS = 45_000L

    suspend fun scrape(
        context: Context,
        url: String,
        extractJs: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): String? {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val handler = Handler(Looper.getMainLooper())
                var finished = false
                var webView: WebView? = null
                var pollCount = 0
                var cfCleared = false

                val timeoutRunnable = Runnable {
                    if (!finished) {
                        finished = true
                        Log.w(TAG, "timeout after ${timeoutMs}ms for $url")
                        handler.removeCallbacksAndMessages(null)
                        try { webView?.destroy() } catch (_: Exception) {}
                        if (cont.isActive) cont.resume(null)
                    }
                }

                cont.invokeOnCancellation {
                    if (!finished) {
                        finished = true
                        handler.removeCallbacksAndMessages(null)
                        try { webView?.destroy() } catch (_: Exception) {}
                    }
                }

                // Smart polling — check if CF challenge has cleared NATURALLY
                val pollRunnable = object : Runnable {
                    override fun run() {
                        if (finished) return
                        pollCount++
                        val wv = webView ?: return

                        // Simple check: is the page title still "Just a moment"?
                        wv.evaluateJavascript("(function(){ try { return document.title; } catch(e){ return ''; } })();") { result ->
                            if (finished) return@evaluateJavascript
                            val title = unescape(result) ?: ""
                            val elapsed = pollCount * 2000

                            if (pollCount <= 3 || pollCount % 5 == 0) {
                                Log.d(TAG, "poll #$pollCount (${elapsed}ms): title='$title'")
                            }

                            // CF challenge pages have these titles:
                            val isChallenge = title.contains("Just a moment", ignoreCase = true) ||
                                    title.contains("Attention Required", ignoreCase = true) ||
                                    title.isEmpty()

                            if (!isChallenge && !cfCleared) {
                                // CF cleared! The real page is loaded.
                                cfCleared = true
                                Log.d(TAG, "✅ CF cleared after ${elapsed}ms! Title: $title")
                                handler.removeCallbacks(this)

                                // Wait 3s for JS-rendered content to populate
                                handler.postDelayed({
                                    if (!finished) {
                                        // NOW extract data (CF is done, page is real)
                                        wv.evaluateJavascript(extractJs) { res ->
                                            if (!finished) {
                                                finished = true
                                                handler.removeCallbacks(timeoutRunnable)
                                                try { webView?.destroy() } catch (_: Exception) {}
                                                if (cont.isActive) cont.resume(unescape(res))
                                            }
                                        }
                                    }
                                }, 3000)
                            } else if (isChallenge) {
                                // Still on CF challenge — keep waiting
                                // The WebView is solving it NATURALLY (it's Chrome!)
                                handler.postDelayed(this, 2000)
                            } else {
                                // Already cleared but waiting for extraction
                                handler.postDelayed(this, 2000)
                            }
                        }
                    }
                }

                @SuppressLint("SetJavaScriptEnabled")
                val wv = WebView(context).apply {
                    // Use the REAL device User-Agent (it's Chrome!)
                    // Just remove "wv" from the UA string
                    val ua = settings.userAgentString ?: ""
                    settings.userAgentString = ua.replace("; wv", "")

                    // Standard browser settings — let Chrome be Chrome
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    settings.loadsImagesAutomatically = true
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.allowFileAccess = true
                    settings.allowContentAccess = true

                    // Cookies — essential for CF
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            Log.d(TAG, "onPageFinished: $url")
                            // DO NOT inject any JS here — let CF run its own scripts
                            // Start polling to detect when CF clears
                            if (!finished) {
                                handler.removeCallbacks(pollRunnable)
                                handler.postDelayed(pollRunnable, 2000)
                            }
                        }

                        // Let all redirects happen naturally
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: android.webkit.WebResourceRequest?
                        ): Boolean {
                            return false
                        }
                    }
                }
                webView = wv

                handler.postDelayed(timeoutRunnable, timeoutMs)
                // Load the page — CF will challenge, WebView (Chrome) will solve it
                wv.loadUrl(url)
            }
        }
    }

    private fun unescape(s: String?): String? {
        if (s == null) return null
        var r = s
        if (r.startsWith("\"") && r.endsWith("\"")) {
            r = r.substring(1, r.length - 1)
        }
        return r
            .replace("\\\"", "\"")
            .replace("\\/", "/")
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\\\", "\\")
            .replace("\\u003C", "<")
            .replace("\\u003E", ">")
            .replace("\\u0026", "&")
            .replace("\\u0027", "'")
    }
}
