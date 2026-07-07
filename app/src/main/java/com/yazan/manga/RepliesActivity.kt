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
import com.yazan.manga.data.AuthManager
import com.yazan.manga.data.CommentsManager
import com.yazan.manga.data.ReportsManager
import com.yazan.manga.ui.RepliesAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RepliesActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var emptyText: TextView
    private lateinit var commentInput: android.widget.EditText
    private lateinit var sendBtn: MaterialButton
    private lateinit var adapter: RepliesAdapter

    private var parentId: String = ""
    private var contextId: String = ""
    private var contextType: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_replies)

        parentId = intent.getStringExtra("parent_id") ?: ""
        contextId = intent.getStringExtra("context_id") ?: ""
        contextType = intent.getStringExtra("context_type") ?: "manga"

        initViews()
        loadReplies()
    }

    private fun initViews() {
        val parentAuthor = intent.getStringExtra("parent_author") ?: "تعليق"
        findViewById<TextView>(R.id.repliesTitle).text = "💬 ردود على: $parentAuthor"

        recyclerView = findViewById(R.id.repliesRecyclerView)
        loadingIndicator = findViewById(R.id.loadingIndicator)
        emptyText = findViewById(R.id.emptyText)
        commentInput = findViewById(R.id.replyInput)
        sendBtn = findViewById(R.id.btnSendReply)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        adapter = RepliesAdapter(
            currentUser = AuthManager.getCurrentUser(this),
            onLike = { id -> CommentsManager.toggleLike(this, id); loadReplies() },
            onDislike = { id -> CommentsManager.toggleDislike(this, id); loadReplies() },
            onDelete = { id -> confirmDelete(id) },
            onEdit = { id, text -> CommentsManager.editComment(this, id, text); loadReplies() },
            onBan = { id -> confirmBan(id) },
            onReport = { id, text -> showReportDialog(id, text) },
            onViewProfile = { email -> openUserProfile(email) }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        sendBtn.setOnClickListener {
            val text = commentInput.text.toString().trim()
            if (text.isNotEmpty()) sendReply(text)
        }
    }

    private fun sendReply(text: String) {
        val user = AuthManager.getCurrentUser(this)
        if (user == null) {
            android.widget.Toast.makeText(this, "يجب تسجيل الدخول", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        loadingIndicator.visibility = View.VISIBLE
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                CommentsManager.addComment(this@RepliesActivity, contextId, contextType, text, parentId)
            }
            loadingIndicator.visibility = View.GONE
            if (error != null) {
                android.widget.Toast.makeText(this@RepliesActivity, error, android.widget.Toast.LENGTH_LONG).show()
            } else {
                commentInput.text.clear()
                loadReplies()
            }
        }
    }

    private fun loadReplies() {
        lifecycleScope.launch {
            val replies = withContext(Dispatchers.IO) {
                CommentsManager.getReplies(this@RepliesActivity, parentId)
            }
            if (replies.isEmpty()) {
                emptyText.visibility = View.VISIBLE
                recyclerView.visibility = View.GONE
            } else {
                emptyText.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
                adapter.submitList(replies)
            }
        }
    }

    private fun confirmDelete(id: String) {
        AlertDialog.Builder(this)
            .setTitle("حذف الرد")
            .setMessage("هل تريد حذف هذا الرد؟")
            .setPositiveButton("حذف") { _, _ ->
                CommentsManager.deleteComment(this, id)
                loadReplies()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun confirmBan(id: String) {
        AlertDialog.Builder(this)
            .setTitle("حظر المستخدم")
            .setMessage("سيتم حظر هذا المستخدم وحذف كل تعليقاته. متابعة؟")
            .setPositiveButton("حظر") { _, _ ->
                val err = CommentsManager.banUser(this, id)
                if (err != null) android.widget.Toast.makeText(this, err, android.widget.Toast.LENGTH_SHORT).show()
                else loadReplies()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showReportDialog(commentId: String, commentText: String) {
        val reasons = arrayOf("إساءة", "محتوى غير لائق", "سبام", "تحرش", "أخرى")
        AlertDialog.Builder(this)
            .setTitle("الإبلاغ عن الرد")
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

    private fun openUserProfile(email: String) {
        val intent = android.content.Intent(this, UserProfileActivity::class.java)
        intent.putExtra("user_email", email)
        startActivity(intent)
    }
}
