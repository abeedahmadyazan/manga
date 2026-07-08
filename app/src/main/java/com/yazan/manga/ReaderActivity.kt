package com.yazan.manga

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yazan.manga.data.MangaChapter
import com.yazan.manga.data.MangaRepository
import com.yazan.manga.ui.ReaderAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReaderActivity : AppCompatActivity() {

    private lateinit var repository: MangaRepository
    private lateinit var adapter: ReaderAdapter

    private lateinit var pagesRecyclerView: RecyclerView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var errorText: TextView
    private lateinit var chapterTitleText: TextView
    private lateinit var pageCounter: TextView
    private lateinit var btnZoomIn: ImageButton
    private lateinit var btnZoomOut: ImageButton
    private lateinit var topBarOverlay: View

    private var currentChapter: MangaChapter? = null
    private var currentChapterId: String = ""
    private var currentChapterNumber: String = ""
    private var chapterUrl: String = ""
    private var mangaSlug: String = ""
    private var zoomLevel = 1.0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)

        repository = MangaRepository()

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
        btnZoomIn = findViewById(R.id.btnZoomIn)
        btnZoomOut = findViewById(R.id.btnZoomOut)
        topBarOverlay = findViewById(R.id.topBarOverlay)

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

        // Zoom controls
        btnZoomIn.setOnClickListener {
            zoomLevel = (zoomLevel + 0.25f).coerceAtMost(3.0f)
            applyZoom()
        }
        btnZoomOut.setOnClickListener {
            zoomLevel = (zoomLevel - 0.25f).coerceAtLeast(0.5f)
            applyZoom()
        }

        adapter = ReaderAdapter()
        pagesRecyclerView.layoutManager = LinearLayoutManager(this)
        pagesRecyclerView.adapter = adapter

        pagesRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as LinearLayoutManager
                val visible = lm.findFirstVisibleItemPosition() + 1
                pageCounter.text = "$visible / ${adapter.itemCount}"
                // Auto-hide top bar on fast scroll down, show on scroll up
                if (dy > 30) topBarOverlay.animate().alpha(0f).setDuration(200).start()
                else if (dy < -10) topBarOverlay.animate().alpha(1f).setDuration(200).start()
            }
        })

        // Tap to toggle top bar
        pagesRecyclerView.setOnClickListener {
            topBarOverlay.animate()
                .alpha(if (topBarOverlay.alpha == 0f) 1f else 0f)
                .setDuration(200).start()
        }
    }

    private fun applyZoom() {
        adapter.setZoom(zoomLevel)
    }

    private fun loadPages() {
        val chapter = currentChapter ?: return
        loadingIndicator.visibility = View.VISIBLE
        errorText.visibility = View.GONE

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                repository.getChapterPages(chapter)
            }

            loadingIndicator.visibility = View.GONE

            result.onSuccess { pages ->
                if (pages.isEmpty()) {
                    errorText.text = "لا توجد صفحات لهذا الفصل"
                    errorText.visibility = View.VISIBLE
                    pageCounter.text = "0 / 0"
                } else {
                    adapter.submitList(pages)
                    pageCounter.text = "1 / ${pages.size}"
                }
            }.onFailure {
                // Show a generic error message — never expose the real cause to the user
                errorText.text = "حدث خطأ أثناء تحميل الفصل. حاول مرة أخرى لاحقاً."
                errorText.visibility = View.VISIBLE
            }
        }
    }
}
