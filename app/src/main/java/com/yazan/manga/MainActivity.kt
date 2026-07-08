package com.yazan.manga

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.navigation.NavigationView
import com.yazan.manga.data.AuthManager
import com.yazan.manga.data.MangaListItem
import com.yazan.manga.data.MangaRepository
import com.yazan.manga.ui.MangaAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var errorText: TextView
    private lateinit var adapter: MangaAdapter

    private val repository = MangaRepository()
    private var currentTab = "latest"
    private var currentPage = 1
    private var isLoading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Apply the saved dark mode setting on startup
        val prefs = getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
            if (prefs.getBoolean("dark_mode", true)) androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            else androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
        )
        setContentView(R.layout.activity_main)

        initViews()
        loadManga()
    }

    private fun initViews() {
        drawerLayout = findViewById(R.id.drawerLayout)
        navigationView = findViewById(R.id.navigationView)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        recyclerView = findViewById(R.id.mangaRecyclerView)
        loadingIndicator = findViewById(R.id.loadingIndicator)
        errorText = findViewById(R.id.errorText)

        adapter = MangaAdapter { manga ->
            val intent = Intent(this, MangaDetailsActivity::class.java)
            intent.putExtra("manga_id", manga.id)
            intent.putExtra("manga_title", manga.title)
            intent.putExtra("manga_cover", manga.cover)
            startActivity(intent)
        }

        recyclerView.layoutManager = GridLayoutManager(this, 3)
        recyclerView.adapter = adapter

        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || isLoading) return
                val lm = rv.layoutManager as GridLayoutManager
                if (lm.findLastVisibleItemPosition() >= adapter.itemCount - 6) {
                    loadMore()
                }
            }
        })

        swipeRefresh.setOnRefreshListener {
            currentPage = 1
            loadManga()
        }
        swipeRefresh.setColorSchemeResources(R.color.primary)

        // Menu button
        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        // Search
        val btnSearch = findViewById<ImageButton>(R.id.btnSearch)
        val btnCloseSearch = findViewById<ImageButton>(R.id.btnCloseSearch)
        val searchBar = findViewById<LinearLayout>(R.id.searchBar)
        val topBar = findViewById<LinearLayout>(R.id.topBar)
        val searchInput = findViewById<EditText>(R.id.searchInput)

        btnSearch.setOnClickListener {
            searchBar.visibility = View.VISIBLE
            topBar.visibility = View.GONE
            searchInput.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(searchInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }

        btnCloseSearch.setOnClickListener {
            searchBar.visibility = View.GONE
            topBar.visibility = View.VISIBLE
            searchInput.text.clear()
            searchInput.clearFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(searchInput.windowToken, 0)
            currentPage = 1
            loadManga()
        }

        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                val query = searchInput.text.toString().trim()
                if (query.isNotEmpty()) searchManga(query)
                true
            } else false
        }

        // Tabs
        val tabLatest = findViewById<MaterialButton>(R.id.tabLatest)
        val tabPopular = findViewById<MaterialButton>(R.id.tabPopular)

        val tabs = mapOf(
            "latest" to tabLatest,
            "popular" to tabPopular,
        )

        fun setActiveTab(active: String) {
            tabs.forEach { (key, btn) ->
                if (key == active) {
                    btn.setBackgroundColor(getColor(R.color.primary))
                    btn.setTextColor(getColor(R.color.white))
                } else {
                    btn.setBackgroundColor(getColor(R.color.surface))
                    btn.setTextColor(getColor(R.color.text_secondary))
                }
            }
        }

        setActiveTab(currentTab)

        tabLatest.setOnClickListener {
            if (currentTab == "latest") return@setOnClickListener
            currentTab = "latest"; currentPage = 1; setActiveTab(currentTab); loadManga()
        }
        tabPopular.setOnClickListener {
            if (currentTab == "popular") return@setOnClickListener
            currentTab = "popular"; currentPage = 1; setActiveTab(currentTab); loadManga()
        }

        // Navigation drawer
        navigationView.setNavigationItemSelectedListener { item ->
            drawerLayout.closeDrawer(GravityCompat.START)
            when (item.itemId) {
                R.id.menuProfile -> startActivity(Intent(this, ProfileActivity::class.java))
                R.id.menuSettings -> startActivity(Intent(this, SettingsActivity::class.java))
                R.id.menuFavorites -> {
                    // Open a dialog with the 4 custom lists
                    val user = com.yazan.manga.data.AuthManager.getCurrentUser(this)
                    if (user == null) {
                        Toast.makeText(this, "سجّل الدخول أولاً", Toast.LENGTH_SHORT).show()
                    } else {
                        showCustomListsDialog(user.email)
                    }
                }
                R.id.menuHistory -> Toast.makeText(this, "قريباً", Toast.LENGTH_SHORT).show()
                R.id.menuDownloads -> Toast.makeText(this, "قريباً", Toast.LENGTH_SHORT).show()
                R.id.menuAbout -> Toast.makeText(this, "تطبيق مانجا v1.0", Toast.LENGTH_SHORT).show()
            }
            true
        }

        // Make header clickable
        val headerView = navigationView.getHeaderView(0)
        headerView.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, ProfileActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        updateMenuHeader()
    }

    private fun updateMenuHeader() {
        val headerView = navigationView.getHeaderView(0)
        val user = AuthManager.getCurrentUser(this)
        val tvName = headerView.findViewById<TextView>(R.id.menuUserName)
        val tvEmail = headerView.findViewById<TextView>(R.id.menuUserEmail)
        if (user != null) {
            tvName.text = user.name
            tvEmail.text = user.email
        } else {
            tvName.text = "زائر"
            tvEmail.text = "اضغط لتسجيل الدخول"
        }
    }

    /** Shows a dialog with the 4 custom lists, then opens the selected one. */
    private fun showCustomListsDialog(email: String) {
        val options = arrayOf(
            "⭐ المفضلة",
            "📚 أتابع لاحقاً",
            "👀 أرغب بمتابعتها",
            "✅ أنهيتها"
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("قوائمي المخصصة")
            .setItems(options) { _, which ->
                val intent = Intent(this, MangaListActivity::class.java)
                intent.putExtra("user_email", email)
                intent.putExtra("initial_tab", which)
                startActivity(intent)
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun loadManga() {
        if (isLoading) return
        isLoading = true
        loadingIndicator.visibility = View.VISIBLE
        errorText.visibility = View.GONE

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                when (currentTab) {
                    "popular" -> repository.getPopularManga(currentPage)
                    "popular" -> repository.getPopularManga(currentPage)
                    else -> repository.getLatestManga(currentPage)
                }
            }

            loadingIndicator.visibility = View.GONE
            swipeRefresh.isRefreshing = false
            isLoading = false

            result.onSuccess { items ->
                if (items.isEmpty()) {
                    errorText.text = "لا توجد مانجا"
                    errorText.visibility = View.VISIBLE
                }
                adapter.submitList(items)
            }.onFailure { e ->
                errorText.text = "حدث خطأ أثناء التحميل"
                errorText.visibility = View.VISIBLE
            }
        }
    }

    private fun searchManga(query: String) {
        if (isLoading) return
        isLoading = true
        loadingIndicator.visibility = View.VISIBLE
        errorText.visibility = View.GONE

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                repository.searchManga(query)
            }

            loadingIndicator.visibility = View.GONE
            swipeRefresh.isRefreshing = false
            isLoading = false

            result.onSuccess { items ->
                if (items.isEmpty()) {
                    errorText.text = "لا توجد نتائج"
                    errorText.visibility = View.VISIBLE
                }
                adapter.submitList(items)
            }.onFailure { e ->
                errorText.text = "حدث خطأ أثناء البحث"
                errorText.visibility = View.VISIBLE
            }
        }
    }

    private fun loadMore() {
        if (isLoading) return
        currentPage++
        isLoading = true

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                when (currentTab) {
                    "popular" -> repository.getPopularManga(currentPage)
                    "popular" -> repository.getPopularManga(currentPage)
                    else -> repository.getLatestManga(currentPage)
                }
            }

            isLoading = false
            result.onSuccess { items -> adapter.appendList(items) }
        }
    }
}
