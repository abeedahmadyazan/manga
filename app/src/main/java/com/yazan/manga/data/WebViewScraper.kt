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
    private const val DEFAULT_TIMEOUT_MS = 25_000L

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
     */
    private suspend fun executeJs(context: Context, url: String, js: String, timeoutMs: Long): String? {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val handler = Handler(Looper.getMainLooper())
                var finished = false
                var webView: WebView? = null
                val timeoutRunnable = Runnable {
                    if (!finished) {
                        finished = true
                        Log.w(TAG, "timeout for $url")
                        try { webView?.destroy() } catch (_: Exception) {}
                        if (cont.isActive) cont.resume(null)
                    }
                }

                cont.invokeOnCancellation {
                    if (!finished) {
                        finished = true
                        handler.removeCallbacks(timeoutRunnable)
                        try { webView?.destroy() } catch (_: Exception) {}
                    }
                }

                @SuppressLint("SetJavaScriptEnabled")
                val wv = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    // Block images (we only need the DOM, not the heavy images).
                    // loadsImagesAutomatically=false is API 24+ and stops <img> loads.
                    settings.loadsImagesAutomatically = false
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    webViewClient = object : WebViewClient() {
                        // Cancel image subresource requests at the network level.
                        override fun shouldInterceptRequest(view: WebView?, request: android.webkit.WebResourceRequest?): android.webkit.WebResourceResponse? {
                            val u = request?.url?.toString() ?: return null
                            // Block image MIME types / extensions to save bandwidth.
                            if (u.endsWith(".jpg") || u.endsWith(".jpeg") || u.endsWith(".png") || u.endsWith(".webp") || u.endsWith(".gif")) {
                                return android.webkit.WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                            }
                            return null
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            // CF challenge redirects — wait for the REAL content page.
                            // If the title is "Just a moment...", we're still on the
                            // challenge page; the WebView will auto-redirect when CF
                            // clears, and onPageFinished will fire again.
                            val title = view?.title ?: ""
                            if (title.contains("Just a moment", ignoreCase = true) ||
                                title.contains("Attention Required", ignoreCase = true)) {
                                Log.d(TAG, "CF challenge in progress, waiting...")
                                return
                            }
                            // Real page loaded → wait a bit for any JS-rendered
                            // content, then extract via our injected JS.
                            handler.postDelayed({
                                if (!finished) {
                                    view?.evaluateJavascript(js) { result ->
                                        if (!finished) {
                                            finished = true
                                            handler.removeCallbacks(timeoutRunnable)
                                            try { webView?.destroy() } catch (_: Exception) {}
                                            if (cont.isActive) cont.resume(unescapeJs(result))
                                        }
                                    }
                                }
                            }, 1500)  // 1.5s for JS-rendered images to populate
                        }
                    }
                }
                webView = wv
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
