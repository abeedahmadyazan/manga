package com.yazan.manga

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
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
import com.bumptech.glide.Glide
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
    private lateinit var recyclerView: RecyclerView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var errorText: TextView
    private lateinit var adapter: MangaAdapter

    private val repository = MangaRepository()

    private var currentTab = "latest"        // latest | popular | search
    private var currentPage = 1
    private var isLoading = false
    private var lastSearchQuery: String = ""

    // Tab buttons
    private lateinit var tabLatest: MaterialButton
    private lateinit var tabPopular: MaterialButton
    private lateinit var tabSearch: MaterialButton

    // Search bar
    private lateinit var searchBar: View
    private lateinit var searchInput: android.widget.EditText
    private lateinit var btnCloseSearch: ImageButton
    private lateinit var tabsRow: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        loadManga()
    }

    private fun initViews() {
        drawerLayout = findViewById(R.id.drawerLayout)
        navigationView = findViewById(R.id.navigationView)
        recyclerView = findViewById(R.id.mangaRecyclerView)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        loadingIndicator = findViewById(R.id.loadingIndicator)
        errorText = findViewById(R.id.errorText)

        adapter = MangaAdapter { manga -> openMangaDetails(manga) }

        // 3-column grid on phones, more on tablets
        val spanCount = if (resources.configuration.screenWidthDp >= 600) 5 else 3
        recyclerView.layoutManager = GridLayoutManager(this, spanCount)
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
        swipeRefresh.setColorSchemeResources(R.color.primary, R.color.accent)

        // Hamburger menu button
        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        // Search button — toggles the search bar
        val btnSearch = findViewById<ImageButton>(R.id.btnSearch)
        searchBar = findViewById(R.id.searchBar)
        searchInput = findViewById(R.id.searchInput)
        btnCloseSearch = findViewById(R.id.btnCloseSearch)
        tabsRow = findViewById(R.id.tabsRow)

        btnSearch.setOnClickListener {
            switchTab("search")
            searchBar.visibility = View.VISIBLE
            searchInput.requestFocus()
            showKeyboard(searchInput)
        }

        btnCloseSearch.setOnClickListener {
            searchBar.visibility = View.GONE
            searchInput.text.clear()
            searchInput.clearFocus()
            hideKeyboard(searchInput)
            if (currentTab == "search") {
                switchTab("latest")
            }
        }

        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                val query = searchInput.text.toString().trim()
                if (query.isNotEmpty()) {
                    lastSearchQuery = query
                    hideKeyboard(searchInput)
                    searchManga(query)
                }
                true
            } else false
        }

        // Tabs
        tabLatest = findViewById(R.id.tabLatest)
        tabPopular = findViewById(R.id.tabPopular)
        tabSearch = findViewById(R.id.tabSearch)

        tabLatest.setOnClickListener { switchTab("latest") }
        tabPopular.setOnClickListener { switchTab("popular") }
        tabSearch.setOnClickListener {
            switchTab("search")
            searchBar.visibility = View.VISIBLE
            searchInput.requestFocus()
            showKeyboard(searchInput)
        }

        updateTabStyles()

        // Drawer menu
        navigationView.setNavigationItemSelectedListener { item ->
            drawerLayout.closeDrawer(GravityCompat.START)
            when (item.itemId) {
                R.id.menuFavorites -> { Toast.makeText(this, "المفضلة - قريباً", Toast.LENGTH_SHORT).show(); true }
                R.id.menuHistory   -> { Toast.makeText(this, "سجل القراءة - قريباً", Toast.LENGTH_SHORT).show(); true }
                R.id.menuDownloads -> { Toast.makeText(this, "التنزيلات - قريباً", Toast.LENGTH_SHORT).show(); true }
                R.id.menuProfile   -> { startActivity(Intent(this, ProfileActivity::class.java)); true }
                R.id.menuSettings  -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
                R.id.menuAbout     -> {
                    android.app.AlertDialog.Builder(this)
                        .setTitle("عن التطبيق")
                        .setMessage("تطبيق مانجا v1.0.0\nمصدر البيانات: 3asq.pro\n© 2026")
                        .setPositiveButton("حسناً", null)
                        .show()
                    true
                }
                else -> false
            }
        }

        // Header click → profile
        val headerView = navigationView.getHeaderView(0)
        headerView.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, ProfileActivity::class.java))
        }
    }

    private fun switchTab(tab: String) {
        if (currentTab == tab) return
        currentTab = tab
        currentPage = 1
        lastSearchQuery = ""
        if (tab != "search") {
            searchBar.visibility = View.GONE
        }
        updateTabStyles()
        loadManga()
    }

    private fun updateTabStyles() {
        val tabs = listOf(
            "latest" to tabLatest,
            "popular" to tabPopular,
            "search" to tabSearch
        )
        tabs.forEach { (key, btn) ->
            if (key == currentTab) {
                btn.background = getDrawable(R.drawable.bg_tab_active)
                btn.setTextColor(getColor(R.color.white))
            } else {
                btn.background = getDrawable(R.drawable.bg_tab_inactive)
                btn.setTextColor(getColor(R.color.text_secondary))
            }
        }
    }

    private fun openMangaDetails(manga: MangaListItem) {
        val intent = Intent(this, MangaDetailsActivity::class.java)
        intent.putExtra("manga_slug", manga.id)
        intent.putExtra("manga_title", manga.title)
        intent.putExtra("manga_cover", manga.cover)
        startActivity(intent)
    }

    private fun loadManga() {
        if (isLoading) return
        isLoading = true
        loadingIndicator.visibility = View.VISIBLE
        errorText.visibility = View.GONE

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                when (currentTab) {
                    "popular" -> repository.getPopularArabic(currentPage)
                    "search"  -> if (lastSearchQuery.isNotEmpty())
                        repository.searchManga(lastSearchQuery)
                    else repository.getLatestArabic(currentPage)
                    else      -> repository.getLatestArabic(currentPage)
                }
            }

            loadingIndicator.visibility = View.GONE
            swipeRefresh.isRefreshing = false
            isLoading = false

            result.onSuccess { items ->
                if (items.isEmpty() && currentPage == 1) {
                    errorText.text = if (currentTab == "search") "لا توجد نتائج" else "لا توجد مانجا"
                    errorText.visibility = View.VISIBLE
                }
                adapter.submitList(items)
            }.onFailure { e ->
                errorText.text = "فشل التحميل: ${e.message}"
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
                    errorText.text = "لا توجد نتائج لـ \"$query\""
                    errorText.visibility = View.VISIBLE
                }
                adapter.submitList(items)
            }.onFailure { e ->
                errorText.text = "فشل البحث: ${e.message}"
                errorText.visibility = View.VISIBLE
            }
        }
    }

    private fun loadMore() {
        if (isLoading) return
        if (currentTab == "search") return   // search returns all results at once
        currentPage++
        isLoading = true

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                when (currentTab) {
                    "popular" -> repository.getPopularArabic(currentPage)
                    else      -> repository.getLatestArabic(currentPage)
                }
            }
            isLoading = false
            result.onSuccess { items ->
                if (items.isNotEmpty()) adapter.appendList(items)
                else currentPage--   // rollback
            }.onFailure {
                currentPage--
            }
        }
    }

    private fun updateMenuHeader() {
        val headerView = navigationView.getHeaderView(0)
        val tvName = headerView.findViewById<TextView>(R.id.menuUserName)
        val tvEmail = headerView.findViewById<TextView>(R.id.menuUserEmail)
        val avatarLetter = headerView.findViewById<TextView>(R.id.menuAvatarLetter)
        val avatarImg = headerView.findViewById<android.widget.ImageView>(R.id.menuAvatar)

        val user = AuthManager.getCurrentUser(this)
        if (user != null) {
            tvName.text = user.name
            tvEmail.text = user.email
            val first = user.name.firstOrNull()?.uppercaseChar()?.toString() ?: "U"
            avatarLetter.text = first
            avatarLetter.visibility = View.VISIBLE
            if (user.avatar.isNotEmpty()) {
                avatarImg.visibility = View.VISIBLE
                Glide.with(this).load(user.avatar).circleCrop().into(avatarImg)
            } else {
                avatarImg.visibility = View.GONE
            }
        } else {
            tvName.text = "زائر"
            tvEmail.text = "اضغط لتسجيل الدخول"
            avatarLetter.text = "ز"
            avatarLetter.visibility = View.VISIBLE
            avatarImg.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        updateMenuHeader()
    }

    private fun showKeyboard(view: View) {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(view, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard(view: View) {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }
}
