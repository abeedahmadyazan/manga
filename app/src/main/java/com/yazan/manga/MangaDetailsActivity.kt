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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.bumptech.glide.Glide
import com.yazan.manga.data.MangaChapter
import com.yazan.manga.data.MangaDetails
import com.yazan.manga.data.MangaRepository
import com.yazan.manga.ui.ChapterAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MangaDetailsActivity : AppCompatActivity() {

    private lateinit var repository: MangaRepository
    private var details: MangaDetails? = null
    private lateinit var chapterAdapter: ChapterAdapter

    // Views
    private lateinit var titleText: TextView
    private lateinit var coverImg: android.widget.ImageView
    private lateinit var authorText: TextView
    private lateinit var statusText: TextView
    private lateinit var chaptersCountText: TextView
    private lateinit var descriptionText: TextView
    private lateinit var genresText: TextView
    private lateinit var chaptersRecyclerView: RecyclerView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var errorText: TextView
    private lateinit var btnRead: com.google.android.material.button.MaterialButton

    private var mangaId: String = ""
    private var mangaTitle: String = ""
    private var mangaCover: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manga_details)

        repository = MangaRepository()

        // Get manga info from intent
        mangaId = intent.getStringExtra("manga_id") ?: ""
        mangaTitle = intent.getStringExtra("manga_title") ?: ""
        mangaCover = intent.getStringExtra("manga_cover") ?: ""

        initViews()
        loadDetails()
    }

    private fun initViews() {
        titleText = findViewById(R.id.mangaTitle)
        coverImg = findViewById(R.id.mangaCover)
        authorText = findViewById(R.id.tvAuthor)
        statusText = findViewById(R.id.tvStatus)
        chaptersCountText = findViewById(R.id.tvChapters)
        descriptionText = findViewById(R.id.tvDescription)
        genresText = findViewById(R.id.tvGenres)
        chaptersRecyclerView = findViewById(R.id.chaptersRecyclerView)
        loadingIndicator = findViewById(R.id.loadingIndicator)
        errorText = findViewById(R.id.errorText)
        btnRead = findViewById(R.id.btnRead)

        // Set title
        titleText.text = mangaTitle

        // Load cover
        Glide.with(this)
            .load(mangaCover)
            .centerCrop()
            .placeholder(R.color.surface)
            .into(coverImg)

        // Back button
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            finish()
        }

        // Chapter list
        chapterAdapter = ChapterAdapter { chapter ->
            openReader(chapter)
        }
        chaptersRecyclerView.layoutManager = LinearLayoutManager(this)
        chaptersRecyclerView.adapter = chapterAdapter

        // Read button → open first chapter
        btnRead.setOnClickListener {
            details?.chapters?.let { chapters ->
                if (chapters.isNotEmpty()) {
                    // Find first chapter (oldest = last in descending list)
                    val first = chapters.lastOrNull() ?: chapters.first()
                    openReader(first)
                }
            }
        }

        // Swipe to refresh
        findViewById<SwipeRefreshLayout>(R.id.swipeRefresh).setOnRefreshListener {
            loadDetails()
        }
    }

    private fun loadDetails() {
        loadingIndicator.visibility = View.VISIBLE
        errorText.visibility = View.GONE

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                repository.getMangaDetails(mangaId)
            }

            loadingIndicator.visibility = View.GONE
            findViewById<SwipeRefreshLayout>(R.id.swipeRefresh).isRefreshing = false

            result.onSuccess { data ->
                details = data
                displayDetails(data)
            }.onFailure { e ->
                errorText.text = "فشل تحميل التفاصيل: ${e.message}"
                errorText.visibility = View.VISIBLE
            }
        }
    }

    private fun displayDetails(data: MangaDetails) {
        titleText.text = data.title

        // Reload cover with full quality
        Glide.with(this)
            .load(data.cover)
            .centerCrop()
            .placeholder(R.color.surface)
            .into(coverImg)

        authorText.text = "المؤلف: ${data.author.ifEmpty { "غير معروف" }}"
        statusText.text = when (data.status.lowercase()) {
            "ongoing" -> "مستمرة"
            "completed" -> "مكتملة"
            else -> data.status
        }
        chaptersCountText.text = "${data.chapters.size} فصل"

        descriptionText.text = data.description.ifEmpty { "لا يوجد وصف" }
        genresText.text = data.genres.joinToString(" · ")

        chapterAdapter.submitList(data.chapters)
    }

    private fun openReader(chapter: MangaChapter) {
        val intent = Intent(this, ReaderActivity::class.java)
        intent.putExtra("chapter_id", chapter.id)
        intent.putExtra("chapter_number", chapter.number)
        intent.putExtra("chapter_title", chapter.title)
        intent.putExtra("chapter_date", chapter.date)
        intent.putExtra("chapter_source", chapter.source)
        // Pass all chapters for navigation
        details?.let { d ->
            val chaptersJson = com.google.gson.Gson().toJson(d.chapters)
            intent.putExtra("all_chapters", chaptersJson)
        }
        startActivity(intent)
    }
}
