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
import com.google.android.material.tabs.TabLayout
import com.google.firebase.firestore.ListenerRegistration
import com.yazan.manga.data.AuthManager
import com.yazan.manga.data.MangaListsManager
import com.yazan.manga.ui.MangaAdapter

/**
 * Full-page custom-lists screen with 4 tabs:
 *   ⭐ المفضلة | 📚 أتابع لاحقاً | 👀 أرغب بمتابعتها | ✅ أنهيتها
 *
 * All lists are cloud-backed (Firestore user_lists/{email}) and update in real-time.
 */
class MangaListActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var emptyState: View
    private lateinit var emptyText: TextView
    private lateinit var tabs: TabLayout

    private lateinit var adapter: MangaAdapter
    private var listener: ListenerRegistration? = null

    private var email: String = ""
    private var currentListType: MangaListsManager.ListType = MangaListsManager.ListType.FAVORITES

    // Map tab index → list type
    private val tabListTypes = arrayOf(
        MangaListsManager.ListType.FAVORITES,
        MangaListsManager.ListType.WATCH_LATER,
        MangaListsManager.ListType.WANT_TO_WATCH,
        MangaListsManager.ListType.COMPLETED
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manga_list)

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

        // Optional: pre-select a tab (default = 0 = favorites)
        val initialTab = intent.getIntExtra("initial_tab", 0)

        initViews(initialTab)
        startListening()
    }

    private fun initViews(initialTab: Int) {
        recyclerView = findViewById(R.id.mangaRecyclerView)
        loadingIndicator = findViewById(R.id.loadingIndicator)
        emptyState = findViewById(R.id.emptyState)
        emptyText = findViewById(R.id.emptyText)
        tabs = findViewById(R.id.listTabs)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        adapter = MangaAdapter(
            onClick = { entry ->
                val intent = Intent(this, MangaDetailsActivity::class.java)
                intent.putExtra("manga_id", entry.id)
                intent.putExtra("manga_title", entry.title)
                intent.putExtra("manga_cover", entry.cover)
                startActivity(intent)
            },
            onRemoveClick = { entry ->
                confirmRemove(entry.id, entry.title)
            }
        )
        recyclerView.layoutManager = GridLayoutManager(this, 3)
        recyclerView.adapter = adapter

        // Select the initial tab
        if (initialTab in 0..3) {
            tabs.getTabAt(initialTab)?.select()
            currentListType = tabListTypes[initialTab]
        }

        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentListType = tabListTypes[tab.position]
                renderCurrentList()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun startListening() {
        loadingIndicator.visibility = View.VISIBLE
        listener?.remove()
        listener = MangaListsManager.listenToMyLists(
            email = email,
            onUpdate = { lists ->
                loadingIndicator.visibility = View.GONE
                // Cache the full lists and render the currently-selected tab
                cachedLists = lists
                renderCurrentList()
            },
            onError = {
                loadingIndicator.visibility = View.GONE
                Toast.makeText(this, "تعذّر تحميل القائمة", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private var cachedLists: MangaListsManager.UserLists = MangaListsManager.UserLists()

    private fun renderCurrentList() {
        val entries = when (currentListType) {
            MangaListsManager.ListType.FAVORITES -> cachedLists.favorites
            MangaListsManager.ListType.WATCH_LATER -> cachedLists.watchLater
            MangaListsManager.ListType.WANT_TO_WATCH -> cachedLists.wantToWatch
            MangaListsManager.ListType.COMPLETED -> cachedLists.completed
        }
        val mangaSummaries = entries.map {
            com.yazan.manga.data.MangaListItem(
                id = it.id,
                title = it.title,
                cover = it.cover
            )
        }
        if (mangaSummaries.isEmpty()) {
            emptyState.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            emptyText.text = "لا توجد مانجا في ${currentListType.label} بعد"
        } else {
            emptyState.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        }
        adapter.submitList(mangaSummaries)
    }

    /** Shows a confirmation dialog, then removes the manga from the current list. */
    private fun confirmRemove(mangaId: String, mangaTitle: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("إزالة من القائمة")
            .setMessage("هل تريد إزالة \"$mangaTitle\" من ${currentListType.label}؟")
            .setPositiveButton("إزالة") { _, _ ->
                MangaListsManager.removeFromList(this, email, currentListType, mangaId)
                Toast.makeText(this, "تمت الإزالة", Toast.LENGTH_SHORT).show()
                // The real-time listener will refresh the list automatically.
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        listener?.remove()
        listener = null
    }
}
