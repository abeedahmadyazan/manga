package com.yazan.manga

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yazan.manga.data.ChapterPage
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

    private var currentChapter: MangaChapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)

        repository = MangaRepository()

        // Get chapter from intent
        val chapterId = intent.getStringExtra("chapter_id") ?: ""
        val chapterNumber = intent.getStringExtra("chapter_number") ?: ""
        val chapterTitle = intent.getStringExtra("chapter_title") ?: ""
        val chapterDate = intent.getStringExtra("chapter_date") ?: ""
        val chapterSource = intent.getStringExtra("chapter_source") ?: "3asq"

        currentChapter = MangaChapter(
            id = chapterId,
            number = chapterNumber,
            title = chapterTitle,
            date = chapterDate,
            source = chapterSource
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

        chapterTitleText.text = currentChapter?.title?.ifEmpty {
            "الفصل ${currentChapter?.number}"
        } ?: ""

        // Back button
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            finish()
        }

        // Adapter
        adapter = ReaderAdapter()
        pagesRecyclerView.layoutManager = LinearLayoutManager(this)
        pagesRecyclerView.adapter = adapter

        // Page counter on scroll
        pagesRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as LinearLayoutManager
                val visible = lm.findFirstVisibleItemPosition() + 1
                pageCounter.text = "$visible / ${adapter.itemCount}"
            }
        })
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
                } else {
                    adapter.submitList(pages)
                    pageCounter.text = "1 / ${pages.size}"
                }
            }.onFailure { e ->
                errorText.text = "فشل تحميل الصفحات: ${e.message}"
                errorText.visibility = View.VISIBLE
            }
        }
    }
}
