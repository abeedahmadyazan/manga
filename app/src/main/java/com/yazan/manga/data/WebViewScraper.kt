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
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Headless WebView scraper — bypasses Cloudflare Bot Protection by letting a
 * REAL Android WebView (Chrome engine, V8, real TLS fingerprint) load the page.
 *
 * Cloudflare's "Just a moment..." challenge is solved automatically by the
 * WebView's JS engine, then we inject a JS snippet that extracts the data we
 * need (manga lists, chapter links, image URLs) and returns it as JSON.
 *
 * Usage:
 *   val html = WebViewScraper.fetchHtml(context, "https://mangalik.net/manga/one-piece/1/")
 *   val pages = WebViewScraper.fetchJsonArray(context, url, jsToExtractPages)
 *
 * All calls are suspendable and run off the main thread.
 */
object WebViewScraper {

    private const val TAG = "WebViewScraper"
    private const val DEFAULT_TIMEOUT_MS = 45_000L  // CF challenge can take 10-30s on slow networks

    /** Fetch the raw HTML of a URL after CF challenge is solved. */
    suspend fun fetchHtml(context: Context, url: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): String {
        val js = "javascript:(function(){ return document.documentElement.outerHTML; })();"
        val result = executeJs(context, url, js, timeoutMs)
        return result ?: ""
    }

    /**
     * Extract all <img> URLs that look like manga pages from a chapter URL.
     * Returns a JSON array of image URLs (high quality, in order).
     */
    suspend fun fetchChapterImages(context: Context, url: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): List<String> {
        // Injected JS: find all manga page images.
        // WordPress Madara theme uses .wp-manga-chapter-img or .reading-content img.
        val js = """
            javascript:(function(){
                var urls = [];
                var selectors = [
                    'img.wp-manga-chapter-img',
                    '.reading-content img.wp-manga-chapter-img',
                    '.reading-content img.img-responsive',
                    '.read-container img',
                    'div.reading-content img'
                ];
                for (var s = 0; s < selectors.length; s++) {
                    var imgs = document.querySelectorAll(selectors[s]);
                    if (imgs.length > 0) {
                        for (var i = 0; i < imgs.length; i++) {
                            var src = imgs[i].src || imgs[i].getAttribute('data-src') || imgs[i].getAttribute('data-lazy-src') || '';
                            if (!src) continue;
                            if (src.startsWith('//')) src = 'https:' + src;
                            if (src.indexOf('logo') >= 0 || src.indexOf('avatar') >= 0 || src.indexOf('favicon') >= 0) continue;
                            if (src.indexOf('wp-content/uploads/') < 0 && src.indexOf('manga') < 0) continue;
                            urls.push(src);
                        }
                        break;
                    }
                }
                return JSON.stringify(urls);
            })();
        """.trimIndent()

        val json = executeJs(context, url, js, timeoutMs) ?: return emptyList()
        return try {
            JSONArray(json).let { arr ->
                (0 until arr.length()).map { arr.getString(it) }.distinct()
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchChapterImages parse failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Public helper: load a URL in a headless WebView, wait for CF challenge to
     * clear, then execute the given JS snippet and return its string result.
     * Used by MangaRepository's mangalik fetchers (listing/search/details).
     */
    suspend fun executeJsForString(context: Context, url: String, js: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): String? {
        return executeJs(context, url, js, timeoutMs)
    }

    /**
     * Load a URL in a headless WebView, wait for CF challenge to clear, then
     * execute the given JS snippet and return its string result.
     *
     * Multi-layer CF detection:
     *  - URL still contains /cdn-cgi/challenge-platform/ → challenge in progress
     *  - title = "Just a moment..." → challenge in progress
     *  - body has <div id="cf-challenge-running"> → challenge in progress
     * When none match → real content loaded → wait 2s for JS rendering → inject JS.
     *
     * Images are NOT blocked (CF challenge sometimes requires loading an image
     * as part of the proof-of-work; blocking images can cause the challenge to
     * loop forever).
     */
    private suspend fun executeJs(context: Context, url: String, js: String, timeoutMs: Long): String? {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val handler = Handler(Looper.getMainLooper())
                var finished = false
                var webView: WebView? = null
                var challengeStartMs = System.currentTimeMillis()
                var pollCount = 0

                val timeoutRunnable = Runnable {
                    if (!finished) {
                        finished = true
                        Log.w(TAG, "timeout ($timeoutMs ms) for $url — CF challenge may not have cleared")
                        try { webView?.destroy() } catch (_: Exception) {}
                        if (cont.isActive) cont.resume(null)
                    }
                }

                cont.invokeOnCancellation {
                    if (!finished) {
                        finished = true
                        handler.removeCallbacks(timeoutRunnable)
                        handler.removeCallbacksAndMessages(null)
                        try { webView?.destroy() } catch (_: Exception) {}
                    }
                }

                // Poll the WebView every 1s to check if the CF challenge has cleared.
                // onPageFinished alone is unreliable for CF — the challenge page itself
                // fires onPageFinished, and sometimes the real page's JS-rendered
                // content needs extra time after onPageFinished.
                val pollRunnable = object : Runnable {
                    override fun run() {
                        if (finished) return
                        pollCount++
                        val wv = webView ?: return
                        // Check the current title + URL to detect CF challenge.
                        wv.evaluateJavascript("javascript:(function(){ try { return JSON.stringify({title: document.title, url: location.href, hasChallenge: !!document.getElementById('cf-challenge-running') || (document.body && document.body.innerHTML.indexOf('Just a moment') >= 0), bodyLen: document.body ? document.body.innerHTML.length : 0}); } catch(e){ return '{}'; } })();") { result ->
                            if (finished) return@evaluateJavascript
                            val r = unescapeJs(result) ?: "{}"
                            try {
                                val o = org.json.JSONObject(r)
                                val title = o.optString("title", "")
                                val hasChallenge = o.optBoolean("hasChallenge", false) ||
                                    title.contains("Just a moment", ignoreCase = true) ||
                                    title.contains("Attention Required", ignoreCase = true)
                                val bodyLen = o.optInt("bodyLen", 0)
                                val elapsed = System.currentTimeMillis() - challengeStartMs
                                if (pollCount <= 3 || pollCount % 5 == 0) {
                                    Log.d(TAG, "poll #$pollCount (${elapsed}ms): title='$title' bodyLen=$bodyLen challenge=$hasChallenge")
                                }
                                if (!hasChallenge && bodyLen > 1000) {
                                    // Challenge cleared → wait 2s for JS-rendered content, then inject.
                                    Log.d(TAG, "CF cleared after ${elapsed}ms, extracting data...")
                                    handler.removeCallbacks(this)
                                    handler.postDelayed({
                                        if (!finished) {
                                            wv.evaluateJavascript(js) { res ->
                                                if (!finished) {
                                                    finished = true
                                                    handler.removeCallbacks(timeoutRunnable)
                                                    try { webView?.destroy() } catch (_: Exception) {}
                                                    if (cont.isActive) cont.resume(unescapeJs(res))
                                                }
                                            }
                                        }
                                    }, 2000)
                                } else {
                                    // Still on challenge page — keep polling every 1s.
                                    handler.postDelayed(this, 1000)
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "poll parse failed: ${e.message}")
                                handler.postDelayed(this, 1000)
                            }
                        }
                    }
                }

                @SuppressLint("SetJavaScriptEnabled")
                val wv = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    // DO NOT block images — CF challenge sometimes requires loading
                    // an image as part of the proof-of-work. Blocking them causes
                    // the challenge to loop forever.
                    settings.loadsImagesAutomatically = true
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            Log.d(TAG, "onPageFinished: $url")
                            // Start polling once the first page load finishes.
                            // The poll runnable will keep checking until CF clears.
                            if (!finished) {
                                handler.removeCallbacks(pollRunnable)
                                handler.postDelayed(pollRunnable, 500)
                            }
                        }
                    }
                }
                webView = wv
                challengeStartMs = System.currentTimeMillis()
                handler.postDelayed(timeoutRunnable, timeoutMs)
                wv.loadUrl(url)
            }
        }
    }

    /** Decode a JS-returned string (WebView wraps it in quotes + escapes). */
    private fun unescapeJs(s: String?): String? {
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
    }
}
