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
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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

    private var currentChapter: MangaChapter? = null
    private var currentChapterId: String = ""
    private var currentChapterNumber: String = ""
    private var chapterUrl: String = ""
    private var mangaSlug: String = ""

    private var pages: List<String> = emptyList()
    private var currentPageIndex: Int = 0
    private var barsVisible = false

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
        pagesRecyclerView = findViewById(R.id.pagesRecyclerView)
        loadingIndicator = findViewById(R.id.loadingIndicator)
        errorText = findViewById(R.id.errorText)
        chapterTitleText = findViewById(R.id.chapterTitle)
        pageCounter = findViewById(R.id.pageCounter)
        btnPrevPage = findViewById(R.id.btnPrevPage)
        btnNextPage = findViewById(R.id.btnNextPage)
        pageSeekBar = findViewById(R.id.pageSeekBar)
        topBarOverlay = findViewById(R.id.topBarOverlay)
        bottomBar = findViewById(R.id.bottomBar)

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
            }.onFailure {
                errorText.text = "حدث خطأ أثناء تحميل الفصل. حاول مرة أخرى لاحقاً."
                errorText.visibility = View.VISIBLE
            }
        }
    }

    private fun setupPages(pageUrls: List<String>) {
        pages = pageUrls
        pageCounter.text = "1 / ${pages.size}"
        pageSeekBar.max = pages.size - 1

        val adapter = PagesAdapter(pages) { pageIndex ->
            // Tap on a page toggles the UI bars
            toggleBars()
        }

        // Track which page is visible
        pagesRecyclerView.layoutManager = LinearLayoutManager(this)
        pagesRecyclerView.adapter = adapter
        pagesRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                val visibleItem = layoutManager.findFirstVisibleItemPosition()
                if (visibleItem != RecyclerView.NO_POSITION && visibleItem != currentPageIndex) {
                    currentPageIndex = visibleItem
                    pageCounter.text = "${currentPageIndex + 1} / ${pages.size}"
                    pageSeekBar.progress = currentPageIndex
                }
            }
        })

        // Preload first few pages
        preloadPages(0)
    }

    private fun preloadPages(startIndex: Int) {
        val indices = listOf(startIndex + 1, startIndex + 2, startIndex + 3)
            .filter { it in pages.indices }
        for (i in indices) {
            try {
                Glide.with(this)
                    .load(pages[i])
                    .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                    .preload()
            } catch (e: Exception) {}
        }
    }

    private fun scrollToPage(index: Int) {
        if (index < 0 || index >= pages.size) return
        pagesRecyclerView.smoothScrollToPosition(index)
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
            private val pageNumber: TextView = v.findViewById(R.id.pageNumber)

            fun bind(url: String, position: Int) {
                pageNumber.text = "${position + 1} / ${pages.size}"
                pageProgress.visibility = View.VISIBLE

                Glide.with(itemView.context)
                    .load(url)
                    .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                    .listener(object : com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable> {
                        override fun onResourceReady(
                            resource: android.graphics.drawable.Drawable,
                            model: Any,
                            target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>,
                            dataSource: com.bumptech.glide.load.DataSource,
                            isFirstResource: Boolean
                        ): Boolean {
                            // unused params
                            @Suppress("UNUSED_PARAMETER") fun unused(a: Any?, b: Any?, c: Any?, d: Any?, e: Any?) {}
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

                // Preload next pages when this page becomes visible
                preloadPages(position)
            }
        }
    }
}
