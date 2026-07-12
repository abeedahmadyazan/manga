package com.yazan.manga
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.LinearLayoutManager

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
    private lateinit var pagesRecyclerView: RecyclerView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var errorText: TextView
    private lateinit var chapterTitleText: TextView
    private lateinit var pageCounter: TextView
    private lateinit var btnPrevPage: ImageButton
    private lateinit var btnNextPage: ImageButton
    private lateinit var pageSeekBar: SeekBar
    private lateinit var topBarOverlay: View
    private lateinit var bottomBar: View
    private lateinit var btnReadingMode: ImageButton
    private var currentChapter: MangaChapter? = null
    private var currentChapterId: String = ""
    private var currentChapterNumber: String = ""
    private var chapterUrl: String = ""
    private var mangaSlug: String = ""
    private var pages: List<String> = emptyList()
    private var currentPageIndex: Int = 0
    private var barsVisible = false

    // Reading modes
    // "manga" = one page per screen (PagerSnapHelper + zoom) — best for Japanese manga
    // "webtoon" = vertical scroll (all pages connected) — best for Korean manhwa (Lookism, etc.)
    private var readingMode: String = "manga"
    private var snapHelper: PagerSnapHelper? = null
    private val PREFS_NAME = "reader_prefs"
    private val KEY_READING_MODE = "reading_mode"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)
        // Keep screen on while reading
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        overridePendingTransition(R.anim.slide_in_bottom, R.anim.fade_out)
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

        // Auto-detect reading mode based on source:
        // manhwa (3asq, mhh) → webtoon mode (vertical scroll)
        // manga (mangadex) → manga mode (one page per screen)
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val savedMode = prefs.getString(KEY_READING_MODE, null)
        if (savedMode != null) {
            readingMode = savedMode
        } else {
            // Auto-detect: manhwa sources use webtoon mode
            readingMode = if (chapterSource == "3asq" || chapterSource == "mhh") "webtoon" else "manga"
        }

        initViews()
        loadPages()
    }
    private fun initViews() {
        pagesRecyclerView = findViewById(R.id.pagesRecyclerView)
        pagesRecyclerView.layoutManager = LinearLayoutManager(this)
        applyReadingMode()

        loadingIndicator = findViewById(R.id.loadingIndicator)
        errorText = findViewById(R.id.errorText)
        chapterTitleText = findViewById(R.id.chapterTitle)
        pageCounter = findViewById(R.id.pageCounter)
        btnPrevPage = findViewById(R.id.btnPrevPage)
        btnNextPage = findViewById(R.id.btnNextPage)
        pageSeekBar = findViewById(R.id.pageSeekBar)
        topBarOverlay = findViewById(R.id.topBarOverlay)
        bottomBar = findViewById(R.id.bottomBar)
        btnReadingMode = findViewById(R.id.btnReadingMode) ?: ImageButton(this)

        chapterTitleText.text = "الفصل $currentChapterNumber"
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnChapterComments).setOnClickListener {
            val intent = Intent(this, CommentsActivity::class.java)
            intent.putExtra("context_id", currentChapterId)
            intent.putExtra("context_type", "chapter")
            intent.putExtra("context_title", "الفصل $currentChapterNumber")
            startActivity(intent)
        }
        btnPrevPage.setOnClickListener { scrollToPage(currentPageIndex - 1) }
        btnNextPage.setOnClickListener { scrollToPage(currentPageIndex + 1) }

        // Zoom IN + Zoom OUT — operate on the currently visible page
        findViewById<ImageButton>(R.id.btnZoomIn).setOnClickListener {
            findVisiblePageView()?.zoomIn()
        }
        findViewById<ImageButton>(R.id.btnZoomOut).setOnClickListener {
            findVisiblePageView()?.zoomOut()
        }

        // Reading mode toggle button
        btnReadingMode.setOnClickListener { showReadingModeDialog() }
        updateReadingModeIcon()

        pageSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && pages.isNotEmpty()) {
                    scrollToPage(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun applyReadingMode() {
        // Remove old snap helper
        snapHelper?.attachToRecyclerView(null)
        snapHelper = null

        if (readingMode == "manga") {
            // Manga mode: one page per screen with snap + zoom
            snapHelper = PagerSnapHelper()
            snapHelper?.attachToRecyclerView(pagesRecyclerView)
        }
        // Webtoon mode: no snap, free vertical scroll (all pages connected)
    }

    private fun updateReadingModeIcon() {
        // Update icon based on mode
        try {
            if (readingMode == "webtoon") {
                btnReadingMode.setImageResource(R.drawable.ic_menu)
            } else {
                btnReadingMode.setImageResource(R.drawable.ic_menu)
            }
        } catch (e: Exception) {}
    }

    private fun showReadingModeDialog() {
        val options = arrayOf(
            "📖 وضع المانجا — صفحة لكل شاشة\n(مناسب للمانجا اليابانية)",
            "📜 وضع المانهوا — تمرير عمودي متصل\n(مناسب للمانهوا الكورية مثل Lookism)"
        )
        val checkedItem = if (readingMode == "webtoon") 1 else 0

        AlertDialog.Builder(this)
            .setTitle("وضع القراءة")
            .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                val newMode = if (which == 1) "webtoon" else "manga"
                if (newMode != readingMode) {
                    readingMode = newMode
                    // Save preference
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit()
                        .putString(KEY_READING_MODE, readingMode)
                        .apply()
                    // Apply new mode
                    applyReadingMode()
                    updateReadingModeIcon()
                    // Refresh adapter
                    pagesRecyclerView.adapter?.notifyDataSetChanged()
                    Toast.makeText(this,
                        if (readingMode == "webtoon") "تم التبديل لوضع المانهوا (تمرير عمودي)"
                        else "تم التبديل لوضع المانجا (صفحة لكل شاشة)",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("فهمت") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun loadPages() {
        val chapter = currentChapter ?: return
        loadingIndicator.visibility = View.VISIBLE
        errorText.visibility = View.GONE
        val localPages = intent.getStringArrayExtra("local_pages")
        if (chapter.source == "local" || localPages != null) {
            loadingIndicator.visibility = View.GONE
            val pagesList = localPages?.toList().orEmpty()
            if (pagesList.isEmpty()) {
                errorText.text = "تعذّر العثور على الصفحات المحمّلة"
                errorText.visibility = View.VISIBLE
                pageCounter.text = "0 / 0"
            } else {
                setupPages(pagesList)
            }
            return
        }
        if (mangaSlug.isNotBlank() && currentChapterNumber.isNotBlank() &&
            com.yazan.manga.data.DownloadManager.isChapterDownloaded(this, mangaSlug, currentChapterNumber)) {
            val cached = com.yazan.manga.data.DownloadManager.getDownloadedPages(this, mangaSlug, currentChapterNumber)
            if (cached.isNotEmpty()) {
                loadingIndicator.visibility = View.GONE
                setupPages(cached)
                return
            }
        }
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
                    val pageUrls = pageList.map { it.url }
                    setupPages(pageUrls)
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
            }.onFailure { e ->
                val msg = e.message.orEmpty()
                errorText.text = when {
                    msg.contains("غير متاح", true) || msg.contains("تعذّر", true) ->
                        "لا تتوفر صفحات عربية لهذا الفصل بعد.\nجرّب فصلاً آخر أو مصدراً مختلفاً."
                    msg.contains("HTTP 4", true) || msg.contains("HTTP 5", true) ->
                        "تعذّر تحميل الفصل من المصدر. حاول لاحقاً."
                    else -> "حدث خطأ أثناء تحميل الفصل.\n${msg.take(80)}"
                }
                errorText.visibility = View.VISIBLE
            }
        }
    }
    private fun setupPages(pageUrls: List<String>) {
        pages = pageUrls
        pageCounter.text = "1 / ${pages.size}"
        pageSeekBar.max = if (pages.size > 1) pages.size - 1 else 0
        val adapter = PagesAdapter(pages) { pageIndex ->
            toggleBars()
        }
        pagesRecyclerView.adapter = adapter
        pagesRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    val lm = recyclerView.layoutManager as LinearLayoutManager
                    val pos = if (readingMode == "manga") {
                        lm.findFirstCompletelyVisibleItemPosition()
                    } else {
                        lm.findFirstVisibleItemPosition()
                    }
                    if (pos != RecyclerView.NO_POSITION && pos != currentPageIndex) {
                        currentPageIndex = pos
                        pageCounter.text = "${pos + 1} / ${pages.size}"
                        pageSeekBar.progress = pos
                        preloadPages(pos)
                    }
                }
            }
        })
        // Only preload first page in manga mode (webtoon mode loads on demand)
        if (readingMode == "manga") {
            preloadPages(0)
        }
    }
    private fun preloadPages(startIndex: Int) {
        val nextIndex = startIndex + 1
        if (nextIndex !in pages.indices) return
        try {
            Glide.with(this)
                .load(pages[nextIndex])
                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                .priority(com.bumptech.glide.Priority.LOW)
                .preload()
        } catch (e: Exception) {}
    }
    private fun scrollToPage(index: Int) {
        if (index < 0 || index >= pages.size) return
        pagesRecyclerView.smoothScrollToPosition(index)
    }
    private fun findVisiblePageView(): com.yazan.manga.ui.ZoomableImageView? {
        val lm = pagesRecyclerView.layoutManager as? LinearLayoutManager ?: return null
        val pos = lm.findFirstVisibleItemPosition()
        if (pos == RecyclerView.NO_POSITION) return null
        val holder = pagesRecyclerView.findViewHolderForAdapterPosition(pos) ?: return null
        return holder.itemView.findViewById(R.id.pageImage)
    }
    private fun toggleBars() {
        barsVisible = !barsVisible
        val targetAlpha = if (barsVisible) 1f else 0f
        topBarOverlay.visibility = View.VISIBLE
        bottomBar.visibility = View.VISIBLE
        topBarOverlay.animate().alpha(targetAlpha).setDuration(200).start()
        bottomBar.animate().alpha(targetAlpha).setDuration(200).start()
        if (!barsVisible) {
            topBarOverlay.postDelayed({ if (!barsVisible) topBarOverlay.visibility = View.GONE }, 200)
            bottomBar.postDelayed({ if (!barsVisible) bottomBar.visibility = View.GONE }, 200)
        }
    }
    // =============================================================
    //  RecyclerView Adapter for pages
    // =============================================================
    inner class PagesAdapter(
        private val pages: List<String>,
        private val onTap: (Int) -> Unit
    ) : RecyclerView.Adapter<PagesAdapter.PageVH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageVH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_reader_page, parent, false)
            return PageVH(view)
        }
        override fun onBindViewHolder(holder: PageVH, position: Int) {
            holder.bind(pages[position], position)
        }
        override fun getItemCount() = pages.size
        inner class PageVH(v: View) : RecyclerView.ViewHolder(v) {
            private val pageImage: ZoomableImageView = v.findViewById(R.id.pageImage)
            private val pageProgress: ProgressBar = v.findViewById(R.id.pageProgress)
            fun bind(url: String, position: Int) {
                pageProgress.visibility = View.VISIBLE

                // Adjust layout based on reading mode
                val imageParams = pageImage.layoutParams
                val parentParams = itemView.layoutParams

                if (readingMode == "webtoon") {
                    // Webtoon mode: 
                    // - width = match_parent (fill screen width)
                    // - height = wrap_content (image keeps natural aspect ratio)
                    // - adjustViewBounds = true (scale up to fill width)
                    // This makes images FULL WIDTH and properly tall (not tiny thumbnails)
                    imageParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    imageParams.width = ViewGroup.LayoutParams.MATCH_PARENT
                    pageImage.adjustViewBounds = true
                    pageImage.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    pageImage.setZoomEnabled(false)
                    
                    // Parent (FrameLayout) also needs wrap_content height
                    parentParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                } else {
                    // Manga mode: one page fills the entire screen
                    imageParams.height = ViewGroup.LayoutParams.MATCH_PARENT
                    imageParams.width = ViewGroup.LayoutParams.MATCH_PARENT
                    pageImage.adjustViewBounds = false
                    pageImage.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    pageImage.setZoomEnabled(true)
                    
                    // Parent fills screen too
                    parentParams.height = ViewGroup.LayoutParams.MATCH_PARENT
                }
                pageImage.layoutParams = imageParams
                itemView.layoutParams = parentParams

                val request = Glide.with(itemView.context)
                    .load(url)
                    .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                    .priority(com.bumptech.glide.Priority.HIGH)

                // In webtoon mode, DON'T override size.
                // With adjustViewBounds=true + wrap_content + match_parent width,
                // Glide auto-decodes at screen width with full height.
                // This gives best quality without OOM.
                // Use ARGB_8888 for 32-bit color depth (no banding).
                if (readingMode == "webtoon") {
                    request.format(com.bumptech.glide.load.DecodeFormat.PREFER_ARGB_8888)
                }

                request.listener(object : com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable> {
                        override fun onResourceReady(
                            resource: android.graphics.drawable.Drawable,
                            model: Any,
                            target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>,
                            dataSource: com.bumptech.glide.load.DataSource,
                            isFirstResource: Boolean
                        ): Boolean {
                            pageProgress.visibility = View.GONE
                            return false
                        }
                        override fun onLoadFailed(
                            e: com.bumptech.glide.load.engine.GlideException?,
                            model: Any?,
                            target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>,
                            isFirstResource: Boolean
                        ): Boolean {
                            pageProgress.visibility = View.GONE
                            return false
                        }
                    })
                    .into(pageImage)
                pageImage.setOnClickListener { onTap(position) }
                // Only preload in manga mode (small images).
                // In webtoon mode, preloading large images causes OOM.
                if (readingMode == "manga") {
                    preloadPages(position)
                }
            }
        }
    }
    override fun onBackPressed() {
        super.onBackPressed()
        overridePendingTransition(R.anim.fade_in, R.anim.slide_out_left)
    }
}