package com.yazan.manga

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import com.google.firebase.firestore.ListenerRegistration
import com.yazan.manga.data.AuthManager
import com.yazan.manga.data.CloudCommentsManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    private lateinit var adapter: CommentsAdapter

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

        adapter = CommentsAdapter(
            currentUser = AuthManager.getCurrentUser(this),
            onLike = { c -> AuthManager.getCurrentUser(this)?.let { CloudCommentsManager.toggleLike(c.id, it.email, true) {} } },
            onDislike = { c -> AuthManager.getCurrentUser(this)?.let { CloudCommentsManager.toggleLike(c.id, it.email, false) {} } },
            onReply = { c -> openReplies(c.id, c.authorName) },
            onDelete = { c -> confirmDelete(c.id) },
            onBan = { c -> confirmBan(c.authorEmail, c.authorName) },
            onProfile = { email ->
            val intent = Intent(this, UserProfileActivity::class.java)
            intent.putExtra("user_email", email)
            startActivity(intent)
        }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        sendBtn.setOnClickListener {
            val text = commentInput.text.toString().trim()
            if (text.isNotEmpty()) sendComment(text)
        }

        sortTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentSort = when (tab.position) { 1 -> "oldest"; 2 -> "likes"; else -> "newest" }
                updateList()
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
            updateList()
        }
    }

    private fun updateList() {
        val topLevel = allComments.filter { it.parentId == null }
        val sorted = when (currentSort) {
            "oldest" -> topLevel.sortedBy { it.createdAt }
            "likes" -> topLevel.sortedByDescending { it.likes.size - it.dislikes.size }
            else -> topLevel.sortedByDescending { it.createdAt }
        }
        val repliesMap = allComments.filter { it.parentId != null }.groupBy { it.parentId!! }
        
        if (sorted.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyText.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        }
        adapter.updateList(sorted, repliesMap)
    }

    private fun sendComment(text: String) {
        val user = AuthManager.getCurrentUser(this)
        if (user == null) { Toast.makeText(this, "يجب تسجيل الدخول", Toast.LENGTH_SHORT).show(); return }
        sendBtn.isEnabled = false
        CloudCommentsManager.addComment(this, contextId, contextType, text, null) { success, error ->
            sendBtn.isEnabled = true
            if (success) commentInput.text.clear()
            else Toast.makeText(this, error ?: "فشل", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmDelete(commentId: String) {
        AlertDialog.Builder(this).setTitle("حذف التعليق").setMessage("هل تريد الحذف؟")
            .setPositiveButton("حذف") { _, _ -> CloudCommentsManager.deleteComment(commentId) {} }
            .setNegativeButton("إلغاء", null).show()
    }

    private fun confirmBan(email: String, name: String) {
        AlertDialog.Builder(this).setTitle("حظر المستخدم").setMessage("حظر $name؟")
            .setPositiveButton("حظر") { _, _ -> CloudCommentsManager.banUser(email, this) {} }
            .setNegativeButton("إلغاء", null).show()
    }

    private fun openReplies(commentId: String, authorName: String) {
        val intent = Intent(this, RepliesActivity::class.java)
        intent.putExtra("parent_id", commentId)
        intent.putExtra("context_id", contextId)
        intent.putExtra("context_type", contextType)
        intent.putExtra("parent_author", authorName)
        startActivity(intent)
    }

    override fun onDestroy() { super.onDestroy(); listener?.remove() }
}

class CommentsAdapter(
    private val currentUser: AuthManager.User?,
    private val onLike: (CloudCommentsManager.Comment) -> Unit,
    private val onDislike: (CloudCommentsManager.Comment) -> Unit,
    private val onReply: (CloudCommentsManager.Comment) -> Unit,
    private val onDelete: (CloudCommentsManager.Comment) -> Unit,
    private val onBan: (CloudCommentsManager.Comment) -> Unit,
    private val onProfile: (String) -> Unit
) : RecyclerView.Adapter<CommentsAdapter.VH>() {

    private val items = mutableListOf<CloudCommentsManager.Comment>()
    private val repliesMap = mutableMapOf<String, List<CloudCommentsManager.Comment>>()
    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun updateList(top: List<CloudCommentsManager.Comment>, replies: Map<String, List<CloudCommentsManager.Comment>>) {
        items.clear(); items.addAll(top)
        repliesMap.clear(); repliesMap.putAll(replies)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_comment, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) { holder.bind(items[position]) }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val avatar = v.findViewById<TextView>(R.id.commentAvatar)
        private val adminBadge = v.findViewById<TextView>(R.id.commentAdminBadge)
        private val author = v.findViewById<TextView>(R.id.commentAuthor)
        private val time = v.findViewById<TextView>(R.id.commentTime)
        private val text = v.findViewById<TextView>(R.id.commentText)
        private val btnLike = v.findViewById<TextView>(R.id.btnLike)
        private val btnDislike = v.findViewById<TextView>(R.id.btnDislike)
        private val btnReply = v.findViewById<TextView>(R.id.btnReply)
        private val btnDelete = v.findViewById<TextView>(R.id.btnDelete)

        fun bind(c: CloudCommentsManager.Comment) {
            avatar.text = c.authorName.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            adminBadge.visibility = if (c.isAdmin) View.VISIBLE else View.GONE
            author.text = c.authorName
            time.text = sdf.format(Date(c.createdAt))
            text.text = c.text
            btnLike.text = "👍 ${c.likes.size}"
            btnDislike.text = "👎 ${c.dislikes.size}"
            
            val liked = currentUser != null && c.likes.contains(currentUser.email)
            val disliked = currentUser != null && c.dislikes.contains(currentUser.email)
            btnLike.alpha = if (liked) 1f else 0.5f
            btnDislike.alpha = if (disliked) 1f else 0.5f

            val isOwner = currentUser?.email == c.authorEmail
            val canDelete = isOwner || (currentUser?.isAdmin == true)
            btnDelete.visibility = if (canDelete) View.VISIBLE else View.GONE

            btnLike.setOnClickListener { onLike(c) }
            btnDislike.setOnClickListener { onDislike(c) }
            btnReply.setOnClickListener { onReply(c) }
            btnDelete.setOnClickListener { onDelete(c) }
            
            author.setOnClickListener { onProfile(c.authorEmail) }
            avatar.setOnClickListener { onProfile(c.authorEmail) }
            
            itemView.setOnLongClickListener {
                if (currentUser?.isAdmin == true && !c.isAdmin) { onBan(c); true }
                else false
            }
        }
    }
}
