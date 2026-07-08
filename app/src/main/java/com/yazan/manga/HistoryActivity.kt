package com.yazan.manga

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.ListenerRegistration
import com.yazan.manga.data.AuthManager
import com.yazan.manga.data.ReadingHistoryManager
import com.yazan.manga.ui.MangaAdapter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var emptyText: TextView
    private var listener: ListenerRegistration? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manga_list)

        val user = AuthManager.getCurrentUser(this)
        if (user == null) {
            Toast.makeText(this, "سجّل الدخول أولاً", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        findViewById<TextView>(R.id.listTitle).text = "سجل القراءة"
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        // Hide the tabs (reuse the manga_list layout)
        findViewById<com.google.android.material.tabs.TabLayout>(R.id.listTabs).visibility = View.GONE

        recyclerView = findViewById(R.id.mangaRecyclerView)
        loadingIndicator = findViewById(R.id.loadingIndicator)
        emptyText = findViewById(R.id.emptyText)

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
                    // Show as a list of manga (deduplicated by mangaId)
                    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    val items = entries.map {
                        com.yazan.manga.data.MangaListItem(
                            id = it.mangaId,
                            title = "${it.mangaTitle} — الفصل ${it.chapterNumber}",
                            cover = it.mangaCover
                        )
                    }
                    val adapter = MangaAdapter(
                        onClick = { entry ->
                            val intent = Intent(this, MangaDetailsActivity::class.java)
                            intent.putExtra("manga_id", entry.id)
                            intent.putExtra("manga_title", entry.title.substringBefore(" — "))
                            intent.putExtra("manga_cover", entry.cover)
                            startActivity(intent)
                        }
                    )
                    recyclerView.adapter = adapter
                    adapter.submitList(items)
                }
            },
            onError = {
                loadingIndicator.visibility = View.GONE
                Toast.makeText(this, "تعذّر تحميل السجل", Toast.LENGTH_SHORT).show()
            }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        listener?.remove()
        listener = null
    }
}
