package com.yazan.manga

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.yazan.manga.data.MangaListItem
import com.yazan.manga.data.MangaRepository
import com.yazan.manga.ui.MangaAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var errorText: TextView
    private lateinit var adapter: MangaAdapter

    private val repository = MangaRepository()
    private var currentTab = "latest"  // "latest" or "top"
    private var currentPage = 1
    private var isLoading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        loadManga()
    }

    private fun initViews() {
        recyclerView = findViewById(R.id.mangaRecyclerView)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        loadingIndicator = findViewById(R.id.loadingIndicator)
        errorText = findViewById(R.id.errorText)

        adapter = MangaAdapter { manga ->
            // Open manga details
            val intent = Intent(this, MangaDetailsActivity::class.java)
            intent.putExtra("manga_id", manga.id)
            intent.putExtra("manga_title", manga.title)
            intent.putExtra("manga_cover", manga.cover)
            startActivity(intent)
        }

        recyclerView.layoutManager = GridLayoutManager(this, 3)
        recyclerView.adapter = adapter

        // Pagination - load more when scrolled to bottom
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || isLoading) return
                val lm = rv.layoutManager as GridLayoutManager
                if (lm.findLastVisibleItemPosition() >= adapter.itemCount - 6) {
                    loadMore()
                }
            }
        })

        // Swipe to refresh
        swipeRefresh.setOnRefreshListener {
            currentPage = 1
            loadManga()
        }
        swipeRefresh.setColorSchemeResources(R.color.primary)

        // Tab buttons
        val tabLatest = findViewById<MaterialButton>(R.id.tabLatest)
        val tabPopular = findViewById<MaterialButton>(R.id.tabPopular)

        tabLatest.setOnClickListener {
            if (currentTab == "latest") return@setOnClickListener
            currentTab = "latest"
            currentPage = 1
            tabLatest.setBackgroundColor(getColor(R.color.primary))
            tabLatest.setTextColor(getColor(R.color.white))
            tabPopular.setBackgroundColor(getColor(R.color.surface))
            tabPopular.setTextColor(getColor(R.color.text_secondary))
            loadManga()
        }

        tabPopular.setOnClickListener {
            if (currentTab == "top") return@setOnClickListener
            currentTab = "top"
            currentPage = 1
            tabPopular.setBackgroundColor(getColor(R.color.primary))
            tabPopular.setTextColor(getColor(R.color.white))
            tabLatest.setBackgroundColor(getColor(R.color.surface))
            tabLatest.setTextColor(getColor(R.color.text_secondary))
            loadManga()
        }

        // Search button
        val btnSearch = findViewById<ImageButton>(R.id.btnSearch)
        val searchBar = findViewById<LinearLayout>(R.id.searchBar)
        val searchInput = findViewById<android.widget.EditText>(R.id.searchInput)
        val btnCloseSearch = findViewById<ImageButton>(R.id.btnCloseSearch)

        btnSearch.setOnClickListener {
            searchBar.visibility = View.VISIBLE
            findViewById<LinearLayout>(R.id.topBar).visibility = View.GONE
            searchInput.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(searchInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }

        btnCloseSearch.setOnClickListener {
            searchBar.visibility = View.GONE
            findViewById<LinearLayout>(R.id.topBar).visibility = View.VISIBLE
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
                if (query.isNotEmpty()) {
                    searchManga(query)
                }
                true
            } else false
        }
    }

    private fun loadManga() {
        if (isLoading) return
        isLoading = true
        loadingIndicator.visibility = View.VISIBLE
        errorText.visibility = View.GONE

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                if (currentTab == "top") {
                    repository.getPopularManga(currentPage)
                } else {
                    repository.getLatestManga(currentPage)
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
                    errorText.text = "لا توجد نتائج"
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
        currentPage++
        isLoading = true

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                if (currentTab == "top") {
                    repository.getPopularManga(currentPage)
                } else {
                    repository.getLatestManga(currentPage)
                }
            }

            isLoading = false
            result.onSuccess { items ->
                adapter.appendList(items)
            }
        }
    }
}
