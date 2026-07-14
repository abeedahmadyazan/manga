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
 * Enhanced Stealth WebView — bypasses Cloudflare Bot Protection by:
 *
 * 1. Using the REAL Chrome User-Agent (not a fake one)
 * 2. Injecting stealth JS that hides:
 *    - navigator.webdriver flag
 *    - WebGL renderer info
 *    - Chrome runtime properties
 *    - Permissions API
 * 3. NOT blocking images (CF challenge needs them)
 * 4. Proper cookie persistence
 * 5. 60s timeout with smart polling
 *
 * This is the Android equivalent of playwright-stealth.
 */
object StealthWebView {

    private const val TAG = "StealthWebView"
    private const val DEFAULT_TIMEOUT_MS = 60_000L

    /**
     * Stealth JS injected BEFORE the page loads.
     * Hides the "webdriver" flag and other bot detection signals.
     */
    private const val STEALTH_JS = """
        // Hide webdriver flag
        Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
        
        // Fake plugins (real Chrome has them)
        Object.defineProperty(navigator, 'plugins', {
            get: () => [1, 2, 3, 4, 5],
        });
        
        // Fake languages
        Object.defineProperty(navigator, 'languages', {
            get: () => ['ar', 'en-US', 'en'],
        });
        
        // Hide Chrome automation
        window.chrome = { runtime: {} };
        
        // Fix permissions
        const originalQuery = window.navigator.permissions.query;
        window.navigator.permissions.query = (parameters) =>
            parameters.name === 'notifications'
                ? Promise.resolve({ state: Notification.permission })
                : originalQuery(parameters);
    """

    /**
     * Load a URL with stealth mode, wait for CF to clear, then extract data.
     * Returns the JS evaluation result as a string (JSON).
     */
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

                // Smart polling: check every 1.5s if CF cleared
                val pollRunnable = object : Runnable {
                    override fun run() {
                        if (finished) return
                        pollCount++
                        val wv = webView ?: return

                        // Check if CF challenge has cleared
                        wv.evaluateJavascript("""
                            (function(){
                                try {
                                    var title = document.title || '';
                                    var bodyLen = document.body ? document.body.innerHTML.length : 0;
                                    var hasCf = title.indexOf('Just a moment') >= 0 
                                        || title.indexOf('Attention') >= 0
                                        || (document.body && document.body.innerHTML.indexOf('cf-challenge') >= 0);
                                    return JSON.stringify({title: title, bodyLen: bodyLen, hasCf: hasCf});
                                } catch(e) { return '{"title":"","bodyLen":0,"hasCf":true}'; }
                            })();
                        """) { result ->
                            if (finished) return@evaluateJavascript
                            val r = unescape(result) ?: "{}"
                            try {
                                val o = org.json.JSONObject(r)
                                val hasCf = o.optBoolean("hasCf", true)
                                val bodyLen = o.optInt("bodyLen", 0)
                                val elapsed = pollCount * 1500

                                if (pollCount <= 3 || pollCount % 5 == 0) {
                                    Log.d(TAG, "poll #$pollCount (${elapsed}ms): cf=$hasCf body=$bodyLen")
                                }

                                if (!hasCf && bodyLen > 2000) {
                                    // CF cleared! Wait 2s for JS rendering, then extract
                                    Log.d(TAG, "CF cleared after ${elapsed}ms, extracting...")
                                    handler.removeCallbacks(this)
                                    handler.postDelayed({
                                        if (!finished) {
                                            wv.evaluateJavascript(extractJs) { res ->
                                                if (!finished) {
                                                    finished = true
                                                    handler.removeCallbacks(timeoutRunnable)
                                                    try { webView?.destroy() } catch (_: Exception) {}
                                                    if (cont.isActive) cont.resume(unescape(res))
                                                }
                                            }
                                        }
                                    }, 2000)
                                } else {
                                    // Still on CF challenge — keep polling
                                    handler.postDelayed(this, 1500)
                                }
                            } catch (e: Exception) {
                                handler.postDelayed(this, 1500)
                            }
                        }
                    }
                }

                @SuppressLint("SetJavaScriptEnabled")
                val wv = WebView(context).apply {
                    // Use REAL Chrome User-Agent from the device
                    val realUA = settings.userAgentString
                    // Remove "wv" suffix which reveals it's a WebView
                    settings.userAgentString = realUA?.replace("; wv", "") ?: realUA

                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    settings.loadsImagesAutomatically = true  // CF needs images!
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.setSupportZoom(false)
                    settings.builtInZoomControls = false
                    settings.displayZoomControls = false
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.allowFileAccess = true
                    settings.allowContentAccess = true

                    // Cookies
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            Log.d(TAG, "onPageFinished: $url")
                            // Inject stealth JS immediately
                            view?.evaluateJavascript(STEALTH_JS, null)
                            // Start polling
                            if (!finished) {
                                handler.removeCallbacks(pollRunnable)
                                handler.postDelayed(pollRunnable, 1000)
                            }
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: android.webkit.WebResourceRequest?
                        ): Boolean {
                            return false
                        }
                    }
                }
                webView = wv

                // Inject stealth JS before the page loads
                wv.evaluateJavascript(STEALTH_JS, null)

                handler.postDelayed(timeoutRunnable, timeoutMs)
                wv.loadUrl(url)
            }
        }
    }

    /** Unescape a JS-returned string (WebView wraps in quotes + escapes). */
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
