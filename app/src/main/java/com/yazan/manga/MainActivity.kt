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
import androidx.appcompat.app.AlertDialog
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
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var errorText: TextView
    private lateinit var adapter: MangaAdapter
    private var skeletonAdapter: com.yazan.manga.ui.SkeletonAdapter? = null

    private val repository = MangaRepository(this)
    private var currentTab = "latest"
    private var currentContentType = "3asq"  // 3asq = مصدر 2, manga = مصدر 1
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

        // Clear old cached data in BACKGROUND (not on main thread — prevents ANR)
        Thread {
            try {
                com.yazan.manga.data.CacheManager.clearAllCache(this)
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "Cache clear failed: ${e.message}")
            }
        }.start()

        // Check for app updates on startup (non-blocking, runs on background thread)
        com.yazan.manga.data.InAppUpdateManager.checkForUpdate(this)
        // NOTE: AnnouncementManager disabled on startup — it was calling the
        // Vercel API which returns 426 (blocks for 3+ seconds). Can re-enable
        // later once the Vercel API is redeployed.

        initViews()
        loadManga()
        checkBroadcasts()
    }

    // =============================================================
    //  Broadcasts (admin messages — popup + force block + bell)
    // =============================================================
    private fun showForceBlockPopup(
        broadcast: com.yazan.manga.data.ApiClient.Broadcast,
        prefs: android.content.SharedPreferences,
        seenKey: String
    ) {
        val builder = AlertDialog.Builder(this)
            .setTitle("⚠️ " + broadcast.title)
            .setMessage(broadcast.message)
            .setCancelable(false)

        if (broadcast.linkText != null && broadcast.linkUrl != null) {
            builder.setPositiveButton(broadcast.linkText) { dialog, _ ->
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("link", broadcast.linkUrl))
                Toast.makeText(this, "تم نسخ الرابط", Toast.LENGTH_SHORT).show()
                prefs.edit().putBoolean(seenKey, true).apply()
                dialog.dismiss()
            }
            builder.setNegativeButton("خروج") { dialog, _ ->
                prefs.edit().putBoolean(seenKey, true).apply()
                dialog.dismiss()
            }
        } else {
            builder.setPositiveButton("حسنًا") { dialog, _ ->
                prefs.edit().putBoolean(seenKey, true).apply()
                dialog.dismiss()
            }
        }

        builder.show()
    }

    private fun checkBroadcasts() {
        Thread {
            try {
                val broadcasts = com.yazan.manga.data.ApiClient.getBroadcasts()
                val prefs = getSharedPreferences("broadcast_seen", android.content.Context.MODE_PRIVATE)
                val appVersion = try {
                    val pInfo = packageManager.getPackageInfo(packageName, 0)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) pInfo.longVersionCode.toInt()
                    else @Suppress("DEPRECATION") pInfo.versionCode
                } catch (e: Exception) { 1 }

                // Update bell badge
                val unreadCount = broadcasts.count { !prefs.getBoolean("${it.id}_v$appVersion", false) }
                runOnUiThread {
                    try {
                        val bellBtn = findViewById<android.widget.ImageButton?>(R.id.btnNotifications)
                        if (bellBtn != null) {
                            if (unreadCount > 0) {
                                bellBtn.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#FF6B6B"))
                            } else {
                                bellBtn.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#FFFFFF"))
                            }
                        }
                    } catch (ex: Exception) {}
                }

                if (broadcasts.isEmpty()) return@Thread

                // Check for force block
                val forceBlocks = broadcasts.filter { it.forceBlock }
                for (fb in forceBlocks) {
                    val seenKey = "fb_${fb.id}_v$appVersion"
                    if (!prefs.getBoolean(seenKey, false)) {
                        runOnUiThread { showForceBlockPopup(fb, prefs, seenKey) }
                        return@Thread
                    }
                }

                // Check for normal unseen
                val unseen = broadcasts.filter { !it.forceBlock && !prefs.getBoolean("${it.id}_v$appVersion", false) }
                if (unseen.isNotEmpty()) {
                    val seenKey = "${unseen[0].id}_v$appVersion"
                    runOnUiThread { showBroadcastPopup(unseen[0], prefs, seenKey) }
                }
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "Broadcast check failed: ${e.message}")
            }
        }.start()
    }

    private fun showBroadcastPopup(
        broadcast: com.yazan.manga.data.ApiClient.Broadcast,
        prefs: android.content.SharedPreferences,
        seenKey: String
    ) {
        val builder = AlertDialog.Builder(this)
            .setTitle(broadcast.title)
            .setMessage(broadcast.message)
            .setPositiveButton("تم") { dialog, _ ->
                prefs.edit().putBoolean(seenKey, true).apply()
                dialog.dismiss()
            }
            .setCancelable(true)

        if (broadcast.linkText != null && broadcast.linkUrl != null) {
            builder.setNeutralButton(broadcast.linkText) { dialog, _ ->
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("link", broadcast.linkUrl))
                Toast.makeText(this, "تم نسخ الرابط", Toast.LENGTH_SHORT).show()
                prefs.edit().putBoolean(seenKey, true).apply()
                dialog.dismiss()
            }
        }

        builder.show()
    }


    private fun initViews() {
        drawerLayout = findViewById(R.id.drawerLayout)
        navigationView = findViewById(R.id.navigationView)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        recyclerView = findViewById(R.id.mangaRecyclerView)
        loadingIndicator = findViewById(R.id.loadingIndicator)
        errorText = findViewById(R.id.errorText)

        adapter = MangaAdapter(
            onClick = { manga ->
                val intent = Intent(this, MangaDetailsActivity::class.java)
                intent.putExtra("manga_id", manga.id)
                intent.putExtra("manga_title", manga.title)
                intent.putExtra("manga_cover", manga.cover)
                startActivity(intent)
            }
        )

        recyclerView.layoutManager = GridLayoutManager(this, 3)
        recyclerView.setHasFixedSize(true)
        // Cache more off-screen views so scrolling back/forward doesn't
        // rebind+reload images. Default is only 2 — too low for a 3-col grid.
        recyclerView.setItemViewCacheSize(12)
        // Larger recycled view pool — prevents view inflation when the list
        // is long (e.g. after loading 100+ manga).
        recyclerView.recycledViewPool.setMaxRecycledViews(0, 30)
        // Disable item change animations — without this, swapping the list
        // triggers a fade-in/out on every item, which looks like jank.
        (recyclerView.itemAnimator as? androidx.recyclerview.widget.DefaultItemAnimator)?.apply {
            changeDuration = 0
            addDuration = 200
            moveDuration = 200
        }
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
        findViewById<android.widget.ImageButton?>(R.id.btnNotifications)?.setOnClickListener {
            startActivity(Intent(this, NotificationsActivity::class.java))
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        }
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
            val activeDrawable = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 18f * resources.displayMetrics.density
                setColor(getColor(R.color.primary))
            }
            val inactiveDrawable = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 18f * resources.displayMetrics.density
                setColor(getColor(android.R.color.transparent))
            }
            tabs.forEach { (key, btn) ->
                try {
                    if (key == active) {
                        btn.background = activeDrawable
                        btn.setTextColor(getColor(R.color.white))
                        btn.typeface = android.graphics.Typeface.DEFAULT_BOLD
                    } else {
                        btn.background = inactiveDrawable
                        btn.setTextColor(getColor(R.color.text_secondary))
                        btn.typeface = android.graphics.Typeface.DEFAULT
                    }
                } catch (e: Exception) {
                    android.util.Log.w("MainActivity", "setActiveTab error: ${e.message}")
                }
            }
        }

        setActiveTab(currentTab)

        // === Source selector — 3 buttons ===
        val chipSource1 = findViewById<MaterialButton>(R.id.chipSource1)
        val chipSource2 = findViewById<MaterialButton>(R.id.chipSource2)
        val chipSource3 = findViewById<MaterialButton>(R.id.chipSource3)

        fun setActiveSource(source: String) {
            currentContentType = source
            val activeBg = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 22f * resources.displayMetrics.density
                setColor(getColor(R.color.primary))
            }
            val inactiveBg = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 22f * resources.displayMetrics.density
                setColor(getColor(R.color.surface))
            }
            val buttons = listOf(chipSource1, chipSource2, chipSource3)
            val types = listOf("manga", "3asq", "mhh")
            buttons.forEachIndexed { i, btn ->
                if (types[i] == source) {
                    btn.background = activeBg
                    btn.setTextColor(getColor(R.color.black))
                } else {
                    btn.background = inactiveBg
                    btn.setTextColor(getColor(R.color.text_secondary))
                }
            }
            currentPage = 1
            adapter.submitList(emptyList())
            loadManga()
        }

        chipSource1.setOnClickListener { if (currentContentType != "manga") setActiveSource("manga") }
        chipSource2.setOnClickListener { if (currentContentType != "3asq") setActiveSource("3asq") }
        chipSource3.setOnClickListener { if (currentContentType != "mhh") setActiveSource("mhh") }

        setActiveSource("3asq")

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
                R.id.menuHistory -> {
                    val user = com.yazan.manga.data.AuthManager.getCurrentUser(this)
                    if (user == null) {
                        Toast.makeText(this, "سجّل الدخول أولاً", Toast.LENGTH_SHORT).show()
                    } else {
                        startActivity(Intent(this, HistoryActivity::class.java))
                    }
                }
                R.id.menuDownloads -> startActivity(Intent(this, DownloadsActivity::class.java))
                R.id.menuAbout -> Toast.makeText(this, "تطبيق مانجا v1.0", Toast.LENGTH_SHORT).show()
                R.id.menuShare -> {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "https://yz-manga-app.netlify.app")
                    }
                    startActivity(Intent.createChooser(shareIntent, "مشاركة عبر"))
                }
            }
            true
        }

        // Make header clickable
        val headerView = navigationView.getHeaderView(0)
        headerView.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, ProfileActivity::class.java))
        }

        // Bottom navigation
        val bottomNav = findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNav)
        bottomNav.selectedItemId = R.id.navHome
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navHome -> {
                    // Already home — just close the drawer if open
                    true
                }
                R.id.navLists -> {
                    val user = com.yazan.manga.data.AuthManager.getCurrentUser(this)
                    if (user == null) {
                        Toast.makeText(this, "سجّل الدخول أولاً", Toast.LENGTH_SHORT).show()
                        false
                    } else {
                        showCustomListsDialog(user.email)
                        false // don't actually select — we open a dialog instead
                    }
                }
                R.id.navHistory -> {
                    val user = com.yazan.manga.data.AuthManager.getCurrentUser(this)
                    if (user == null) {
                        Toast.makeText(this, "سجّل الدخول أولاً", Toast.LENGTH_SHORT).show()
                        false
                    } else {
                        startActivity(Intent(this, HistoryActivity::class.java))
                        false // we leave the screen, no need to mark selected
                    }
                }
                R.id.navProfile -> {
                    startActivity(Intent(this, ProfileActivity::class.java))
                    false
                }
                else -> false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Position the drawer content below the status bar only (raised position).
        // Previously this included the 56dp toolbar offset, which pushed the drawer
        // too far down. Now the drawer starts right under the status bar — its
        // header + menu fill more of the screen and don't feel "half-empty".
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBarHeight = if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else 0
        navigationView.setPadding(0, statusBarHeight, 0, 0)
        // Refresh the user's admin status from Firestore (in case they were
        // granted/revoked admin from another device or by another admin).
        com.yazan.manga.data.AuthManager.refreshAdminStatus(this)
        updateMenuHeader()
    }

    private fun updateMenuHeader() {
        val headerView = navigationView.getHeaderView(0)
        val user = AuthManager.getCurrentUser(this)
        val tvName = headerView.findViewById<TextView>(R.id.menuUserName)
        val tvEmail = headerView.findViewById<TextView>(R.id.menuUserEmail)
        val imgAvatar = headerView.findViewById<android.widget.ImageView>(R.id.menuAvatar)
        val tvLetter = headerView.findViewById<TextView>(R.id.menuAvatarLetter)
        if (user != null) {
            tvName.text = user.name
            // Don't show the email in the drawer — only the username
            tvEmail.text = "@${user.username.removePrefix("@")}"
            // Show the avatar image if available, otherwise the first letter
            val avatarFile = user.avatar.takeIf { it.isNotEmpty() }?.let { java.io.File(it) }
            if (avatarFile != null && avatarFile.exists()) {
                imgAvatar.visibility = View.VISIBLE
                tvLetter.visibility = View.GONE
                imgAvatar.imageTintList = null // clear the white tint so the photo shows
                imgAvatar.setPadding(0, 0, 0, 0)
                Glide.with(this).load(user.avatar).circleCrop().into(imgAvatar)
            } else {
                imgAvatar.visibility = View.GONE
                tvLetter.visibility = View.VISIBLE
                tvLetter.text = user.name.firstOrNull()?.uppercaseChar()?.toString() ?: "؟"
            }
        } else {
            tvName.text = "زائر"
            tvEmail.text = "اضغط لتسجيل الدخول"
            imgAvatar.visibility = View.VISIBLE
            tvLetter.visibility = View.GONE
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

        // Show skeleton only when loading from scratch (page 1, no items yet)
        if (currentPage == 1 && adapter.itemCount == 0) {
            showSkeleton()
        } else {
            loadingIndicator.visibility = View.VISIBLE
        }
        errorText.visibility = View.GONE

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                when (currentTab) {
                    "popular" -> repository.getPopularManga(currentPage, currentContentType)
                    else -> repository.getLatestManga(currentPage, currentContentType)
                }
            }

            hideSkeleton()
            loadingIndicator.visibility = View.GONE
            swipeRefresh.isRefreshing = false
            isLoading = false

            result.onSuccess { items ->
                if (items.isEmpty() && currentPage == 1) {
                    // Only show "no manga" if it's page 1 AND we have no cached items
                    if (adapter.itemCount == 0) {
                        errorText.text = "لا توجد مانجا حالياً 📭\nاسحب للأسفل للتحديث"
                        errorText.visibility = View.VISIBLE
                    }
                    // If we have cached items, keep them — don't clear the list
                } else {
                    errorText.visibility = View.GONE
                    adapter.submitList(items)
                }
            }.onFailure {
                // On error, keep the existing list — don't clear it.
                // Only show the error if there's nothing to show.
                if (adapter.itemCount == 0) {
                    errorText.text = "حدث خطأ أثناء التحميل"
                    errorText.visibility = View.VISIBLE
                } else {
                    Toast.makeText(this@MainActivity, "تعذّر التحديث", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** Swap the recycler's adapter to a 9-cell skeleton grid and start shimmer. */
    private fun showSkeleton() {
        if (skeletonAdapter == null) {
            skeletonAdapter = com.yazan.manga.ui.SkeletonAdapter(count = 9)
        }
        recyclerView.adapter = skeletonAdapter
    }

    /** Restore the real manga adapter so the recycler is ready to show real data. */
    private fun hideSkeleton() {
        if (recyclerView.adapter !== adapter) {
            recyclerView.adapter = adapter
        }
    }

    private fun searchManga(query: String) {
        if (isLoading) return
        isLoading = true

        // Show skeleton for fresh searches (no items yet from previous search)
        if (adapter.itemCount == 0) {
            showSkeleton()
        } else {
            loadingIndicator.visibility = View.VISIBLE
        }
        errorText.visibility = View.GONE

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                repository.searchManga(query, 1, currentContentType)
            }

            hideSkeleton()
            loadingIndicator.visibility = View.GONE
            swipeRefresh.isRefreshing = false
            isLoading = false

            result.onSuccess { items ->
                if (items.isEmpty()) {
                    errorText.text = "لا توجد نتائج بحث 🔍\nجرّب كلمة أخرى"
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
                    "popular" -> repository.getPopularManga(currentPage, currentContentType)
                    else -> repository.getLatestManga(currentPage, currentContentType)
                }
            }

            isLoading = false
            result.onSuccess { items -> adapter.appendList(items) }
                .onFailure { 
                    currentPage--  // Revert page increment on failure
                    Toast.makeText(this@MainActivity, "تعذّر تحميل المزيد", Toast.LENGTH_SHORT).show()
                }
        }
    }
    override fun onBackPressed() {
        super.onBackPressed()
        overridePendingTransition(R.anim.fade_in, R.anim.slide_out_left)
    }
}