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
import com.google.firebase.firestore.ListenerRegistration
import com.yazan.manga.data.AuthManager
import com.yazan.manga.data.CloudCommentsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
    private var allComments: List<CloudCommentsManager.Comment> = emptyList()
    private var listener: ListenerRegistration? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_comments)

        contextId = intent.getStringExtra("context_id") ?: ""
        contextType = intent.getStringExtra("context_type") ?: "manga"
        contextTitle = intent.getStringExtra("context_title") ?: "تعليقات"

        initViews()
        startListening()
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

        swipeRefresh.setOnRefreshListener { swipeRefresh.isRefreshing = false }
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

    private fun startListening() {
        loadingIndicator.visibility = View.VISIBLE
        listener = CloudCommentsManager.listenToComments(contextId) { comments ->
            loadingIndicator.visibility = View.GONE
            allComments = comments
            renderComments()
        }
    }

    private fun sendComment(text: String) {
        val user = AuthManager.getCurrentUser(this)
        if (user == null) { Toast.makeText(this, "يجب تسجيل الدخول", Toast.LENGTH_SHORT).show(); return }

        sendBtn.isEnabled = false
        CloudCommentsManager.addComment(this, contextId, contextType, text, null) { success, error ->
            sendBtn.isEnabled = true
            if (success) {
                commentInput.text.clear()
                Toast.makeText(this, "تم إرسال التعليق", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, error ?: "فشل الإرسال", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun renderComments() {
        val topLevel = allComments.filter { it.parentId == null }
        val sorted = when (currentSort) {
            "oldest" -> topLevel.sortedBy { it.createdAt }
            "likes" -> topLevel.sortedByDescending { it.likes.size - it.dislikes.size }
            else -> topLevel.sortedByDescending { it.createdAt }
        }

        if (sorted.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            return
        }

        emptyText.visibility = View.GONE
        recyclerView.visibility = View.VISIBLE
        recyclerView.layoutManager = LinearLayoutManager(this)
        val user = AuthManager.getCurrentUser(this)

        recyclerView.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val tv = TextView(parent.context).apply {
                    setPadding(32, 24, 32, 24)
                    setTextColor(getColor(R.color.white))
                    textSize = 13f
                }
                return object : RecyclerView.ViewHolder(tv) {}
            }

            override fun getItemCount() = sorted.size

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val c = sorted[position]
                val replies = allComments.filter { it.parentId == c.id }
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                val isOwner = user?.email == c.authorEmail
                val canDelete = isOwner || (user?.isAdmin == true)

                var text = "${if (c.isAdmin) "👑 " else ""}${c.authorName}\n${c.text}\n👍 ${c.likes.size} | 👎 ${c.dislikes.size} | 💬 ${replies.size} رد | ${sdf.format(java.util.Date(c.createdAt))}${if (c.editedAt != null) " (معدّل)" else ""}"
                if (canDelete) text += "\n[احذف]"

                (holder.itemView as TextView).text = text

                holder.itemView.setOnClickListener {
                    val options = mutableListOf("💬 رد")
                    if (canDelete) options.add("🗑️ حذف")
                    if (user != null && !isOwner) options.add("👍 إعجاب")
                    if (user != null && !isOwner) options.add("👎 عدم إعجاب")
                    if (user?.isAdmin == true && !c.isAdmin) options.add("🚫 حظر")

                    AlertDialog.Builder(this@CommentsActivity)
                        .setItems(options.toTypedArray()) { _, which ->
                            val action = options[which]
                            when {
                                action.startsWith("💬") -> openReplies(c.id, c.authorName)
                                action.startsWith("🗑️") -> {
                                    AlertDialog.Builder(this@CommentsActivity)
                                        .setTitle("حذف").setMessage("هل تريد الحذف؟")
                                        .setPositiveButton("حذف") { _, _ ->
                                            CloudCommentsManager.deleteComment(c.id) { ok ->
                                                if (!ok) Toast.makeText(this@CommentsActivity, "فشل الحذف", Toast.LENGTH_SHORT).show()
                                            }
                                        }.setNegativeButton("إلغاء", null).show()
                                }
                                action.startsWith("👍") -> {
                                    user?.let { CloudCommentsManager.toggleLike(c.id, it.email, true) {} }
                                }
                                action.startsWith("👎") -> {
                                    user?.let { CloudCommentsManager.toggleLike(c.id, it.email, false) {} }
                                }
                                action.startsWith("🚫") -> {
                                    AlertDialog.Builder(this@CommentsActivity)
                                        .setTitle("حظر المستخدم").setMessage("حظر ${c.authorName} وحذف تعليقاته؟")
                                        .setPositiveButton("حظر") { _, _ ->
                                            CloudCommentsManager.banUser(c.authorEmail, this@CommentsActivity) {}
                                        }.setNegativeButton("إلغاء", null).show()
                                }
                            }
                        }.show()
                }
            }
        }
    }

    private fun openReplies(commentId: String, authorName: String) {
        val intent = Intent(this, RepliesActivity::class.java)
        intent.putExtra("parent_id", commentId)
        intent.putExtra("context_id", contextId)
        intent.putExtra("context_type", contextType)
        intent.putExtra("parent_author", authorName)
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        listener?.remove()
    }
}
