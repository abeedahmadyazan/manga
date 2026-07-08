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
import com.yazan.manga.data.AuthManager
import com.yazan.manga.data.MangaChapter
import com.yazan.manga.data.MangaDetails
import com.yazan.manga.data.MangaListsManager
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
    private lateinit var sourcesGroup: ChipGroup
    private lateinit var sourcesContainer: View
    private lateinit var chaptersRecyclerView: RecyclerView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var errorText: TextView
    private lateinit var btnRead: MaterialButton
    private lateinit var btnShare: ImageButton
    private lateinit var btnAddToList: ImageButton

    private var mangaSlug: String = ""
    private var mangaTitle: String = ""
    private var mangaCover: String = ""
    private var chaptersDescending: Boolean = true  // default: newest first

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
        sourcesGroup = findViewById(R.id.sourcesChipGroup)
        sourcesContainer = findViewById(R.id.sourcesContainer)
        chaptersRecyclerView = findViewById(R.id.chaptersRecyclerView)
        loadingIndicator = findViewById(R.id.loadingIndicator)
        errorText = findViewById(R.id.errorText)
        btnRead = findViewById(R.id.btnRead)
        btnShare = findViewById(R.id.btnShare)
        btnAddToList = findViewById(R.id.btnAddToList)

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

        // Add to a custom list (favorites / watch later / want to watch / completed)
        btnAddToList.setOnClickListener { showAddToListDialog() }

        // General manga comments (separate from per-chapter comments)
        findViewById<ImageButton>(R.id.btnMangaComments).setOnClickListener {
            val intent = Intent(this, CommentsActivity::class.java)
            intent.putExtra("context_id", mangaSlug)
            intent.putExtra("context_type", "manga")
            intent.putExtra("context_title", mangaTitle)
            startActivity(intent)
        }

        chapterAdapter = ChapterAdapter(
            onClick = { chapter -> openReader(chapter) },
            onCommentsClick = { chapter -> openChapterComments(chapter) },
            mangaIdProvider = { mangaSlug }
        )
        chaptersRecyclerView.layoutManager = LinearLayoutManager(this)
        chaptersRecyclerView.adapter = chapterAdapter

        btnRead.setOnClickListener {
            details?.chapters?.lastOrNull()?.let { openReader(it) }
        }

        // Chapter sort toggle
        val btnSort = findViewById<MaterialButton>(R.id.btnSortChapters)
        btnSort.setOnClickListener {
            chaptersDescending = !chaptersDescending
            btnSort.text = if (chaptersDescending) "الأحدث" else "الأقدم"
            details?.let { displayChapters(it.chapters) }
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
                errorText.text = "حدث خطأ أثناء تحميل التفاصيل"
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
        // Status: keep label short + color-code it
        val rawStatus = data.status.ifEmpty { "مستمرة" }
        statusText.text = normalizeStatus(rawStatus)
        statusText.setTextColor(getStatusColor(rawStatus))
        // Rating: just the number (the ★ is in the layout)
        ratingText.text = data.rating?.let {
            if (it >= 10) "10" else String.format("%.1f", it)
        } ?: "—"
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

        // Build source chips. Each chip is labeled "المصدر 1", "المصدر 2", ...
        // (per the user's request — never reveal the real upstream provider).
        // Each chip's tag holds the source key, so when the user picks it we
        // can swap the chapter list from chaptersBySource.
        sourcesGroup.removeAllViews()
        if (data.sources.size > 1) {
            sourcesContainer.visibility = View.VISIBLE
            data.sources.forEachIndexed { idx, src ->
                val chip = Chip(this).apply {
                    // Label: "المصدر 1 (عربي) · 64 فصل" — short and informative
                    val langLabel = if (src.language == "ar") "عربي" else "إنجليزي"
                    text = "${src.label} · $langLabel · ${src.chapterCount}"
                    isClickable = true
                    isCheckable = true
                    tag = src.key
                    setChipBackgroundColorResource(R.color.surface_light)
                    setTextColor(getColor(R.color.text_primary))
                    textSize = 12f
                    // Highlight the first source as selected by default
                    isChecked = idx == 0
                }
                chip.setOnClickListener {
                    // Mark this chip as selected, deselect the rest
                    for (i in 0 until sourcesGroup.childCount) {
                        val other = sourcesGroup.getChildAt(i) as Chip
                        other.isChecked = (other === chip)
                    }
                    // Swap chapter list
                    val key = chip.tag as? String
                    val chaptersForSource = data.chaptersBySource[key].orEmpty()
                    chapterAdapter.submitList(emptyList())  // clear first to reset scroll
                    if (chaptersForSource.isNotEmpty()) {
                        chaptersCountText.text = chaptersForSource.size.toString()
                        displayChapters(chaptersForSource)
                        btnRead.visibility = View.VISIBLE
                    } else {
                        chaptersCountText.text = "0"
                        btnRead.visibility = View.GONE
                    }
                }
                sourcesGroup.addView(chip)
            }
        } else {
            sourcesContainer.visibility = View.GONE
        }

        if (data.chapters.isEmpty()) {
            chaptersCountText.text = "0"
            chapterAdapter.submitList(emptyList())
            btnRead.visibility = View.GONE
        } else {
            chaptersCountText.text = data.chapters.size.toString()
            displayChapters(data.chapters)
            btnRead.visibility = View.VISIBLE
        }
    }

    /** Translate raw status strings (en/ar) to short Arabic labels. */
    private fun normalizeStatus(raw: String): String {
        val v = raw.trim().lowercase()
        return when {
            v.contains("ongoing") || v.contains("مستمر") -> "مستمرة"
            v.contains("completed") || v.contains("منته") || v.contains("مكتمل") -> "منتهية"
            v.contains("hiatus") || v.contains("متوقف") || v.contains("متوقفة") -> "متوقفة"
            v.contains("cancelled") || v.contains("ملغ") -> "ملغاة"
            else -> raw
        }
    }

    /** Get the color resource for a status string. */
    private fun getStatusColor(raw: String): Int {
        val v = raw.trim().lowercase()
        val resId = when {
            v.contains("ongoing") || v.contains("مستمر") || v.isEmpty() -> R.color.status_ongoing
            v.contains("completed") || v.contains("منته") || v.contains("مكتمل") -> R.color.status_completed
            v.contains("hiatus") || v.contains("متوقف") || v.contains("متوقفة") -> R.color.status_hiatus
            v.contains("cancelled") || v.contains("ملغ") -> R.color.status_cancelled
            else -> R.color.status_ongoing
        }
        return getColor(resId)
    }

    /** Display the chapter list in the current sort order (newest/oldest first). */
    private fun displayChapters(chapters: List<MangaChapter>) {
        val sorted = if (chaptersDescending) {
            chapters.sortedByDescending { it.number.toFloatOrNull() ?: 0f }
        } else {
            chapters.sortedBy { it.number.toFloatOrNull() ?: 0f }
        }
        chapterAdapter.submitList(sorted)
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
        intent.putExtra("manga_title", mangaTitle)
        intent.putExtra("manga_cover", mangaCover)
        startActivity(intent)
    }

    private fun openChapterComments(chapter: MangaChapter) {
        val intent = Intent(this, CommentsActivity::class.java)
        intent.putExtra("context_id", chapter.id)
        intent.putExtra("context_type", "chapter")
        intent.putExtra("context_title", "الفصل ${chapter.number}")
        startActivity(intent)
    }

    private fun showAddToListDialog() {
        val user = AuthManager.getCurrentUser(this)
        if (user == null) {
            android.widget.Toast.makeText(this, "سجّل الدخول أولاً", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val entry = MangaListsManager.MangaEntry(
            id = mangaSlug,
            title = mangaTitle,
            cover = mangaCover
        )
        val options = MangaListsManager.ListType.values().map { it.label }.toTypedArray()
        val checked = intArrayOf(0)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("إضافة لقائمة")
            .setSingleChoiceItems(options, checked[0]) { _, which -> checked[0] = which }
            .setPositiveButton("إضافة") { _, _ ->
                val type = MangaListsManager.ListType.values()[checked[0]]
                MangaListsManager.addToList(this, user.email, type, entry)
                android.widget.Toast.makeText(this, "تمت الإضافة إلى ${type.label}", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }
}
