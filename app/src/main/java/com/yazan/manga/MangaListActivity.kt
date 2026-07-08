package com.yazan.manga

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.ListenerRegistration
import com.yazan.manga.data.AuthManager
import com.yazan.manga.data.MangaListsManager
import com.yazan.manga.ui.MangaAdapter

/**
 * Displays the manga entries in a specific user list (favorites / watch later /
 * want to watch / completed). The list is loaded from Firestore in real-time.
 *
 * Intent extras:
 *   - list_type (String) — one of: "favorites", "watchLater", "wantToWatch", "completed"
 *   - user_email (String, optional) — defaults to the current user
 */
class MangaListActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var emptyText: TextView
    private lateinit var titleText: TextView

    private lateinit var adapter: MangaAdapter
    private var listener: ListenerRegistration? = null

    private var listType: MangaListsManager.ListType = MangaListsManager.ListType.FAVORITES
    private var email: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manga_list)

        // Parse the list type
        val typeKey = intent.getStringExtra("list_type") ?: "favorites"
        listType = when (typeKey) {
            "watchLater" -> MangaListsManager.ListType.WATCH_LATER
            "wantToWatch" -> MangaListsManager.ListType.WANT_TO_WATCH
            "completed" -> MangaListsManager.ListType.COMPLETED
            else -> MangaListsManager.ListType.FAVORITES
        }

        // Email: default to current user
        email = intent.getStringExtra("user_email") ?: ""
        if (email.isEmpty()) {
            val user = AuthManager.getCurrentUser(this)
            if (user == null) {
                Toast.makeText(this, "سجّل الدخول أولاً", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            email = user.email
        }

        initViews()
        startListening()
    }

    private fun initViews() {
        recyclerView = findViewById(R.id.mangaRecyclerView)
        loadingIndicator = findViewById(R.id.loadingIndicator)
        emptyText = findViewById(R.id.emptyText)
        titleText = findViewById(R.id.listTitle)

        titleText.text = listType.label
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        adapter = MangaAdapter { entry ->
            // Open manga details
            val intent = Intent(this, MangaDetailsActivity::class.java)
            intent.putExtra("manga_id", entry.id)
            intent.putExtra("manga_title", entry.title)
            intent.putExtra("manga_cover", entry.cover)
            startActivity(intent)
        }
        recyclerView.layoutManager = GridLayoutManager(this, 3)
        recyclerView.adapter = adapter
    }

    private fun startListening() {
        loadingIndicator.visibility = View.VISIBLE
        listener?.remove()
        listener = MangaListsManager.listenToMyLists(
            email = email,
            onUpdate = { lists ->
                loadingIndicator.visibility = View.GONE
                val entries = when (listType) {
                    MangaListsManager.ListType.FAVORITES -> lists.favorites
                    MangaListsManager.ListType.WATCH_LATER -> lists.watchLater
                    MangaListsManager.ListType.WANT_TO_WATCH -> lists.wantToWatch
                    MangaListsManager.ListType.COMPLETED -> lists.completed
                }
                // Convert MangaEntry → MangaListItem for the adapter
                val mangaSummaries = entries.map {
                    com.yazan.manga.data.MangaListItem(
                        id = it.id,
                        title = it.title,
                        cover = it.cover
                    )
                }
                if (mangaSummaries.isEmpty()) {
                    emptyText.visibility = View.VISIBLE
                    recyclerView.visibility = View.GONE
                    emptyText.text = "لا توجد مانجا في ${listType.label} بعد"
                } else {
                    emptyText.visibility = View.GONE
                    recyclerView.visibility = View.VISIBLE
                }
                adapter.submitList(mangaSummaries)
            },
            onError = {
                loadingIndicator.visibility = View.GONE
                Toast.makeText(this, "تعذّر تحميل القائمة", Toast.LENGTH_SHORT).show()
            }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        listener?.remove()
        listener = null
    }
}
