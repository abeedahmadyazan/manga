package com.yazan.manga

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.yazan.manga.ui.BaseSwipeBackActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.ListenerRegistration
import com.yazan.manga.data.AuthManager
import com.yazan.manga.data.ReadingHistoryManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : BaseSwipeBackActivity() {

    private lateinit var recyclerView: RecyclerView
    private var listener: ListenerRegistration? = null
    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manga_list)
        com.yazan.manga.data.AmoledMode.applyIfEnabled(this)

        val user = AuthManager.getCurrentUser(this)
        if (user == null) {
            android.widget.Toast.makeText(this, "سجّل الدخول أولاً", android.widget.Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        findViewById<TextView>(R.id.listTitle).text = "سجل القراءة"
        findViewById<android.widget.ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<com.google.android.material.tabs.TabLayout>(R.id.listTabs).visibility = View.GONE

        recyclerView = findViewById(R.id.mangaRecyclerView)
        val loadingIndicator = findViewById<android.widget.ProgressBar>(R.id.loadingIndicator)
        val emptyText = findViewById<TextView>(R.id.emptyText)

        recyclerView.layoutManager = LinearLayoutManager(this)

        loadingIndicator.visibility = View.VISIBLE
        listener = ReadingHistoryManager.listenToHistory(
            email = user.email,
            onUpdate = { entries ->
                loadingIndicator.visibility = View.GONE
                if (entries.isEmpty()) {
                    emptyText.visibility = View.VISIBLE
                    recyclerView.visibility = View.GONE
                    emptyText.text = "لا يوجد سجل قراءة بعد"
                } else {
                    emptyText.visibility = View.GONE
                    recyclerView.visibility = View.VISIBLE
                    recyclerView.adapter = HistoryAdapter(entries) { entry ->
                        val intent = Intent(this, MangaDetailsActivity::class.java)
                        intent.putExtra("manga_id", entry.mangaId)
                        intent.putExtra("manga_title", entry.mangaTitle)
                        intent.putExtra("manga_cover", entry.mangaCover)
                        startActivity(intent)
                    }
                }
            },
            onError = {
                loadingIndicator.visibility = View.GONE
                android.widget.Toast.makeText(this, "تعذّر تحميل السجل", android.widget.Toast.LENGTH_SHORT).show()
            }
        )
    }

    private inner class HistoryAdapter(
        private val items: List<ReadingHistoryManager.HistoryEntry>,
        private val onClick: (ReadingHistoryManager.HistoryEntry) -> Unit
    ) : RecyclerView.Adapter<HistoryAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
        override fun getItemCount() = items.size

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            private val title: TextView = v.findViewById(R.id.historyTitle)
            private val chapter: TextView = v.findViewById(R.id.historyChapter)
            private val time: TextView = v.findViewById(R.id.historyTime)

            fun bind(entry: ReadingHistoryManager.HistoryEntry) {
                val displayTitle = if (entry.mangaTitle.isNotEmpty()) {
                    entry.mangaTitle
                } else {
                    entry.chapterTitle
                }
                title.text = displayTitle
                // Show the chapter title (e.g. "الفصل 1181") — use the actual
                // chapter title if it has a name, otherwise the number.
                chapter.text = if (entry.chapterTitle.isNotEmpty() && entry.chapterTitle != "الفصل ${entry.chapterNumber}") {
                    entry.chapterTitle
                } else {
                    "الفصل ${entry.chapterNumber}"
                }
                time.text = sdf.format(Date(entry.readAt))
                itemView.setOnClickListener { onClick(entry) }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        listener?.remove()
        listener = null
    }
}
