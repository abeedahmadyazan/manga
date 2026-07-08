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
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
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
    private lateinit var altTitleText: TextView
    private lateinit var coverImg: android.widget.ImageView
    private lateinit var authorText: TextView
    private lateinit var statusText: TextView
    private lateinit var ratingText: TextView
    private lateinit var chaptersCountText: TextView
    private lateinit var descriptionText: TextView
    private lateinit var genresGroup: ChipGroup
    private lateinit var chaptersRecyclerView: RecyclerView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var errorText: TextView
    private lateinit var btnRead: MaterialButton
    private lateinit var btnShare: ImageButton

    private var mangaSlug: String = ""
    private var mangaTitle: String = ""
    private var mangaCover: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manga_details)

        repository = MangaRepository()
        mangaSlug = intent.getStringExtra("manga_id") ?: ""
        mangaTitle = intent.getStringExtra("manga_title") ?: ""
        mangaCover = intent.getStringExtra("manga_cover") ?: ""

        initViews()
        loadDetails()
    }

    private fun initViews() {
        titleText = findViewById(R.id.mangaTitle)
        altTitleText = findViewById(R.id.tvAltTitle)
        coverImg = findViewById(R.id.mangaCover)
        authorText = findViewById(R.id.tvAuthor)
        statusText = findViewById(R.id.tvStatus)
        ratingText = findViewById(R.id.tvRating)
        chaptersCountText = findViewById(R.id.tvChapters)
        descriptionText = findViewById(R.id.tvDescription)
        genresGroup = findViewById(R.id.genresChipGroup)
        chaptersRecyclerView = findViewById(R.id.chaptersRecyclerView)
        loadingIndicator = findViewById(R.id.loadingIndicator)
        errorText = findViewById(R.id.errorText)
        btnRead = findViewById(R.id.btnRead)
        btnShare = findViewById(R.id.btnShare)

        titleText.text = mangaTitle
        Glide.with(this).load(mangaCover)
            .centerCrop()
            .placeholder(R.color.surface_light)
            .into(coverImg)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        btnShare.setOnClickListener {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "$mangaTitle\nمانجا")
            }
            startActivity(Intent.createChooser(shareIntent, "مشاركة عبر"))
        }

        chapterAdapter = ChapterAdapter(
            onClick = { chapter -> openReader(chapter) },
            onCommentsClick = { chapter -> openChapterComments(chapter) }
        )
        chaptersRecyclerView.layoutManager = LinearLayoutManager(this)
        chaptersRecyclerView.adapter = chapterAdapter

        btnRead.setOnClickListener {
            details?.chapters?.lastOrNull()?.let { openReader(it) }
        }

        findViewById<SwipeRefreshLayout>(R.id.swipeRefresh).setOnRefreshListener {
            loadDetails()
        }
    }

    private fun loadDetails() {
        loadingIndicator.visibility = View.VISIBLE
        errorText.visibility = View.GONE
        btnRead.visibility = View.GONE

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                repository.getMangaDetails(mangaSlug)
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
        if (data.cover.isNotEmpty()) {
            Glide.with(this).load(data.cover)
                .centerCrop()
                .placeholder(R.color.surface_light)
                .into(coverImg)
        }
        authorText.text = "المؤلف: ${data.author.ifEmpty { "غير معروف" }}"
        statusText.text = data.status.ifEmpty { "مستمرة" }
        ratingText.text = data.rating?.let { "⭐ $it" } ?: "⭐ —"
        descriptionText.text = data.description.ifEmpty { "لا يوجد وصف" }

        // Genres as chips
        genresGroup.removeAllViews()
        data.genres.forEach { genre ->
            val chip = Chip(this).apply {
                text = genre
                isClickable = false
                isCheckable = false
                setChipBackgroundColorResource(R.color.surface_light)
                setTextColor(getColor(R.color.text_primary))
                textSize = 12f
            }
            genresGroup.addView(chip)
        }

        // Subtitle shows source
        altTitleText.visibility = View.GONE

        chaptersCountText.text = "${data.chapters.size} فصل"
        chapterAdapter.submitList(data.chapters)
        btnRead.visibility = if (data.chapters.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun openReader(chapter: MangaChapter) {
        val intent = Intent(this, ReaderActivity::class.java)
        intent.putExtra("chapter_id", chapter.id)
        intent.putExtra("chapter_number", chapter.number)
        intent.putExtra("chapter_title", chapter.title)
        intent.putExtra("chapter_date", chapter.date)
        intent.putExtra("chapter_source", chapter.source)
        intent.putExtra("chapter_url", chapter.externalUrl ?: "")
        intent.putExtra("manga_slug", mangaSlug)
        startActivity(intent)
    }

    private fun openChapterComments(chapter: MangaChapter) {
        val intent = Intent(this, CommentsActivity::class.java)
        intent.putExtra("context_id", chapter.id)
        intent.putExtra("context_type", "chapter")
        intent.putExtra("context_title", "الفصل ${chapter.number}")
        startActivity(intent)
    }
}
