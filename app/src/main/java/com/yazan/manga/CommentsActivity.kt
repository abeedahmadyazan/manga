package com.yazan.manga

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import com.yazan.manga.data.AuthManager
import com.yazan.manga.data.CommentsManager
import com.yazan.manga.data.ReportsManager
import com.yazan.manga.ui.CommentsAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CommentsActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var emptyText: TextView
    private lateinit var commentInput: android.widget.EditText
    private lateinit var sendBtn: MaterialButton
    private lateinit var sortTabs: TabLayout
    private lateinit var adapter: CommentsAdapter

    private var contextId: String = ""
    private var contextType: String = "manga"
    private var contextTitle: String = ""
    private var currentSort: String = "newest"

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

        recyclerView = findViewById(R.id.commentsRecyclerView)
        loadingIndicator = findViewById(R.id.loadingIndicator)
        emptyText = findViewById(R.id.emptyText)
        commentInput = findViewById(R.id.commentInput)
        sendBtn = findViewById(R.id.btnSendComment)
        sortTabs = findViewById(R.id.sortTabs)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        adapter = CommentsAdapter(
            currentUser = AuthManager.getCurrentUser(this),
            onLike = { id -> CommentsManager.toggleLike(this, id); loadComments() },
            onDislike = { id -> CommentsManager.toggleDislike(this, id); loadComments() },
            onDelete = { id -> confirmDelete(id) },
            onEdit = { id, text -> CommentsManager.editComment(this, id, text); loadComments() },
            onBan = { id -> confirmBan(id) },
            onSuspend = { email, reason -> showSuspendDuration(email, reason) },
            onReport = { id, text -> showReportDialog(id, text) },
            onOpenReplies = { comment -> openRepliesScreen(comment) },
            onViewProfile = { email -> openUserProfile(email) }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        sendBtn.setOnClickListener {
            val text = commentInput.text.toString().trim()
            if (text.isNotEmpty()) sendComment(text)
        }

        sortTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentSort = when (tab.position) {
                    1 -> "oldest"
                    2 -> "likes"
                    else -> "newest"
                }
                loadComments()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun sendComment(text: String) {
        val user = AuthManager.getCurrentUser(this)
        if (user == null) {
            android.widget.Toast.makeText(this, "يجب تسجيل الدخول أولاً", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        loadingIndicator.visibility = View.VISIBLE
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                CommentsManager.addComment(this@CommentsActivity, contextId, contextType, text, null)
            }
            loadingIndicator.visibility = View.GONE
            if (error != null) {
                android.widget.Toast.makeText(this@CommentsActivity, error, android.widget.Toast.LENGTH_LONG).show()
            } else {
                commentInput.text.clear()
                loadComments()
            }
        }
    }

    private fun loadComments() {
        lifecycleScope.launch {
            val (topLevel, replies) = withContext(Dispatchers.IO) {
                val all = CommentsManager.getComments(this@CommentsActivity, contextId)
                val top = all.filter { it.parentId == null }
                val sorted = CommentsManager.sortComments(top, currentSort)
                val repsMap = all.filter { it.parentId != null }.groupBy { it.parentId!! }
                Pair(sorted, repsMap)
            }
            if (topLevel.isEmpty()) {
                emptyText.visibility = View.VISIBLE
                recyclerView.visibility = View.GONE
            } else {
                emptyText.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
                adapter.submitList(topLevel, replies)
            }
        }
    }

    private fun confirmDelete(commentId: String) {
        AlertDialog.Builder(this)
            .setTitle("حذف التعليق")
            .setMessage("هل أنت متأكد من حذف هذا التعليق؟")
            .setPositiveButton("حذف") { _, _ ->
                CommentsManager.deleteComment(this, commentId)
                loadComments()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun confirmBan(commentId: String) {
        AlertDialog.Builder(this)
            .setTitle("حظر المستخدم")
            .setMessage("سيتم حظر هذا المستخدم وحذف كل تعليقاته. متابعة؟")
            .setPositiveButton("حظر") { _, _ ->
                val error = CommentsManager.banUser(this, commentId)
                if (error != null) {
                    android.widget.Toast.makeText(this, error, android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    android.widget.Toast.makeText(this, "تم حظر المستخدم", android.widget.Toast.LENGTH_SHORT).show()
                    loadComments()
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showSuspendDuration(email: String, reason: String) {
        val options = arrayOf("إيقاف يومين", "إيقاف أسبوع", "إيقاف شهر", "إيقاف دائم")
        AlertDialog.Builder(this)
            .setTitle("مدة الإيقاف")
            .setItems(options) { _, which ->
                val days = when (which) { 0 -> 2; 1 -> 7; 2 -> 30; else -> 0 }
                val err = AuthManager.suspendUser(this, email, days, reason)
                if (err == null) {
                    android.widget.Toast.makeText(this, "تم إيقاف الحساب", android.widget.Toast.LENGTH_SHORT).show()
                    loadComments()
                } else {
                    android.widget.Toast.makeText(this, err, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showReportDialog(commentId: String, commentText: String) {
        val reasons = arrayOf("إساءة", "محتوى غير لائق", "سبام", "تحرش", "أخرى")
        AlertDialog.Builder(this)
            .setTitle("الإبلاغ عن التعليق")
            .setItems(reasons) { _, which ->
                val reason = reasons[which]
                val err = ReportsManager.reportComment(this, commentId, commentText, reason)
                if (err == null) {
                    android.widget.Toast.makeText(this, "تم إرسال البلاغ، شكراً لك", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    android.widget.Toast.makeText(this, err, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun openRepliesScreen(comment: CommentsManager.Comment) {
        val intent = android.content.Intent(this, RepliesActivity::class.java)
        intent.putExtra("parent_id", comment.id)
        intent.putExtra("context_id", contextId)
        intent.putExtra("context_type", contextType)
        intent.putExtra("parent_author", comment.authorName)
        startActivity(intent)
    }

    private fun openUserProfile(email: String) {
        val intent = android.content.Intent(this, UserProfileActivity::class.java)
        intent.putExtra("user_email", email)
        startActivity(intent)
    }
}
