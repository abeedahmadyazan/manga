package com.yazan.manga

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.yazan.manga.data.MangaChapter
import com.yazan.manga.data.MangaRepository
import com.yazan.manga.data.ReadingHistoryManager
import com.yazan.manga.ui.ZoomableImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReaderActivity : AppCompatActivity() {

    private lateinit var repository: MangaRepository

    private lateinit var pageImage: ZoomableImageView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var errorText: TextView
    private lateinit var chapterTitleText: TextView
    private lateinit var pageCounter: TextView
    private lateinit var btnZoomIn: ImageButton
    private lateinit var btnZoomOut: ImageButton
    private lateinit var btnPrevPage: ImageButton
    private lateinit var btnNextPage: ImageButton
    private lateinit var pageSeekBar: SeekBar
    private lateinit var topBarOverlay: View
    private lateinit var bottomBar: View

    private var currentChapter: MangaChapter? = null
    private var currentChapterId: String = ""
    private var currentChapterNumber: String = ""
    private var chapterUrl: String = ""
    private var mangaSlug: String = ""

    private var pages: List<String> = emptyList()
    private var currentPageIndex: Int = 0
    private var zoomLevel = 1.0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)

        repository = MangaRepository(this)

        currentChapterId = intent.getStringExtra("chapter_id") ?: ""
        currentChapterNumber = intent.getStringExtra("chapter_number") ?: ""
        val chapterTitle = intent.getStringExtra("chapter_title") ?: ""
        val chapterDate = intent.getStringExtra("chapter_date") ?: ""
        val chapterSource = intent.getStringExtra("chapter_source") ?: "3asq"
        chapterUrl = intent.getStringExtra("chapter_url") ?: ""
        mangaSlug = intent.getStringExtra("manga_slug") ?: ""

        currentChapter = MangaChapter(
            id = currentChapterId,
            number = currentChapterNumber,
            title = chapterTitle,
            date = chapterDate,
            source = chapterSource,
            externalUrl = chapterUrl.ifEmpty { null }
        )

        initViews()
        loadPages()
    }

    private fun initViews() {
        pageImage = findViewById(R.id.pageImage)
        loadingIndicator = findViewById(R.id.loadingIndicator)
        errorText = findViewById(R.id.errorText)
        chapterTitleText = findViewById(R.id.chapterTitle)
        pageCounter = findViewById(R.id.pageCounter)
        btnZoomIn = findViewById(R.id.btnZoomIn)
        btnZoomOut = findViewById(R.id.btnZoomOut)
        btnPrevPage = findViewById(R.id.btnPrevPage)
        btnNextPage = findViewById(R.id.btnNextPage)
        pageSeekBar = findViewById(R.id.pageSeekBar)
        topBarOverlay = findViewById(R.id.topBarOverlay)
        bottomBar = findViewById(R.id.bottomBar)

        chapterTitleText.text = "الفصل $currentChapterNumber"

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        // Comments button
        findViewById<ImageButton>(R.id.btnChapterComments).setOnClickListener {
            val intent = Intent(this, CommentsActivity::class.java)
            intent.putExtra("context_id", currentChapterId)
            intent.putExtra("context_type", "chapter")
            intent.putExtra("context_title", "الفصل $currentChapterNumber")
            startActivity(intent)
        }

        // Zoom controls — these adjust the base scale. When zoomed in >1x,
        // the user can drag/pan the image with one finger.
        // Zoom IN: +0.5x per tap (max 5x)
        // Zoom OUT: -0.25x per tap (min 0.5x) — a smaller step so the user
        // can nudge the image slightly smaller ("إبعاد حبة للورا")
        btnZoomIn.setOnClickListener {
            zoomLevel = (zoomLevel + 0.5f).coerceAtMost(5.0f)
            pageImage.scaleX = zoomLevel
            pageImage.scaleY = zoomLevel
        }
        btnZoomOut.setOnClickListener {
            zoomLevel = (zoomLevel - 0.25f).coerceAtLeast(0.5f)
            pageImage.scaleX = zoomLevel
            pageImage.scaleY = zoomLevel
            if (zoomLevel == 1.0f) {
                pageImage.resetZoom()
            }
        }

        // Page navigation
        btnPrevPage.setOnClickListener { goToPage(currentPageIndex - 1) }
        btnNextPage.setOnClickListener { goToPage(currentPageIndex + 1) }

        // SeekBar
        pageSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && pages.isNotEmpty()) {
                    goToPage(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Tap to toggle top + bottom bars
        pageImage.setOnClickListener {
            val targetAlpha = if (topBarOverlay.alpha == 0f) 1f else 0f
            topBarOverlay.animate().alpha(targetAlpha).setDuration(200).start()
            bottomBar.animate().alpha(targetAlpha).setDuration(200).start()
        }
    }

    private fun loadPages() {
        val chapter = currentChapter ?: return
        loadingIndicator.visibility = View.VISIBLE
        errorText.visibility = View.GONE

        // If this is a local chapter (opened from Downloads), read pages
        // directly from disk — no network fetch needed.
        val localPages = intent.getStringArrayExtra("local_pages")
        if (chapter.source == "local" || localPages != null) {
            loadingIndicator.visibility = View.GONE
            val pagesList = localPages?.toList().orEmpty()
            if (pagesList.isEmpty()) {
                errorText.text = "تعذّر العثور على الصفحات المحمّلة"
                errorText.visibility = View.VISIBLE
                pageCounter.text = "0 / 0"
            } else {
                pages = pagesList
                currentPageIndex = 0
                pageSeekBar.max = pages.size - 1
                showPage(0)
            }
            return
        }

        // Otherwise: check if the chapter has been downloaded previously.
        // If so, read from disk instead of hitting the network.
        if (mangaSlug.isNotBlank() && currentChapterNumber.isNotBlank() &&
            com.yazan.manga.data.DownloadManager.isChapterDownloaded(this, mangaSlug, currentChapterNumber)) {
            val cached = com.yazan.manga.data.DownloadManager.getDownloadedPages(this, mangaSlug, currentChapterNumber)
            if (cached.isNotEmpty()) {
                loadingIndicator.visibility = View.GONE
                pages = cached
                currentPageIndex = 0
                pageSeekBar.max = pages.size - 1
                showPage(0)
                return
            }
        }

        // Default: fetch from the network.
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                repository.getChapterPages(chapter)
            }

            loadingIndicator.visibility = View.GONE

            result.onSuccess { pageList ->
                if (pageList.isEmpty()) {
                    errorText.text = "لا توجد صفحات لهذا الفصل"
                    errorText.visibility = View.VISIBLE
                    pageCounter.text = "0 / 0"
                } else {
                    pages = pageList.map { it.url }
                    currentPageIndex = 0
                    pageSeekBar.max = pages.size - 1
                    showPage(0)
                    // Record this chapter in the user's cloud reading history
                    ReadingHistoryManager.recordChapterRead(this@ReaderActivity,
                        ReadingHistoryManager.HistoryEntry(
                            mangaId = mangaSlug,
                            mangaTitle = intent.getStringExtra("manga_title") ?: "",
                            mangaCover = intent.getStringExtra("manga_cover") ?: "",
                            chapterId = currentChapterId,
                            chapterNumber = currentChapterNumber,
                            chapterTitle = chapter?.title ?: ""
                        )
                    )
                }
            }.onFailure {
                errorText.text = "حدث خطأ أثناء تحميل الفصل. حاول مرة أخرى لاحقاً."
                errorText.visibility = View.VISIBLE
            }
        }
    }

    private fun showPage(index: Int) {
        if (index < 0 || index >= pages.size) return
        currentPageIndex = index
        pageCounter.text = "${index + 1} / ${pages.size}"
        pageSeekBar.progress = index
        // Reset zoom when changing pages
        zoomLevel = 1.0f
        pageImage.scaleX = 1.0f
        pageImage.scaleY = 1.0f
        pageImage.resetZoom()

        // Show spinner while loading this page
        loadingIndicator.visibility = View.VISIBLE

        // Load current page with listener to hide spinner
        Glide.with(this)
            .load(pages[index])
            .listener(object : com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable> {
                override fun onResourceReady(
                    resource: android.graphics.drawable.Drawable,
                    model: Any,
                    target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>,
                    dataSource: com.bumptech.glide.load.DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    loadingIndicator.visibility = View.GONE
                    return false
                }

                override fun onLoadFailed(
                    e: com.bumptech.glide.load.engine.GlideException?,
                    model: Any,
                    target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>,
                    isFirstResource: Boolean
                ): Boolean {
                    loadingIndicator.visibility = View.GONE
                    return false
                }
            })
            .into(pageImage)

        // Preload next 2 pages so the user finds them already loaded
        preloadNextPages(index)
    }

    /**
     * Preload up to 2 pages ahead of the current page so when the user
     * navigates forward, the image is already in Glide's disk cache.
     */
    private fun preloadNextPages(currentIndex: Int) {
        val preloadIndices = listOf(currentIndex + 1, currentIndex + 2)
            .filter { it in pages.indices }
        for (i in preloadIndices) {
            try {
                Glide.with(this)
                    .load(pages[i])
                    .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                    .preload()
            } catch (e: Exception) {
                // Ignore preload errors — it's a best-effort optimization
            }
        }
    }

    private fun goToPage(index: Int) {
        if (index < 0 || index >= pages.size) return
        showPage(index)
    }
}
