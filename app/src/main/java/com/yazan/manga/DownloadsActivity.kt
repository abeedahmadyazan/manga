package com.yazan.manga

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.yazan.manga.ui.BaseSwipeBackActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yazan.manga.data.DownloadManager
import com.yazan.manga.ui.DownloadsAdapter

/**
 * Lists every chapter the user has downloaded. Tapping one opens it directly
 * from local storage (offline). Long-press / delete button removes it.
 *
 * The "التنزيلات" item in the drawer opens this screen.
 */
class DownloadsActivity : BaseSwipeBackActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: View
    private lateinit var adapter: DownloadsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)
        com.yazan.manga.data.AmoledMode.applyIfEnabled(this)

        recyclerView = findViewById(R.id.downloadsRecyclerView)
        emptyState = findViewById(R.id.emptyState)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnClearAll).setOnClickListener {
            if (adapter.itemCount == 0) {
                Toast.makeText(this, "لا توجد تنزيلات للحذف", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
                .setTitle("حذف كل التنزيلات")
                .setMessage("هل تريد حذف جميع الفصول المحمّلة؟")
                .setPositiveButton("حذف الكل") { _, _ ->
                    val all = DownloadManager.listAllDownloads(this)
                    all.forEach { DownloadManager.deleteChapter(this, it.mangaId, it.chapterNumber) }
                    refreshList()
                    Toast.makeText(this, "تم حذف ${all.size} فصل", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }

        adapter = DownloadsAdapter(
            onClick = { chapter -> openDownloadedReader(chapter) },
            onDeleteClick = { chapter ->
                AlertDialog.Builder(this)
                    .setTitle("حذف الفصل")
                    .setMessage("هل تريد حذف فصل ${chapter.chapterNumber} من التنزيلات؟")
                    .setPositiveButton("حذف") { _, _ ->
                        DownloadManager.deleteChapter(this, chapter.mangaId, chapter.chapterNumber)
                        refreshList()
                        Toast.makeText(this, "تم الحذف", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("إلغاء", null)
                    .show()
            }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        val items = DownloadManager.listAllDownloads(this)
        adapter.submitList(items)
        emptyState.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * Open the Reader with the downloaded chapter. We pass a special extra
     * `local_pages` containing the local file paths so ReaderActivity can
     * skip the network fetch.
     */
    private fun openDownloadedReader(chapter: DownloadManager.DownloadedChapter) {
        val pages = DownloadManager.getDownloadedPages(this, chapter.mangaId, chapter.chapterNumber)
        if (pages.isEmpty()) {
            Toast.makeText(this, "الملفات المحمّلة مفقودة", Toast.LENGTH_SHORT).show()
            refreshList()
            return
        }
        val intent = android.content.Intent(this, ReaderActivity::class.java).apply {
            putExtra("chapter_id", "local-${chapter.mangaId}-${chapter.chapterNumber}")
            putExtra("chapter_number", chapter.chapterNumber)
            putExtra("chapter_title", "الفصل ${chapter.chapterNumber}")
            putExtra("chapter_date", "")
            putExtra("chapter_source", "local")
            putExtra("chapter_url", "")
            putExtra("manga_slug", chapter.mangaId)
            putExtra("manga_title", chapter.mangaId)
            putExtra("manga_cover", "")
            // Pass local page paths as a string array
            putExtra("local_pages", pages.toTypedArray())
        }
        startActivity(intent)
    }
}
