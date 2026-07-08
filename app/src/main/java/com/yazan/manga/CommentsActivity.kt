package com.yazan.manga

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import com.yazan.manga.data.AuthManager
import com.yazan.manga.data.CommentsManager
import com.yazan.manga.data.ReportsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CommentsActivity : AppCompatActivity() {

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var emptyText: TextView
    private lateinit var commentInput: EditText
    private lateinit var sendBtn: MaterialButton
    private lateinit var sortTabs: TabLayout

    private var contextId: String = ""
    private var contextType: String = "manga"
    private var contextTitle: String = ""
    private var currentSort: String = "newest"
    private var currentComments: MutableList<CommentsManager.Comment> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_comments)

        contextId = intent.getStringExtra("context_id") ?: ""
        contextType = intent.getStringExtra("context_type") ?: "manga"
        contextTitle = intent.getStringExtra("context_title") ?: "تعليقات"

        initViews()
        loadComments()
    }

    private fun initViews() {
        findViewById<TextView>(R.id.commentsTitle).text = "💬 $contextTitle"
        swipeRefresh = findViewById(R.id.swipeRefresh)
        recyclerView = findViewById(R.id.commentsRecyclerView)
        loadingIndicator = findViewById(R.id.loadingIndicator)
        emptyText = findViewById(R.id.emptyText)
        commentInput = findViewById(R.id.commentInput)
        sendBtn = findViewById(R.id.btnSendComment)
        sortTabs = findViewById(R.id.sortTabs)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        swipeRefresh.setOnRefreshListener { loadComments(); swipeRefresh.isRefreshing = false }
        swipeRefresh.setColorSchemeResources(R.color.primary)

        sendBtn.setOnClickListener {
            val text = commentInput.text.toString().trim()
            if (text.isNotEmpty()) sendComment(text)
        }

        sortTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentSort = when (tab.position) { 1 -> "oldest"; 2 -> "likes"; else -> "newest" }
                renderComments()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun sendComment(text: String) {
        val user = AuthManager.getCurrentUser(this)
        if (user == null) { Toast.makeText(this, "يجب تسجيل الدخول", Toast.LENGTH_SHORT).show(); return }

        val cd = CommentsManager.checkCooldown(this)
        if (!cd.allowed) { Toast.makeText(this, "انتظر ${cd.secondsLeft} ثانية", Toast.LENGTH_SHORT).show(); return }

        if (contextType == "chapter" && !CommentsManager.checkChapterLimit(this, contextId, user.email)) {
            Toast.makeText(this, "وصلت للحد الأقصى (2 تعليقات/فصل)", Toast.LENGTH_SHORT).show(); return
        }

        val error = CommentsManager.addComment(this, contextId, contextType, text, null)
        if (error != null) Toast.makeText(this, error, Toast.LENGTH_LONG).show()
        else { commentInput.text.clear(); loadComments() }
    }

    private fun loadComments() {
        currentComments = CommentsManager.getComments(this, contextId).toMutableList()
        renderComments()
    }

    private fun renderComments() {
        if (currentComments.isEmpty()) { emptyText.visibility = View.VISIBLE; recyclerView.visibility = View.GONE; return }
        emptyText.visibility = View.GONE; recyclerView.visibility = View.VISIBLE
        recyclerView.layoutManager = LinearLayoutManager(this)

        val sorted = CommentsManager.sortComments(currentComments.filter { it.parentId == null }, currentSort)
        val user = AuthManager.getCurrentUser(this)

        recyclerView.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val tv = TextView(parent.context).apply { setPadding(32, 24, 32, 24); setTextColor(getColor(R.color.white)); textSize = 13f }
                return object : RecyclerView.ViewHolder(tv) {}
            }
            override fun getItemCount() = sorted.size
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val c = sorted[position]
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                val liked = user != null && c.likes.contains(user.email)
                val isOwner = user?.email == c.authorEmail
                val canDelete = isOwner || (user?.isAdmin == true)
                var text = "${if (c.isAdmin) "👑 " else ""}${c.authorName}\n${c.text}\n👍 ${c.likes.size} | 👎 ${c.dislikes.size} | ${sdf.format(java.util.Date(c.createdAt))}"
                if (canDelete) text += "\n[احذف]"

                (holder.itemView as TextView).text = text
                holder.itemView.setOnClickListener {
                    if (canDelete) {
                        AlertDialog.Builder(this@CommentsActivity)
                            .setTitle("حذف التعليق").setMessage("هل تريد حذف هذا التعليق؟")
                            .setPositiveButton("حذف") { _, _ -> CommentsManager.deleteComment(this@CommentsActivity, c.id); loadComments() }
                            .setNegativeButton("إلغاء", null).show()
                    }
                }
            }
        }
    }

    private fun openRepliesScreen(commentId: String, authorName: String) {
        val intent = Intent(this, RepliesActivity::class.java)
        intent.putExtra("parent_id", commentId)
        intent.putExtra("context_id", contextId)
        intent.putExtra("context_type", contextType)
        intent.putExtra("parent_author", authorName)
        startActivity(intent)
    }
}
